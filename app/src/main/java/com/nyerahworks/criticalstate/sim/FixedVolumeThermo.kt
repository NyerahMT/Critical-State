package com.nyerahworks.criticalstate.sim

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
    val residualKjKg: Double,
)

/**
 * Fixed-volume saturated mixture closure from total mass and total U.
 *
 * The original prototype bisected the complete pressure range for 54 iterations
 * every call. That drove IF97 to essentially machine precision even though the
 * surrounding reduced-order model only diagnoses closure errors above fractions
 * of a kJ/kg. This solver keeps the exact same closure equation, but warm-starts
 * from the previous pressure, searches a local bracket first, exits on a tight
 * physical residual and falls back to the full range when a transient moves the
 * solution outside the local neighborhood.
 */
internal class TwoPhaseVolumeSolver(
    private val water: WaterProperties,
    private val volumeM3: Double,
    private val minPressureMpa: Double,
    private val maxPressureMpa: Double,
) {
    companion object {
        private const val ENERGY_TOLERANCE_KJ_KG = 0.001
        private const val LOCAL_INITIAL_SPAN_MPA = 0.04
        private const val MAX_BISECTION_ITERATIONS = 24
    }

    private var lastPressureMpa = 0.5 * (minPressureMpa + maxPressureMpa)

    fun solve(massKg: Double, internalEnergyKj: Double): TwoPhaseEquilibrium {
        require(massKg > 0.0)
        val targetV = volumeM3 / massKg
        val targetU = internalEnergyKj / massKg

        fun eval(p: Double): TwoPhaseEquilibrium {
            val sat = water.saturationP(p)
            val vf = 1.0 / sat.liquidDensityKgM3
            val vg = 1.0 / sat.vapourDensityKgM3
            val xRaw = (targetV - vf) / (vg - vf)
            val x = xRaw.coerceIn(0.0, 1.0)
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
                residualKjKg = predictedU - targetU,
            )
        }

        val warmPressure = lastPressureMpa.coerceIn(minPressureMpa, maxPressureMpa)
        val warm = eval(warmPressure)
        if (abs(warm.residualKjKg) <= ENERGY_TOLERANCE_KJ_KG) return warm

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
        if (a.residualKjKg * b.residualKjKg > 0.0) {
            val best = listOf(warm, a, b).minByOrNull { abs(it.residualKjKg) } ?: warm
            lastPressureMpa = best.pressureMpa
            return best
        }

        var best = if (abs(a.residualKjKg) < abs(b.residualKjKg)) a else b
        repeat(MAX_BISECTION_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            val m = eval(mid)
            if (abs(m.residualKjKg) < abs(best.residualKjKg)) best = m
            if (abs(m.residualKjKg) <= ENERGY_TOLERANCE_KJ_KG) {
                lastPressureMpa = m.pressureMpa
                return m
            }
            if (a.residualKjKg * m.residualKjKg <= 0.0) {
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
