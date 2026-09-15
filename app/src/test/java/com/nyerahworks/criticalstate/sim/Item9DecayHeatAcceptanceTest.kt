package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Item9DecayHeatAcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun everyRetainedDecayGroupIsActiveAndShutdownDecayRemainsContinuous() {
        val fractions = DecayHeatModel.FRACTIONS
        assertEquals("dead decay slot was not removed", 10, fractions.size)
        assertTrue("every retained decay group must contribute", fractions.all { it > 0.0 && it.isFinite() })
        assertEquals(
            "removing the zero-fraction slot must not renormalize the existing basis",
            0.0667507277,
            DecayHeatModel.TOTAL_DELAYED_HEAT_FRACTION,
            1.0e-12,
        )

        val model = DecayHeatModel()
        val p0 = model.snapshot().powerMw
        val p1 = model.advance(0.0, 1.0).powerMw
        val p60 = model.advance(0.0, 59.0).powerMw
        val p3600 = model.advance(0.0, 3540.0).powerMw

        val expectedP0 = ReferencePlant.RATED_THERMAL_POWER_MW *
            DecayHeatModel.TOTAL_DELAYED_HEAT_FRACTION
        assertEquals("equilibrium shutdown heat changed when deleting the dead slot", expectedP0, p0, 1.0e-9)
        assertTrue("shutdown decay must remain positive and monotonic", p0 > p1 && p1 > p60 && p60 > p3600 && p3600 > 0.0)
        assertTrue("shutdown decay returned non-finite power", listOf(p0, p1, p60, p3600).all { it.isFinite() })

        rawLog(String.format(
            Locale.US,
            "ITEM9 groups=%d allFractionsPositive=true totalFraction=%.10f P0=%.6fMW P1=%.6fMW P60=%.6fMW P3600=%.6fMW\n",
            fractions.size,
            DecayHeatModel.TOTAL_DELAYED_HEAT_FRACTION,
            p0,
            p1,
            p60,
            p3600,
        ))
    }
}
