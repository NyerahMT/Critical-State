package com.nyerahworks.criticalstate.sim

import kotlin.math.max

internal class DynamicSensor(
    initial: Double,
    private val timeConstantS: Double,
    private val biasFraction: Double = 0.0,
) {
    private var indicated = initial
    private var bias = 0.0

    fun reset(value: Double) {
        indicated = value
        bias = 0.0
    }

    fun advance(trueValue: Double, dt: Double): Double {
        val target = trueValue * (1.0 + biasFraction) + bias
        val alpha = if (timeConstantS <= 0.0) 1.0 else (dt / timeConstantS).coerceIn(0.0, 1.0)
        indicated += (target - indicated) * alpha
        return indicated
    }

    fun value(): Double = indicated
}

internal data class InstrumentSnapshot(
    val reactorPowerFraction: Double,
    val primaryPressureMpa: Double,
    val totalPrimaryFlowKgS: Double,
    val hotLegTemperatureK: Double,
    val coldLegTemperatureK: Double,
    val sgLevelFraction: Double,
    val sgPressureMpa: Double,
    val turbineRpm: Double,
    val generatorMw: Double,
    val condenserPressureKpa: Double,
)

internal class InstrumentationModel {
    private val power = DynamicSensor(1.0, 0.20)
    private val pressure = DynamicSensor(ReferencePlant.PRIMARY_PRESSURE_MPA, 0.50)
    private val flow = DynamicSensor(ReferencePlant.CORE_FLOW_KG_PER_S, 0.35)
    private val hot = DynamicSensor(ReferencePlant.HOT_LEG_T_K, 0.80)
    private val cold = DynamicSensor(ReferencePlant.COLD_LEG_T_K, 0.80)
    private val sgLevel = DynamicSensor(ReferencePlant.SG_REFERENCE_LEVEL, 1.50)
    private val sgPressure = DynamicSensor(ReferencePlant.SG_PRESSURE_MPA, 0.80)
    private val rpm = DynamicSensor(ReferencePlant.SYNCHRONOUS_RPM, 0.15)
    private val gen = DynamicSensor(ReferencePlant.RATED_GROSS_ELECTRIC_MW, 0.35)
    private val condenser = DynamicSensor(ReferencePlant.CONDENSER_PRESSURE_MPA * 1000.0, 0.80)

    fun reset() {
        power.reset(1.0)
        pressure.reset(ReferencePlant.PRIMARY_PRESSURE_MPA)
        flow.reset(ReferencePlant.CORE_FLOW_KG_PER_S)
        hot.reset(ReferencePlant.HOT_LEG_T_K)
        cold.reset(ReferencePlant.COLD_LEG_T_K)
        sgLevel.reset(ReferencePlant.SG_REFERENCE_LEVEL)
        sgPressure.reset(ReferencePlant.SG_PRESSURE_MPA)
        rpm.reset(ReferencePlant.SYNCHRONOUS_RPM)
        gen.reset(ReferencePlant.RATED_GROSS_ELECTRIC_MW)
        condenser.reset(ReferencePlant.CONDENSER_PRESSURE_MPA * 1000.0)
    }

    fun advance(
        reactorPowerFraction: Double,
        primaryPressureMpa: Double,
        totalFlowKgS: Double,
        hotLegK: Double,
        coldLegK: Double,
        averageSgLevel: Double,
        averageSgPressureMpa: Double,
        turbineRpm: Double,
        generatorMw: Double,
        condenserPressureMpa: Double,
        dt: Double,
    ): InstrumentSnapshot = InstrumentSnapshot(
        reactorPowerFraction = power.advance(reactorPowerFraction, dt),
        primaryPressureMpa = pressure.advance(primaryPressureMpa, dt),
        totalPrimaryFlowKgS = flow.advance(totalFlowKgS, dt),
        hotLegTemperatureK = hot.advance(hotLegK, dt),
        coldLegTemperatureK = cold.advance(coldLegK, dt),
        sgLevelFraction = sgLevel.advance(averageSgLevel, dt),
        sgPressureMpa = sgPressure.advance(averageSgPressureMpa, dt),
        turbineRpm = rpm.advance(turbineRpm, dt),
        generatorMw = gen.advance(generatorMw, dt),
        condenserPressureKpa = condenser.advance(condenserPressureMpa * 1000.0, dt),
    )
}

internal data class ProtectionSnapshot(
    val reactorTrip: Boolean,
    val tripReasons: List<String>,
    val overpowerVote: Boolean,
    val pressureVote: Boolean,
    val lowFlowVote: Boolean,
    val temperatureVote: Boolean,
    val lowSgLevelVote: Boolean,
)

private class PersistentTrip(private val delayS: Double) {
    private var timer = 0.0
    fun update(active: Boolean, dt: Double): Boolean {
        timer = if (active) timer + dt else 0.0
        return timer >= delayS
    }
    fun reset() { timer = 0.0 }
}

/** Generic fictional 2-out-of-3 protection voting over dedicated sensor channels. */
internal class ProtectionSystem {
    private val powerChannels = listOf(
        DynamicSensor(1.0, 0.08, -0.002),
        DynamicSensor(1.0, 0.10, 0.0),
        DynamicSensor(1.0, 0.12, 0.002),
    )
    private val pressureChannels = listOf(
        DynamicSensor(ReferencePlant.PRIMARY_PRESSURE_MPA, 0.25, -0.0015),
        DynamicSensor(ReferencePlant.PRIMARY_PRESSURE_MPA, 0.30, 0.0),
        DynamicSensor(ReferencePlant.PRIMARY_PRESSURE_MPA, 0.35, 0.0015),
    )
    private val flowChannels = listOf(
        DynamicSensor(1.0, 0.20, -0.003),
        DynamicSensor(1.0, 0.25, 0.0),
        DynamicSensor(1.0, 0.30, 0.003),
    )

    private val overpowerTimer = PersistentTrip(0.18)
    private val pressureTimer = PersistentTrip(0.35)
    private val flowTimer = PersistentTrip(0.50)
    private val tempTimer = PersistentTrip(0.40)
    private val levelTimer = PersistentTrip(1.0)
    private val fluxRateTimer = PersistentTrip(0.12)

    private var latched = false
    private val reasons = linkedSetOf<String>()
    private var previousPower = 1.0

    fun reset() {
        latched = false
        reasons.clear()
        previousPower = 1.0
        powerChannels.forEach { it.reset(1.0) }
        pressureChannels.forEach { it.reset(ReferencePlant.PRIMARY_PRESSURE_MPA) }
        flowChannels.forEach { it.reset(1.0) }
        overpowerTimer.reset()
        pressureTimer.reset()
        flowTimer.reset()
        tempTimer.reset()
        levelTimer.reset()
        fluxRateTimer.reset()
    }

    fun manualTrip() {
        latched = true
        reasons += "MANUAL"
    }

    fun advance(
        truePowerFraction: Double,
        truePressureMpa: Double,
        trueFlowFraction: Double,
        hotLegTemperatureK: Double,
        minimumSgLevel: Double,
        turbineTrip: Boolean,
        dt: Double,
    ): ProtectionSnapshot {
        val p = powerChannels.map { it.advance(truePowerFraction, dt) }
        val press = pressureChannels.map { it.advance(truePressureMpa, dt) }
        val flow = flowChannels.map { it.advance(trueFlowFraction, dt) }

        val overpowerVote = vote(p.map { it > 1.18 })
        val pressureVote = vote(press.map { it > 16.6 || it < 14.2 })
        val lowFlowVote = truePowerFraction > 0.25 && vote(flow.map { it < 0.82 })
        val temperatureVote = hotLegTemperatureK > 615.0
        val lowLevelVote = truePowerFraction > 0.20 && minimumSgLevel < 0.20
        val fluxRate = (p.average() - previousPower) / max(dt, 1.0e-6)
        previousPower = p.average()
        val fluxRateVote = truePowerFraction > 0.05 && fluxRate > 0.10

        if (overpowerTimer.update(overpowerVote, dt)) trip("OVERPOWER")
        if (pressureTimer.update(pressureVote, dt)) trip("RCS PRESSURE")
        if (flowTimer.update(lowFlowVote, dt)) trip("LOW RCS FLOW")
        if (tempTimer.update(temperatureVote, dt)) trip("HIGH RCS TEMPERATURE")
        if (levelTimer.update(lowLevelVote, dt)) trip("LOW STEAM GENERATOR LEVEL")
        if (fluxRateTimer.update(fluxRateVote, dt)) trip("HIGH FLUX RATE")
        if (turbineTrip && truePowerFraction > 0.35) trip("TURBINE TRIP")

        return ProtectionSnapshot(
            reactorTrip = latched,
            tripReasons = reasons.toList(),
            overpowerVote = overpowerVote,
            pressureVote = pressureVote,
            lowFlowVote = lowFlowVote,
            temperatureVote = temperatureVote,
            lowSgLevelVote = lowLevelVote,
        )
    }

    fun snapshot() = ProtectionSnapshot(
        reactorTrip = latched,
        tripReasons = reasons.toList(),
        overpowerVote = false,
        pressureVote = false,
        lowFlowVote = false,
        temperatureVote = false,
        lowSgLevelVote = false,
    )

    private fun vote(channels: List<Boolean>): Boolean = channels.count { it } >= 2

    private fun trip(reason: String) {
        latched = true
        reasons += reason
    }
}
