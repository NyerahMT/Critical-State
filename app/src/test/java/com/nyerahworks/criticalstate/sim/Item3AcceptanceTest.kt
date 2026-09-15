package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Item3AcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Test
    fun item3DesignPointHoldsForTenMinutesWithoutOperatorInput() {
        val simulator = ReactorSimulator()
        val initial = simulator.snapshot()

        val initialTavg = 0.5 * (initial.hotLegTemperatureK + initial.coldLegTemperatureK)
        val initialSgLevel = initial.steamGeneratorLevelFraction.average()
        val initialNetMwe = initial.generatorPowerMw
        val pressureLow = 15.51 - 0.10
        val pressureHigh = 15.51 + 0.10
        val tavgLow = initialTavg - 1.0
        val tavgHigh = initialTavg + 1.0
        val sgLow = 0.65 - 0.03
        val sgHigh = 0.65 + 0.03
        val mweLow = initialNetMwe * 0.98
        val mweHigh = initialNetMwe * 1.02

        var minP = initial.primaryPressureMpa
        var maxP = initial.primaryPressureMpa
        var minTavg = initialTavg
        var maxTavg = initialTavg
        var minSg = initialSgLevel
        var maxSg = initialSgLevel
        var minMwe = initialNetMwe
        var maxMwe = initialNetMwe
        var anyTrip = initial.tripped
        var breakerOpened = !initial.generatorBreakerClosed

        val trace = StringBuilder()
        fun appendTrace(t: Int, s: PlantState) {
            val tavg = 0.5 * (s.hotLegTemperatureK + s.coldLegTemperatureK)
            val sg = s.steamGeneratorLevelFraction.average()
            trace.append(String.format(
                Locale.US,
                "ITEM3 t=%3ds P=%.5f Tavg=%.3f Th=%.3f Tc=%.3f SG=%.5f net=%.3f gross=%.3f aux=%.3f flow=%.1f core=%.1f SGQ=%.1f header=%.4f fw=%.1f@%.2f trip=%s breaker=%s massRes=%.3f energyRes=%.3f\n",
                t,
                s.primaryPressureMpa,
                tavg,
                s.hotLegTemperatureK,
                s.coldLegTemperatureK,
                sg,
                s.generatorPowerMw,
                s.generatorGrossPowerMw,
                s.auxiliaryPowerMw,
                s.totalPrimaryFlowKgPerS,
                s.totalCoreHeatMw,
                s.secondaryHeatRemovalMw,
                s.mainSteamPressureMpa,
                s.feedwaterFlowKgPerS,
                s.feedwaterTemperatureK,
                s.tripped,
                s.generatorBreakerClosed,
                s.massConservationErrorKg,
                s.energyConservationErrorMj,
            ))
        }

        appendTrace(0, initial)
        var finalState = initial
        for (t in 1..600) {
            finalState = simulator.advance(1.0, 1.0)
            val tavg = 0.5 * (finalState.hotLegTemperatureK + finalState.coldLegTemperatureK)
            val sg = finalState.steamGeneratorLevelFraction.average()
            val mwe = finalState.generatorPowerMw
            minP = minOf(minP, finalState.primaryPressureMpa)
            maxP = maxOf(maxP, finalState.primaryPressureMpa)
            minTavg = minOf(minTavg, tavg)
            maxTavg = maxOf(maxTavg, tavg)
            minSg = minOf(minSg, sg)
            maxSg = maxOf(maxSg, sg)
            minMwe = minOf(minMwe, mwe)
            maxMwe = maxOf(maxMwe, mwe)
            anyTrip = anyTrip || finalState.tripped
            breakerOpened = breakerOpened || !finalState.generatorBreakerClosed
            if (t % 30 == 0 || t == 1) appendTrace(t, finalState)
        }

        val finalTavg = 0.5 * (finalState.hotLegTemperatureK + finalState.coldLegTemperatureK)
        val finalSg = finalState.steamGeneratorLevelFraction.average()
        val summary = String.format(
            Locale.US,
            "ITEM3-SUMMARY t0 P=%.5f Tavg=%.3f SG=%.5f net=%.3f | t600 P=%.5f Tavg=%.3f SG=%.5f net=%.3f | ranges P=[%.5f,%.5f] Tavg=[%.3f,%.3f] SG=[%.5f,%.5f] net=[%.3f,%.3f] trip=%s breakerOpened=%s massRes=%.3f kg energyRes=%.3f MJ\n",
            initial.primaryPressureMpa, initialTavg, initialSgLevel, initialNetMwe,
            finalState.primaryPressureMpa, finalTavg, finalSg, finalState.generatorPowerMw,
            minP, maxP, minTavg, maxTavg, minSg, maxSg, minMwe, maxMwe,
            anyTrip, breakerOpened, finalState.massConservationErrorKg, finalState.energyConservationErrorMj,
        )
        rawLog(trace.toString() + summary)

        assertTrue("primary pressure left band: $summary", minP >= pressureLow && maxP <= pressureHigh)
        assertTrue("Tavg left band: $summary", minTavg >= tavgLow && maxTavg <= tavgHigh)
        assertTrue("mean SG level left band: $summary", minSg >= sgLow && maxSg <= sgHigh)
        assertTrue("net MWe left band: $summary", minMwe >= mweLow && maxMwe <= mweHigh)
        assertFalse("plant tripped during hold: $summary", anyTrip)
        assertFalse("generator breaker opened during hold: $summary", breakerOpened)
    }
}
