package com.nyerahworks.criticalstate.sim

import com.hummeling.if97.IF97

internal data class WaterState(
    val pressureMpa: Double,
    val temperatureK: Double,
    val enthalpyKjKg: Double,
    val internalEnergyKjKg: Double,
    val entropyKjKgK: Double,
    val densityKgM3: Double,
    val cpKjKgK: Double,
    val viscosityPaS: Double,
    val conductivityWmK: Double,
    val quality: Double? = null,
)

internal data class SaturationState(
    val pressureMpa: Double,
    val temperatureK: Double,
    val liquidEnthalpyKjKg: Double,
    val vapourEnthalpyKjKg: Double,
    val liquidInternalEnergyKjKg: Double,
    val vapourInternalEnergyKjKg: Double,
    val liquidDensityKgM3: Double,
    val vapourDensityKgM3: Double,
) {
    val latentHeatKjKg: Double get() = vapourEnthalpyKjKg - liquidEnthalpyKjKg
}

/**
 * Project-owned thermodynamic seam. All plant code uses this interface rather
 * than calling a particular IF97 library directly.
 */
internal interface WaterProperties {
    fun statePT(pressureMpa: Double, temperatureK: Double): WaterState
    fun statePH(pressureMpa: Double, enthalpyKjKg: Double): WaterState
    fun saturationP(pressureMpa: Double): SaturationState
    fun enthalpyPS(pressureMpa: Double, entropyKjKgK: Double): Double
}

/**
 * Tiny direct-mapped exact caches for the expensive IF97 calls.
 *
 * No thermodynamic inputs are rounded or interpolated: a hit requires the exact
 * Double bit patterns used by the caller. The plant repeatedly asks for the same
 * pressure/enthalpy state while coupling a timestep and while composing a UI
 * snapshot, so this removes duplicate library work without changing physics.
 * Direct mapping also avoids the object allocation and GC churn of an LRU map.
 */
private class WaterStateCache(size: Int) {
    private val capacity = Integer.highestOneBit(size.coerceAtLeast(2) - 1) shl 1
    private val mask = capacity - 1
    private val occupied = BooleanArray(capacity)
    private val firstBits = LongArray(capacity)
    private val secondBits = LongArray(capacity)
    private val values = arrayOfNulls<WaterState>(capacity)

    fun get(first: Double, second: Double, producer: () -> WaterState): WaterState {
        val a = java.lang.Double.doubleToRawLongBits(first)
        val b = java.lang.Double.doubleToRawLongBits(second)
        val index = hash(a, b) and mask
        if (occupied[index] && firstBits[index] == a && secondBits[index] == b) {
            return values[index]!!
        }
        val value = producer()
        occupied[index] = true
        firstBits[index] = a
        secondBits[index] = b
        values[index] = value
        return value
    }

    private fun hash(a: Long, b: Long): Int {
        var x = a xor (a ushr 33) xor java.lang.Long.rotateLeft(b, 21)
        x *= -49064778989728563L
        x = x xor (x ushr 29)
        return (x xor (x ushr 32)).toInt()
    }
}

private class SaturationCache(size: Int) {
    private val capacity = Integer.highestOneBit(size.coerceAtLeast(2) - 1) shl 1
    private val mask = capacity - 1
    private val occupied = BooleanArray(capacity)
    private val pressureBits = LongArray(capacity)
    private val values = arrayOfNulls<SaturationState>(capacity)

    fun get(pressure: Double, producer: () -> SaturationState): SaturationState {
        val bits = java.lang.Double.doubleToRawLongBits(pressure)
        val index = ((bits xor (bits ushr 32)).toInt() * -1640531527) and mask
        if (occupied[index] && pressureBits[index] == bits) return values[index]!!
        val value = producer()
        occupied[index] = true
        pressureBits[index] = bits
        values[index] = value
        return value
    }
}

private class ScalarStateCache(size: Int) {
    private val capacity = Integer.highestOneBit(size.coerceAtLeast(2) - 1) shl 1
    private val mask = capacity - 1
    private val occupied = BooleanArray(capacity)
    private val firstBits = LongArray(capacity)
    private val secondBits = LongArray(capacity)
    private val values = DoubleArray(capacity)

    fun get(first: Double, second: Double, producer: () -> Double): Double {
        val a = java.lang.Double.doubleToRawLongBits(first)
        val b = java.lang.Double.doubleToRawLongBits(second)
        val mixed = a xor java.lang.Long.rotateLeft(b, 17) xor (a ushr 31)
        val index = ((mixed xor (mixed ushr 32)).toInt() * -1640531527) and mask
        if (occupied[index] && firstBits[index] == a && secondBits[index] == b) {
            return values[index]
        }
        val value = producer()
        occupied[index] = true
        firstBits[index] = a
        secondBits[index] = b
        values[index] = value
        return value
    }
}

internal class If97WaterProperties : WaterProperties {
    private val if97 = IF97()
    private val ptCache = WaterStateCache(1024)
    private val phCache = WaterStateCache(2048)
    private val saturationCache = SaturationCache(512)
    private val psCache = ScalarStateCache(256)

    override fun statePT(pressureMpa: Double, temperatureK: Double): WaterState =
        ptCache.get(pressureMpa, temperatureK) {
            val h = if97.specificEnthalpyPT(pressureMpa, temperatureK)
            val rho = if97.densityPT(pressureMpa, temperatureK)
            WaterState(
                pressureMpa = pressureMpa,
                temperatureK = temperatureK,
                enthalpyKjKg = h,
                internalEnergyKjKg = if97.specificInternalEnergyPT(pressureMpa, temperatureK),
                entropyKjKgK = if97.specificEntropyPT(pressureMpa, temperatureK),
                densityKgM3 = rho,
                cpKjKgK = if97.isobaricHeatCapacityPT(pressureMpa, temperatureK),
                viscosityPaS = if97.dynamicViscosityPT(pressureMpa, temperatureK),
                conductivityWmK = if97.thermalConductivityPT(pressureMpa, temperatureK),
                quality = null,
            )
        }

    override fun statePH(pressureMpa: Double, enthalpyKjKg: Double): WaterState =
        phCache.get(pressureMpa, enthalpyKjKg) {
            val temperature = if97.temperaturePH(pressureMpa, enthalpyKjKg)
            val rho = if97.densityPH(pressureMpa, enthalpyKjKg)
            val u = enthalpyKjKg - pressureMpa * 1000.0 / rho
            val quality = runCatching { if97.vapourFractionPH(pressureMpa, enthalpyKjKg) }
                .getOrNull()
                ?.takeIf { it in 0.0..1.0 }
            WaterState(
                pressureMpa = pressureMpa,
                temperatureK = temperature,
                enthalpyKjKg = enthalpyKjKg,
                internalEnergyKjKg = u,
                entropyKjKgK = if97.specificEntropyPH(pressureMpa, enthalpyKjKg),
                densityKgM3 = rho,
                cpKjKgK = if97.isobaricHeatCapacityPH(pressureMpa, enthalpyKjKg),
                viscosityPaS = if97.dynamicViscosityPH(pressureMpa, enthalpyKjKg),
                conductivityWmK = if97.thermalConductivityPH(pressureMpa, enthalpyKjKg),
                quality = quality,
            )
        }

    override fun saturationP(pressureMpa: Double): SaturationState =
        saturationCache.get(pressureMpa) {
            val vf = if97.specificVolumeSaturatedLiquidP(pressureMpa)
            val vg = if97.specificVolumeSaturatedVapourP(pressureMpa)
            SaturationState(
                pressureMpa = pressureMpa,
                temperatureK = if97.saturationTemperatureP(pressureMpa),
                liquidEnthalpyKjKg = if97.specificEnthalpySaturatedLiquidP(pressureMpa),
                vapourEnthalpyKjKg = if97.specificEnthalpySaturatedVapourP(pressureMpa),
                liquidInternalEnergyKjKg = if97.specificInternalEnergySaturatedLiquidP(pressureMpa),
                vapourInternalEnergyKjKg = if97.specificInternalEnergySaturatedVapourP(pressureMpa),
                liquidDensityKgM3 = 1.0 / vf,
                vapourDensityKgM3 = 1.0 / vg,
            )
        }

    override fun enthalpyPS(pressureMpa: Double, entropyKjKgK: Double): Double =
        psCache.get(pressureMpa, entropyKjKgK) {
            if97.specificEnthalpyPS(pressureMpa, entropyKjKgK)
        }
}
