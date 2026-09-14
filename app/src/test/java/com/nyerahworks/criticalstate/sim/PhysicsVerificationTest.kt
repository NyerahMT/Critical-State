package com.nyerahworks.criticalstate.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lower-level equation/behavior checks corresponding to the model-theory V&V matrix. */
class PhysicsVerificationTest {
    private val water: WaterProperties = If97WaterProperties()

    @Test
    fun criticalPointKineticsPreservesEquilibrium() {
        val kinetics = PointKineticsModel()
        repeat(2_000) { kinetics.advance(reactivity = 0.0, dt = 0.005) }
        val state = kinetics.snapshot()
        assertEquals(1.0, state.neutronPopulation, 2.0e-6)
        assertTrue(state.precursors.all { it.isFinite() && it >= 0.0 })
    }

    @Test
    fun positiveReactivityInhourAndSimulatedPeriodHaveSameSign() {
        val kinetics = PointKineticsModel()
        val rho = 50.0e-5
        val inhour = kinetics.inhourPeriod(rho)
        repeat(20_000) { kinetics.advance(rho, 0.005) }
        val simulated = kinetics.snapshot().periodSeconds
        assertTrue(inhour != null && inhour > 0.0)
        assertTrue(simulated != null && simulated > 0.0)
    }

    @Test
    fun shutdownXenonRisesBeforeLongTermDecay() {
        val poison = PoisonModel()
        var sixHours = poison.snapshot()
        repeat(6 * 60) { sixHours = poison.advance(powerFraction = 0.0, dt = 60.0) }
        assertTrue(sixHours.xenon > 1.0)
        assertTrue(sixHours.xenonReactivity < 0.0)

        var longShutdown = sixHours
        repeat(7 * 24 * 60) { longShutdown = poison.advance(powerFraction = 0.0, dt = 60.0) }
        assertTrue(longShutdown.xenon < sixHours.xenon)
    }

    @Test
    fun shutdownSamariumBuildsFromPromethiumInventory() {
        val poison = PoisonModel()
        var state = poison.snapshot()
        repeat(24 * 60) { state = poison.advance(powerFraction = 0.0, dt = 60.0) }
        assertTrue(state.samarium > 1.0)
        assertTrue(state.samariumReactivity < 0.0)
    }

    @Test
    fun decayHeatIsSeparateAndPersistsAfterFissionStops() {
        val decay = DecayHeatModel()
        val initial = decay.snapshot()
        assertEquals(
            DecayHeatModel.TOTAL_DELAYED_HEAT_FRACTION * ReferencePlant.RATED_THERMAL_POWER_MW,
            initial.powerMw,
            1.0e-6,
        )
        val afterMinute = decay.advance(fissionPowerMw = 0.0, dt = 60.0)
        val afterHour = decay.advance(fissionPowerMw = 0.0, dt = 3540.0)
        assertTrue(afterMinute.powerMw > 0.0)
        assertTrue(afterHour.powerMw > 0.0)
        assertTrue(afterHour.powerMw < afterMinute.powerMw)
    }

    @Test
    fun pumpRatedOperatingPointProducesRatedHead() {
        val density = water.statePT(
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            0.5 * (ReferencePlant.HOT_LEG_T_K + ReferencePlant.COLD_LEG_T_K),
        ).densityKgM3
        val pump = CentrifugalPump(
            ratedRpm = ReferencePlant.RCP_RATED_RPM,
            ratedFlowKgS = ReferencePlant.FLOW_PER_LOOP_KG_PER_S,
            ratedDensityKgM3 = density,
            shutoffHeadM = ReferencePlant.RCP_SHUTOFF_HEAD_M,
            ratedHeadM = ReferencePlant.RCP_RATED_HEAD_M,
            ratedEfficiency = ReferencePlant.RCP_EFFICIENCY,
            rotorInertiaKgM2 = ReferencePlant.RCP_ROTOR_INERTIA_KG_M2,
        )
        val rated = pump.snapshot(ReferencePlant.FLOW_PER_LOOP_KG_PER_S, density, 1.0)
        assertEquals(ReferencePlant.RCP_RATED_HEAD_M, rated.headM, 1.0e-6)
        assertTrue(rated.shaftPowerMw > rated.hydraulicPowerMw)
    }

    @Test
    fun primaryLoopRatedPumpAndSystemLossAreNearBalance() {
        val loop = PrimaryLoopModel(0, water)
        val initial = loop.snapshot(ReferencePlant.PRIMARY_PRESSURE_MPA, 1.0)
        val pumpPressureMpa = 0.5 * (
            water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, initial.hotLegTemperatureK).densityKgM3 +
                water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, initial.coldLegTemperatureK).densityKgM3
            ) * 9.80665 * initial.pump.headM / 1.0e6
        val netDriveMpa = pumpPressureMpa + initial.buoyancyHeadKpa / 1000.0
        assertEquals(netDriveMpa, initial.pressureLossMpa, 0.03)
    }

    @Test
    fun steamGeneratorReferenceMassAndEnergyStreamsAreBalanced() {
        val sg = SteamGeneratorModel(0, water)
        val state = sg.snapshot()
        assertEquals(state.steamFlowKgS, state.feedwaterFlowKgS, 1.0e-9)
        assertEquals(
            ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT,
            state.heatTransferMw,
            1.0e-9,
        )
        assertTrue(state.secondaryMassKg > 0.0)
        assertTrue(state.primaryMassKg > 0.0)
        assertTrue(state.levelFraction in 0.0..1.0)
    }

    @Test
    fun boronInventoryChangesByMassBalanceRatherThanInstantCommand() {
        val chemistry = ChemicalShimModel(referenceRcsMassKg = 300_000.0)
        chemistry.setMakeup(flowKgS = 10.0, boronPpm = 0.0)
        val immediate = chemistry.snapshot()
        assertEquals(ReferencePlant.REFERENCE_BORON_PPM, immediate.boronPpm, 1.0e-12)
        val after = chemistry.advance(60.0)
        assertTrue(after.boronPpm < immediate.boronPpm)
        assertTrue(after.boronReactivity > immediate.boronReactivity)
    }

    @Test
    fun fixedVolumeSaturationClosureReturnsReferencePressure() {
        val solver = TwoPhaseVolumeSolver(
            water = water,
            volumeM3 = ReferencePlant.PZR_VOLUME_M3,
            minPressureMpa = 5.0,
            maxPressureMpa = 21.5,
        )
        val sat = water.saturationP(ReferencePlant.PRIMARY_PRESSURE_MPA)
        val liquidVolume = ReferencePlant.PZR_REFERENCE_LIQUID_VOLUME_M3
        val vapourVolume = ReferencePlant.PZR_VOLUME_M3 - liquidVolume
        val ml = liquidVolume * sat.liquidDensityKgM3
        val mv = vapourVolume * sat.vapourDensityKgM3
        val mass = ml + mv
        val energy = ml * sat.liquidInternalEnergyKjKg + mv * sat.vapourInternalEnergyKjKg
        val recovered = solver.solve(mass, energy)
        assertEquals(ReferencePlant.PRIMARY_PRESSURE_MPA, recovered.pressureMpa, 1.0e-5)
        assertTrue(kotlin.math.abs(recovered.residualKjKg) < 1.0e-4)
    }
}
