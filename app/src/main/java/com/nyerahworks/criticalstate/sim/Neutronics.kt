package com.nyerahworks.criticalstate.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

internal data class KineticsSnapshot(
    val neutronPopulation: Double,
    val precursors: DoubleArray,
    val periodSeconds: Double?,
)

/** Six delayed-neutron-group point kinetics with an implicit Euler solve. */
internal class PointKineticsModel {
    companion object {
        val BETA = doubleArrayOf(
            0.000215,
            0.001424,
            0.001274,
            0.002568,
            0.000748,
            0.000273,
        )
        val LAMBDA = doubleArrayOf(
            0.0124,
            0.0305,
            0.111,
            0.301,
            1.14,
            3.01,
        )
        val BETA_TOTAL = BETA.sum()
        private const val SOURCE_STRENGTH = 1.0e-11
    }

    private var neutronPopulation = 1.0
    private val precursors = DoubleArray(6) { i ->
        BETA[i] / (ReferencePlant.PROMPT_GENERATION_TIME_S * LAMBDA[i])
    }
    private var periodSeconds: Double? = null

    fun snapshot(): KineticsSnapshot = KineticsSnapshot(
        neutronPopulation = neutronPopulation,
        precursors = precursors.copyOf(),
        periodSeconds = periodSeconds,
    )

    fun reset() {
        neutronPopulation = 1.0
        for (i in precursors.indices) {
            precursors[i] = BETA[i] /
                (ReferencePlant.PROMPT_GENERATION_TIME_S * LAMBDA[i])
        }
        periodSeconds = null
    }

    fun advance(reactivity: Double, dt: Double): KineticsSnapshot {
        if (dt <= 0.0) return snapshot()
        val oldN = max(neutronPopulation, ReferencePlant.MIN_NEUTRON_POPULATION)
        val a = (reactivity - BETA_TOTAL) / ReferencePlant.PROMPT_GENERATION_TIME_S

        var rhs = oldN + dt * SOURCE_STRENGTH
        var delayedTerm = 0.0
        for (i in 0 until 6) {
            val denominator = 1.0 + dt * LAMBDA[i]
            rhs += dt * LAMBDA[i] * precursors[i] / denominator
            delayedTerm += dt * LAMBDA[i] *
                (dt * BETA[i] / ReferencePlant.PROMPT_GENERATION_TIME_S) / denominator
        }

        val denominator = 1.0 - dt * a - delayedTerm
        var newN = if (denominator > 1.0e-14) rhs / denominator else oldN
        if (!newN.isFinite() || newN < ReferencePlant.MIN_NEUTRON_POPULATION) {
            newN = ReferencePlant.MIN_NEUTRON_POPULATION
        }

        for (i in 0 until 6) {
            precursors[i] =
                (precursors[i] + dt * BETA[i] /
                    ReferencePlant.PROMPT_GENERATION_TIME_S * newN) /
                    (1.0 + dt * LAMBDA[i])
            if (!precursors[i].isFinite() || precursors[i] < 0.0) precursors[i] = 0.0
        }

        val omega = ln(newN / oldN) / dt
        periodSeconds = if (abs(omega) < 1.0e-10) null else 1.0 / omega
        neutronPopulation = newN
        return snapshot()
    }

    /** Numerical root of the six-group inhour relation, used by tests. */
    fun inhourPeriod(reactivity: Double): Double? {
        if (abs(reactivity) < 1.0e-12) return null
        fun f(omega: Double): Double {
            var rho = ReferencePlant.PROMPT_GENERATION_TIME_S * omega
            for (i in 0 until 6) {
                rho += BETA[i] * omega / (omega + LAMBDA[i])
            }
            return rho - reactivity
        }

        val positive = reactivity > 0.0
        var lo = if (positive) 1.0e-10 else -0.0123
        var hi = if (positive) 5000.0 else -1.0e-10
        var flo = f(lo)
        var fhi = f(hi)
        if (flo * fhi > 0.0) return null
        repeat(100) {
            val mid = 0.5 * (lo + hi)
            val fm = f(mid)
            if (flo * fm <= 0.0) {
                hi = mid
                fhi = fm
            } else {
                lo = mid
                flo = fm
            }
        }
        val omega = 0.5 * (lo + hi)
        return 1.0 / omega
    }
}

internal data class RodDriveSnapshot(
    val commandedInsertion: Double,
    val actualInsertion: Double,
    val velocityPerSecond: Double,
    val scramActive: Boolean,
)

internal class RodDriveModel {
    private var command = ReferencePlant.REFERENCE_ROD_INSERTION
    private var position = ReferencePlant.REFERENCE_ROD_INSERTION
    private var velocity = 0.0
    private var scramActive = false

    fun snapshot() = RodDriveSnapshot(command, position, velocity, scramActive)

    fun reset() {
        command = ReferencePlant.REFERENCE_ROD_INSERTION
        position = ReferencePlant.REFERENCE_ROD_INSERTION
        velocity = 0.0
        scramActive = false
    }

    fun commandInsertion(fraction: Double) {
        if (!scramActive) command = fraction.coerceIn(0.0, 1.0)
    }

    fun scram() {
        scramActive = true
        command = 1.0
    }

    fun clearScram() {
        scramActive = false
        velocity = 0.0
    }

    fun advance(dt: Double): RodDriveSnapshot {
        if (scramActive) {
            // Generic gravity/spring insertion: acceleration falls as the bank seats,
            // with hydraulic drag on velocity.  No teleporting of rod position.
            val acceleration = 1.8 * (1.0 - position) - 1.7 * velocity
            velocity = (velocity + acceleration * dt).coerceAtLeast(0.0)
            position = (position + velocity * dt).coerceAtMost(1.0)
            if (position >= 0.9999) {
                position = 1.0
                velocity = 0.0
            }
        } else {
            val maxRate = 0.0065
            val delta = command - position
            val step = delta.coerceIn(-maxRate * dt, maxRate * dt)
            position = (position + step).coerceIn(0.0, 1.0)
            velocity = if (dt > 0.0) step / dt else 0.0
        }
        return snapshot()
    }

    fun reactivity(): Double {
        fun integralWorth(x: Double): Double {
            val c = x.coerceIn(0.0, 1.0)
            return c - sin(2.0 * PI * c) / (2.0 * PI)
        }
        return ReferencePlant.TOTAL_CONTROL_BANK_WORTH *
            (integralWorth(ReferencePlant.REFERENCE_ROD_INSERTION) - integralWorth(position))
    }
}

internal data class PoisonSnapshot(
    val iodine: Double,
    val xenon: Double,
    val promethium: Double,
    val samarium: Double,
    val xenonReactivity: Double,
    val samariumReactivity: Double,
)

/**
 * Reduced isotope-balance model. Inventories are normalized to the full-power
 * equilibrium reference state; half-lives are physical and the absorption
 * mappings are explicitly calibration-required.
 */
internal class PoisonModel {
    private val lambdaI = ln(2.0) / (6.57 * 3600.0)
    private val lambdaXe = ln(2.0) / (9.14 * 3600.0)
    private val fullPowerXeBurnout = 2.5e-5
    private val xeDirectFraction = 0.05
    private val xeRemovalRef = lambdaXe + fullPowerXeBurnout
    private val iodineToXeScale = (1.0 - xeDirectFraction) * xeRemovalRef / lambdaI

    private val lambdaPm = ln(2.0) / (53.08 * 3600.0)
    private val fullPowerSmBurnout = 8.0e-6
    private val pmToSmScale = fullPowerSmBurnout / lambdaPm

    private var iodine = 1.0
    private var xenon = 1.0
    private var promethium = 1.0
    private var samarium = 1.0

    fun reset() {
        iodine = 1.0
        xenon = 1.0
        promethium = 1.0
        samarium = 1.0
    }

    fun advance(powerFraction: Double, dt: Double): PoisonSnapshot {
        val p = powerFraction.coerceAtLeast(0.0)
        // Implicit updates preserve positivity under time acceleration.
        iodine = (iodine + dt * lambdaI * p) / (1.0 + dt * lambdaI)

        val xeSource = xeDirectFraction * xeRemovalRef * p +
            iodineToXeScale * lambdaI * iodine
        val xeRemoval = lambdaXe + fullPowerXeBurnout * p
        xenon = (xenon + dt * xeSource) / (1.0 + dt * xeRemoval)

        promethium = (promethium + dt * lambdaPm * p) / (1.0 + dt * lambdaPm)
        val smSource = pmToSmScale * lambdaPm * promethium
        samarium = (samarium + dt * smSource) /
            (1.0 + dt * fullPowerSmBurnout * p)

        return snapshot()
    }

    fun snapshot() = PoisonSnapshot(
        iodine = iodine,
        xenon = xenon,
        promethium = promethium,
        samarium = samarium,
        xenonReactivity = -0.025 * (xenon - 1.0),
        samariumReactivity = -0.006 * (samarium - 1.0),
    )
}

internal data class ChemistrySnapshot(
    val boronPpm: Double,
    val boronReactivity: Double,
)

internal class ChemicalShimModel(
    private val referenceRcsMassKg: Double,
) {
    private var boronMassEquivalent = ReferencePlant.REFERENCE_BORON_PPM * referenceRcsMassKg
    private var makeupFlowKgS = 0.0
    private var makeupBoronPpm = ReferencePlant.REFERENCE_BORON_PPM

    fun setMakeup(flowKgS: Double, boronPpm: Double) {
        makeupFlowKgS = flowKgS.coerceIn(0.0, 20.0)
        makeupBoronPpm = boronPpm.coerceIn(0.0, 2500.0)
    }

    fun reset() {
        boronMassEquivalent = ReferencePlant.REFERENCE_BORON_PPM * referenceRcsMassKg
        makeupFlowKgS = 0.0
        makeupBoronPpm = ReferencePlant.REFERENCE_BORON_PPM
    }

    fun advance(dt: Double): ChemistrySnapshot {
        val current = boronMassEquivalent / referenceRcsMassKg
        // Equal letdown maintains RCS mass while changing dissolved inventory.
        boronMassEquivalent += dt * makeupFlowKgS * (makeupBoronPpm - current)
        return snapshot()
    }

    fun snapshot(): ChemistrySnapshot {
        val ppm = boronMassEquivalent / referenceRcsMassKg
        return ChemistrySnapshot(
            boronPpm = ppm,
            boronReactivity = ReferencePlant.BORON_WORTH_PER_PPM *
                (ppm - ReferencePlant.REFERENCE_BORON_PPM),
        )
    }
}
