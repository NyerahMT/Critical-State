package com.nyerahworks.criticalstate.sim

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

internal data class SteamGeneratorSnapshot(
    val index: Int,
    val pressureMpa: Double,
    val saturationTemperatureK: Double,
    val levelFraction: Double,
    val steamQuality: Double,
    val steamFlowKgS: Double,
    val feedwaterFlowKgS: Double,
    val feedwaterDemandKgS: Double,
    val heatTransferMw: Double,
    val primaryOutletEnthalpyKjKg: Double,
    val primaryOutletTemperatureK: Double,
    val wallAverageTemperatureK: Double,
    val secondaryMassKg: Double,
    val primaryMassKg: Double,
    val storedEnergyMj: Double,
    val diagnostic: String? = null,
)

/**
 * Four-segment primary/tube-wall model coupled to a conserved two-phase
 * secondary inventory.  Steam production and level are state consequences.
 */
internal class SteamGeneratorModel(
    private val index: Int,
    private val water: WaterProperties,
) {
    private val solver = TwoPhaseVolumeSolver(
        water,
        ReferencePlant.SG_SECONDARY_VOLUME_M3,
        1.0,
        10.0,
    )

    private val primaryH = DoubleArray(ReferencePlant.SG_SEGMENTS)
    private val wallT = DoubleArray(ReferencePlant.SG_SEGMENTS)
    private val primaryConductance = DoubleArray(ReferencePlant.SG_SEGMENTS)
    private val secondaryConductance = DoubleArray(ReferencePlant.SG_SEGMENTS)

    private val referenceSteamFlowKgS: Double
    private val steamLineK: Double
    private var secondaryMassKg: Double
    private var secondaryEnergyKj: Double
    private var equilibrium: TwoPhaseEquilibrium
    private var feedwaterIntegral = 0.0
    private var lastFeedwaterFlow = 0.0
    private var lastSteamFlow = 0.0
    private var lastHeatTransferMw = ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT
    private var diagnostic: String? = null

    init {
        val sat = water.saturationP(ReferencePlant.SG_PRESSURE_MPA)
        val liquidV = ReferencePlant.SG_SECONDARY_VOLUME_M3 * ReferencePlant.SG_REFERENCE_LIQUID_FRACTION
        val vapourV = ReferencePlant.SG_SECONDARY_VOLUME_M3 - liquidV
        val ml = liquidV * sat.liquidDensityKgM3
        val mv = vapourV * sat.vapourDensityKgM3
        secondaryMassKg = ml + mv
        secondaryEnergyKj = ml * sat.liquidInternalEnergyKjKg + mv * sat.vapourInternalEnergyKjKg
        equilibrium = solver.solve(secondaryMassKg, secondaryEnergyKj)

        val hFw = water.statePT(ReferencePlant.SG_PRESSURE_MPA + 1.0, ReferencePlant.FEEDWATER_T_K).enthalpyKjKg
        referenceSteamFlowKgS = (
            ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT * 1000.0 /
                (sat.vapourEnthalpyKjKg - hFw)
            )
        val denominator = sqrt(max(
            ReferencePlant.SG_PRESSURE_MPA * ReferencePlant.SG_PRESSURE_MPA -
                ReferencePlant.STEAM_HEADER_PRESSURE_MPA * ReferencePlant.STEAM_HEADER_PRESSURE_MPA,
            1.0e-6,
        ) / sat.temperatureK)
        steamLineK = referenceSteamFlowKgS / denominator
        initializePrimaryAndWalls()
        lastFeedwaterFlow = referenceSteamFlowKgS
        lastSteamFlow = referenceSteamFlowKgS
    }

    private fun initializePrimaryAndWalls() {
        val p = ReferencePlant.PRIMARY_PRESSURE_MPA
        val hot = water.statePT(p, ReferencePlant.HOT_LEG_T_K)
        val cold = water.statePT(p, ReferencePlant.COLD_LEG_T_K)
        val satT = water.saturationP(ReferencePlant.SG_PRESSURE_MPA).temperatureK
        var hIn = hot.enthalpyKjKg
        for (i in primaryH.indices) {
            val fraction = (i + 1.0) / primaryH.size
            val hOut = hot.enthalpyKjKg + fraction * (cold.enthalpyKjKg - hot.enthalpyKjKg)
            primaryH[i] = hOut
            val tIn = water.statePH(p, hIn).temperatureK
            val tOut = water.statePH(p, hOut).temperatureK
            val tMean = 0.5 * (tIn + tOut)
            val qMw = ReferencePlant.FLOW_PER_LOOP_KG_PER_S * (hIn - hOut) / 1000.0
            val ua = qMw / max(tMean - satT, 1.0)
            primaryConductance[i] = max(0.1, 2.0 * ua)
            secondaryConductance[i] = max(0.1, 2.0 * ua)
            wallT[i] = 0.5 * (tMean + satT)
            hIn = hOut
        }
    }

    fun reset() {
        val sat = water.saturationP(ReferencePlant.SG_PRESSURE_MPA)
        val liquidV = ReferencePlant.SG_SECONDARY_VOLUME_M3 * ReferencePlant.SG_REFERENCE_LIQUID_FRACTION
        val vapourV = ReferencePlant.SG_SECONDARY_VOLUME_M3 - liquidV
        val ml = liquidV * sat.liquidDensityKgM3
        val mv = vapourV * sat.vapourDensityKgM3
        secondaryMassKg = ml + mv
        secondaryEnergyKj = ml * sat.liquidInternalEnergyKjKg + mv * sat.vapourInternalEnergyKjKg
        equilibrium = solver.solve(secondaryMassKg, secondaryEnergyKj)
        feedwaterIntegral = 0.0
        lastFeedwaterFlow = referenceSteamFlowKgS
        lastSteamFlow = referenceSteamFlowKgS
        lastHeatTransferMw = ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT
        diagnostic = null
        initializePrimaryAndWalls()
    }

    fun advance(
        primaryInletEnthalpyKjKg: Double,
        primaryMassFlowKgS: Double,
        primaryPressureMpa: Double,
        headerPressureMpa: Double,
        feedwaterFlowKgS: Double,
        feedwaterEnthalpyKjKg: Double,
        heatExchangerCondition: Double,
        dt: Double,
    ): SteamGeneratorSnapshot {
        diagnostic = null
        val flow = max(0.0, primaryMassFlowKgS)
        val secondaryT = equilibrium.temperatureK
        var hIn = primaryInletEnthalpyKjKg
        var totalQSecondary = 0.0
        var primaryMass = 0.0
        var primaryStoredMj = 0.0
        val segmentWallCap = ReferencePlant.SG_WALL_HEAT_CAPACITY_MJ_PER_K /
            ReferencePlant.SG_SEGMENTS
        val foulingScale = (0.75 + 0.25 * heatExchangerCondition).coerceIn(0.5, 1.0)

        for (i in primaryH.indices) {
            val state = water.statePH(primaryPressureMpa, primaryH[i])
            val segmentVolume = ReferencePlant.SG_PRIMARY_VOLUME_M3 / ReferencePlant.SG_SEGMENTS
            val mass = max(1.0, state.densityKgM3 * segmentVolume)
            primaryMass += mass
            primaryStoredMj += mass * state.internalEnergyKjKg / 1000.0

            val flowScale = (flow / ReferencePlant.FLOW_PER_LOOP_KG_PER_S)
                .coerceIn(0.05, 1.5)
                .let { Math.pow(it, 0.8) }
            val gp = primaryConductance[i] * flowScale * foulingScale
            val gs = secondaryConductance[i] * foulingScale
            val inletState = water.statePH(primaryPressureMpa, hIn)
            val primaryMeanT = 0.5 * (inletState.temperatureK + state.temperatureK)
            val qPw = gp * (primaryMeanT - wallT[i])
            val qWs = gs * (wallT[i] - secondaryT)

            val dhdt = (flow * (hIn - primaryH[i]) - qPw * 1000.0) / mass
            primaryH[i] += dhdt * dt
            wallT[i] += (qPw - qWs) / segmentWallCap * dt
            totalQSecondary += qWs
            hIn = primaryH[i]
        }

        val pSg = equilibrium.pressureMpa
        val tSg = equilibrium.temperatureK
        val pressureTerm = max(pSg * pSg - headerPressureMpa * headerPressureMpa, 0.0)
        val steamFlow = if (pressureTerm > 0.0) {
            steamLineK * sqrt(pressureTerm / max(tSg, 1.0))
        } else 0.0
        val sat = water.saturationP(pSg)

        secondaryMassKg += (feedwaterFlowKgS - steamFlow) * dt
        secondaryEnergyKj += (
            feedwaterFlowKgS * feedwaterEnthalpyKjKg +
                totalQSecondary * 1000.0 -
                steamFlow * sat.vapourEnthalpyKjKg
            ) * dt
        if (secondaryMassKg < 1.0 || !secondaryMassKg.isFinite() || !secondaryEnergyKj.isFinite()) {
            diagnostic = "SG${index + 1} conserved state invalid"
            secondaryMassKg = max(1.0, secondaryMassKg.takeIf { it.isFinite() } ?: 1.0)
        }
        equilibrium = solver.solve(secondaryMassKg, secondaryEnergyKj)
        if (kotlin.math.abs(equilibrium.residualKjKg) > 1.0) {
            diagnostic = "SG${index + 1} phase closure outside equilibrium envelope"
        }

        lastFeedwaterFlow = feedwaterFlowKgS
        lastSteamFlow = steamFlow
        lastHeatTransferMw = totalQSecondary
        return snapshot(primaryPressureMpa, primaryMass, primaryStoredMj)
    }

    fun feedwaterDemand(dt: Double): Double {
        val level = geometricLevel()
        val error = ReferencePlant.SG_REFERENCE_LEVEL - level
        feedwaterIntegral = (feedwaterIntegral + error * dt).coerceIn(-0.25, 0.25)
        val kp = 2.2 * referenceSteamFlowKgS
        val ki = 0.12 * referenceSteamFlowKgS
        return (lastSteamFlow + kp * error + ki * feedwaterIntegral)
            .coerceIn(0.20 * referenceSteamFlowKgS, 1.35 * referenceSteamFlowKgS)
    }

    fun steamOutletEnthalpyKjKg(): Double =
        water.saturationP(equilibrium.pressureMpa).vapourEnthalpyKjKg

    fun snapshot(
        primaryPressureMpa: Double = ReferencePlant.PRIMARY_PRESSURE_MPA,
        primaryMassOverride: Double? = null,
        primaryStoredOverrideMj: Double? = null,
    ): SteamGeneratorSnapshot {
        var primaryMass = 0.0
        var primaryStored = 0.0
        primaryH.forEach { h ->
            val s = water.statePH(primaryPressureMpa, h)
            val m = s.densityKgM3 * ReferencePlant.SG_PRIMARY_VOLUME_M3 / ReferencePlant.SG_SEGMENTS
            primaryMass += m
            primaryStored += m * s.internalEnergyKjKg / 1000.0
        }
        primaryMass = primaryMassOverride ?: primaryMass
        primaryStored = primaryStoredOverrideMj ?: primaryStored
        val wallStored = wallT.sum() *
            (ReferencePlant.SG_WALL_HEAT_CAPACITY_MJ_PER_K / ReferencePlant.SG_SEGMENTS)
        val out = water.statePH(primaryPressureMpa, primaryH.last())
        return SteamGeneratorSnapshot(
            index = index,
            pressureMpa = equilibrium.pressureMpa,
            saturationTemperatureK = equilibrium.temperatureK,
            levelFraction = geometricLevel(),
            steamQuality = equilibrium.quality,
            steamFlowKgS = lastSteamFlow,
            feedwaterFlowKgS = lastFeedwaterFlow,
            feedwaterDemandKgS = feedwaterDemand(0.0),
            heatTransferMw = lastHeatTransferMw,
            primaryOutletEnthalpyKjKg = primaryH.last(),
            primaryOutletTemperatureK = out.temperatureK,
            wallAverageTemperatureK = wallT.average(),
            secondaryMassKg = secondaryMassKg,
            primaryMassKg = primaryMass,
            storedEnergyMj = secondaryEnergyKj / 1000.0 + primaryStored + wallStored,
            diagnostic = diagnostic,
        )
    }

    private fun geometricLevel(): Double =
        (equilibrium.liquidVolumeM3 / ReferencePlant.SG_SECONDARY_VOLUME_M3)
            .coerceIn(0.0, 1.0)
}

internal data class SteamHeaderSnapshot(
    val pressureMpa: Double,
    val temperatureK: Double,
    val enthalpyKjKg: Double,
    val massKg: Double,
    val internalEnergyKj: Double,
    val diagnostic: String? = null,
)

internal class MainSteamHeaderModel(
    private val water: WaterProperties,
) {
    private val solver = SinglePhaseVolumeSolver(
        water,
        ReferencePlant.STEAM_HEADER_VOLUME_M3,
        1.0,
        9.0,
    )
    private var massKg: Double
    private var energyKj: Double
    private var state: SinglePhaseVolumeState
    private var diagnostic: String? = null

    init {
        val t0 = water.saturationP(ReferencePlant.STEAM_HEADER_PRESSURE_MPA).temperatureK + 3.0
        val s = water.statePT(ReferencePlant.STEAM_HEADER_PRESSURE_MPA, t0)
        massKg = s.densityKgM3 * ReferencePlant.STEAM_HEADER_VOLUME_M3
        energyKj = massKg * s.internalEnergyKjKg
        state = solver.solve(massKg, energyKj)
    }

    fun reset() {
        val t0 = water.saturationP(ReferencePlant.STEAM_HEADER_PRESSURE_MPA).temperatureK + 3.0
        val s = water.statePT(ReferencePlant.STEAM_HEADER_PRESSURE_MPA, t0)
        massKg = s.densityKgM3 * ReferencePlant.STEAM_HEADER_VOLUME_M3
        energyKj = massKg * s.internalEnergyKjKg
        state = solver.solve(massKg, energyKj)
        diagnostic = null
    }

    fun advance(
        inflows: List<Pair<Double, Double>>,
        turbineFlowKgS: Double,
        dt: Double,
    ): SteamHeaderSnapshot {
        val oldH = state.state.enthalpyKjKg
        val mIn = inflows.sumOf { it.first }
        val eInKw = inflows.sumOf { it.first * it.second }
        massKg += (mIn - turbineFlowKgS) * dt
        energyKj += (eInKw - turbineFlowKgS * oldH) * dt
        diagnostic = null
        if (massKg < 0.1 || !massKg.isFinite() || !energyKj.isFinite()) {
            diagnostic = "Main steam header conserved state invalid"
            massKg = max(0.1, massKg.takeIf { it.isFinite() } ?: 0.1)
        }
        state = solver.solve(massKg, energyKj)
        if (kotlin.math.abs(state.residualM3Kg) > 1.0e-3) {
            diagnostic = "Main steam header closure outside steam envelope"
        }
        return snapshot()
    }

    fun snapshot() = SteamHeaderSnapshot(
        pressureMpa = state.pressureMpa,
        temperatureK = state.state.temperatureK,
        enthalpyKjKg = state.state.enthalpyKjKg,
        massKg = massKg,
        internalEnergyKj = energyKj,
        diagnostic = diagnostic,
    )
}

internal data class CondenserSnapshot(
    val pressureMpa: Double,
    val saturationTemperatureK: Double,
    val levelFraction: Double,
    val heatRejectionMw: Double,
    val coolingWaterOutletK: Double,
    val liquidEnthalpyKjKg: Double,
    val totalMassKg: Double,
    val internalEnergyKj: Double,
    val diagnostic: String? = null,
)

internal class CondenserModel(
    private val water: WaterProperties,
) {
    private val solver = TwoPhaseVolumeSolver(
        water,
        ReferencePlant.CONDENSER_VOLUME_M3,
        0.004,
        0.10,
    )
    private var massKg: Double
    private var energyKj: Double
    private var equilibrium: TwoPhaseEquilibrium
    private var lastHeatRejectionMw = 0.0
    private var coolingOutletK = ReferencePlant.COOLING_WATER_INLET_K
    private var diagnostic: String? = null

    init {
        val sat = water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA)
        val vl = ReferencePlant.CONDENSER_REFERENCE_LIQUID_VOLUME_M3
        val vv = ReferencePlant.CONDENSER_VOLUME_M3 - vl
        val ml = vl * sat.liquidDensityKgM3
        val mv = vv * sat.vapourDensityKgM3
        massKg = ml + mv
        energyKj = ml * sat.liquidInternalEnergyKjKg + mv * sat.vapourInternalEnergyKjKg
        equilibrium = solver.solve(massKg, energyKj)
        calculateHeatRejection(1.0)
    }

    fun reset() {
        val sat = water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA)
        val vl = ReferencePlant.CONDENSER_REFERENCE_LIQUID_VOLUME_M3
        val vv = ReferencePlant.CONDENSER_VOLUME_M3 - vl
        val ml = vl * sat.liquidDensityKgM3
        val mv = vv * sat.vapourDensityKgM3
        massKg = ml + mv
        energyKj = ml * sat.liquidInternalEnergyKjKg + mv * sat.vapourInternalEnergyKjKg
        equilibrium = solver.solve(massKg, energyKj)
        diagnostic = null
        calculateHeatRejection(1.0)
    }

    fun advance(
        turbineExhaustFlowKgS: Double,
        turbineExhaustEnthalpyKjKg: Double,
        condensateOutflowKgS: Double,
        condenserCondition: Double,
        dt: Double,
    ): CondenserSnapshot {
        val sat = water.saturationP(equilibrium.pressureMpa)
        val qMw = calculateHeatRejection(condenserCondition)
        massKg += (turbineExhaustFlowKgS - condensateOutflowKgS) * dt
        energyKj += (
            turbineExhaustFlowKgS * turbineExhaustEnthalpyKjKg -
                condensateOutflowKgS * sat.liquidEnthalpyKjKg -
                qMw * 1000.0
            ) * dt
        diagnostic = null
        if (massKg < 1.0 || !massKg.isFinite() || !energyKj.isFinite()) {
            diagnostic = "Condenser conserved state invalid"
            massKg = max(1.0, massKg.takeIf { it.isFinite() } ?: 1.0)
        }
        equilibrium = solver.solve(massKg, energyKj)
        if (kotlin.math.abs(equilibrium.residualKjKg) > 1.0) {
            diagnostic = "Condenser phase closure outside equilibrium envelope"
        }
        calculateHeatRejection(condenserCondition)
        return snapshot()
    }

    private fun calculateHeatRejection(condition: Double): Double {
        val uaMwK = 130.0 * (0.70 + 0.30 * condition).coerceIn(0.4, 1.0)
        val cpKjKgK = 4.18
        val cMwK = ReferencePlant.CONDENSER_COOLING_FLOW_KG_S * cpKjKgK / 1000.0
        val effectiveness = 1.0 - exp(-uaMwK / max(cMwK, 1.0e-6))
        val deltaT = max(0.0, equilibrium.temperatureK - ReferencePlant.COOLING_WATER_INLET_K)
        lastHeatRejectionMw = effectiveness * cMwK * deltaT
        coolingOutletK = ReferencePlant.COOLING_WATER_INLET_K +
            lastHeatRejectionMw / max(cMwK, 1.0e-6)
        return lastHeatRejectionMw
    }

    fun snapshot(): CondenserSnapshot {
        val sat = water.saturationP(equilibrium.pressureMpa)
        return CondenserSnapshot(
            pressureMpa = equilibrium.pressureMpa,
            saturationTemperatureK = equilibrium.temperatureK,
            levelFraction = (equilibrium.liquidVolumeM3 /
                ReferencePlant.CONDENSER_VOLUME_M3).coerceIn(0.0, 1.0),
            heatRejectionMw = lastHeatRejectionMw,
            coolingWaterOutletK = coolingOutletK,
            liquidEnthalpyKjKg = sat.liquidEnthalpyKjKg,
            totalMassKg = massKg,
            internalEnergyKj = energyKj,
            diagnostic = diagnostic,
        )
    }
}
