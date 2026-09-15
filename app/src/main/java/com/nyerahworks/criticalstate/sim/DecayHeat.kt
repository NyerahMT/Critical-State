package com.nyerahworks.criticalstate.sim

import kotlin.math.exp
import kotlin.math.ln

internal data class DecayHeatSnapshot(
    val powerMw: Double,
    val storedEnergyMj: Double,
)

/**
 * Ten active stored-energy decay groups fitted to the public Way-Wigner
 * long-run trend used by the model bible as an order-of-magnitude oracle.
 *
 * The previous eleven-slot table contained a 0.2 s slot with exactly zero
 * fraction. It contributed neither power nor stored energy, so it has been
 * deleted rather than pretending it was a physical decay group. The remaining
 * fractions are unchanged and still sum to ~6.675% at equilibrium; the prompt
 * deposited fraction is the complement so steady thermal power remains exactly
 * the fission-power normalization rather than being double counted.
 */
internal class DecayHeatModel {
    companion object {
        private val HALF_LIFE_S = doubleArrayOf(
            1.0023744673,
            5.0237728630,
            25.1785082359,
            126.1914688960,
            632.4555320337,
            3169.7863849222,
            15886.5646944856,
            79621.4341106994,
            399052.4629937758,
            2_000_000.0,
        )
        val FRACTIONS = doubleArrayOf(
            0.0054989284,
            0.0219465174,
            0.0090141344,
            0.0089934640,
            0.0056246053,
            0.0044361261,
            0.0029898788,
            0.0025503626,
            0.0000821636,
            0.0056145471,
        )
        val TOTAL_DELAYED_HEAT_FRACTION = FRACTIONS.sum()
        val PROMPT_DEPOSIT_FRACTION = 1.0 - TOTAL_DELAYED_HEAT_FRACTION
        private val LAMBDA = DoubleArray(HALF_LIFE_S.size) { i -> ln(2.0) / HALF_LIFE_S[i] }
    }

    // Stored energy is MJ because MW == MJ/s.
    private val groupEnergyMj = DoubleArray(FRACTIONS.size) { i ->
        FRACTIONS[i] * ReferencePlant.RATED_THERMAL_POWER_MW / LAMBDA[i]
    }

    fun reset() {
        for (i in groupEnergyMj.indices) {
            groupEnergyMj[i] = FRACTIONS[i] *
                ReferencePlant.RATED_THERMAL_POWER_MW / LAMBDA[i]
        }
    }

    fun advance(fissionPowerMw: Double, dt: Double): DecayHeatSnapshot {
        for (i in groupEnergyMj.indices) {
            val lambda = LAMBDA[i]
            val e = exp(-lambda * dt)
            val equilibriumEnergy = if (lambda > 0.0) {
                FRACTIONS[i] * fissionPowerMw / lambda
            } else 0.0
            groupEnergyMj[i] = groupEnergyMj[i] * e + equilibriumEnergy * (1.0 - e)
            if (!groupEnergyMj[i].isFinite() || groupEnergyMj[i] < 0.0) groupEnergyMj[i] = 0.0
        }
        return snapshot()
    }

    fun snapshot(): DecayHeatSnapshot {
        var p = 0.0
        var e = 0.0
        for (i in groupEnergyMj.indices) {
            p += LAMBDA[i] * groupEnergyMj[i]
            e += groupEnergyMj[i]
        }
        return DecayHeatSnapshot(powerMw = p, storedEnergyMj = e)
    }
}
