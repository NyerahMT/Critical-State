package com.nyerahworks.criticalstate

import com.nyerahworks.criticalstate.sim.ReactorSimulator
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Item7OperatorGlassAcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun operatorGlassUsesInstrumentChannelsWhileTestsRetainTrueState() {
        val simulator = ReactorSimulator()
        val glass = OperatorGlass()

        val true0 = simulator.snapshot()
        val shown0 = glass.present(true0, 0.0)
        assertEquals(true0.primaryPressureMpa, shown0.primaryPressureMpa, 1.0e-12)
        assertEquals(true0.totalPrimaryFlowKgPerS, shown0.totalPrimaryFlowKgPerS, 1.0e-12)

        // A turbine trip gives the existing instrument channels a sharp but
        // physically finite transient. Advance the glass at the production
        // 0.10 s cadence so its documented time constants are actually visible.
        simulator.tripTurbine()
        var trueState = true0
        var shown = shown0
        val report = StringBuilder("ITEM7-OPERATOR-GLASS\n")
        repeat(10) { index ->
            trueState = simulator.advance(0.10, 1.0)
            shown = glass.present(trueState, 0.10)
            if (index == 0 || index == 4 || index == 9) {
                report.append(String.format(
                    Locale.US,
                    "ITEM7 t=%.1fs P true=%.5f shown=%.5f MPa | flow true=%.1f shown=%.1f kg/s | " +
                        "rpm true=%.1f shown=%.1f | gross true=%.2f shown=%.2f MW | cond true=%.4f shown=%.4f kPa\n",
                    (index + 1) * 0.10,
                    trueState.primaryPressureMpa,
                    shown.primaryPressureMpa,
                    trueState.totalPrimaryFlowKgPerS,
                    shown.totalPrimaryFlowKgPerS,
                    trueState.turbineRpm,
                    shown.turbineRpm,
                    trueState.generatorGrossPowerMw,
                    shown.generatorGrossPowerMw,
                    trueState.condenserPressureKpa,
                    shown.condenserPressureKpa,
                ))
            }
        }

        val generatorLag = abs(shown.generatorGrossPowerMw - trueState.generatorGrossPowerMw)
        val condenserLag = abs(shown.condenserPressureKpa - trueState.condenserPressureKpa)
        assertTrue("operator generator indication collapsed to true state", generatorLag > 0.01)
        assertTrue("operator condenser indication collapsed to true state", condenserLag > 1.0e-4)

        // Solver truth remains available to tests/diagnostics; the presentation
        // boundary must not rewrite verification quantities or control state.
        assertEquals(trueState.massConservationErrorKg, shown.massConservationErrorKg, 0.0)
        assertEquals(trueState.energyConservationErrorMj, shown.energyConservationErrorMj, 0.0)
        assertEquals(trueState.diagnostic, shown.diagnostic)
        assertEquals(trueState.rodInsertion, shown.rodInsertion, 0.0)

        report.append(String.format(
            Locale.US,
            "ITEM7-SUMMARY generatorLag=%.3fMW condenserLag=%.5fkPa diagnosticsPreserved=true\n",
            generatorLag,
            condenserLag,
        ))
        rawLog(report.toString())
    }
}
