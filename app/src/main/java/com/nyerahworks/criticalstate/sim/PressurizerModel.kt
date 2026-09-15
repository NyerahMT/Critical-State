package com.nyerahworks.criticalstate.sim

import kotlin.math.max
import kotlin.math.sqrt

internal data class PressurizerSnapshot(
    val pressureMpa: Double,
    val saturationTemperatureK: Double,
    val levelFraction: Double,
    val heaterFraction: Double,
    val sprayFraction: Double,
    val surgeFlowKgPerS: Double,
    val sprayFlowKgPerS: Double,
    val reliefFlowKgPerS: Double,
    val liquidMassKg: Double,
    val vapourMassKg: Double,
    val totalMassKg: Double,
    val internalEnergyKj: Double,
    val inventoryResidualKg: Double,
    val heaterPowerMw: Double = 0.0,
    val heatLossMw: Double = 0.0,
    val reliefEnergyMw: Double = 0.0,
    val saturationEnvelopeValid: Boolean = true,
    val diagnostic: String? = null,
)

/**
 * Fixed-volume equilibrium pressurizer. Mass and internal energy are the
 * conserved states; pressure, saturation temperature, phase split and level
 * are thermodynamic consequences.
 *
 * The primary-side surge term remains an inventory/swell coupling boundary,
 * not a resolved hydraulic surge line.
 */
internal class PressurizerModel(
    private val water: WaterProperties,
    referencePrimaryLiquidMassKg: Double,
) {
    companion object {
        const val REFERENCE_PRESSURE_MPA = ReferencePlant.PRIMARY_PRESSURE_MPA
        private const val PRESSURE_DEADBAND_MPA = 0.025
        private const val FULL_CONTROL_ERROR_MPA = 0.25
        private const val SURGE_RESPONSE_TIME_S = 1.5
        private const val MAX_SURGE_FLOW_KG_S = 600.0
        private const val RELIEF_SETPOINT_MPA = 17.0
        private const val RELIEF_COEFFICIENT_KG_S_SQRT_MPA = 18.0
        private const val HEAT_LOSS_MW_PER_K = 0.0005
        private const val AMBIENT_K = 300.0
    }

    private val solver = TwoPhaseVolumeSolver(
        water = water,
        volumeM3 = ReferencePlant.PZR_VOLUME_M3,
        minPressureMpa = 5.0,
        maxPressureMpa = 21.5,
    )

    private val referenceMassKg: Double
    private val referenceEnergyKj: Double
    private val totalPrimaryInventoryKg: Double

    private var massKg: Double
    private var energyKj: Double
    private var equilibrium: TwoPhaseEquilibrium
    private var heaterFraction = 0.0
    private var sprayFraction = 0.0
    private var surgeFlowKgS = 0.0
    private var sprayFlowKgS = 0.0
    private var reliefFlowKgS = 0.0
    private var heaterPowerMw = 0.0
    private var heatLossMw = 0.0
    private var reliefEnergyMw = 0.0
    private var inventoryResidualKg = 0.0
    private var diagnostic: String? = null

    init {
        val sat = water.saturationP(REFERENCE_PRESSURE_MPA)
        val liquidVolume = ReferencePlant.PZR_REFERENCE_LIQUID_VOLUME_M3
        val vapourVolume = ReferencePlant.PZR_VOLUME_M3 - liquidVolume
        val liquidMass = liquidVolume * sat.liquidDensityKgM3
        val vapourMass = vapourVolume * sat.vapourDensityKgM3
        referenceMassKg = liquidMass + vapourMass
        referenceEnergyKj = liquidMass * sat.liquidInternalEnergyKjKg +
            vapourMass * sat.vapourInternalEnergyKjKg
        totalPrimaryInventoryKg = referencePrimaryLiquidMassKg + referenceMassKg
        massKg = referenceMassKg
        energyKj = referenceEnergyKj
        equilibrium = solver.solve(massKg, energyKj)
        appendSaturationDiagnostic()
    }

    fun reset(): PressurizerSnapshot {
        massKg = referenceMassKg
        energyKj = referenceEnergyKj
        diagnostic = null
        equilibrium = solver.solve(massKg, energyKj)
        appendSaturationDiagnostic()
        heaterFraction = 0.0
        sprayFraction = 0.0
        surgeFlowKgS = 0.0
        sprayFlowKgS = 0.0
        reliefFlowKgS = 0.0
        heaterPowerMw = 0.0
        heatLossMw = max(0.0, equilibrium.temperatureK - AMBIENT_K) * HEAT_LOSS_MW_PER_K
        reliefEnergyMw = 0.0
        inventoryResidualKg = 0.0
        return snapshot()
    }

    fun advance(
        primaryLiquidMassKg: Double,
        surgeSourceEnthalpyKjKg: Double,
        spraySourceEnthalpyKjKg: Double,
        dt: Double,
    ): PressurizerSnapshot {
        if (dt <= 0.0) return snapshot()
        diagnostic = null

        val pressure = equilibrium.pressureMpa
        val targetPzrMass = totalPrimaryInventoryKg - primaryLiquidMassKg
        val requestedSurge = ((targetPzrMass - massKg) / SURGE_RESPONSE_TIME_S)
            .coerceIn(-MAX_SURGE_FLOW_KG_S, MAX_SURGE_FLOW_KG_S)
        val blend = (dt / SURGE_RESPONSE_TIME_S).coerceIn(0.0, 1.0)
        surgeFlowKgS += (requestedSurge - surgeFlowKgS) * blend

        val pressureError = REFERENCE_PRESSURE_MPA - pressure
        heaterFraction = if (pressureError > PRESSURE_DEADBAND_MPA) {
            ((pressureError - PRESSURE_DEADBAND_MPA) / FULL_CONTROL_ERROR_MPA).coerceIn(0.0, 1.0)
        } else 0.0
        sprayFraction = if (pressureError < -PRESSURE_DEADBAND_MPA) {
            ((-pressureError - PRESSURE_DEADBAND_MPA) / FULL_CONTROL_ERROR_MPA).coerceIn(0.0, 1.0)
        } else 0.0
        sprayFlowKgS = sprayFraction * ReferencePlant.PZR_MAX_SPRAY_KG_S
        reliefFlowKgS = if (pressure > RELIEF_SETPOINT_MPA) {
            RELIEF_COEFFICIENT_KG_S_SQRT_MPA * sqrt(pressure - RELIEF_SETPOINT_MPA)
        } else 0.0

        val sat = water.saturationP(pressure)
        val surgeH = if (surgeFlowKgS >= 0.0) surgeSourceEnthalpyKjKg else sat.liquidEnthalpyKjKg
        heaterPowerMw = heaterFraction * ReferencePlant.PZR_MAX_HEATER_MW
        heatLossMw = max(0.0, equilibrium.temperatureK - AMBIENT_K) * HEAT_LOSS_MW_PER_K
        reliefEnergyMw = reliefFlowKgS * sat.vapourEnthalpyKjKg / 1000.0

        massKg += (surgeFlowKgS + sprayFlowKgS - reliefFlowKgS) * dt
        energyKj += (
            surgeFlowKgS * surgeH +
                sprayFlowKgS * spraySourceEnthalpyKjKg -
                reliefFlowKgS * sat.vapourEnthalpyKjKg +
                (heaterPowerMw - heatLossMw) * 1000.0
            ) * dt
        if (massKg < 1.0 || !massKg.isFinite() || !energyKj.isFinite()) {
            diagnostic = "Pressurizer conserved state invalid"
            massKg = max(1.0, massKg.takeIf { it.isFinite() } ?: referenceMassKg)
            energyKj = energyKj.takeIf { it.isFinite() } ?: referenceEnergyKj
        }

        equilibrium = solver.solve(massKg, energyKj)
        appendSaturationDiagnostic()
        inventoryResidualKg = primaryLiquidMassKg + massKg - totalPrimaryInventoryKg
        return snapshot()
    }

    private fun appendSaturationDiagnostic() {
        if (equilibrium.saturationEnvelopeValid) return
        val flag = "PZR saturation (m,U) envelope invalid: ${equilibrium.diagnostic ?: "unspecified closure failure"}"
        diagnostic = listOfNotNull(diagnostic, flag).joinToString("; ")
    }

    fun snapshot(): PressurizerSnapshot = PressurizerSnapshot(
        pressureMpa = equilibrium.pressureMpa,
        saturationTemperatureK = equilibrium.temperatureK,
        levelFraction = (equilibrium.liquidVolumeM3 / ReferencePlant.PZR_VOLUME_M3).coerceIn(0.0, 1.0),
        heaterFraction = heaterFraction,
        sprayFraction = sprayFraction,
        surgeFlowKgPerS = surgeFlowKgS,
        sprayFlowKgPerS = sprayFlowKgS,
        reliefFlowKgPerS = reliefFlowKgS,
        liquidMassKg = equilibrium.liquidMassKg,
        vapourMassKg = equilibrium.vapourMassKg,
        totalMassKg = massKg,
        internalEnergyKj = energyKj,
        inventoryResidualKg = inventoryResidualKg,
        heaterPowerMw = heaterPowerMw,
        heatLossMw = heatLossMw,
        reliefEnergyMw = reliefEnergyMw,
        saturationEnvelopeValid = equilibrium.saturationEnvelopeValid,
        diagnostic = diagnostic,
    )
}
