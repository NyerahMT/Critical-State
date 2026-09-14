package com.nyerahworks.criticalstate.sim

import com.hummeling.if97.IF97

/**
 * Narrow thermodynamic-property seam used by plant models.
 *
 * Keeping IF97 behind this interface prevents the plant solver from depending
 * directly on a particular property-library API.  The implementation can be
 * replaced later without changing pressurizer or primary-system equations.
 */
internal interface WaterProperties {
    fun saturationTemperatureK(pressureMpa: Double): Double
    fun saturatedLiquidSpecificVolumeM3PerKg(pressureMpa: Double): Double
    fun saturatedVapourSpecificVolumeM3PerKg(pressureMpa: Double): Double
    fun saturatedLiquidInternalEnergyKjPerKg(pressureMpa: Double): Double
    fun saturatedVapourInternalEnergyKjPerKg(pressureMpa: Double): Double
    fun saturatedLiquidEnthalpyKjPerKg(pressureMpa: Double): Double
    fun liquidEnthalpyKjPerKg(pressureMpa: Double, temperatureK: Double): Double
    fun liquidDensityKgPerM3(pressureMpa: Double, temperatureK: Double): Double
}

/**
 * IAPWS-IF97 property adapter.
 *
 * Hummeling IF97's default unit system is MPa, K, kJ/kg, and m3/kg, which
 * matches Critical State's thermodynamic solver units.
 */
internal class If97WaterProperties : WaterProperties {
    private val if97 = IF97()

    override fun saturationTemperatureK(pressureMpa: Double): Double =
        if97.saturationTemperatureP(pressureMpa)

    override fun saturatedLiquidSpecificVolumeM3PerKg(pressureMpa: Double): Double =
        if97.specificVolumeSaturatedLiquidP(pressureMpa)

    override fun saturatedVapourSpecificVolumeM3PerKg(pressureMpa: Double): Double =
        if97.specificVolumeSaturatedVapourP(pressureMpa)

    override fun saturatedLiquidInternalEnergyKjPerKg(pressureMpa: Double): Double =
        if97.specificInternalEnergySaturatedLiquidP(pressureMpa)

    override fun saturatedVapourInternalEnergyKjPerKg(pressureMpa: Double): Double =
        if97.specificInternalEnergySaturatedVapourP(pressureMpa)

    override fun saturatedLiquidEnthalpyKjPerKg(pressureMpa: Double): Double =
        if97.specificEnthalpySaturatedLiquidP(pressureMpa)

    override fun liquidEnthalpyKjPerKg(pressureMpa: Double, temperatureK: Double): Double =
        if97.specificEnthalpyPT(pressureMpa, temperatureK)

    override fun liquidDensityKgPerM3(pressureMpa: Double, temperatureK: Double): Double =
        if97.densityPT(pressureMpa, temperatureK)
}
