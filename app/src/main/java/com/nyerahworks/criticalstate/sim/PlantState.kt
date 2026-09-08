package com.nyerahworks.criticalstate.sim

data class PlantState(
    val simulationSeconds: Double = 0.0,
    val neutronPopulation: Double = 1.0,
    val fissionPowerMw: Double = 3411.0,
    val fuelTemperatureK: Double = 1080.0,
    val coolantTemperatureK: Double = 590.0,
    val primaryPressureMpa: Double = 15.51,
    val secondaryHeatRemovalMw: Double = 3411.0,
    val generatorPowerMw: Double = 1115.0,
    val rodInsertion: Double = 0.55,
    val turbineLoad: Double = 1.0,
    val totalReactivityPcm: Double = 0.0,
    val reactorPeriodSeconds: Double? = null,
    val tripped: Boolean = false,
    val diagnostic: String? = null,
)
