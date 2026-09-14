package com.nyerahworks.criticalstate.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorSimulatorTest {
    @Test
    fun referenceStateRemainsNearEquilibrium() {
        val simulator = ReactorSimulator()
        val state = simulator.advance(wallSeconds = 20.0, timeScale = 1.0)

        assertEquals(
            ReactorSimulator.REFERENCE_THERMAL_POWER_MW,
            state.fissionPowerMw,
            ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 0.002,
        )
        assertEquals(590.0, state.coolantTemperatureK, 0.5)
        assertEquals(
            ReactorSimulator.REFERENCE_PRIMARY_PRESSURE_MPA,
            state.primaryPressureMpa,
            0.02,
        )
        assertEquals(0.60, state.pressurizerLevelFraction, 0.02)
    }

    @Test
    fun rodWithdrawalRaisesFissionPower() {
        val simulator = ReactorSimulator()
        simulator.setRodInsertion(0.45)

        val state = simulator.advance(wallSeconds = 2.0, timeScale = 1.0)

        assertTrue(state.fissionPowerMw > ReactorSimulator.REFERENCE_THERMAL_POWER_MW)
        assertTrue(state.totalReactivityPcm > 0.0)
    }

    @Test
    fun primaryHeatupProducesPositivePressurizerSurge() {
        val pressurizer = PressurizerModel()
        var state = pressurizer.snapshot()

        repeat(100) {
            state = pressurizer.advance(
                primaryCoolantTemperatureK = 600.0,
                dt = 0.02,
            )
        }

        assertTrue(state.surgeFlowKgPerS > 0.0)
        assertTrue(state.pressureMpa.isFinite())
        assertTrue(state.levelFraction in 0.0..1.0)
    }

    @Test
    fun primaryCooldownProducesNegativePressurizerSurge() {
        val pressurizer = PressurizerModel()
        var state = pressurizer.snapshot()

        repeat(100) {
            state = pressurizer.advance(
                primaryCoolantTemperatureK = 580.0,
                dt = 0.02,
            )
        }

        assertTrue(state.surgeFlowKgPerS < 0.0)
        assertTrue(state.pressureMpa.isFinite())
        assertTrue(state.levelFraction in 0.0..1.0)
    }
}
