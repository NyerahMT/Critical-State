package com.nyerahworks.criticalstate.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class FeedwaterSnapshot(
    val totalFlowKgS: Double,
    val perSgFlowKgS: List<Double>,
    val enthalpyKjKg: Double,
    val temperatureK: Double,
    val pump: PumpSnapshot,
    val valvePositions: List<Double>,
    val condensateRequiredKgS: Double,
    val auxiliaryPowerMw: Double,
)

/**
 * Common feedwater train. SG level controllers move valves; actual total flow
 * comes from feed-pump head versus static/system resistance and fluid inertia.
 * Turbine extraction steam supplies feedwater heating by an energy balance.
 */
internal class FeedwaterTrainModel(
    private val water: WaterProperties,
) {
    companion object {
        private const val RATED_RPM = 5000.0
        private const val SHUTOFF_HEAD_M = 950.0
        private const val RATED_HEAD_M = 800.0
        private const val EFFICIENCY = 0.84
        private const val INERTIA = 2600.0
        private const val PIPE_DIAMETER_M = 0.70
        private const val PIPE_LENGTH_M = 150.0
        private const val REFERENCE_VALVE = 0.70
        private const val VALVE_RATE_PER_S = 0.12
    }

    private val refFeedState = water.statePT(
        ReferencePlant.SG_PRESSURE_MPA + 1.0,
        ReferencePlant.FEEDWATER_T_K,
    )
    private val satSg = water.saturationP(ReferencePlant.SG_PRESSURE_MPA)
    private val ratedPerSgFlow = ReferencePlant.RATED_THERMAL_POWER_MW /
        ReferencePlant.LOOP_COUNT * 1000.0 /
        (satSg.vapourEnthalpyKjKg - refFeedState.enthalpyKjKg)
    private val ratedTotalFlow = ratedPerSgFlow * ReferencePlant.LOOP_COUNT
    private val pump = CentrifugalPump(
        ratedRpm = RATED_RPM,
        ratedFlowKgS = ratedTotalFlow,
        ratedDensityKgM3 = refFeedState.densityKgM3,
        shutoffHeadM = SHUTOFF_HEAD_M,
        ratedHeadM = RATED_HEAD_M,
        ratedEfficiency = EFFICIENCY,
        rotorInertiaKgM2 = INERTIA,
    )
    private val area = PI * PIPE_DIAMETER_M * PIPE_DIAMETER_M / 4.0
    private val valvePositions = DoubleArray(ReferencePlant.LOOP_COUNT) { REFERENCE_VALVE }
    private val baseValveK: Double
    private val pumpHeadK: Double

    private var totalFlow = ratedTotalFlow
    private var extractionFlow = 0.28 * ratedTotalFlow
    private var extractionEnthalpy = 2500.0
    private var lastSnapshot: FeedwaterSnapshot

    init {
        val rho = refFeedState.densityKgM3
        val ratedVolumetricFlow = ratedTotalFlow / rho
        pumpHeadK = (SHUTOFF_HEAD_M - RATED_HEAD_M) /
            max(ratedVolumetricFlow * ratedVolumetricFlow, 1.0e-9)
        val pumpPa = rho * 9.80665 * RATED_HEAD_M
        val staticPa = (ReferencePlant.SG_PRESSURE_MPA - ReferencePlant.CONDENSER_PRESSURE_MPA) * 1e6
        val v = ratedTotalFlow / (rho * area)
        val dyn = 0.5 * rho * v * v
        val kAtRef = max(1.0, (pumpPa - staticPa) / max(dyn, 1.0))
        baseValveK = kAtRef * REFERENCE_VALVE * REFERENCE_VALVE
        lastSnapshot = buildSnapshot(
            condenserLiquidEnthalpy = water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA)
                .liquidEnthalpyKjKg,
            averageSgPressureMpa = ReferencePlant.SG_PRESSURE_MPA,
            pumpState = pump.snapshot(totalFlow, rho, 1.0),
        )
    }

    fun reset() {
        totalFlow = ratedTotalFlow
        extractionFlow = 0.28 * ratedTotalFlow
        extractionEnthalpy = 2500.0
        for (i in valvePositions.indices) valvePositions[i] = REFERENCE_VALVE
        pump.reset()
        val rho = refFeedState.densityKgM3
        lastSnapshot = buildSnapshot(
            water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA).liquidEnthalpyKjKg,
            ReferencePlant.SG_PRESSURE_MPA,
            pump.snapshot(totalFlow, rho, 1.0),
        )
    }

    fun setExtraction(flowKgS: Double, enthalpyKjKg: Double) {
        extractionFlow = max(0.0, flowKgS)
        extractionEnthalpy = enthalpyKjKg
    }

    fun advance(
        sgDemandsKgS: List<Double>,
        sgPressuresMpa: List<Double>,
        condenserPressureMpa: Double,
        condenserLiquidEnthalpyKjKg: Double,
        pumpCondition: Double,
        dt: Double,
    ): FeedwaterSnapshot {
        for (i in valvePositions.indices) {
            val demand = sgDemandsKgS.getOrElse(i) { ratedPerSgFlow }
            val target = (REFERENCE_VALVE * demand / ratedPerSgFlow).coerceIn(0.03, 1.0)
            val delta = (target - valvePositions[i]).coerceIn(-VALVE_RATE_PER_S * dt, VALVE_RATE_PER_S * dt)
            valvePositions[i] = (valvePositions[i] + delta).coerceIn(0.03, 1.0)
        }

        val avgSgP = if (sgPressuresMpa.isNotEmpty()) sgPressuresMpa.average() else ReferencePlant.SG_PRESSURE_MPA
        val mixH = mixtureEnthalpy(condenserLiquidEnthalpyKjKg)
        val mixState = runCatching { water.statePH(avgSgP + 1.0, mixH) }.getOrElse { refFeedState }
        val rho = mixState.densityKgM3
        val pumpState = pump.advance(totalFlow, rho, pumpCondition, dt)
        val staticPressure = max(0.0, avgSgP - condenserPressureMpa) * 1e6
        val avgValve = max(0.03, valvePositions.average())

        // The previous explicit momentum update was numerically stiff because both
        // the pump curve and valve loss are quadratic in mass flow.  Solve that
        // same inertia equation implicitly for m_dot(n+1):
        //   m1 = m0 + dt*A/L*(drive - resistance*m1^2)
        // This preserves pump head, valve resistance and pipe inertia while
        // preventing the 0↔rated-flow oscillation that defeated level control.
        val speedRatio = (pumpState.rpm / RATED_RPM).coerceAtLeast(0.0)
        val pumpShutoffPa = rho * 9.80665 * SHUTOFF_HEAD_M * speedRatio * speedRatio
        val drivePa = pumpShutoffPa - staticPressure
        val pumpResistancePaPerKgS2 = 9.80665 * pumpHeadK / max(rho, 1.0)
        val valveResistancePaPerKgS2 = baseValveK / (avgValve * avgValve) *
            0.5 / max(rho * area * area, 1.0e-9)
        val resistance = pumpResistancePaPerKgS2 + valveResistancePaPerKgS2
        val inertanceFactor = area / PIPE_LENGTH_M
        val c = totalFlow + dt * inertanceFactor * drivePa
        totalFlow = if (c <= 0.0) {
            0.0
        } else {
            val a = dt * inertanceFactor * resistance
            if (a <= 1.0e-18) c else 2.0 * c / (1.0 + sqrt(1.0 + 4.0 * a * c))
        }.coerceIn(0.0, 1.5 * ratedTotalFlow)

        lastSnapshot = buildSnapshot(
            condenserLiquidEnthalpy = condenserLiquidEnthalpyKjKg,
            averageSgPressureMpa = avgSgP,
            pumpState = pump.snapshot(totalFlow, rho, pumpCondition),
        )
        return lastSnapshot
    }

    fun snapshot(): FeedwaterSnapshot = lastSnapshot

    private fun mixtureEnthalpy(condenserLiquidEnthalpy: Double): Double {
        if (totalFlow <= 1.0e-9) return condenserLiquidEnthalpy
        val usedExtraction = min(extractionFlow, totalFlow)
        val condensate = totalFlow - usedExtraction
        return (condensate * condenserLiquidEnthalpy + usedExtraction * extractionEnthalpy) / totalFlow
    }

    private fun buildSnapshot(
        condenserLiquidEnthalpy: Double,
        averageSgPressureMpa: Double,
        pumpState: PumpSnapshot,
    ): FeedwaterSnapshot {
        val weights = valvePositions.map { it * it }
        val weightSum = max(weights.sum(), 1.0e-9)
        val perSg = weights.map { totalFlow * it / weightSum }
        val usedExtraction = min(extractionFlow, totalFlow)
        val condensate = max(0.0, totalFlow - usedExtraction)
        val mixedH = mixtureEnthalpy(condenserLiquidEnthalpy)
        val hydraulicDeltaH = if (totalFlow > 1.0e-6) {
            pumpState.hydraulicPowerMw * 1000.0 / totalFlow
        } else 0.0
        val hOut = mixedH + hydraulicDeltaH
        val tOut = runCatching { water.statePH(max(0.2, averageSgPressureMpa + 1.0), hOut).temperatureK }
            .getOrElse { ReferencePlant.FEEDWATER_T_K }
        return FeedwaterSnapshot(
            totalFlowKgS = totalFlow,
            perSgFlowKgS = perSg,
            enthalpyKjKg = hOut,
            temperatureK = tOut,
            pump = pumpState,
            valvePositions = valvePositions.toList(),
            condensateRequiredKgS = condensate,
            auxiliaryPowerMw = pumpState.shaftPowerMw,
        )
    }
}

internal data class TurbineGeneratorSnapshot(
    val governorValvePosition: Double,
    val steamFlowKgS: Double,
    val extractionFlowKgS: Double,
    val extractionEnthalpyKjKg: Double,
    val exhaustFlowKgS: Double,
    val exhaustEnthalpyKjKg: Double,
    val mechanicalPowerMw: Double,
    val generatorGrossMw: Double,
    val generatorNetMw: Double,
    val reactivePowerMvar: Double,
    val rpm: Double,
    val rotorAngleRad: Double,
    val breakerClosed: Boolean,
    val synchronized: Boolean,
    val tripped: Boolean,
    val storedRotationalEnergyMj: Double,
    val diagnostic: String? = null,
)

/**
 * Two-stage effective steam turbine with feedwater extraction, shaft inertia,
 * a grid-connected swing equation, and a governor that moves a real valve.
 */
internal class TurbineGeneratorModel(
    private val water: WaterProperties,
) {
    companion object {
        private const val INTERMEDIATE_PRESSURE_MPA = 1.0
        private const val ETA_HP = 0.87
        private const val ETA_LP = 0.82
        private const val GENERATOR_EFFICIENCY = 0.985
        private const val MACHINE_BASE_MW = ReferencePlant.RATED_GROSS_ELECTRIC_MW / GENERATOR_EFFICIENCY
        private const val REFERENCE_VALVE = 0.90
        private const val VALVE_RATE_PER_S = 0.18
        private const val GENERATOR_H_S = 5.0
        private const val GENERATOR_PMAX_PU = 1.35
        private const val SWING_DAMPING = 1.2
        private const val REACTANCE_PU = 1.8
        private const val INTERNAL_EMF_PU = 1.05
    }

    private val ratedSteamFlowKgS: Double
    private val stodolaK: Double
    private val calibratedMechanicalEfficiency: Double

    private var loadCommand = 1.0
    private var valvePosition = REFERENCE_VALVE
    private var governorIntegral = 0.0
    private var breakerClosed = true
    private var turbineTrip = false
    private var rotorAngle = asin(1.0 / GENERATOR_PMAX_PU)
    private var speedDeviationPu = 0.0
    private var freeRotorRpm = ReferencePlant.SYNCHRONOUS_RPM
    private var lastGrossMw = ReferencePlant.RATED_GROSS_ELECTRIC_MW
    private var lastSnapshot: TurbineGeneratorSnapshot

    init {
        val sat = water.saturationP(ReferencePlant.SG_PRESSURE_MPA)
        val hFw = water.statePT(ReferencePlant.SG_PRESSURE_MPA + 1.0, ReferencePlant.FEEDWATER_T_K).enthalpyKjKg
        ratedSteamFlowKgS = ReferencePlant.RATED_THERMAL_POWER_MW * 1000.0 /
            (sat.vapourEnthalpyKjKg - hFw)
        val headerT = water.saturationP(ReferencePlant.STEAM_HEADER_PRESSURE_MPA).temperatureK + 3.0
        val pressureTerm = ReferencePlant.STEAM_HEADER_PRESSURE_MPA * ReferencePlant.STEAM_HEADER_PRESSURE_MPA -
            ReferencePlant.CONDENSER_PRESSURE_MPA * ReferencePlant.CONDENSER_PRESSURE_MPA
        stodolaK = ratedSteamFlowKgS /
            (REFERENCE_VALVE * sqrt(pressureTerm / headerT))

        val headerState = water.statePT(ReferencePlant.STEAM_HEADER_PRESSURE_MPA, headerT)
        val condenserH = water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA).liquidEnthalpyKjKg
        val raw = thermodynamicExpansion(
            steamFlow = ratedSteamFlowKgS,
            inletPressure = ReferencePlant.STEAM_HEADER_PRESSURE_MPA,
            inletEnthalpy = headerState.enthalpyKjKg,
            condenserPressure = ReferencePlant.CONDENSER_PRESSURE_MPA,
            feedwaterFlow = ratedSteamFlowKgS,
            condensateEnthalpy = condenserH,
            mechanicalEfficiency = 1.0,
        )
        calibratedMechanicalEfficiency = (
            ReferencePlant.RATED_GROSS_ELECTRIC_MW /
                max(raw.mechanicalPowerMw * GENERATOR_EFFICIENCY, 1.0)
            ).coerceIn(0.45, 0.98)
        lastSnapshot = buildInitialSnapshot()
    }

    fun setLoadCommand(fraction: Double) {
        loadCommand = fraction.coerceIn(0.30, 1.10)
    }

    fun trip() {
        turbineTrip = true
    }

    fun resetTrip() {
        turbineTrip = false
    }

    fun setBreakerClosed(request: Boolean): Boolean {
        if (!request) {
            breakerClosed = false
            return true
        }
        val speedOk = abs(freeRotorRpm - ReferencePlant.SYNCHRONOUS_RPM) /
            ReferencePlant.SYNCHRONOUS_RPM < 0.005
        val angleWrapped = kotlin.math.atan2(sin(rotorAngle), cos(rotorAngle))
        val angleOk = abs(angleWrapped) < Math.toRadians(10.0)
        return if (speedOk && angleOk) {
            breakerClosed = true
            speedDeviationPu = (freeRotorRpm / ReferencePlant.SYNCHRONOUS_RPM) - 1.0
            true
        } else false
    }

    fun reset() {
        loadCommand = 1.0
        valvePosition = REFERENCE_VALVE
        governorIntegral = 0.0
        breakerClosed = true
        turbineTrip = false
        rotorAngle = asin(1.0 / GENERATOR_PMAX_PU)
        speedDeviationPu = 0.0
        freeRotorRpm = ReferencePlant.SYNCHRONOUS_RPM
        lastGrossMw = ReferencePlant.RATED_GROSS_ELECTRIC_MW
        lastSnapshot = buildInitialSnapshot()
    }

    fun advance(
        header: SteamHeaderSnapshot,
        condenser: CondenserSnapshot,
        totalFeedwaterFlowKgS: Double,
        auxiliaryPowerMw: Double,
        turbineCondition: Double,
        dt: Double,
    ): TurbineGeneratorSnapshot {
        val targetMw = loadCommand * ReferencePlant.RATED_GROSS_ELECTRIC_MW
        val errorPu = (targetMw - lastGrossMw) / ReferencePlant.RATED_GROSS_ELECTRIC_MW
        governorIntegral = (governorIntegral + errorPu * dt).coerceIn(-0.6, 0.6)
        val speedError = if (breakerClosed) 0.0 else
            (ReferencePlant.SYNCHRONOUS_RPM - freeRotorRpm) / ReferencePlant.SYNCHRONOUS_RPM
        val valveCommand = if (turbineTrip) 0.0 else {
            (REFERENCE_VALVE + 0.75 * errorPu + 0.20 * governorIntegral + 1.8 * speedError)
                .coerceIn(0.0, 1.0)
        }
        val deltaValve = (valveCommand - valvePosition)
            .coerceIn(-VALVE_RATE_PER_S * dt, VALVE_RATE_PER_S * dt)
        valvePosition = (valvePosition + deltaValve).coerceIn(0.0, 1.0)

        val pressureTerm = max(
            header.pressureMpa * header.pressureMpa - condenser.pressureMpa * condenser.pressureMpa,
            0.0,
        )
        val steamFlow = stodolaK * valvePosition *
            sqrt(pressureTerm / max(header.temperatureK, 1.0))

        val expansion = thermodynamicExpansion(
            steamFlow = steamFlow,
            inletPressure = header.pressureMpa,
            inletEnthalpy = header.enthalpyKjKg,
            condenserPressure = condenser.pressureMpa,
            feedwaterFlow = totalFeedwaterFlowKgS,
            condensateEnthalpy = condenser.liquidEnthalpyKjKg,
            mechanicalEfficiency = calibratedMechanicalEfficiency *
                (0.88 + 0.12 * turbineCondition).coerceIn(0.7, 1.0),
        )

        val synchronousOmega = 2.0 * PI * ReferencePlant.GRID_HZ
        var electricalMw: Double
        var reactiveMvar = 0.0
        var diagnostic: String? = null
        var rpm: Double

        if (breakerClosed) {
            val pmPu = expansion.mechanicalPowerMw / MACHINE_BASE_MW
            val pePu = GENERATOR_PMAX_PU * sin(rotorAngle)
            val dSpeed = (pmPu - pePu - SWING_DAMPING * speedDeviationPu) /
                (2.0 * GENERATOR_H_S)
            speedDeviationPu += dSpeed * dt
            rotorAngle += synchronousOmega * speedDeviationPu * dt
            electricalMw = pePu * MACHINE_BASE_MW
            reactiveMvar = (1.0 / REACTANCE_PU) *
                (INTERNAL_EMF_PU * cos(rotorAngle) - 1.0) *
                ReferencePlant.RATED_GROSS_ELECTRIC_MW
            rpm = ReferencePlant.SYNCHRONOUS_RPM * (1.0 + speedDeviationPu)
            if (abs(rotorAngle) > Math.toRadians(100.0) || abs(speedDeviationPu) > 0.03) {
                breakerClosed = false
                freeRotorRpm = rpm
                diagnostic = "Generator separated: loss of synchronism"
            }
        } else {
            // Rotor energy from the same inertia constant used in the swing model.
            val omegaMechanical = max(1.0, freeRotorRpm * 2.0 * PI / 60.0)
            val omegaRated = ReferencePlant.SYNCHRONOUS_RPM * 2.0 * PI / 60.0
            val j = 2.0 * GENERATOR_H_S * ReferencePlant.RATED_GROSS_ELECTRIC_MW * 1e6 /
                (omegaRated * omegaRated)
            val lossesW = 0.015 * ReferencePlant.RATED_GROSS_ELECTRIC_MW * 1e6 *
                (freeRotorRpm / ReferencePlant.SYNCHRONOUS_RPM).coerceAtLeast(0.0)
            val torqueNet = (expansion.mechanicalPowerMw * 1e6 - lossesW) / omegaMechanical
            freeRotorRpm = max(0.0, freeRotorRpm + (torqueNet / j) * dt * 60.0 / (2.0 * PI))
            rpm = freeRotorRpm
            electricalMw = 0.0
            rotorAngle += 2.0 * PI * (rpm / ReferencePlant.SYNCHRONOUS_RPM - 1.0) *
                ReferencePlant.GRID_HZ * dt
        }

        electricalMw = max(0.0, electricalMw * GENERATOR_EFFICIENCY)
        lastGrossMw = electricalMw
        val net = max(0.0, electricalMw - auxiliaryPowerMw)
        val omegaMech = rpm * 2.0 * PI / 60.0
        val omegaRated = ReferencePlant.SYNCHRONOUS_RPM * 2.0 * PI / 60.0
        val j = 2.0 * GENERATOR_H_S * ReferencePlant.RATED_GROSS_ELECTRIC_MW * 1e6 /
            (omegaRated * omegaRated)
        val rotMj = 0.5 * j * omegaMech * omegaMech / 1e6

        lastSnapshot = TurbineGeneratorSnapshot(
            governorValvePosition = valvePosition,
            steamFlowKgS = steamFlow,
            extractionFlowKgS = expansion.extractionFlowKgS,
            extractionEnthalpyKjKg = expansion.extractionEnthalpyKjKg,
            exhaustFlowKgS = expansion.exhaustFlowKgS,
            exhaustEnthalpyKjKg = expansion.exhaustEnthalpyKjKg,
            mechanicalPowerMw = expansion.mechanicalPowerMw,
            generatorGrossMw = electricalMw,
            generatorNetMw = net,
            reactivePowerMvar = reactiveMvar,
            rpm = rpm,
            rotorAngleRad = rotorAngle,
            breakerClosed = breakerClosed,
            synchronized = breakerClosed,
            tripped = turbineTrip,
            storedRotationalEnergyMj = rotMj,
            diagnostic = diagnostic,
        )
        return lastSnapshot
    }

    fun snapshot(): TurbineGeneratorSnapshot = lastSnapshot

    private data class Expansion(
        val extractionFlowKgS: Double,
        val extractionEnthalpyKjKg: Double,
        val exhaustFlowKgS: Double,
        val exhaustEnthalpyKjKg: Double,
        val mechanicalPowerMw: Double,
    )

    private fun thermodynamicExpansion(
        steamFlow: Double,
        inletPressure: Double,
        inletEnthalpy: Double,
        condenserPressure: Double,
        feedwaterFlow: Double,
        condensateEnthalpy: Double,
        mechanicalEfficiency: Double,
    ): Expansion {
        if (steamFlow <= 1.0e-9) {
            return Expansion(0.0, inletEnthalpy, 0.0, inletEnthalpy, 0.0)
        }
        val inlet = water.statePH(max(inletPressure, 0.01), inletEnthalpy)
        val hHpIso = water.enthalpyPS(INTERMEDIATE_PRESSURE_MPA, inlet.entropyKjKgK)
        val hHpOut = inletEnthalpy - ETA_HP * max(0.0, inletEnthalpy - hHpIso)

        val targetFeedH = water.statePT(
            ReferencePlant.SG_PRESSURE_MPA + 1.0,
            ReferencePlant.FEEDWATER_T_K,
        ).enthalpyKjKg
        val heatNeedKw = max(0.0, feedwaterFlow * (targetFeedH - condensateEnthalpy))
        val extractionPotential = max(1.0, hHpOut - condensateEnthalpy)
        val extraction = min(0.45 * steamFlow, heatNeedKw / extractionPotential)
        val lpFlow = max(0.0, steamFlow - extraction)

        val hpOutState = water.statePH(INTERMEDIATE_PRESSURE_MPA, hHpOut)
        val hLpIso = water.enthalpyPS(max(condenserPressure, 0.004), hpOutState.entropyKjKgK)
        val hLpOut = hHpOut - ETA_LP * max(0.0, hHpOut - hLpIso)

        val hpPowerKw = steamFlow * max(0.0, inletEnthalpy - hHpOut)
        val lpPowerKw = lpFlow * max(0.0, hHpOut - hLpOut)
        val mechMw = (hpPowerKw + lpPowerKw) / 1000.0 * mechanicalEfficiency
        return Expansion(
            extractionFlowKgS = extraction,
            extractionEnthalpyKjKg = hHpOut,
            exhaustFlowKgS = lpFlow,
            exhaustEnthalpyKjKg = hLpOut,
            mechanicalPowerMw = mechMw,
        )
    }

    private fun buildInitialSnapshot(): TurbineGeneratorSnapshot {
        val omega = ReferencePlant.SYNCHRONOUS_RPM * 2.0 * PI / 60.0
        val j = 2.0 * GENERATOR_H_S * ReferencePlant.RATED_GROSS_ELECTRIC_MW * 1e6 /
            (omega * omega)
        val headerT = water.saturationP(ReferencePlant.STEAM_HEADER_PRESSURE_MPA).temperatureK + 3.0
        val headerH = water.statePT(ReferencePlant.STEAM_HEADER_PRESSURE_MPA, headerT).enthalpyKjKg
        val condenserH = water.saturationP(ReferencePlant.CONDENSER_PRESSURE_MPA).liquidEnthalpyKjKg
        val expansion = thermodynamicExpansion(
            steamFlow = ratedSteamFlowKgS,
            inletPressure = ReferencePlant.STEAM_HEADER_PRESSURE_MPA,
            inletEnthalpy = headerH,
            condenserPressure = ReferencePlant.CONDENSER_PRESSURE_MPA,
            feedwaterFlow = ratedSteamFlowKgS,
            condensateEnthalpy = condenserH,
            mechanicalEfficiency = calibratedMechanicalEfficiency,
        )
        return TurbineGeneratorSnapshot(
            governorValvePosition = valvePosition,
            steamFlowKgS = ratedSteamFlowKgS,
            extractionFlowKgS = expansion.extractionFlowKgS,
            extractionEnthalpyKjKg = expansion.extractionEnthalpyKjKg,
            exhaustFlowKgS = expansion.exhaustFlowKgS,
            exhaustEnthalpyKjKg = expansion.exhaustEnthalpyKjKg,
            mechanicalPowerMw = expansion.mechanicalPowerMw,
            generatorGrossMw = ReferencePlant.RATED_GROSS_ELECTRIC_MW,
            generatorNetMw = ReferencePlant.RATED_NET_ELECTRIC_MW,
            reactivePowerMvar = 0.0,
            rpm = ReferencePlant.SYNCHRONOUS_RPM,
            rotorAngleRad = rotorAngle,
            breakerClosed = true,
            synchronized = true,
            tripped = false,
            storedRotationalEnergyMj = 0.5 * j * omega * omega / 1e6,
        )
    }
}
