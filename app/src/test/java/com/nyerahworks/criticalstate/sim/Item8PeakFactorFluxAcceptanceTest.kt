package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Item8PeakFactorFluxAcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun ratedAreaTimesPeakFactorRemainsOnlyAnEstimate() {
        val state = ReactorSimulator().snapshot()
        val expected = ReferencePlant.RATED_THERMAL_POWER_MW /
            ReferencePlant.CORE_EFFECTIVE_HEAT_AREA_M2 * 1.55
        val actual = state.estimatedPeakFactorHeatFluxMwM2

        assertTrue("peak-factor flux estimate must be finite and positive", actual.isFinite() && actual > 0.0)
        assertEquals("item 8 must not change the existing rated/area x 1.55 calculation", expected, actual, 1.0e-12)

        rawLog(String.format(
            Locale.US,
            "ITEM8 peakFactorFlux=%.9fMW/m2 expected=%.9fMW/m2 formula=rated/area*1.55 resolvedHotChannel=false DNBR=false\n",
            actual,
            expected,
        ))
    }
}
