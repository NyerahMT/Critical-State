package com.nyerahworks.criticalstate.sim

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * First playable reduced-order plant slice.
 *
 * The neutronics model follows the six-group point-kinetics structure from the
 * NYERAH Reactor Simulator Model Theory document. Thermal/secondary constants
 * below are intentionally tagged as estimated/calibration-required scaffolding
 * until benchmark fitting is added.
 */
class ReactorSimulator {
    companion object {
        const val REFERENCE_THERMAL_POWER_MW = 3411.0
        const val REFERENCE_PRIMARY_PRESSURE_MPA = 15.51

        private const val REFERENCE_FUEL_TEMP_K = 1080.0
        private const val REFERENCE_COOLANT_TEMP_K = 590.0
        private const val REFERENCE_ROD_INSERTION = 0.55

        // Reference six-group thermal U-235 set from the model-theory document.
        private val BETA = doubleArrayOf(
            0.000215,
            0.001424,
            0.001274,
            0.002568,
            0.000748,
            0.000273,
        )
        private val LAMBDA = doubleArrayOf(
            0.0124,
            0.0305,
            0.111,
            0.301,
            1.14,
            3.01,
        )
        private val BETA_TOTAL = BETA.sum()

        // Estimated generic value; must be replaced/calibrated in plant definition.
        private const val PROMPT_GENERATION_TIME_S = 2.0e-5

        // Calibration-required generic fallback for an S-shaped integral rod-worth curve.
        private const val TOTAL_BANK_WORTH = 0.0040

        // Calibration-required feedback coefficients for the first playable slice.
        private const val DOPPLER_COEFF_PER_K = -1.4e-5
        private const val MODERATOR_COEFF_PER_K = -4.0e-6

        // Estimated lumped thermal parameters. They preserve energy causality but are
        // not yet benchmark-calibrated plant constants.
        private const val FUEL_HEAT_CAPACITY_MJ_PER_K = 360.0
        private const val COOLANT_HEAT_CAPACITY_MJ_PER_K = 900.0
        private const val FUEL_TO_COOLANT_MW_PER_K =
            REFERENCE_THERMAL_POWER_MW / (REFERENCE_FUEL_TEMP_K - REFERENCE_COOLANT_TEMP_K)
        private const val SECONDARY_RESPONSE_TIME_S = 6.0
        private const val THERMAL_TO_ELECTRIC_EFFICIENCY = 0.327
        private const val MAX_INTERNAL_STEP_S = 0.02
    }

    private var state = PlantState()
    private val precursors = DoubleArray(6) { i ->
        BETA[i] / (PROMPT_GENERATION_TIME_S * LAMBDA[i])
    }

    fun snapshot(): PlantState = state

    fun setRodInsertion(fraction: Double) {
        if (state.tripped) return
        state = state.copy(rodInsertion = fraction.coerceIn(0.0, 1.0))
    }

    fun setTurbineLoad(fraction: Double) {
        state = state.copy(turbineLoad = fraction.coerceIn(0.30, 1.10))
    }

    fun trip() {
        state = state.copy(tripped = true, rodInsertion = 1.0)
    }

    fun reset() {
        state = PlantState()
        for (i in precursors.indices) {
            precursors[i] = BETA[i] / (PROMPT_GENERATION_TIME_S * LAMBDA[i])
        }
    }

    fun advance(wallSeconds: Double, timeScale: Double): PlantState {
        var remaining = max(0.0, wallSeconds * max(0.0, timeScale))
        while (remaining > 0.0) {
            val dt = min(MAX_INTERNAL_STEP_S, remaining)
            advanceInternal(dt)
            remaining -= dt
        }
        return state
    }

    private fun advanceInternal(dt: Double) {
        val old = state
        val rhoRod = rodReactivity(old.rodInsertion)
        val rhoDoppler = DOPPLER_COEFF_PER_K * (old.fuelTemperatureK - REFERENCE_FUEL_TEMP_K)
        val rhoModerator = MODERATOR_COEFF_PER_K * (old.coolantTemperatureK - REFERENCE_COOLANT_TEMP_K)
        val totalRho = rhoRod + rhoDoppler + rhoModerator

        val oldN = max(old.neutronPopulation, 1.0e-15)
        val a = (totalRho - BETA_TOTAL) / PROMPT_GENERATION_TIME_S

        var rhs = oldN
        var delayedDenominatorTerm = 0.0
        for (i in 0 until 6) {
            val groupDenominator = 1.0 + dt * LAMBDA[i]
            rhs += dt * LAMBDA[i] * precursors[i] / groupDenominator
            delayedDenominatorTerm +=
                dt * LAMBDA[i] * (dt * BETA[i] / PROMPT_GENERATION_TIME_S) / groupDenominator
        }

        val denominator = 1.0 - dt * a - delayedDenominatorTerm
        var diagnostic: String? = null
        var newN = if (denominator > 1.0e-12) rhs / denominator else oldN

        if (!newN.isFinite() || newN <= 0.0) {
            newN = oldN
            diagnostic = "Neutronics guard engaged"
        }

        for (i in 0 until 6) {
            precursors[i] =
                (precursors[i] + dt * BETA[i] / PROMPT_GENERATION_TIME_S * newN) /
                    (1.0 + dt * LAMBDA[i])
        }

        val fissionPowerMw = newN * REFERENCE_THERMAL_POWER_MW
        val fuelToCoolantMw = FUEL_TO_COOLANT_MW_PER_K *
            (old.fuelTemperatureK - old.coolantTemperatureK)

        val secondaryTargetMw = REFERENCE_THERMAL_POWER_MW * old.turbineLoad
        val newSecondaryRemovalMw = old.secondaryHeatRemovalMw +
            (secondaryTargetMw - old.secondaryHeatRemovalMw) * dt / SECONDARY_RESPONSE_TIME_S

        val newFuelTempK = old.fuelTemperatureK +
            (fissionPowerMw - fuelToCoolantMw) / FUEL_HEAT_CAPACITY_MJ_PER_K * dt
        val newCoolantTempK = old.coolantTemperatureK +
            (fuelToCoolantMw - newSecondaryRemovalMw) / COOLANT_HEAT_CAPACITY_MJ_PER_K * dt

        val omega = if (newN > 0.0 && oldN > 0.0) ln(newN / oldN) / dt else 0.0
        val period = when {
            kotlin.math.abs(omega) < 1.0e-8 -> null
            else -> 1.0 / omega
        }

        state = old.copy(
            simulationSeconds = old.simulationSeconds + dt,
            neutronPopulation = newN,
            fissionPowerMw = fissionPowerMw,
            fuelTemperatureK = newFuelTempK,
            coolantTemperatureK = newCoolantTempK,
            primaryPressureMpa = REFERENCE_PRIMARY_PRESSURE_MPA,
            secondaryHeatRemovalMw = newSecondaryRemovalMw,
            generatorPowerMw = newSecondaryRemovalMw * THERMAL_TO_ELECTRIC_EFFICIENCY,
            totalReactivityPcm = totalRho * 100_000.0,
            reactorPeriodSeconds = period,
            diagnostic = diagnostic,
        )
    }

    private fun rodReactivity(insertion: Double): Double {
        fun smoothStep(x: Double): Double {
            val c = x.coerceIn(0.0, 1.0)
            return c * c * (3.0 - 2.0 * c)
        }

        return TOTAL_BANK_WORTH *
            (smoothStep(REFERENCE_ROD_INSERTION) - smoothStep(insertion))
    }
}
