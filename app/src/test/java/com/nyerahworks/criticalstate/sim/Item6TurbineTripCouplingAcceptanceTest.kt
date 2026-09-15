package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Item 6 acceptance only.
 *
 * This intentionally exercises the production coupling order unchanged:
 * turbine(header[n], condenser[n]) -> header[n+1] -> condenser[n+1].
 * A 0.10 s simulator advance is the normal 1x runtime quantum, so this test
 * answers whether the existing one-step turbine/header/condenser lag is already
 * causal and usable without adding states or tightening the coupling.
 */
class Item6TurbineTripCouplingAcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun fullPowerTurbineTripIsCausalWithExistingOneStepLag() {
        val simulator = ReactorSimulator()
        val baseline = simulator.snapshot()
        assertFinitePlantState("t=0.0", baseline)

        val baselineSteamFlow = baseline.turbineSteamFlowKgPerS
        val baselineHeaderPressure = baseline.mainSteamPressureMpa
        val baselineCondenserPressure = baseline.condenserPressureKpa
        val baselineAverageSgPressure = baseline.steamGeneratorPressureMpa.average()

        assertTrue("full-power baseline must have positive turbine steam flow", baselineSteamFlow > 0.0)
        assertFalse("full-power baseline unexpectedly reactor-tripped", baseline.tripped)

        val report = StringBuilder()
        report.append("ITEM6-TURBINE-TRIP EXISTING-ONE-STEP-LAG\n")
        appendRow(report, 0.0, baseline)

        simulator.tripTurbine()

        var state = baseline
        var firstTripTime: Double? = null
        var steamFlowAtFirstProductionStep = Double.NaN
        var steamFlowAtFiveSeconds = Double.NaN
        var minHeaderPressure = baselineHeaderPressure
        var maxHeaderPressure = baselineHeaderPressure
        var minCondenserPressure = baselineCondenserPressure
        var maxCondenserPressure = baselineCondenserPressure
        var minAverageSgPressure = baselineAverageSgPressure
        var maxAverageSgPressure = baselineAverageSgPressure
        val seenEnvelopeFlags = linkedSetOf<String>()

        // Normal production runtime advances 0.10 simulated seconds per 1x tick.
        repeat(300) { index ->
            state = simulator.advance(0.10, 1.0)
            val elapsed = (index + 1) * 0.10
            assertFinitePlantState(String.format(Locale.US, "t=%.1f", elapsed), state)

            if (state.tripped && firstTripTime == null) firstTripTime = elapsed
            if (index == 0) steamFlowAtFirstProductionStep = state.turbineSteamFlowKgPerS
            if (index == 49) steamFlowAtFiveSeconds = state.turbineSteamFlowKgPerS

            minHeaderPressure = minOf(minHeaderPressure, state.mainSteamPressureMpa)
            maxHeaderPressure = maxOf(maxHeaderPressure, state.mainSteamPressureMpa)
            minCondenserPressure = minOf(minCondenserPressure, state.condenserPressureKpa)
            maxCondenserPressure = maxOf(maxCondenserPressure, state.condenserPressureKpa)
            val averageSgPressure = state.steamGeneratorPressureMpa.average()
            minAverageSgPressure = minOf(minAverageSgPressure, averageSgPressure)
            maxAverageSgPressure = maxOf(maxAverageSgPressure, averageSgPressure)
            seenEnvelopeFlags += saturationVolumeFlags(state)

            // Preserve the high-resolution first production step and valve-close
            // transient, then report every integer second through 30 s.
            if (index == 0 || index == 4 || (index + 1) % 10 == 0) {
                appendRow(report, elapsed, state)
            }
        }

        // Causal trip: steam is not teleported to zero on the first production
        // quantum, but the finite-rate stop valves still collapse flow promptly.
        assertTrue(
            "turbine steam flow teleported away on the first 0.10 s production step",
            steamFlowAtFirstProductionStep > 0.50 * baselineSteamFlow,
        )
        assertTrue(
            "turbine steam flow did not collapse by 5 s: $steamFlowAtFiveSeconds kg/s",
            steamFlowAtFiveSeconds < 0.02 * baselineSteamFlow,
        )
        assertTrue(
            "turbine steam flow did not remain collapsed at 30 s: ${state.turbineSteamFlowKgPerS} kg/s",
            state.turbineSteamFlowKgPerS < 0.02 * baselineSteamFlow,
        )

        // The explicit turbine-trip input must propagate through protection at
        // the production step rather than waiting for a later thermal threshold.
        assertTrue("RPS never tripped on turbine trip", firstTripTime != null)
        assertTrue(
            "RPS trip propagation exceeded one 0.10 s production step: $firstTripTime s",
            firstTripTime!! <= 0.10 + 1.0e-9,
        )
        assertTrue("RPS trip did not remain set", state.tripped)

        // Secondary inventories/pressures must continue dynamically after the
        // trip. These are movement checks only; they do not impose new physics.
        assertTrue(
            "steam header did not evolve",
            maxHeaderPressure - minHeaderPressure > 1.0e-4,
        )
        assertTrue(
            "condenser did not evolve",
            maxCondenserPressure - minCondenserPressure > 1.0e-3,
        )
        assertTrue(
            "steam-generator secondary pressures did not evolve",
            maxAverageSgPressure - minAverageSgPressure > 1.0e-4,
        )

        // Item 5 established that this transient eventually leaves the declared
        // condenser saturation envelope. Preserve and expose that diagnostic;
        // Item 6 must not hide it by changing the model or clamping it valid.
        assertTrue(
            "known turbine-trip condenser saturation-envelope flag disappeared",
            "CONDENSER" in seenEnvelopeFlags,
        )

        report.append(String.format(
            Locale.US,
            "ITEM6-SUMMARY firstProductionStepFlow=%.3fkg/s flow@5s=%.3fkg/s " +
                "tripAt=%.1fs headerRange=[%.5f,%.5f]MPa condenserRange=[%.4f,%.4f]kPa " +
                "sgAvgRange=[%.5f,%.5f]MPa flags=%s lag=ACCEPTED\n",
            steamFlowAtFirstProductionStep,
            steamFlowAtFiveSeconds,
            firstTripTime,
            minHeaderPressure,
            maxHeaderPressure,
            minCondenserPressure,
            maxCondenserPressure,
            minAverageSgPressure,
            maxAverageSgPressure,
            if (seenEnvelopeFlags.isEmpty()) "NONE" else seenEnvelopeFlags.joinToString(","),
        ))
        rawLog(report.toString())
    }

    private fun appendRow(report: StringBuilder, elapsed: Double, state: PlantState) {
        val flags = saturationVolumeFlags(state)
        report.append(String.format(
            Locale.US,
            "ITEM6 t=%4.1fs steam=%9.3fkg/s headerP=%7.4fMPa condenserP=%8.4fkPa " +
                "tripped=%-5s flags=%s\n",
            elapsed,
            state.turbineSteamFlowKgPerS,
            state.mainSteamPressureMpa,
            state.condenserPressureKpa,
            state.tripped,
            if (flags.isEmpty()) "NONE" else flags.joinToString(","),
        ))
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

    private fun assertFinitePlantState(label: String, s: PlantState) {
        val scalarValues = listOf(
            s.simulationSeconds,
            s.neutronPopulation,
            s.fissionPowerMw,
            s.decayHeatMw,
            s.totalCoreHeatMw,
            s.primaryPressureMpa,
            s.pressurizerLevelFraction,
            s.mainSteamPressureMpa,
            s.mainSteamTemperatureK,
            s.secondaryHeatRemovalMw,
            s.turbineValvePosition,
            s.turbineSteamFlowKgPerS,
            s.turbineRpm,
            s.generatorGrossPowerMw,
            s.generatorPowerMw,
            s.condenserPressureKpa,
            s.condenserLevelFraction,
            s.condenserHeatRejectionMw,
            s.feedwaterFlowKgPerS,
            s.feedwaterTemperatureK,
            s.totalPrimaryFlowKgPerS,
            s.hotLegTemperatureK,
            s.coldLegTemperatureK,
            s.primaryMassResidualKg,
            s.plantEnergyResidualMw,
            s.massConservationErrorKg,
            s.energyConservationErrorMj,
        )
        assertTrue("$label contains a non-finite scalar state", scalarValues.all { it.isFinite() })
        s.reactorPeriodSeconds?.let {
            assertTrue("$label reactor period non-finite: $it", it.isFinite())
        }
        val listValues = s.loopFlowKgPerS + s.loopPumpRpm + s.loopPressureLossMpa +
            s.steamGeneratorPressureMpa + s.steamGeneratorLevelFraction +
            s.steamGeneratorSteamFlowKgPerS + s.steamGeneratorFeedwaterFlowKgPerS +
            s.steamGeneratorHeatTransferMw
        assertTrue("$label contains a non-finite list state", listValues.all { it.isFinite() })

        // A finite state can still be nonsense. These checks specifically catch
        // instantaneous disappearance/creation of the secondary pressure states.
        assertTrue("$label header pressure invalid", s.mainSteamPressureMpa > 0.0)
        assertTrue("$label condenser pressure invalid", s.condenserPressureKpa > 0.0)
        assertTrue("$label SG pressure invalid", s.steamGeneratorPressureMpa.all { it > 0.0 })
        assertTrue("$label turbine flow negative", s.turbineSteamFlowKgPerS >= 0.0)
        assertTrue("$label condenser level non-finite/invalid", s.condenserLevelFraction.isFinite())
        assertTrue("$label pressurizer level non-finite/invalid", s.pressurizerLevelFraction.isFinite())
        assertTrue("$label impossible header pressure jump sentinel", abs(s.mainSteamPressureMpa) < 1.0e6)
    }
}
