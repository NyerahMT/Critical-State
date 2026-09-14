package com.nyerahworks.criticalstate.sim

import kotlin.math.abs

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

/** Fixed-volume saturated mixture closure from total mass and total U. */
internal class TwoPhaseVolumeSolver(
    private val water: WaterProperties,
    private val volumeM3: Double,
    private val minPressureMpa: Double,
    private val maxPressureMpa: Double,
) {
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

        var lo = minPressureMpa
        var hi = maxPressureMpa
        var a = eval(lo)
        var b = eval(hi)
        if (a.residualKjKg * b.residualKjKg > 0.0) {
            return if (abs(a.residualKjKg) < abs(b.residualKjKg)) a else b
        }
        repeat(54) {
            val mid = 0.5 * (lo + hi)
            val m = eval(mid)
            if (a.residualKjKg * m.residualKjKg <= 0.0) {
                hi = mid
                b = m
            } else {
                lo = mid
                a = m
            }
        }
        return if (abs(a.residualKjKg) < abs(b.residualKjKg)) a else b
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

        var lo = minPressureMpa
        var hi = maxPressureMpa
        var a = eval(lo)
        var b = eval(hi)
        if (a.residualM3Kg * b.residualM3Kg > 0.0) {
            return if (abs(a.residualM3Kg) < abs(b.residualM3Kg)) a else b
        }
        repeat(50) {
            val mid = 0.5 * (lo + hi)
            val m = eval(mid)
            if (a.residualM3Kg * m.residualM3Kg <= 0.0) {
                hi = mid
                b = m
            } else {
                lo = mid
                a = m
            }
        }
        return if (abs(a.residualM3Kg) < abs(b.residualM3Kg)) a else b
    }
}
