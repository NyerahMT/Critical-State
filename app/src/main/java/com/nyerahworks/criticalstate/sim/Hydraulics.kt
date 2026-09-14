package com.nyerahworks.criticalstate.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

internal data class PumpSnapshot(
    val rpm: Double,
    val running: Boolean,
    val headM: Double,
    val hydraulicPowerMw: Double,
    val shaftPowerMw: Double,
    val efficiency: Double,
)

/** Generic centrifugal pump with affinity scaling and rotor inertia. */
internal class CentrifugalPump(
    private val ratedRpm: Double,
    private val ratedFlowKgS: Double,
    private val ratedDensityKgM3: Double,
    private val shutoffHeadM: Double,
    private val ratedHeadM: Double,
    private val ratedEfficiency: Double,
    private val rotorInertiaKgM2: Double,
) {
    private val ratedOmega = ratedRpm * 2.0 * PI / 60.0
    private val ratedVolumetricFlow = ratedFlowKgS / ratedDensityKgM3
    private val headK = (shutoffHeadM - ratedHeadM) /
        max(ratedVolumetricFlow * ratedVolumetricFlow, 1.0e-9)
    private val ratedHydraulicW = ratedDensityKgM3 * 9.80665 * ratedHeadM * ratedVolumetricFlow
    private val ratedShaftW = ratedHydraulicW / ratedEfficiency
    private val ratedHydraulicTorque = ratedShaftW / ratedOmega
    private val ratedFrictionTorque = 0.02 * ratedHydraulicTorque
    private val ratedDriveTorque = ratedHydraulicTorque + ratedFrictionTorque

    private var omega = ratedOmega
    private var running = true

    fun setRunning(on: Boolean) {
        running = on
    }

    fun reset() {
        omega = ratedOmega
        running = true
    }

    fun advance(
        massFlowKgS: Double,
        densityKgM3: Double,
        condition: Double,
        dt: Double,
    ): PumpSnapshot {
        val speedRatio = (omega / ratedOmega).coerceAtLeast(0.0)
        val flowQ = massFlowKgS / max(densityKgM3, 1.0)
        val head = headFor(flowQ, speedRatio, condition)
        val efficiency = (ratedEfficiency * (0.88 + 0.12 * condition))
            .coerceIn(0.45, 0.92)
        val hydraulicW = if (massFlowKgS >= 0.0) densityKgM3 * 9.80665 * head * flowQ else 0.0
        val shaftW = max(0.0, hydraulicW) / efficiency
        val hydTorque = if (omega > 2.0) shaftW / omega else 0.0
        val frictionTorque = ratedFrictionTorque * speedRatio * (1.0 + 0.8 * (1.0 - condition))
        val motorTorque = if (running) {
            (ratedDriveTorque + 3.0 * ratedDriveTorque * (1.0 - speedRatio))
                .coerceIn(0.0, 4.0 * ratedDriveTorque)
        } else 0.0
        val domega = (motorTorque - hydTorque - frictionTorque) / rotorInertiaKgM2
        omega = (omega + domega * dt).coerceAtLeast(0.0)

        return PumpSnapshot(
            rpm = omega * 60.0 / (2.0 * PI),
            running = running,
            headM = head,
            hydraulicPowerMw = hydraulicW / 1.0e6,
            shaftPowerMw = shaftW / 1.0e6,
            efficiency = efficiency,
        )
    }

    fun snapshot(
        massFlowKgS: Double,
        densityKgM3: Double,
        condition: Double,
    ): PumpSnapshot {
        val speedRatio = (omega / ratedOmega).coerceAtLeast(0.0)
        val q = massFlowKgS / max(densityKgM3, 1.0)
        val h = headFor(q, speedRatio, condition)
        val eta = (ratedEfficiency * (0.88 + 0.12 * condition)).coerceIn(0.45, 0.92)
        val hyd = max(0.0, densityKgM3 * 9.80665 * h * q)
        return PumpSnapshot(
            rpm = omega * 60.0 / (2.0 * PI),
            running = running,
            headM = h,
            hydraulicPowerMw = hyd / 1e6,
            shaftPowerMw = hyd / eta / 1e6,
            efficiency = eta,
        )
    }

    private fun headFor(qM3S: Double, speedRatio: Double, condition: Double): Double {
        if (speedRatio < 1.0e-4) return 0.0
        val equivalentQ = qM3S / speedRatio
        val base = shutoffHeadM - headK * equivalentQ * abs(equivalentQ)
        return max(0.0, speedRatio * speedRatio * base * (0.92 + 0.08 * condition))
    }
}

internal data class PrimaryLoopSnapshot(
    val index: Int,
    val massFlowKgS: Double,
    val hotLegTemperatureK: Double,
    val coldLegTemperatureK: Double,
    val hotLegEnthalpyKjKg: Double,
    val coldLegEnthalpyKjKg: Double,
    val pump: PumpSnapshot,
    val pressureLossMpa: Double,
    val buoyancyHeadKpa: Double,
    val liquidMassKg: Double,
    val storedEnergyMj: Double,
)

/** One explicit hydraulic state for each of the four primary loops. */
internal class PrimaryLoopModel(
    private val index: Int,
    private val water: WaterProperties,
) {
    companion object {
        private const val HOT_LEG_VOLUME_M3 = 25.0
        private const val COLD_LEG_VOLUME_M3 = ReferencePlant.LOOP_LIQUID_VOLUME_M3 - HOT_LEG_VOLUME_M3
    }

    private var massFlowKgS = ReferencePlant.FLOW_PER_LOOP_KG_PER_S
    private var hotLegEnthalpy = water.statePT(
        ReferencePlant.PRIMARY_PRESSURE_MPA,
        ReferencePlant.HOT_LEG_T_K,
    ).enthalpyKjKg
    private var coldLegEnthalpy = water.statePT(
        ReferencePlant.PRIMARY_PRESSURE_MPA,
        ReferencePlant.COLD_LEG_T_K,
    ).enthalpyKjKg

    private val referenceDensity = 0.5 * (
        water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.HOT_LEG_T_K).densityKgM3 +
            water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.COLD_LEG_T_K).densityKgM3
        )
    private val pump = CentrifugalPump(
        ratedRpm = ReferencePlant.RCP_RATED_RPM,
        ratedFlowKgS = ReferencePlant.FLOW_PER_LOOP_KG_PER_S,
        ratedDensityKgM3 = referenceDensity,
        shutoffHeadM = ReferencePlant.RCP_SHUTOFF_HEAD_M,
        ratedHeadM = ReferencePlant.RCP_RATED_HEAD_M,
        ratedEfficiency = ReferencePlant.RCP_EFFICIENCY,
        rotorInertiaKgM2 = ReferencePlant.RCP_ROTOR_INERTIA_KG_M2,
    )

    private val flowArea = PI * ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M.pow(2.0) / 4.0
    private val calibratedMinorK: Double = calculateReferenceMinorK()

    fun setPumpRunning(on: Boolean) = pump.setRunning(on)

    fun reset() {
        massFlowKgS = ReferencePlant.FLOW_PER_LOOP_KG_PER_S
        hotLegEnthalpy = water.statePT(
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            ReferencePlant.HOT_LEG_T_K,
        ).enthalpyKjKg
        coldLegEnthalpy = water.statePT(
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            ReferencePlant.COLD_LEG_T_K,
        ).enthalpyKjKg
        pump.reset()
    }

    /** Hot/cold leg transport volumes preserve finite coolant travel time. */
    fun advanceTransport(
        coreOutletEnthalpyKjKg: Double,
        sgOutletEnthalpyKjKg: Double,
        pressureMpa: Double,
        dt: Double,
    ) {
        val hot = water.statePH(pressureMpa, hotLegEnthalpy)
        val cold = water.statePH(pressureMpa, coldLegEnthalpy)
        val hotMass = max(1.0, hot.densityKgM3 * HOT_LEG_VOLUME_M3)
        val coldMass = max(1.0, cold.densityKgM3 * COLD_LEG_VOLUME_M3)
        val flow = max(0.0, massFlowKgS)
        hotLegEnthalpy += flow * (coreOutletEnthalpyKjKg - hotLegEnthalpy) / hotMass * dt
        coldLegEnthalpy += flow * (sgOutletEnthalpyKjKg - coldLegEnthalpy) / coldMass * dt
    }

    fun advanceHydraulics(
        pressureMpa: Double,
        condition: Double,
        dt: Double,
    ): PrimaryLoopSnapshot {
        val hot = water.statePH(pressureMpa, hotLegEnthalpy)
        val cold = water.statePH(pressureMpa, coldLegEnthalpy)
        val rho = 0.5 * (hot.densityKgM3 + cold.densityKgM3)
        val pumpState = pump.advance(massFlowKgS, rho, condition, dt)
        val pumpPressurePa = rho * 9.80665 * pumpState.headM
        val buoyancyPa = 9.80665 * ReferencePlant.LOOP_EFFECTIVE_ELEVATION_M *
            (cold.densityKgM3 - hot.densityKgM3)
        val lossPa = pressureLossPa(massFlowKgS, rho, hot.viscosityPaS)
        val residualPa = pumpPressurePa + buoyancyPa - lossPa
        val dmdt = flowArea / ReferencePlant.LOOP_EQUIVALENT_LENGTH_M * residualPa
        massFlowKgS += dmdt * dt
        // Reverse natural circulation is permitted, but bound only at an extreme
        // numerical envelope rather than forcing pump-off flow to zero.
        if (!massFlowKgS.isFinite()) massFlowKgS = 0.0
        massFlowKgS = massFlowKgS.coerceIn(-0.25 * ReferencePlant.FLOW_PER_LOOP_KG_PER_S,
            1.6 * ReferencePlant.FLOW_PER_LOOP_KG_PER_S)
        return snapshot(pressureMpa, condition)
    }

    fun snapshot(
        pressureMpa: Double,
        condition: Double,
    ): PrimaryLoopSnapshot {
        val hot = water.statePH(pressureMpa, hotLegEnthalpy)
        val cold = water.statePH(pressureMpa, coldLegEnthalpy)
        val rho = 0.5 * (hot.densityKgM3 + cold.densityKgM3)
        val lossPa = pressureLossPa(massFlowKgS, rho, 0.5 * (hot.viscosityPaS + cold.viscosityPaS))
        val buoyancyPa = 9.80665 * ReferencePlant.LOOP_EFFECTIVE_ELEVATION_M *
            (cold.densityKgM3 - hot.densityKgM3)
        val hotMass = hot.densityKgM3 * HOT_LEG_VOLUME_M3
        val coldMass = cold.densityKgM3 * COLD_LEG_VOLUME_M3
        return PrimaryLoopSnapshot(
            index = index,
            massFlowKgS = massFlowKgS,
            hotLegTemperatureK = hot.temperatureK,
            coldLegTemperatureK = cold.temperatureK,
            hotLegEnthalpyKjKg = hotLegEnthalpy,
            coldLegEnthalpyKjKg = coldLegEnthalpy,
            pump = pump.snapshot(massFlowKgS, rho, condition),
            pressureLossMpa = lossPa / 1.0e6,
            buoyancyHeadKpa = buoyancyPa / 1000.0,
            liquidMassKg = hotMass + coldMass,
            storedEnergyMj = (
                hotMass * hot.internalEnergyKjKg + coldMass * cold.internalEnergyKjKg
                ) / 1000.0,
        )
    }

    private fun calculateReferenceMinorK(): Double {
        val hot = water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.HOT_LEG_T_K)
        val cold = water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.COLD_LEG_T_K)
        val rho = 0.5 * (hot.densityKgM3 + cold.densityKgM3)
        val q = ReferencePlant.FLOW_PER_LOOP_KG_PER_S / rho
        val v = q / flowArea
        val dyn = 0.5 * rho * v * v
        val re = rho * abs(v) * ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M /
            max(0.5 * (hot.viscosityPaS + cold.viscosityPaS), 1.0e-9)
        val f = frictionFactor(re)
        val straight = f * ReferencePlant.LOOP_EQUIVALENT_LENGTH_M /
            ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M
        val pumpPa = rho * 9.80665 * ReferencePlant.RCP_RATED_HEAD_M
        val buoyPa = 9.80665 * ReferencePlant.LOOP_EFFECTIVE_ELEVATION_M *
            (cold.densityKgM3 - hot.densityKgM3)
        return max(0.0, (pumpPa + buoyPa) / max(dyn, 1.0) - straight)
    }

    private fun pressureLossPa(flowKgS: Double, rho: Double, viscosity: Double): Double {
        if (abs(flowKgS) < 1.0e-9) return 0.0
        val v = flowKgS / max(rho * flowArea, 1.0e-9)
        val re = rho * abs(v) * ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M /
            max(viscosity, 1.0e-9)
        val f = frictionFactor(re)
        val coefficient = f * ReferencePlant.LOOP_EQUIVALENT_LENGTH_M /
            ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M + calibratedMinorK
        return sign(v) * coefficient * 0.5 * rho * v * v
    }

    private fun frictionFactor(re: Double): Double = when {
        re <= 1.0 -> 64.0
        re < 2300.0 -> 64.0 / re
        else -> {
            // Haaland explicit approximation to Colebrook-White.
            val term = (ReferencePlant.LOOP_ROUGHNESS_M /
                (3.7 * ReferencePlant.LOOP_EQUIVALENT_DIAMETER_M)).pow(1.11) + 6.9 / re
            1.0 / (-1.8 * log10(term)).pow(2.0)
        }
    }
}
