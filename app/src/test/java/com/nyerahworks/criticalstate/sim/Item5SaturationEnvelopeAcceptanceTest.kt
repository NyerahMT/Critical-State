package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Item5SaturationEnvelopeAcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun saturationSolverNeverSilentlyAcceptsAnUnbracketedOrOutOfVolumeEnvelopeCandidate() {
        val water = If97WaterProperties()

        // The target specific internal energy is far above the saturated-water
        // envelope throughout 1..10 MPa, so no pressure root can be bracketed.
        val unbracketed = TwoPhaseVolumeSolver(
            water = water,
            volumeM3 = 1.0,
            minPressureMpa = 1.0,
            maxPressureMpa = 10.0,
        ).solve(
            massKg = 10.0,
            internalEnergyKj = 100_000.0,
        )
        assertFalse(unbracketed.saturationEnvelopeValid)
        assertNotNull(unbracketed.diagnostic)
        assertTrue(unbracketed.diagnostic!!.contains("not bracketed/converged"))
        assertFiniteCandidate("unbracketed", unbracketed)

        // v=0.0005 m3/kg is denser than saturated liquid in this pressure
        // interval. The numerical quality clamp may keep the candidate finite,
        // but it must remain explicitly invalid rather than masquerading as a
        // saturated liquid/two-phase solution.
        val denseWater = If97WaterProperties()
        val denseMassKg = 2_000.0
        val denseSat = denseWater.saturationP(5.0)
        val outsideVolume = TwoPhaseVolumeSolver(
            water = denseWater,
            volumeM3 = 1.0,
            minPressureMpa = 1.0,
            maxPressureMpa = 10.0,
        ).solve(
            massKg = denseMassKg,
            internalEnergyKj = denseMassKg * denseSat.liquidInternalEnergyKjKg,
        )
        assertFalse(outsideVolume.saturationEnvelopeValid)
        assertNotNull(outsideVolume.diagnostic)
        assertTrue(outsideVolume.diagnostic!!.contains("specific volume outside saturated-mixture envelope"))
        assertFiniteCandidate("outside-volume", outsideVolume)

        rawLog(
            "ITEM5 SOLVER unbracketed='${unbracketed.diagnostic}' | " +
                "outsideVolume='${outsideVolume.diagnostic}'\n",
        )
    }

    @Test
    fun requestedPlantTransientsStayFiniteAndReportEverySaturationEnvelopeFlag() {
        data class Scenario(
            val name: String,
            val settleSeconds: Int = 0,
            val durationSeconds: Int,
            val command: (ReactorSimulator) -> Unit = {},
        )

        val scenarios = listOf(
            Scenario("DESIGN", durationSeconds = 30),
            Scenario("LOAD+10", settleSeconds = 10, durationSeconds = 60) { it.setTurbineLoad(1.10) },
            Scenario("LOAD-10", settleSeconds = 10, durationSeconds = 60) { it.setTurbineLoad(0.90) },
            Scenario("SCRAM", durationSeconds = 30) { it.trip() },
            Scenario("1-RCP-TRIP", durationSeconds = 45) { it.setReactorCoolantPump(0, false) },
            Scenario("ALL-RCP-OFF", durationSeconds = 45) { it.setAllReactorCoolantPumps(false) },
            Scenario("TURBINE-TRIP", durationSeconds = 30) { it.tripTurbine() },
        )

        val report = StringBuilder()
        report.append("ITEM5-SATURATION-ENVELOPE\n")

        scenarios.forEach { scenario ->
            val simulator = ReactorSimulator()
            val flags = linkedSetOf<String>()
            var state = simulator.snapshot()
            assertFinitePlantState("${scenario.name} t=0", state)
            flags += saturationVolumeFlags(state)

            repeat(scenario.settleSeconds) {
                state = simulator.advance(1.0, 1.0)
                assertFinitePlantState("${scenario.name} settle t=${it + 1}", state)
                flags += saturationVolumeFlags(state)
            }

            scenario.command(simulator)
            repeat(scenario.durationSeconds) {
                state = simulator.advance(1.0, 1.0)
                assertFinitePlantState("${scenario.name} t=${it + 1}", state)
                flags += saturationVolumeFlags(state)
            }

            val shownFlags = if (flags.isEmpty()) "NONE" else flags.joinToString(",")
            report.append(String.format(
                Locale.US,
                "ITEM5 %-12s flags=%s PZR=%.5fMPa/%.4f SG=%s COND=%.4fkPa trip=%s diagnostic=%s\n",
                scenario.name,
                shownFlags,
                state.primaryPressureMpa,
                state.pressurizerLevelFraction,
                state.steamGeneratorPressureMpa.joinToString(prefix = "[", postfix = "]") {
                    String.format(Locale.US, "%.4f", it)
                },
                state.condenserPressureKpa,
                state.tripped,
                state.diagnostic ?: "NONE",
            ))
        }

        rawLog(report.toString())
    }

    private fun saturationVolumeFlags(state: PlantState): Set<String> {
        val diagnostic = state.diagnostic ?: return emptySet()
        return buildSet {
            if (diagnostic.contains("PZR saturation (m,U) envelope invalid")) add("PZR")
            repeat(ReferencePlant.LOOP_COUNT) { index ->
                val volume = "SG${index + 1} SECONDARY"
                if (diagnostic.contains("$volume saturation (m,U) envelope invalid")) add(volume)
            }
            if (diagnostic.contains("CONDENSER saturation (m,U) envelope invalid")) add("CONDENSER")
        }
    }

    private fun assertFiniteCandidate(label: String, state: TwoPhaseEquilibrium) {
        val values = listOf(
            state.pressureMpa,
            state.temperatureK,
            state.liquidMassKg,
            state.vapourMassKg,
            state.liquidVolumeM3,
            state.vapourVolumeM3,
            state.quality,
            state.residualKjKg,
        )
        assertTrue("$label returned a non-finite continuation candidate: $state", values.all { it.isFinite() })
    }

    private fun assertFinitePlantState(label: String, s: PlantState) {
        val scalarValues = listOf(
            s.simulationSeconds,
            s.neutronPopulation,
            s.fissionPowerMw,
            s.decayHeatMw,
            s.totalCoreHeatMw,
            s.totalReactivityPcm,
            s.rodReactivityPcm,
            s.dopplerReactivityPcm,
            s.moderatorReactivityPcm,
            s.boronReactivityPcm,
            s.xenonReactivityPcm,
            s.samariumReactivityPcm,
            s.boronPpm,
            s.iodineInventory,
            s.xenonInventory,
            s.promethiumInventory,
            s.samariumInventory,
            s.burnupMwdPerT,
            s.fuelTemperatureK,
            s.fuelPeakTemperatureK,
            s.cladTemperatureK,
            s.cladPeakTemperatureK,
            s.coolantTemperatureK,
            s.hotLegTemperatureK,
            s.coldLegTemperatureK,
            s.subcoolingMarginK,
            s.hotChannelHeatFluxMwM2,
            s.primaryPressureMpa,
            s.totalPrimaryFlowKgPerS,
            s.pressurizerTemperatureK,
            s.pressurizerLevelFraction,
            s.pressurizerHeaterFraction,
            s.pressurizerSprayFraction,
            s.pressurizerSurgeKgPerS,
            s.pressurizerSprayKgPerS,
            s.pressurizerReliefKgPerS,
            s.mainSteamPressureMpa,
            s.mainSteamTemperatureK,
            s.secondaryHeatRemovalMw,
            s.turbineLoad,
            s.turbineValvePosition,
            s.turbineSteamFlowKgPerS,
            s.turbineRpm,
            s.generatorGrossPowerMw,
            s.generatorPowerMw,
            s.generatorReactivePowerMvar,
            s.condenserPressureKpa,
            s.condenserLevelFraction,
            s.condenserHeatRejectionMw,
            s.feedwaterFlowKgPerS,
            s.feedwaterTemperatureK,
            s.auxiliaryPowerMw,
            s.rodInsertion,
            s.rodCommandInsertion,
            s.primaryMassResidualKg,
            s.plantEnergyResidualMw,
            s.massConservationErrorKg,
            s.energyConservationErrorMj,
        )
        assertTrue("$label contains non-finite scalar state", scalarValues.all { it.isFinite() })
        s.reactorPeriodSeconds?.let {
            assertTrue("$label reactor period non-finite: $it", it.isFinite())
        }
        val listValues = s.loopFlowKgPerS + s.loopPumpRpm + s.loopPressureLossMpa +
            s.steamGeneratorPressureMpa + s.steamGeneratorLevelFraction +
            s.steamGeneratorSteamFlowKgPerS + s.steamGeneratorFeedwaterFlowKgPerS +
            s.steamGeneratorHeatTransferMw
        assertTrue("$label contains non-finite list state", listValues.all { it.isFinite() })
    }
}
