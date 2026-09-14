package com.nyerahworks.criticalstate.sim

import kotlin.math.abs
import kotlin.math.max

internal data class PressurizerSnapshot(
    val pressureMpa: Double,
    val saturationTemperatureK: Double,
    val levelFraction: Double,
    val heaterFraction: Double,
    val sprayFraction: Double,
    val surgeFlowKgPerS: Double,
    val diagnostic: String? = null,
)

/**
 * Reduced-order equilibrium pressurizer.
 *
 * The vessel is represented as a fixed-volume saturated liquid/steam mixture.
 * Its conserved states are total water mass and internal energy. Primary-loop
 * thermal expansion moves inventory through the surge line. Proportional
 * heater and spray controls add/remove energy. Pressure is recovered from the
 * IAPWS-IF97 saturation state that simultaneously satisfies vessel volume,
 * total mass, and total internal energy.
 *
 * This intentionally avoids pretending that the rest of the RCS is already a
 * full hydraulic network. Once explicit hot/cold legs and loop inventories
 * exist, the surge-flow boundary below can be replaced without changing the
 * pressurizer conservation model.
 */
internal class PressurizerModel(
    private val water: WaterProperties = If97WaterProperties(),
) {
    companion object {
        const val REFERENCE_PRESSURE_MPA = 15.51

        // Public four-loop PWR-scale reference values. These remain
        // calibration-required for Critical State's fictional plant definition.
        private const val VESSEL_VOLUME_M3 = 51.0
        private const val REFERENCE_LIQUID_VOLUME_M3 = 30.6
        private const val REFERENCE_VAPOUR_VOLUME_M3 =
            VESSEL_VOLUME_M3 - REFERENCE_LIQUID_VOLUME_M3
        private const val PRIMARY_LIQUID_VOLUME_M3 = 297.0
        private const val REFERENCE_PRIMARY_TEMPERATURE_K = 590.0

        // Control-oriented capacities from public PWR-scale references.
        private const val MAX_HEATER_POWER_MW = 1.872
        private const val MAX_SPRAY_FLOW_KG_PER_S = 39.2

        // Calibration-required controller/hydraulic dynamics.
        private const val PRESSURE_DEADBAND_MPA = 0.025
        private const val FULL_CONTROL_ERROR_MPA = 0.25
        private const val SURGE_RESPONSE_TIME_S = 1.5
        private const val MAX_SURGE_FLOW_KG_PER_S = 300.0
        private const val REFERENCE_PRIMARY_DELTA_T_K = 31.0

        // IF97 supports saturation to the critical point. Keep the numerical
        // pressure solve inside a PWR-relevant envelope with transient room.
        private const val MIN_SOLVE_PRESSURE_MPA = 5.0
        private const val MAX_SOLVE_PRESSURE_MPA = 21.5
        private const val PRESSURE_SOLVE_ITERATIONS = 52
    }

    private data class Equilibrium(
        val pressureMpa: Double,
        val saturationTemperatureK: Double,
        val levelFraction: Double,
        val residualKjPerKg: Double,
    )

    private val referencePressurizerMassKg: Double
    private val totalPrimaryInventoryKg: Double
    private val referenceInternalEnergyKj: Double

    private var massKg: Double
    private var internalEnergyKj: Double
    private var pressureMpa: Double = REFERENCE_PRESSURE_MPA
    private var saturationTemperatureK: Double
    private var levelFraction: Double = REFERENCE_LIQUID_VOLUME_M3 / VESSEL_VOLUME_M3
    private var heaterFraction: Double = 0.0
    private var sprayFraction: Double = 0.0
    private var surgeFlowKgPerS: Double = 0.0
    private var diagnostic: String? = null

    init {
        val vf = water.saturatedLiquidSpecificVolumeM3PerKg(REFERENCE_PRESSURE_MPA)
        val vg = water.saturatedVapourSpecificVolumeM3PerKg(REFERENCE_PRESSURE_MPA)
        val uf = water.saturatedLiquidInternalEnergyKjPerKg(REFERENCE_PRESSURE_MPA)
        val ug = water.saturatedVapourInternalEnergyKjPerKg(REFERENCE_PRESSURE_MPA)

        val liquidMass = REFERENCE_LIQUID_VOLUME_M3 / vf
        val vapourMass = REFERENCE_VAPOUR_VOLUME_M3 / vg

        referencePressurizerMassKg = liquidMass + vapourMass
        referenceInternalEnergyKj = liquidMass * uf + vapourMass * ug

        val primaryMass = water.liquidDensityKgPerM3(
            REFERENCE_PRESSURE_MPA,
            REFERENCE_PRIMARY_TEMPERATURE_K,
        ) * PRIMARY_LIQUID_VOLUME_M3

        totalPrimaryInventoryKg = primaryMass + referencePressurizerMassKg
        massKg = referencePressurizerMassKg
        internalEnergyKj = referenceInternalEnergyKj
        saturationTemperatureK = water.saturationTemperatureK(REFERENCE_PRESSURE_MPA)
    }

    fun reset(): PressurizerSnapshot {
        massKg = referencePressurizerMassKg
        internalEnergyKj = referenceInternalEnergyKj
        pressureMpa = REFERENCE_PRESSURE_MPA
        saturationTemperatureK = water.saturationTemperatureK(REFERENCE_PRESSURE_MPA)
        levelFraction = REFERENCE_LIQUID_VOLUME_M3 / VESSEL_VOLUME_M3
        heaterFraction = 0.0
        sprayFraction = 0.0
        surgeFlowKgPerS = 0.0
        diagnostic = null
        return snapshot()
    }

    fun snapshot(): PressurizerSnapshot = PressurizerSnapshot(
        pressureMpa = pressureMpa,
        saturationTemperatureK = saturationTemperatureK,
        levelFraction = levelFraction,
        heaterFraction = heaterFraction,
        sprayFraction = sprayFraction,
        surgeFlowKgPerS = surgeFlowKgPerS,
        diagnostic = diagnostic,
    )

    fun advance(primaryCoolantTemperatureK: Double, dt: Double): PressurizerSnapshot {
        if (dt <= 0.0) return snapshot()

        diagnostic = null

        val primaryDensity = water.liquidDensityKgPerM3(
            pressureMpa,
            primaryCoolantTemperatureK,
        )
        val primaryMassAtCurrentState = primaryDensity * PRIMARY_LIQUID_VOLUME_M3
        val targetPressurizerMass = totalPrimaryInventoryKg - primaryMassAtCurrentState

        val requestedSurgeFlow =
            ((targetPressurizerMass - massKg) / SURGE_RESPONSE_TIME_S)
                .coerceIn(-MAX_SURGE_FLOW_KG_PER_S, MAX_SURGE_FLOW_KG_PER_S)

        // A small first-order lag keeps the reduced surge boundary from behaving
        // like an infinitely stiff algebraic connection.
        val flowBlend = (dt / SURGE_RESPONSE_TIME_S).coerceIn(0.0, 1.0)
        surgeFlowKgPerS += (requestedSurgeFlow - surgeFlowKgPerS) * flowBlend

        val saturatedLiquidEnthalpy = water.saturatedLiquidEnthalpyKjPerKg(pressureMpa)
        val surgeEnthalpy = if (surgeFlowKgPerS >= 0.0) {
            water.liquidEnthalpyKjPerKg(pressureMpa, primaryCoolantTemperatureK)
        } else {
            saturatedLiquidEnthalpy
        }

        val pressureError = REFERENCE_PRESSURE_MPA - pressureMpa
        heaterFraction = when {
            pressureError <= PRESSURE_DEADBAND_MPA -> 0.0
            else -> ((pressureError - PRESSURE_DEADBAND_MPA) / FULL_CONTROL_ERROR_MPA)
                .coerceIn(0.0, 1.0)
        }

        sprayFraction = when {
            pressureError >= -PRESSURE_DEADBAND_MPA -> 0.0
            else -> ((-pressureError - PRESSURE_DEADBAND_MPA) / FULL_CONTROL_ERROR_MPA)
                .coerceIn(0.0, 1.0)
        }

        val coldLegTemperatureK =
            (primaryCoolantTemperatureK - REFERENCE_PRIMARY_DELTA_T_K / 2.0)
                .coerceAtLeast(273.16)
        val sprayEnthalpy = water.liquidEnthalpyKjPerKg(
            pressureMpa,
            coldLegTemperatureK,
        )
        val sprayCoolingMw =
            sprayFraction * MAX_SPRAY_FLOW_KG_PER_S *
                max(0.0, saturatedLiquidEnthalpy - sprayEnthalpy) / 1000.0
        val heaterPowerMw = heaterFraction * MAX_HEATER_POWER_MW

        massKg = max(1.0, massKg + surgeFlowKgPerS * dt)
        internalEnergyKj += surgeFlowKgPerS * surgeEnthalpy * dt
        internalEnergyKj += (heaterPowerMw - sprayCoolingMw) * 1000.0 * dt

        val equilibrium = solveEquilibrium(massKg, internalEnergyKj)
        pressureMpa = equilibrium.pressureMpa
        saturationTemperatureK = equilibrium.saturationTemperatureK
        levelFraction = equilibrium.levelFraction
        if (abs(equilibrium.residualKjPerKg) > 0.5) {
            diagnostic = "Pressurizer equilibrium solve clamped"
        }

        return snapshot()
    }

    private fun solveEquilibrium(massKg: Double, internalEnergyKj: Double): Equilibrium {
        val targetSpecificVolume = VESSEL_VOLUME_M3 / massKg
        val targetSpecificEnergy = internalEnergyKj / massKg

        fun evaluate(pressure: Double): Equilibrium {
            val vf = water.saturatedLiquidSpecificVolumeM3PerKg(pressure)
            val vg = water.saturatedVapourSpecificVolumeM3PerKg(pressure)
            val uf = water.saturatedLiquidInternalEnergyKjPerKg(pressure)
            val ug = water.saturatedVapourInternalEnergyKjPerKg(pressure)

            val quality =
                ((targetSpecificVolume - vf) / (vg - vf)).coerceIn(0.0, 1.0)
            val predictedSpecificEnergy = uf + quality * (ug - uf)

            val liquidMass = (1.0 - quality) * massKg
            val liquidVolume = liquidMass * vf

            return Equilibrium(
                pressureMpa = pressure,
                saturationTemperatureK = water.saturationTemperatureK(pressure),
                levelFraction = (liquidVolume / VESSEL_VOLUME_M3).coerceIn(0.0, 1.0),
                residualKjPerKg = predictedSpecificEnergy - targetSpecificEnergy,
            )
        }

        var low = MIN_SOLVE_PRESSURE_MPA
        var high = MAX_SOLVE_PRESSURE_MPA
        var lowState = evaluate(low)
        var highState = evaluate(high)

        if (lowState.residualKjPerKg == 0.0) return lowState
        if (highState.residualKjPerKg == 0.0) return highState

        if (lowState.residualKjPerKg * highState.residualKjPerKg > 0.0) {
            return if (abs(lowState.residualKjPerKg) < abs(highState.residualKjPerKg)) {
                lowState
            } else {
                highState
            }
        }

        repeat(PRESSURE_SOLVE_ITERATIONS) {
            val mid = 0.5 * (low + high)
            val midState = evaluate(mid)

            if (lowState.residualKjPerKg * midState.residualKjPerKg <= 0.0) {
                high = mid
                highState = midState
            } else {
                low = mid
                lowState = midState
            }
        }

        return if (abs(lowState.residualKjPerKg) < abs(highState.residualKjPerKg)) {
            lowState
        } else {
            highState
        }
    }
}
