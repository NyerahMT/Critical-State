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
 * Project-owned thermodynamic seam.  All plant code uses this interface rather
 * than calling a particular IF97 library directly.
 */
internal interface WaterProperties {
    fun statePT(pressureMpa: Double, temperatureK: Double): WaterState
    fun statePH(pressureMpa: Double, enthalpyKjKg: Double): WaterState
    fun saturationP(pressureMpa: Double): SaturationState
    fun enthalpyPS(pressureMpa: Double, entropyKjKgK: Double): Double
}

internal class If97WaterProperties : WaterProperties {
    private val if97 = IF97()

    override fun statePT(pressureMpa: Double, temperatureK: Double): WaterState {
        val h = if97.specificEnthalpyPT(pressureMpa, temperatureK)
        val rho = if97.densityPT(pressureMpa, temperatureK)
        return WaterState(
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

    override fun statePH(pressureMpa: Double, enthalpyKjKg: Double): WaterState {
        val temperature = if97.temperaturePH(pressureMpa, enthalpyKjKg)
        val rho = if97.densityPH(pressureMpa, enthalpyKjKg)
        val u = enthalpyKjKg - pressureMpa * 1000.0 / rho
        val quality = runCatching { if97.vapourFractionPH(pressureMpa, enthalpyKjKg) }
            .getOrNull()
            ?.takeIf { it in 0.0..1.0 }
        return WaterState(
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

    override fun saturationP(pressureMpa: Double): SaturationState {
        val vf = if97.specificVolumeSaturatedLiquidP(pressureMpa)
        val vg = if97.specificVolumeSaturatedVapourP(pressureMpa)
        return SaturationState(
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
        if97.specificEnthalpyPS(pressureMpa, entropyKjKgK)
}
