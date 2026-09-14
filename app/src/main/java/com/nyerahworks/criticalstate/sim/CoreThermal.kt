package com.nyerahworks.criticalstate.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

internal data class CoreThermalSnapshot(
    val fuelAverageK: Double,
    val fuelPeakK: Double,
    val cladAverageK: Double,
    val cladPeakK: Double,
    val coolantAverageK: Double,
    val coolantOutletK: Double,
    val coolantOutletEnthalpyKjKg: Double,
    val moderatorDensityKgM3: Double,
    val subcoolingMarginK: Double,
    val hotChannelHeatFluxMwM2: Double,
    val storedEnergyMj: Double,
    val coolantMassKg: Double,
    val diagnostic: String? = null,
)

private data class CoreNode(
    var fuelCenterK: Double,
    var fuelMidK: Double,
    var fuelSurfaceK: Double,
    var cladK: Double,
    var coolantEnthalpyKjKg: Double,
)

/**
 * Six axial channels with three radial fuel nodes, one clad node and one
 * flowing coolant energy state per axial slice.
 */
internal class CoreThermalModel(
    private val water: WaterProperties,
) {
    private val axialFractions: DoubleArray
    private val nodes: Array<CoreNode>

    private val centerCapMjK = ReferencePlant.TOTAL_FUEL_HEAT_CAPACITY_MJ_PER_K /
        ReferencePlant.CORE_AXIAL_NODES * (1.0 / 9.0)
    private val midCapMjK = ReferencePlant.TOTAL_FUEL_HEAT_CAPACITY_MJ_PER_K /
        ReferencePlant.CORE_AXIAL_NODES * (3.0 / 9.0)
    private val surfaceCapMjK = ReferencePlant.TOTAL_FUEL_HEAT_CAPACITY_MJ_PER_K /
        ReferencePlant.CORE_AXIAL_NODES * (5.0 / 9.0)
    private val cladCapMjK = ReferencePlant.TOTAL_CLAD_HEAT_CAPACITY_MJ_PER_K /
        ReferencePlant.CORE_AXIAL_NODES

    private val gCenterMid = ReferencePlant.FUEL_CENTER_MID_CONDUCTANCE_MW_PER_K /
        ReferencePlant.CORE_AXIAL_NODES
    private val gMidSurface = ReferencePlant.FUEL_MID_SURFACE_CONDUCTANCE_MW_PER_K /
        ReferencePlant.CORE_AXIAL_NODES
    private val gGap = ReferencePlant.FUEL_GAP_CONDUCTANCE_MW_PER_K /
        ReferencePlant.CORE_AXIAL_NODES

    init {
        val raw = DoubleArray(ReferencePlant.CORE_AXIAL_NODES) { i ->
            sin(PI * (i + 0.5) / ReferencePlant.CORE_AXIAL_NODES)
        }
        val sum = raw.sum()
        axialFractions = DoubleArray(raw.size) { i -> raw[i] / sum }
        nodes = initializeNodes()
    }

    private fun initializeNodes(): Array<CoreNode> {
        val p = ReferencePlant.PRIMARY_PRESSURE_MPA
        var inletH = water.statePT(p, ReferencePlant.COLD_LEG_T_K).enthalpyKjKg
        return Array(ReferencePlant.CORE_AXIAL_NODES) { i ->
            val q = ReferencePlant.RATED_THERMAL_POWER_MW * axialFractions[i]
            val outletH = inletH + q * 1000.0 / ReferencePlant.CORE_FLOW_KG_PER_S
            val coolant = water.statePH(p, outletH)
            val gConv = convectiveConductanceMwK(coolant, ReferencePlant.CORE_FLOW_KG_PER_S)
            val clad = coolant.temperatureK + q / max(gConv, 1.0e-6)
            val surface = clad + q / gGap
            val mid = surface + (4.0 / 9.0) * q / gMidSurface
            val center = mid + (1.0 / 9.0) * q / gCenterMid
            inletH = outletH
            CoreNode(center, mid, surface, clad, outletH)
        }
    }

    fun reset() {
        val fresh = initializeNodes()
        for (i in nodes.indices) nodes[i] = fresh[i]
    }

    fun advance(
        totalCoreHeatMw: Double,
        inletEnthalpyKjKg: Double,
        totalMassFlowKgS: Double,
        primaryPressureMpa: Double,
        dt: Double,
    ): CoreThermalSnapshot {
        var hIn = inletEnthalpyKjKg
        val flow = max(totalMassFlowKgS, 1.0)
        var diagnostic: String? = null

        for (i in nodes.indices) {
            val node = nodes[i]
            val qNode = max(0.0, totalCoreHeatMw) * axialFractions[i]
            val coolant = runCatching { water.statePH(primaryPressureMpa, node.coolantEnthalpyKjKg) }
                .getOrElse {
                    diagnostic = "Core coolant property solve failed"
                    water.statePT(primaryPressureMpa, ReferencePlant.COLD_LEG_T_K)
                }
            val gConv = convectiveConductanceMwK(coolant, flow)

            val q12 = gCenterMid * (node.fuelCenterK - node.fuelMidK)
            val q23 = gMidSurface * (node.fuelMidK - node.fuelSurfaceK)
            val qGap = gGap * (node.fuelSurfaceK - node.cladK)
            val qConv = gConv * (node.cladK - coolant.temperatureK)

            node.fuelCenterK += (qNode / 9.0 - q12) / centerCapMjK * dt
            node.fuelMidK += (qNode * 3.0 / 9.0 + q12 - q23) / midCapMjK * dt
            node.fuelSurfaceK += (qNode * 5.0 / 9.0 + q23 - qGap) / surfaceCapMjK * dt
            node.cladK += (qGap - qConv) / cladCapMjK * dt

            val coolantVolume = ReferencePlant.CORE_COOLANT_VOLUME_M3 /
                ReferencePlant.CORE_AXIAL_NODES
            val coolantMass = max(1.0, coolant.densityKgM3 * coolantVolume)
            val dhdt = (flow * (hIn - node.coolantEnthalpyKjKg) + qConv * 1000.0) /
                coolantMass
            node.coolantEnthalpyKjKg += dhdt * dt
            hIn = node.coolantEnthalpyKjKg

            if (!node.fuelCenterK.isFinite() || !node.coolantEnthalpyKjKg.isFinite()) {
                diagnostic = "Core thermal state invalid"
            }
        }
        return snapshot(primaryPressureMpa, flow, diagnostic)
    }

    fun snapshot(
        primaryPressureMpa: Double = ReferencePlant.PRIMARY_PRESSURE_MPA,
        totalMassFlowKgS: Double = ReferencePlant.CORE_FLOW_KG_PER_S,
        diagnostic: String? = null,
    ): CoreThermalSnapshot {
        var fuelWeighted = 0.0
        var fuelPeak = 0.0
        var cladSum = 0.0
        var cladPeak = 0.0
        var coolantTempWeighted = 0.0
        var densityWeighted = 0.0
        var coolantMass = 0.0
        var stored = 0.0
        var outletT = ReferencePlant.HOT_LEG_T_K
        var outletH = nodes.last().coolantEnthalpyKjKg
        var minSubcool = Double.POSITIVE_INFINITY

        nodes.forEachIndexed { _, node ->
            val s = water.statePH(primaryPressureMpa, node.coolantEnthalpyKjKg)
            val nodeFuelAverage = (node.fuelCenterK + 3.0 * node.fuelMidK + 5.0 * node.fuelSurfaceK) / 9.0
            fuelWeighted += nodeFuelAverage
            fuelPeak = max(fuelPeak, node.fuelCenterK)
            cladSum += node.cladK
            cladPeak = max(cladPeak, node.cladK)
            coolantTempWeighted += s.temperatureK
            densityWeighted += s.densityKgM3
            val volume = ReferencePlant.CORE_COOLANT_VOLUME_M3 / ReferencePlant.CORE_AXIAL_NODES
            val mass = s.densityKgM3 * volume
            coolantMass += mass
            stored += centerCapMjK * node.fuelCenterK + midCapMjK * node.fuelMidK +
                surfaceCapMjK * node.fuelSurfaceK + cladCapMjK * node.cladK
            stored += mass * s.internalEnergyKjKg / 1000.0
            val tsat = water.saturationP(primaryPressureMpa).temperatureK
            minSubcool = minOf(minSubcool, tsat - s.temperatureK)
        }
        val outletState = water.statePH(primaryPressureMpa, outletH)
        outletT = outletState.temperatureK

        // Average surface heat flux times a benchmark/calibration-required peaking factor.
        val totalArea = ReferencePlant.CORE_EFFECTIVE_HEAT_AREA_M2
        val hotFlux = ReferencePlant.RATED_THERMAL_POWER_MW / totalArea * 1.55

        return CoreThermalSnapshot(
            fuelAverageK = fuelWeighted / nodes.size,
            fuelPeakK = fuelPeak,
            cladAverageK = cladSum / nodes.size,
            cladPeakK = cladPeak,
            coolantAverageK = coolantTempWeighted / nodes.size,
            coolantOutletK = outletT,
            coolantOutletEnthalpyKjKg = outletH,
            moderatorDensityKgM3 = densityWeighted / nodes.size,
            subcoolingMarginK = minSubcool,
            hotChannelHeatFluxMwM2 = hotFlux,
            storedEnergyMj = stored,
            coolantMassKg = coolantMass,
            diagnostic = diagnostic,
        )
    }

    private fun convectiveConductanceMwK(state: WaterState, massFlowKgS: Double): Double {
        val area = ReferencePlant.CORE_FLOW_AREA_M2
        val velocity = massFlowKgS / max(state.densityKgM3 * area, 1.0e-6)
        val re = state.densityKgM3 * abs(velocity) * ReferencePlant.CORE_HYDRAULIC_DIAMETER_M /
            max(state.viscosityPaS, 1.0e-9)
        val pr = state.cpKjKgK * 1000.0 * state.viscosityPaS /
            max(state.conductivityWmK, 1.0e-9)
        val nu = if (re < 2300.0) {
            3.66
        } else {
            val f = (0.79 * ln(max(re, 3000.0)) - 1.64).pow(-2.0)
            val numerator = (f / 8.0) * (re - 1000.0) * pr
            val denominator = 1.0 + 12.7 * kotlin.math.sqrt(f / 8.0) *
                (pr.pow(2.0 / 3.0) - 1.0)
            max(3.66, numerator / max(denominator, 0.1))
        }
        val hWm2K = nu * state.conductivityWmK / ReferencePlant.CORE_HYDRAULIC_DIAMETER_M
        val nodeArea = ReferencePlant.CORE_EFFECTIVE_HEAT_AREA_M2 / ReferencePlant.CORE_AXIAL_NODES
        return max(0.05, hWm2K * nodeArea / 1.0e6)
    }
}
