package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
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
        val rods = RodDriveModel()
        rods.commandInsertion(0.35)
        val immediate = rods.snapshot()
        assertEquals(ReferencePlant.REFERENCE_ROD_INSERTION, immediate.actualInsertion, 1.0e-9)

        val after = rods.advance(1.0)
        assertTrue(after.actualInsertion < ReferencePlant.REFERENCE_ROD_INSERTION)
        assertTrue(after.actualInsertion > 0.35)
    }

    @Test
    fun withdrawalRaisesNeutronPowerThroughKinetics() {
        val rods = RodDriveModel()
        rods.commandInsertion(0.545)
        rods.advance(0.2)
        val rho = rods.reactivity()
        assertTrue(rho > 0.0)

        val kinetics = PointKineticsModel()
        val after = kinetics.advance(rho, 0.01)
        assertTrue(after.neutronPopulation > 1.0)
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
    fun item1ScramWorthAndFiveSecondTrace() {
        val fullStrokePcm = ReferencePlant.TOTAL_CONTROL_BANK_WORTH * 1.0e5
        assertTrue("full stroke worth $fullStrokePcm pcm", fullStrokePcm >= 4000.0)

        val rod = RodDriveModel()
        rod.scram()
        var elapsed = 0.0
        while (elapsed < 5.0 - 1.0e-12) {
            rod.advance(0.01)
            elapsed += 0.01
        }
        val fromReferencePcm = -rod.reactivity() * 1.0e5
        assertTrue("55%-to-full worth $fromReferencePcm pcm", fromReferencePcm >= 2000.0)

        val simulator = ReactorSimulator()
        val trace = mutableListOf<PlantState>()
        simulator.trip()
        trace += simulator.snapshot()
        repeat(10) {
            trace += simulator.advance(0.5, 1.0)
        }

        assertTrue(trace[1].rodInsertion > trace[0].rodInsertion)
        assertTrue(trace[1].rodInsertion < 1.0)
        assertTrue(trace.last().rodInsertion >= 0.9999)
        assertTrue(trace.all { it.totalReactivityPcm.isFinite() })
        assertTrue(trace.all { it.fissionPowerMw.isFinite() && it.fissionPowerMw >= 0.0 })
        assertTrue(trace.all { it.decayHeatMw.isFinite() && it.decayHeatMw > 0.0 })
        assertTrue(trace.last().rodReactivityPcm <= -2000.0)

        val metrics = buildString {
            append(String.format(Locale.US, "ITEM1 fullStroke=%.3f pcm from55=%.3f pcm\n", fullStrokePcm, fromReferencePcm))
            trace.forEachIndexed { index, s ->
                append(String.format(
                    Locale.US,
                    "ITEM1 t=%.1f rho=%.3f pcm rod=%.5f fission=%.3f MW decay=%.3f MW\n",
                    index * 0.5,
                    s.totalReactivityPcm,
                    s.rodInsertion,
                    s.fissionPowerMw,
                    s.decayHeatMw,
                ))
            }
        }
        FileOutputStream(FileDescriptor.out).use { out ->
            out.write(metrics.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    @Test
    fun item2FeedwaterIntegralAndPlusMinusTenPercentLoadRecovery() {
        // First prove the I term itself changes demand when dt is nonzero.
        val water = If97WaterProperties()
        val sg = SteamGeneratorModel(0, water)
        val hotH = water.statePT(ReferencePlant.PRIMARY_PRESSURE_MPA, ReferencePlant.HOT_LEG_T_K).enthalpyKjKg
        val fwH = water.statePT(ReferencePlant.SG_PRESSURE_MPA + 1.0, ReferencePlant.FEEDWATER_T_K).enthalpyKjKg
        repeat(20) {
            sg.advance(
                primaryInletEnthalpyKjKg = hotH,
                primaryMassFlowKgS = ReferencePlant.FLOW_PER_LOOP_KG_PER_S,
                primaryPressureMpa = ReferencePlant.PRIMARY_PRESSURE_MPA,
                headerPressureMpa = ReferencePlant.STEAM_HEADER_PRESSURE_MPA,
                feedwaterFlowKgS = 0.0,
                feedwaterEnthalpyKjKg = fwH,
                heatExchangerCondition = 1.0,
                dt = 0.1,
            )
        }
        val disturbedLevel = sg.snapshot().levelFraction
        val demand0 = sg.feedwaterDemand(0.0)
        val demand1 = sg.feedwaterDemand(0.5)
        val demand2 = sg.feedwaterDemand(0.5)
        val errorSign = ReferencePlant.SG_REFERENCE_LEVEL - disturbedLevel
        val integralMovesDemand = if (errorSign > 0.0) demand2 > demand1 else demand2 < demand1

        data class LoadResult(
            val load: Double,
            val baseline: Double,
            val peakError: Double,
            val finalLevel: Double,
            val finalError: Double,
            val minLevel: Double,
            val maxLevel: Double,
            val tripped: Boolean,
        )

        fun runLoad(load: Double): LoadResult {
            val simulator = ReactorSimulator()
            val baselineState = simulator.advance(15.0, 1.0)
            val baseline = baselineState.steamGeneratorLevelFraction.average()
            simulator.setTurbineLoad(load)
            val samples = mutableListOf<PlantState>()
            repeat(12) {
                samples += simulator.advance(10.0, 1.0)
            }
            val levels = samples.map { it.steamGeneratorLevelFraction.average() }
            val errors = levels.map { abs(it - ReferencePlant.SG_REFERENCE_LEVEL) }
            return LoadResult(
                load = load,
                baseline = baseline,
                peakError = errors.maxOrNull() ?: 0.0,
                finalLevel = levels.last(),
                finalError = errors.last(),
                minLevel = levels.minOrNull() ?: baseline,
                maxLevel = levels.maxOrNull() ?: baseline,
                tripped = samples.any { it.tripped },
            )
        }

        val plus = runLoad(1.10)
        val minus = runLoad(0.90)
        val metrics = String.format(
            Locale.US,
            "ITEM2 Icheck level=%.5f demand0=%.3f demand1=%.3f demand2=%.3f kg/s | +10%% baseline=%.5f min=%.5f max=%.5f peakErr=%.5f final=%.5f finalErr=%.5f trip=%s | -10%% baseline=%.5f min=%.5f max=%.5f peakErr=%.5f final=%.5f finalErr=%.5f trip=%s\n",
            disturbedLevel, demand0, demand1, demand2,
            plus.baseline, plus.minLevel, plus.maxLevel, plus.peakError, plus.finalLevel, plus.finalError, plus.tripped,
            minus.baseline, minus.minLevel, minus.maxLevel, minus.peakError, minus.finalLevel, minus.finalError, minus.tripped,
        )
        FileOutputStream(FileDescriptor.out).use { out ->
            out.write(metrics.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        assertTrue("SG I-term did not change demand with nonzero dt", integralMovesDemand)
        assertTrue("+10% load SG level did not turn back toward setpoint: $metrics", plus.finalError < plus.peakError)
        assertTrue("-10% load SG level did not turn back toward setpoint: $metrics", minus.finalError < minus.peakError)
        assertTrue("+10% load SG level left physical range: $metrics", plus.minLevel > 0.0 && plus.maxLevel < 1.0)
        assertTrue("-10% load SG level left physical range: $metrics", minus.minLevel > 0.0 && minus.maxLevel < 1.0)
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
