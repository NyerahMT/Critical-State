package com.nyerahworks.criticalstate.sim

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class TwoPhaseEquilibrium(
    val pressureMpa: Double,
    val temperatureK: Double,
    val liquidMassKg: Double,
    val vapourMassKg: Double,
    val liquidVolumeM3: Double,
    val vapourVolumeM3: Double,
    val quality: Double,
    val rawQuality: Double,
    val residualKjKg: Double,
    /** True only when the saturated (m,U,V) closure is inside its declared envelope. */
    val saturationEnvelopeValid: Boolean,
    /** Non-null whenever this result is only a finite continuation candidate, not a valid saturation solve. */
    val diagnostic: String? = null,
)

/**
 * Fixed-volume saturated mixture closure from total mass and total U.
 *
 * This remains an equilibrium saturation model. If (m,U,V) cannot establish a
 * pressure root inside the configured pressure interval, if the energy residual
 * does not converge to ENERGY_TOLERANCE_KJ_KG, or if the implied quality lies
 * outside the saturated-mixture volume envelope, the returned finite candidate
 * is explicitly marked invalid. It is never silently promoted to a valid
 * subcooled/superheated two-phase state.
 */
internal class TwoPhaseVolumeSolver(
    private val water: WaterProperties,
    private val volumeM3: Double,
    private val minPressureMpa: Double,
    private val maxPressureMpa: Double,
) {
    companion object {
        /** Declared maximum |u_sat(m,V,p) - U/m| for a valid saturation closure. */
        internal const val ENERGY_TOLERANCE_KJ_KG = 0.001
        private const val QUALITY_TOLERANCE = 1.0e-9
        private const val LOCAL_INITIAL_SPAN_MPA = 0.04
        private const val MAX_BISECTION_ITERATIONS = 24
    }

    private var lastPressureMpa = 0.5 * (minPressureMpa + maxPressureMpa)

    fun solve(massKg: Double, internalEnergyKj: Double): TwoPhaseEquilibrium {
        require(massKg > 0.0 && massKg.isFinite())
        require(internalEnergyKj.isFinite())
        val targetV = volumeM3 / massKg
        val targetU = internalEnergyKj / massKg

        fun eval(p: Double): TwoPhaseEquilibrium {
            val sat = water.saturationP(p)
            val vf = 1.0 / sat.liquidDensityKgM3
            val vg = 1.0 / sat.vapourDensityKgM3
            val xRaw = (targetV - vf) / (vg - vf)
            // Clamp only to keep a finite continuation candidate. rawQuality and
            // saturationEnvelopeValid retain whether that candidate is physical.
            val x = if (xRaw.isFinite()) xRaw.coerceIn(0.0, 1.0) else 0.0
            val predictedU = sat.liquidInternalEnergyKjKg +
                x * (sat.vapourInternalEnergyKjKg - sat.liquidInternalEnergyKjKg)
            val vapourMass = x * massKg
            val liquidMass = massKg - vapourMass
            return TwoPhaseEquilibrium(
                pressureMpa = p,
                temperatureK = sat.temperatureK,
                liquidMassKg = liquidMass,
                vapourMassKg = vapourMass,
                liquidVolumeM3 = liquidMass / sat.liquidDensityKgM3,
                vapourVolumeM3 = vapourMass / sat.vapourDensityKgM3,
                quality = x,
                rawQuality = xRaw,
                residualKjKg = predictedU - targetU,
                saturationEnvelopeValid = false,
            )
        }

        fun finish(
            candidate: TwoPhaseEquilibrium,
            pressureRootEstablished: Boolean,
        ): TwoPhaseEquilibrium {
            val reasons = mutableListOf<String>()
            val finite = candidate.pressureMpa.isFinite() &&
                candidate.temperatureK.isFinite() &&
                candidate.liquidMassKg.isFinite() &&
                candidate.vapourMassKg.isFinite() &&
                candidate.liquidVolumeM3.isFinite() &&
                candidate.vapourVolumeM3.isFinite() &&
                candidate.residualKjKg.isFinite() && candidate.rawQuality.isFinite()
            if (!finite) reasons += "non-finite saturation candidate"
            if (!pressureRootEstablished) {
                reasons += String.format(
                    Locale.US,
                    "pressure root not bracketed/converged in [%.4f, %.4f] MPa",
                    minPressureMpa,
                    maxPressureMpa,
                )
            }
            if (!candidate.residualKjKg.isFinite() ||
                abs(candidate.residualKjKg) > ENERGY_TOLERANCE_KJ_KG
            ) {
                reasons += String.format(
                    Locale.US,
                    "energy residual %+.6f kJ/kg exceeds %.6f kJ/kg envelope",
                    candidate.residualKjKg,
                    ENERGY_TOLERANCE_KJ_KG,
                )
            }
            if (!candidate.rawQuality.isFinite() ||
                candidate.rawQuality < -QUALITY_TOLERANCE ||
                candidate.rawQuality > 1.0 + QUALITY_TOLERANCE
            ) {
                reasons += String.format(
                    Locale.US,
                    "specific volume outside saturated-mixture envelope (raw quality %.6f)",
                    candidate.rawQuality,
                )
            }
            val valid = reasons.isEmpty()
            if (candidate.pressureMpa.isFinite()) lastPressureMpa = candidate.pressureMpa
            return candidate.copy(
                saturationEnvelopeValid = valid,
                diagnostic = reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        }

        val warmPressure = lastPressureMpa.coerceIn(minPressureMpa, maxPressureMpa)
        val warm = eval(warmPressure)
        if (abs(warm.residualKjKg) <= ENERGY_TOLERANCE_KJ_KG) {
            return finish(warm, pressureRootEstablished = true)
        }

        var span = LOCAL_INITIAL_SPAN_MPA
        var lo = max(minPressureMpa, warmPressure - span)
        var hi = min(maxPressureMpa, warmPressure + span)
        var a = eval(lo)
        var b = eval(hi)
        repeat(6) {
            if (a.residualKjKg * b.residualKjKg <= 0.0) return@repeat
            span *= 2.0
            lo = max(minPressureMpa, warmPressure - span)
            hi = min(maxPressureMpa, warmPressure + span)
            a = eval(lo)
            b = eval(hi)
        }

        if (a.residualKjKg * b.residualKjKg > 0.0) {
            lo = minPressureMpa
            hi = maxPressureMpa
            a = eval(lo)
            b = eval(hi)
        }
        if (!a.residualKjKg.isFinite() || !b.residualKjKg.isFinite() ||
            a.residualKjKg * b.residualKjKg > 0.0
        ) {
            val finiteCandidates = listOf(warm, a, b).filter { it.residualKjKg.isFinite() }
            val best = finiteCandidates.minByOrNull { abs(it.residualKjKg) } ?: warm
            return finish(best, pressureRootEstablished = false)
        }

        var best = if (abs(a.residualKjKg) < abs(b.residualKjKg)) a else b
        repeat(MAX_BISECTION_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            val m = eval(mid)
            if (abs(m.residualKjKg) < abs(best.residualKjKg)) best = m
            if (abs(m.residualKjKg) <= ENERGY_TOLERANCE_KJ_KG) {
                return finish(m, pressureRootEstablished = true)
            }
            if (a.residualKjKg * m.residualKjKg <= 0.0) {
                hi = mid
                b = m
            } else {
                lo = mid
                a = m
            }
        }
        return finish(best, pressureRootEstablished = true)
    }
}

internal data class SinglePhaseVolumeState(
    val pressureMpa: Double,
    val state: WaterState,
    val residualM3Kg: Double,
)

/**
 * Fixed-volume single-phase closure from m, U, and V.
 * For a candidate pressure, h = u + p*v exactly, then IF97 supplies density.
 */
internal class SinglePhaseVolumeSolver(
    private val water: WaterProperties,
    private val volumeM3: Double,
    private val minPressureMpa: Double,
    private val maxPressureMpa: Double,
) {
    companion object {
        private const val VOLUME_TOLERANCE_M3_KG = 1.0e-9
        private const val MAX_BISECTION_ITERATIONS = 24
    }

    private var lastPressureMpa = 0.5 * (minPressureMpa + maxPressureMpa)

    fun solve(massKg: Double, internalEnergyKj: Double): SinglePhaseVolumeState {
        require(massKg > 0.0)
        val vTarget = volumeM3 / massKg
        val uTarget = internalEnergyKj / massKg

        fun eval(p: Double): SinglePhaseVolumeState {
            val h = uTarget + p * 1000.0 * vTarget
            val s = water.statePH(p, h)
            return SinglePhaseVolumeState(
                pressureMpa = p,
                state = s,
                residualM3Kg = 1.0 / s.densityKgM3 - vTarget,
            )
        }

        val warmPressure = lastPressureMpa.coerceIn(minPressureMpa, maxPressureMpa)
        val warm = eval(warmPressure)
        if (abs(warm.residualM3Kg) <= VOLUME_TOLERANCE_M3_KG) return warm

        var span = max(0.002, (maxPressureMpa - minPressureMpa) * 0.01)
        var lo = max(minPressureMpa, warmPressure - span)
        var hi = min(maxPressureMpa, warmPressure + span)
        var a = eval(lo)
        var b = eval(hi)
        repeat(6) {
            if (a.residualM3Kg * b.residualM3Kg <= 0.0) return@repeat
            span *= 2.0
            lo = max(minPressureMpa, warmPressure - span)
            hi = min(maxPressureMpa, warmPressure + span)
            a = eval(lo)
            b = eval(hi)
        }

        if (a.residualM3Kg * b.residualM3Kg > 0.0) {
            lo = minPressureMpa
            hi = maxPressureMpa
            a = eval(lo)
            b = eval(hi)
        }
        if (a.residualM3Kg * b.residualM3Kg > 0.0) {
            val best = listOf(warm, a, b).minByOrNull { abs(it.residualM3Kg) } ?: warm
            lastPressureMpa = best.pressureMpa
            return best
        }

        var best = if (abs(a.residualM3Kg) < abs(b.residualM3Kg)) a else b
        repeat(MAX_BISECTION_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            val m = eval(mid)
            if (abs(m.residualM3Kg) < abs(best.residualM3Kg)) best = m
            if (abs(m.residualM3Kg) <= VOLUME_TOLERANCE_M3_KG) {
                lastPressureMpa = m.pressureMpa
                return m
            }
            if (a.residualM3Kg * m.residualM3Kg <= 0.0) {
                hi = mid
                b = m
            } else {
                lo = mid
                a = m
            }
        }
        lastPressureMpa = best.pressureMpa
        return best
    }
}
