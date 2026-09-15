package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Test

class Item10AllRcpDiagnosticTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun diagnoseAllRcpOffPropertyFailure() {
        val sim = ReactorSimulator()
        sim.setAllReactorCoolantPumps(false)
        var s = sim.snapshot()

        repeat(6000) { i ->
            try {
                s = sim.advance(0.10, 1.0)
            } catch (t: Throwable) {
                val stack = t.stackTrace.take(16).joinToString("\n") { "  at $it" }
                rawLog(String.format(
                    Locale.US,
                    "ITEM10-DIAG ALL-RCP failure step=%d t=%.3fs P=%.6fMPa flow=%.3fkg/s Th/Tc=%.3f/%.3fK core=%.3fMW fission=%.3fMW decay=%.3fMW rpm=%s loopFlow=%s SGp=%s SGlvl=%s PZRlvl=%.5f flags=%s diagnostic=%s\nexception=%s: %s\n%s\n",
                    i + 1,
                    s.simulationSeconds,
                    s.primaryPressureMpa,
                    s.totalPrimaryFlowKgPerS,
                    s.hotLegTemperatureK,
                    s.coldLegTemperatureK,
                    s.totalCoreHeatMw,
                    s.fissionPowerMw,
                    s.decayHeatMw,
                    s.loopPumpRpm.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.2f", it) },
                    s.loopFlowKgPerS.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.2f", it) },
                    s.steamGeneratorPressureMpa.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.4f", it) },
                    s.steamGeneratorLevelFraction.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.4f", it) },
                    s.pressurizerLevelFraction,
                    if (s.diagnostic == null) "NONE" else "SET",
                    s.diagnostic ?: "NONE",
                    t.javaClass.name,
                    t.message ?: "(no message)",
                    stack,
                ))
                return
            }

            if ((i + 1) % 100 == 0) {
                rawLog(String.format(
                    Locale.US,
                    "ITEM10-DIAG ALL-RCP t=%.1fs P=%.5f flow=%.1f Th/Tc=%.2f/%.2f core=%.1f rpm0=%.1f flags=%s\n",
                    s.simulationSeconds,
                    s.primaryPressureMpa,
                    s.totalPrimaryFlowKgPerS,
                    s.hotLegTemperatureK,
                    s.coldLegTemperatureK,
                    s.totalCoreHeatMw,
                    s.loopPumpRpm[0],
                    s.diagnostic ?: "NONE",
                ))
            }
        }
        rawLog("ITEM10-DIAG ALL-RCP completed 600s without property failure\n")
    }
}
