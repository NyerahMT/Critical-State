package com.nyerahworks.criticalstate.sim

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

internal data class ComponentConditionSnapshot(
    val rcp: List<Double>,
    val steamGenerator: List<Double>,
    val turbine: Double,
    val condenser: Double,
    val feedwaterPump: Double,
    val accumulatedThermalDamage: Double,
)

/**
 * Hidden component condition. Condition never multiplies a player-facing score;
 * it is consumed only by physical component equations (pump head/efficiency,
 * heat-transfer conductance, turbine efficiency and mechanical loss).
 */
internal class ComponentConditionModel {
    private val rcp = DoubleArray(ReferencePlant.LOOP_COUNT) { 1.0 }
    private val sg = DoubleArray(ReferencePlant.LOOP_COUNT) { 1.0 }
    private var turbine = 1.0
    private var condenser = 1.0
    private var feedwaterPump = 1.0
    private var accumulatedThermalDamage = 0.0
    private var previousHotLegK = ReferencePlant.HOT_LEG_T_K

    fun reset() {
        rcp.fill(1.0)
        sg.fill(1.0)
        turbine = 1.0
        condenser = 1.0
        feedwaterPump = 1.0
        accumulatedThermalDamage = 0.0
        previousHotLegK = ReferencePlant.HOT_LEG_T_K
    }

    fun advance(
        loopSnapshots: List<PrimaryLoopSnapshot>,
        steamGenerators: List<SteamGeneratorSnapshot>,
        turbineSnapshot: TurbineGeneratorSnapshot,
        condenserSnapshot: CondenserSnapshot,
        feedwaterSnapshot: FeedwaterSnapshot,
        hotLegTemperatureK: Double,
        dt: Double,
    ): ComponentConditionSnapshot {
        if (dt <= 0.0) return snapshot()

        loopSnapshots.forEachIndexed { i, loop ->
            val speed = loop.pump.rpm / ReferencePlant.RCP_RATED_RPM
            val flow = kotlin.math.abs(loop.massFlowKgS) / ReferencePlant.FLOW_PER_LOOP_KG_PER_S
            val stress = max(speed, flow)
            val wearRate = 2.0e-10 * (1.0 + 8.0 * max(0.0, stress - 1.0).pow(2.0))
            rcp[i] = (rcp[i] - wearRate * dt).coerceIn(0.0, 1.0)
        }

        steamGenerators.forEachIndexed { i, generator ->
            val thermalStress = max(0.0, generator.heatTransferMw /
                (ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT) - 1.0)
            val foulingRate = 7.0e-11 * (1.0 + 5.0 * thermalStress * thermalStress)
            sg[i] = (sg[i] - foulingRate * dt).coerceIn(0.0, 1.0)
        }

        val turbineStress = max(
            turbineSnapshot.rpm / ReferencePlant.SYNCHRONOUS_RPM,
            turbineSnapshot.mechanicalPowerMw / max(ReferencePlant.RATED_GROSS_ELECTRIC_MW, 1.0),
        )
        turbine = (turbine - 1.2e-10 *
            (1.0 + 10.0 * max(0.0, turbineStress - 1.0).pow(2.0)) * dt)
            .coerceIn(0.0, 1.0)

        val condenserStress = max(0.0,
            condenserSnapshot.pressureMpa / ReferencePlant.CONDENSER_PRESSURE_MPA - 1.0)
        condenser = (condenser - 6.0e-11 *
            (1.0 + 4.0 * condenserStress.pow(2.0)) * dt).coerceIn(0.0, 1.0)

        val fwStress = feedwaterSnapshot.totalFlowKgS /
            max(feedwaterSnapshot.perSgFlowKgS.sum(), 1.0)
        feedwaterPump = (feedwaterPump - 8.0e-11 *
            (1.0 + 3.0 * max(0.0, fwStress - 1.0).pow(2.0)) * dt).coerceIn(0.0, 1.0)

        // Reduced Miner's-rule proxy driven by thermal excursion rate. This is
        // intentionally generic; it never pretends to be an ASME fatigue usage factor.
        val rampRateKPerS = kotlin.math.abs(hotLegTemperatureK - previousHotLegK) / dt
        accumulatedThermalDamage += rampRateKPerS.pow(2.0) * dt * 1.0e-11
        previousHotLegK = hotLegTemperatureK
        return snapshot()
    }

    fun restore(component: String, amount: Double) {
        val delta = amount.coerceIn(0.0, 1.0)
        when (component.lowercase()) {
            "turbine" -> turbine = (turbine + delta).coerceAtMost(1.0)
            "condenser" -> condenser = (condenser + delta).coerceAtMost(1.0)
            "feedwater" -> feedwaterPump = (feedwaterPump + delta).coerceAtMost(1.0)
            "rcp" -> for (i in rcp.indices) rcp[i] = (rcp[i] + delta).coerceAtMost(1.0)
            "sg" -> for (i in sg.indices) sg[i] = (sg[i] + delta).coerceAtMost(1.0)
        }
    }

    fun snapshot() = ComponentConditionSnapshot(
        rcp = rcp.toList(),
        steamGenerator = sg.toList(),
        turbine = turbine,
        condenser = condenser,
        feedwaterPump = feedwaterPump,
        accumulatedThermalDamage = accumulatedThermalDamage,
    )
}

internal data class EconomicsSnapshot(
    val generatedMwh: Double,
    val revenueDollars: Double,
    val cashDollars: Double,
    val debtDollars: Double,
    val capacityFactor: Double,
)

/** Long-timescale game accounting driven only by physical net electrical output. */
internal class EconomicsModel {
    companion object {
        private const val ENERGY_PRICE_PER_MWH = 55.0
        private const val VARIABLE_OM_PER_MWH = 9.0
        private const val INITIAL_DEBT = 750_000_000.0
        private const val ANNUAL_DEBT_RATE = 0.055
    }

    private var generatedMwh = 0.0
    private var revenue = 0.0
    private var cash = 0.0
    private var debt = INITIAL_DEBT
    private var elapsedS = 0.0

    fun reset() {
        generatedMwh = 0.0
        revenue = 0.0
        cash = 0.0
        debt = INITIAL_DEBT
        elapsedS = 0.0
    }

    fun advance(netPowerMw: Double, dt: Double): EconomicsSnapshot {
        val energyMwh = max(0.0, netPowerMw) * dt / 3600.0
        generatedMwh += energyMwh
        val grossRevenue = energyMwh * ENERGY_PRICE_PER_MWH
        val variableCost = energyMwh * VARIABLE_OM_PER_MWH
        val interest = debt * ANNUAL_DEBT_RATE * dt / (365.25 * 86400.0)
        revenue += grossRevenue
        cash += grossRevenue - variableCost - interest
        debt += interest
        elapsedS += dt
        return snapshot()
    }

    fun snapshot(): EconomicsSnapshot {
        val possibleMwh = ReferencePlant.RATED_NET_ELECTRIC_MW * elapsedS / 3600.0
        return EconomicsSnapshot(
            generatedMwh = generatedMwh,
            revenueDollars = revenue,
            cashDollars = cash,
            debtDollars = debt,
            capacityFactor = if (possibleMwh > 0.0) generatedMwh / possibleMwh else 0.0,
        )
    }
}

internal data class ConservationSnapshot(
    val massResidualKg: Double,
    val massDriftKgPerS: Double,
    val energyResidualMj: Double,
    val energyResidualMw: Double,
)

/**
 * Whole-plant conservation ledger. It treats fission heat as an internal source,
 * condenser cooling-water heat and generator output as boundary sinks, and
 * pressurizer relief as the only modeled water-mass loss to the environment.
 */
internal class ConservationAudit {
    private var initialized = false
    private var referenceMassKg = 0.0
    private var referenceStoredEnergyMj = 0.0
    private var cumulativeReliefKg = 0.0
    private var cumulativeNuclearInputMj = 0.0
    private var cumulativeElectricalOutputMj = 0.0
    private var cumulativeCoolingOutputMj = 0.0
    private var previousMassResidual = 0.0
    private var previousEnergyResidual = 0.0

    fun reset() {
        initialized = false
        referenceMassKg = 0.0
        referenceStoredEnergyMj = 0.0
        cumulativeReliefKg = 0.0
        cumulativeNuclearInputMj = 0.0
        cumulativeElectricalOutputMj = 0.0
        cumulativeCoolingOutputMj = 0.0
        previousMassResidual = 0.0
        previousEnergyResidual = 0.0
    }

    fun advance(
        totalWaterMassKg: Double,
        totalStoredEnergyMj: Double,
        fissionPowerMw: Double,
        generatorNetMw: Double,
        condenserHeatRejectionMw: Double,
        reliefFlowKgS: Double,
        dt: Double,
    ): ConservationSnapshot {
        if (!initialized) {
            initialized = true
            referenceMassKg = totalWaterMassKg
            referenceStoredEnergyMj = totalStoredEnergyMj
        }
        cumulativeReliefKg += max(0.0, reliefFlowKgS) * dt
        cumulativeNuclearInputMj += max(0.0, fissionPowerMw) * dt
        cumulativeElectricalOutputMj += max(0.0, generatorNetMw) * dt
        cumulativeCoolingOutputMj += max(0.0, condenserHeatRejectionMw) * dt

        val expectedMass = referenceMassKg - cumulativeReliefKg
        val massResidual = totalWaterMassKg - expectedMass
        val expectedStored = referenceStoredEnergyMj + cumulativeNuclearInputMj -
            cumulativeElectricalOutputMj - cumulativeCoolingOutputMj
        val energyResidual = totalStoredEnergyMj - expectedStored
        val massDrift = if (dt > 0.0) (massResidual - previousMassResidual) / dt else 0.0
        val energyDrift = if (dt > 0.0) (energyResidual - previousEnergyResidual) / dt else 0.0
        previousMassResidual = massResidual
        previousEnergyResidual = energyResidual
        return ConservationSnapshot(massResidual, massDrift, energyResidual, energyDrift)
    }
}
