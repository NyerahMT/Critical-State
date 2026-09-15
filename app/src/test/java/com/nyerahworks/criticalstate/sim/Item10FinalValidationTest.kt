package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Item10FinalValidationTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun finalTransientAndLongRunValidationMatrix() {
        val report = StringBuilder("ITEM10-FINAL-VALIDATION\n")
        validateOneRcpTrip(report)
        validateAllRcpNaturalCirculation(report)
        validateTurbineTrip(report)
        validateLoadSteps(report)
        validateSixtyMinuteFastForwardAndConservation(report)
        rawLog(report.toString())
    }

    private fun validateOneRcpTrip(report: StringBuilder) {
        val sim = ReactorSimulator()
        val s0 = sim.snapshot()
        val rpm0 = s0.loopPumpRpm[0]
        val flow0 = s0.loopFlowKgPerS[0]
        sim.setReactorCoolantPump(0, false)

        var s = s0
        var rpm5 = rpm0
        var flow5 = flow0
        var rpm30 = rpm0
        var flow30 = flow0
        repeat(600) { i ->
            s = sim.advance(0.10, 1.0)
            assertFinite("1RCP t=${(i + 1) * .1}", s)
            if (i == 49) { rpm5 = s.loopPumpRpm[0]; flow5 = s.loopFlowKgPerS[0] }
            if (i == 299) { rpm30 = s.loopPumpRpm[0]; flow30 = s.loopFlowKgPerS[0] }
        }
        assertTrue("tripped RCP did not coast down", rpm5 < rpm0 && rpm30 < rpm5)
        assertTrue("tripped-loop flow did not reduce", flow30 < flow0)
        assertTrue("healthy loops lost positive flow on one-RCP trip", s.loopFlowKgPerS.drop(1).all { it > 0.0 })
        report.append(String.format(
            Locale.US,
            "ITEM10 1-RCP t0 rpm=%.1f flow=%.1f | t5 rpm=%.1f flow=%.1f | t30 rpm=%.1f flow=%.1f | t60 rpm=%.1f flow=%.1f otherFlows=%s trip=%s flags=%s\n",
            rpm0, flow0, rpm5, flow5, rpm30, flow30,
            s.loopPumpRpm[0], s.loopFlowKgPerS[0],
            s.loopFlowKgPerS.drop(1).joinToString(prefix="[", postfix="]") { String.format(Locale.US, "%.1f", it) },
            s.tripped, flags(s),
        ))
    }

    private fun validateAllRcpNaturalCirculation(report: StringBuilder) {
        val sim = ReactorSimulator()
        val s0 = sim.snapshot()
        sim.setAllReactorCoolantPumps(false)
        var s = s0
        val rows = linkedMapOf<Int, PlantState>()
        repeat(6000) { i ->
            s = sim.advance(0.10, 1.0)
            assertFinite("ALLRCP t=${(i + 1) * .1}", s)
            val sec = ((i + 1) / 10)
            if ((i + 1) % 10 == 0 && sec in setOf(10, 30, 60, 120, 300, 600)) rows[sec] = s
        }
        assertTrue("all RCPs did not coast down", s.loopPumpRpm.all { it < 0.20 * ReferencePlant.RCP_RATED_RPM })
        assertTrue("natural-circulation flow was not established", s.loopFlowKgPerS.all { it > 0.0 })
        rows.forEach { (t, r) ->
            report.append(String.format(
                Locale.US,
                "ITEM10 ALL-RCP t=%3ds rpm=%s flows=%s total=%.1fkg/s P=%.4fMPa Th/Tc=%.2f/%.2fK trip=%s flags=%s\n",
                t,
                r.loopPumpRpm.joinToString(prefix="[", postfix="]") { String.format(Locale.US, "%.1f", it) },
                r.loopFlowKgPerS.joinToString(prefix="[", postfix="]") { String.format(Locale.US, "%.1f", it) },
                r.totalPrimaryFlowKgPerS, r.primaryPressureMpa, r.hotLegTemperatureK, r.coldLegTemperatureK,
                r.tripped, flags(r),
            ))
        }
    }

    private fun validateTurbineTrip(report: StringBuilder) {
        val sim = ReactorSimulator()
        val s0 = sim.snapshot()
        sim.tripTurbine()
        var s = sim.advance(0.10, 1.0)
        val firstFlow = s.turbineSteamFlowKgPerS
        val firstHeader = s.mainSteamPressureMpa
        val firstCond = s.condenserPressureKpa
        assertTrue("turbine trip steam flow teleported", firstFlow > 0.50 * s0.turbineSteamFlowKgPerS)
        assertTrue("turbine trip did not propagate to RPS in production quantum", s.tripped)
        repeat(49) { s = sim.advance(0.10, 1.0); assertFinite("TURB t=${(it + 2) * .1}", s) }
        val flow5 = s.turbineSteamFlowKgPerS
        repeat(250) { s = sim.advance(0.10, 1.0); assertFinite("TURB tail", s) }
        assertTrue("turbine steam did not collapse", flow5 < 0.02 * s0.turbineSteamFlowKgPerS)
        assertTrue("steam header did not evolve", abs(s.mainSteamPressureMpa - firstHeader) > 1e-4)
        assertTrue("condenser did not evolve", abs(s.condenserPressureKpa - firstCond) > 1e-3)
        report.append(String.format(
            Locale.US,
            "ITEM10 TURBINE t0 steam=%.1f header=%.4f cond=%.4f | t0.1 steam=%.1f header=%.4f cond=%.4f trip=true | t5 steam=%.3f | t30 header=%.4f cond=%.4f flags=%s\n",
            s0.turbineSteamFlowKgPerS, s0.mainSteamPressureMpa, s0.condenserPressureKpa,
            firstFlow, firstHeader, firstCond, flow5, s.mainSteamPressureMpa, s.condenserPressureKpa, flags(s),
        ))
    }

    private fun validateLoadSteps(report: StringBuilder) {
        for (load in listOf(1.10, 0.90)) {
            val sim = ReactorSimulator()
            val base = sim.snapshot()
            sim.setTurbineLoad(load)
            var s = base
            var peakLevelError = 0.0
            var minP = base.primaryPressureMpa
            var maxP = base.primaryPressureMpa
            var maxHeater = 0.0
            var maxSpray = 0.0
            repeat(1200) {
                s = sim.advance(0.10, 1.0)
                assertFinite("LOAD $load", s)
                val level = s.steamGeneratorLevelFraction.average()
                peakLevelError = maxOf(peakLevelError, abs(level - ReferencePlant.SG_REFERENCE_LEVEL))
                minP = minOf(minP, s.primaryPressureMpa)
                maxP = maxOf(maxP, s.primaryPressureMpa)
                maxHeater = maxOf(maxHeater, s.pressurizerHeaterFraction)
                maxSpray = maxOf(maxSpray, s.pressurizerSprayFraction)
            }
            val finalLevel = s.steamGeneratorLevelFraction.average()
            val finalLevelError = abs(finalLevel - ReferencePlant.SG_REFERENCE_LEVEL)
            assertTrue("load $load SG level final error $finalLevelError", finalLevelError < 0.01)
            assertTrue("load $load SG level did not recover from peak", finalLevelError < peakLevelError)
            assertFalse("load $load caused reactor trip", s.tripped)
            assertTrue("load $load opened generator breaker", s.generatorBreakerClosed)
            if (load > 1.0) assertTrue("low-pressure load step never demanded PZR heaters", maxHeater > 0.0)
            if (load < 1.0) assertTrue("high-pressure load step never demanded PZR spray", maxSpray > 0.0)
            report.append(String.format(
                Locale.US,
                "ITEM10 LOAD %.2f levelFinal=%.5f levelPeakErr=%.5f Pfinal=%.5fMPa Prange=[%.5f,%.5f] heaterMax=%.3f sprayMax=%.3f trip=%s breaker=%s flags=%s\n",
                load, finalLevel, peakLevelError, s.primaryPressureMpa, minP, maxP,
                maxHeater, maxSpray, s.tripped, s.generatorBreakerClosed, flags(s),
            ))
        }
    }

    private fun validateSixtyMinuteFastForwardAndConservation(report: StringBuilder) {
        val sim = ReactorSimulator()
        var s = sim.snapshot()
        val checkpoints = listOf(600, 1800, 3600)
        var checkpointIndex = 0
        // Exercise the actual 60x API while accumulating a full simulated hour.
        repeat(600) {
            s = sim.advance(0.10, 60.0) // 6 simulated seconds per runtime quantum
            assertFinite("60X t=${s.simulationSeconds}", s)
            assertTrue("60x primary pressure non-positive", s.primaryPressureMpa > 0.0)
            assertTrue("60x primary flow non-positive", s.totalPrimaryFlowKgPerS > 0.0)
            assertTrue("60x temperatures non-positive", s.hotLegTemperatureK > 0.0 && s.coldLegTemperatureK > 0.0)
            assertTrue("60x SG level outside physical fraction", s.steamGeneratorLevelFraction.all { v -> v in 0.0..1.0 })
            assertTrue("60x left saturation envelope at design hold: ${s.diagnostic}", flags(s) == "NONE")
            assertFalse("60x design hold tripped", s.tripped)
            assertTrue("60x design hold opened breaker", s.generatorBreakerClosed)

            while (
                checkpointIndex < checkpoints.size &&
                s.simulationSeconds + 1.0e-6 >= checkpoints[checkpointIndex]
            ) {
                val cp = checkpoints[checkpointIndex]
                val inputEnergyMj = ReferencePlant.RATED_THERMAL_POWER_MW * s.simulationSeconds
                val fractionalEnergyClosure = abs(s.energyConservationErrorMj) / inputEnergyMj
                report.append(String.format(
                    Locale.US,
                    "ITEM10 60X t=%4ds P=%.5fMPa Tavg=%.3fK flow=%.1f SG=%.5f net=%.2fMW massRes=%+.3fkg energyRes=%+.3fMJ energyRate=%+.3fMW fracClosure=%.8f flags=%s\n",
                    cp, s.primaryPressureMpa, 0.5 * (s.hotLegTemperatureK + s.coldLegTemperatureK),
                    s.totalPrimaryFlowKgPerS, s.steamGeneratorLevelFraction.average(), s.generatorPowerMw,
                    s.massConservationErrorKg, s.energyConservationErrorMj, s.plantEnergyResidualMw,
                    fractionalEnergyClosure, flags(s),
                ))
                checkpointIndex += 1
            }
        }

        assertTrue("60x run did not reach one simulated hour", s.simulationSeconds >= 3599.9)
        assertTrue("60x missed a requested conservation checkpoint", checkpointIndex == checkpoints.size)

        val finalInputEnergyMj = ReferencePlant.RATED_THERMAL_POWER_MW * s.simulationSeconds
        val finalFractionalEnergyClosure = abs(s.energyConservationErrorMj) / finalInputEnergyMj
        // These are closure tolerances for the reduced-order states we actually
        // conserve, not physical transient limits. 1 MW is 0.029% of rated
        // thermal power; 1e-4 cumulative closure is 0.01% of nuclear input.
        // Both leave numerical-platform margin while rejecting secular drift.
        assertTrue(
            "mass residual excessive after 60 min: ${s.massConservationErrorKg}",
            abs(s.massConservationErrorKg) < 10.0,
        )
        assertTrue(
            "instantaneous modeled-energy closure defect exceeds 1 MW: ${s.plantEnergyResidualMw} MW",
            abs(s.plantEnergyResidualMw) < 1.0,
        )
        assertTrue(
            "cumulative modeled-energy closure exceeds 1e-4 of nuclear input: $finalFractionalEnergyClosure",
            finalFractionalEnergyClosure < 1.0e-4,
        )
    }

    private fun flags(s: PlantState): String {
        val d = s.diagnostic ?: return "NONE"
        val out = mutableListOf<String>()
        if (d.contains("PZR saturation (m,U) envelope invalid")) out += "PZR"
        repeat(ReferencePlant.LOOP_COUNT) { i ->
            if (d.contains("SG${i + 1} SECONDARY saturation (m,U) envelope invalid")) out += "SG${i + 1}"
        }
        if (d.contains("CONDENSER saturation (m,U) envelope invalid")) out += "CONDENSER"
        return if (out.isEmpty()) "NONE" else out.joinToString(",")
    }

    private fun assertFinite(label: String, s: PlantState) {
        val values = listOf(
            s.simulationSeconds, s.fissionPowerMw, s.decayHeatMw, s.totalCoreHeatMw,
            s.primaryPressureMpa, s.totalPrimaryFlowKgPerS, s.hotLegTemperatureK,
            s.coldLegTemperatureK, s.pressurizerLevelFraction, s.mainSteamPressureMpa,
            s.turbineSteamFlowKgPerS, s.turbineRpm, s.generatorPowerMw,
            s.condenserPressureKpa, s.feedwaterFlowKgPerS,
            s.massConservationErrorKg, s.energyConservationErrorMj, s.plantEnergyResidualMw,
        ) + s.loopFlowKgPerS + s.loopPumpRpm + s.steamGeneratorPressureMpa +
            s.steamGeneratorLevelFraction + s.steamGeneratorSteamFlowKgPerS
        assertTrue("$label contains NaN/non-finite state", values.all { it.isFinite() })
    }
}
