package com.nyerahworks.criticalstate.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorSimulatorTest {
    @Test
    fun referencePlantStartsAtDeclaredDesignPoint() {
        val simulator = ReactorSimulator()
        val state = simulator.snapshot()

        assertEquals(ReferencePlant.RATED_THERMAL_POWER_MW, state.fissionPowerMw, 1.0e-6)
        assertEquals(ReferencePlant.PRIMARY_PRESSURE_MPA, state.primaryPressureMpa, 0.03)
        assertEquals(ReferencePlant.CORE_FLOW_KG_PER_S, state.totalPrimaryFlowKgPerS,
            ReferencePlant.CORE_FLOW_KG_PER_S * 0.03)
        assertEquals(ReferencePlant.REFERENCE_ROD_INSERTION, state.rodInsertion, 1.0e-9)
        assertFalse(state.tripped)
    }

    @Test
    fun coupledReferenceStateRemainsFiniteAndPlantLike() {
        val simulator = ReactorSimulator()
        val state = simulator.advance(wallSeconds = 3.0, timeScale = 1.0)

        assertTrue(state.fissionPowerMw.isFinite() && state.fissionPowerMw > 0.0)
        assertTrue(state.totalCoreHeatMw.isFinite() && state.totalCoreHeatMw > 0.0)
        assertTrue(state.primaryPressureMpa in 10.0..20.0)
        assertTrue(state.totalPrimaryFlowKgPerS > 0.35 * ReferencePlant.CORE_FLOW_KG_PER_S)
        assertTrue(state.steamGeneratorPressureMpa.all { it.isFinite() && it > 0.5 })
        assertTrue(state.steamGeneratorLevelFraction.all { it in 0.0..1.0 })
        assertTrue(state.condenserPressureKpa.isFinite() && state.condenserPressureKpa > 0.0)
        assertTrue(state.generatorPowerMw.isFinite() && state.generatorPowerMw >= 0.0)
    }

    @Test
    fun rodCommandMovesDriveRatherThanTeleportingBank() {
        val simulator = ReactorSimulator()
        simulator.setRodInsertion(0.35)
        val immediate = simulator.snapshot()
        assertEquals(ReferencePlant.REFERENCE_ROD_INSERTION, immediate.rodInsertion, 1.0e-9)

        val after = simulator.advance(1.0, 1.0)
        assertTrue(after.rodInsertion < ReferencePlant.REFERENCE_ROD_INSERTION)
        assertTrue(after.rodInsertion > 0.35)
    }

    @Test
    fun withdrawalRaisesNeutronPowerThroughKinetics() {
        val simulator = ReactorSimulator()
        simulator.setRodInsertion(0.45)
        val state = simulator.advance(wallSeconds = 4.0, timeScale = 1.0)

        assertTrue(state.fissionPowerMw > 0.98 * ReferencePlant.RATED_THERMAL_POWER_MW)
        assertTrue(state.rodReactivityPcm > 0.0)
    }

    @Test
    fun reactorTripUsesRodInsertionDynamicsAndLeavesDecayHeat() {
        val simulator = ReactorSimulator()
        simulator.trip()
        val immediate = simulator.snapshot()
        assertTrue(immediate.tripped)
        assertTrue(immediate.rodInsertion < 1.0)

        val state = simulator.advance(3.0, 1.0)
        assertTrue(state.rodInsertion > ReferencePlant.REFERENCE_ROD_INSERTION)
        assertTrue(state.decayHeatMw > 0.0)
        assertTrue(state.fissionPowerMw < ReferencePlant.RATED_THERMAL_POWER_MW)
    }

    @Test
    fun rcpTripProducesCoastdownNotInstantZeroFlow() {
        val simulator = ReactorSimulator()
        val initial = simulator.snapshot()
        simulator.setReactorCoolantPump(0, false)
        val state = simulator.advance(2.0, 1.0)

        assertTrue(state.loopPumpRpm[0] < initial.loopPumpRpm[0])
        assertTrue(state.loopPumpRpm[0] > 0.0)
        assertTrue(state.loopFlowKgPerS[0] > 0.0)
    }

    @Test
    fun turbineTripAtPowerTriggersReactorProtection() {
        val simulator = ReactorSimulator()
        simulator.tripTurbine()
        val state = simulator.advance(0.5, 1.0)

        assertTrue(state.tripped)
        assertTrue(state.tripReasons.any { it.contains("TURBINE") })
    }

    @Test
    fun fastForwardPreservesFinitePhysicalState() {
        val simulator = ReactorSimulator()
        val state = simulator.advance(wallSeconds = 0.5, timeScale = 60.0)

        assertEquals(30.0, state.simulationSeconds, 0.2)
        assertTrue(state.fissionPowerMw.isFinite() && state.fissionPowerMw >= 0.0)
        assertTrue(state.primaryPressureMpa.isFinite())
        assertTrue(state.loopFlowKgPerS.all { it.isFinite() })
        assertTrue(state.steamGeneratorLevelFraction.all { it.isFinite() })
        assertTrue(state.massConservationErrorKg.isFinite())
        assertTrue(state.energyConservationErrorMj.isFinite())
    }

    @Test
    fun inhourSolverReturnsPositivePeriodForPositiveReactivity() {
        val kinetics = PointKineticsModel()
        val period = kinetics.inhourPeriod(50.0e-5)
        assertTrue(period != null && period > 0.0 && period.isFinite())
    }

    @Test
    fun waterPropertyLayerReturnsConsistentPwrState() {
        val water = If97WaterProperties()
        val state = water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.COLD_LEG_T_K)
        val recovered = water.statePH(ReferencePlant.PRIMARY_PRESSURE_MPA, state.enthalpyKjKg)

        assertEquals(ReferencePlant.COLD_LEG_T_K, recovered.temperatureK, 0.05)
        assertTrue(state.densityKgM3 > 500.0)
        assertTrue(state.cpKjKgK > 3.0)
        assertTrue(state.viscosityPaS > 0.0)
    }
}
