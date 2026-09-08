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
    }

    @Test
    fun rodWithdrawalRaisesFissionPower() {
        val simulator = ReactorSimulator()
        simulator.setRodInsertion(0.45)

        val state = simulator.advance(wallSeconds = 2.0, timeScale = 1.0)

        assertTrue(state.fissionPowerMw > ReactorSimulator.REFERENCE_THERMAL_POWER_MW)
        assertTrue(state.totalReactivityPcm > 0.0)
    }
}
