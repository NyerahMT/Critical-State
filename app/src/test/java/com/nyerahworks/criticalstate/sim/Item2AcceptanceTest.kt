package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Item2AcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private data class LoadResult(
        val load: Double,
        val baseline: Double,
        val peakError: Double,
        val finalLevel: Double,
        val finalError: Double,
        val tripped: Boolean,
        val breakerOpened: Boolean,
    )

    private fun runLoad(load: Double): LoadResult {
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
            tripped = samples.any { it.tripped },
            breakerOpened = samples.any { !it.generatorBreakerClosed },
        )
    }

    @Test
    fun item2StrictPlusMinusTenPercentAcceptance() {
        val plus = runLoad(1.10)
        val minus = runLoad(0.90)

        val metrics = String.format(
            Locale.US,
            "ITEM2-STRICT +10%% final=%.5f finalErr=%.5f peakErr=%.5f trip=%s breakerOpened=%s | -10%% final=%.5f finalErr=%.5f peakErr=%.5f trip=%s breakerOpened=%s\n",
            plus.finalLevel,
            plus.finalError,
            plus.peakError,
            plus.tripped,
            plus.breakerOpened,
            minus.finalLevel,
            minus.finalError,
            minus.peakError,
            minus.tripped,
            minus.breakerOpened,
        )
        rawLog(metrics)

        assertTrue("+10% final SG level error >= 0.01: $metrics", plus.finalError < 0.01)
        assertTrue("-10% final SG level error >= 0.01: $metrics", minus.finalError < 0.01)
        assertTrue("+10% final error did not improve from peak: $metrics", plus.finalError < plus.peakError)
        assertTrue("-10% final error did not improve from peak: $metrics", minus.finalError < minus.peakError)
        assertFalse("+10% caused reactor trip: $metrics", plus.tripped)
        assertFalse("-10% caused reactor trip: $metrics", minus.tripped)
        assertFalse("+10% opened generator breaker: $metrics", plus.breakerOpened)
        assertFalse("-10% opened generator breaker: $metrics", minus.breakerOpened)
    }
}
