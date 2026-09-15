package com.nyerahworks.criticalstate

import com.nyerahworks.criticalstate.sim.InstrumentationModel
import com.nyerahworks.criticalstate.sim.PlantState
import com.nyerahworks.criticalstate.sim.ReferencePlant
import kotlin.math.max

/**
 * Presentation boundary between the true plant state and the operator glass.
 *
 * Physics/tests keep ReactorSimulator.snapshot() as the true reduced-order state.
 * The runtime publishes this indicated copy so process values that already have
 * instrumentation channels inherit their lag/bias behavior instead of exposing
 * the underlying solver state directly.
 */
internal class OperatorGlass {
    private val instruments = InstrumentationModel()

    fun reset() {
        instruments.reset()
    }

    fun present(trueState: PlantState, dt: Double): PlantState {
        val indicated = instruments.advance(
            reactorPowerFraction = trueState.fissionPowerMw / ReferencePlant.RATED_THERMAL_POWER_MW,
            primaryPressureMpa = trueState.primaryPressureMpa,
            totalFlowKgS = trueState.totalPrimaryFlowKgPerS,
            hotLegK = trueState.hotLegTemperatureK,
            coldLegK = trueState.coldLegTemperatureK,
            averageSgLevel = trueState.steamGeneratorLevelFraction.average(),
            averageSgPressureMpa = trueState.steamGeneratorPressureMpa.average(),
            turbineRpm = trueState.turbineRpm,
            generatorMw = trueState.generatorGrossPowerMw,
            condenserPressureMpa = trueState.condenserPressureKpa / 1000.0,
            dt = max(0.0, dt),
        )

        val indicatedNetMw = max(0.0, indicated.generatorMw - trueState.auxiliaryPowerMw)
        return trueState.copy(
            fissionPowerMw = indicated.reactorPowerFraction * ReferencePlant.RATED_THERMAL_POWER_MW,
            primaryPressureMpa = indicated.primaryPressureMpa,
            totalPrimaryFlowKgPerS = indicated.totalPrimaryFlowKgS,
            hotLegTemperatureK = indicated.hotLegTemperatureK,
            coldLegTemperatureK = indicated.coldLegTemperatureK,
            turbineRpm = indicated.turbineRpm,
            generatorGrossPowerMw = indicated.generatorMw,
            generatorPowerMw = indicatedNetMw,
            condenserPressureKpa = indicated.condenserPressureKpa,
        )
    }
}
