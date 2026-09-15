package com.nyerahworks.criticalstate.sim

data class PlantState(
    val simulationSeconds: Double = 0.0,

    // Neutronics / reactivity
    val neutronPopulation: Double = 1.0,
    val fissionPowerMw: Double = ReferencePlant.RATED_THERMAL_POWER_MW,
    val decayHeatMw: Double = DecayHeatModel.TOTAL_DELAYED_HEAT_FRACTION * ReferencePlant.RATED_THERMAL_POWER_MW,
    val totalCoreHeatMw: Double = ReferencePlant.RATED_THERMAL_POWER_MW,
    val totalReactivityPcm: Double = 0.0,
    val rodReactivityPcm: Double = 0.0,
    val dopplerReactivityPcm: Double = 0.0,
    val moderatorReactivityPcm: Double = 0.0,
    val boronReactivityPcm: Double = 0.0,
    val xenonReactivityPcm: Double = 0.0,
    val samariumReactivityPcm: Double = 0.0,
    val reactorPeriodSeconds: Double? = null,
    val boronPpm: Double = ReferencePlant.REFERENCE_BORON_PPM,
    val iodineInventory: Double = 1.0,
    val xenonInventory: Double = 1.0,
    val promethiumInventory: Double = 1.0,
    val samariumInventory: Double = 1.0,
    val burnupMwdPerT: Double = 0.0,

    // Core thermal state
    val fuelTemperatureK: Double = 1080.0,
    val fuelPeakTemperatureK: Double = 1200.0,
    val cladTemperatureK: Double = 620.0,
    val cladPeakTemperatureK: Double = 640.0,
    val coolantTemperatureK: Double = 0.5 * (ReferencePlant.COLD_LEG_T_K + ReferencePlant.HOT_LEG_T_K),
    val hotLegTemperatureK: Double = ReferencePlant.HOT_LEG_T_K,
    val coldLegTemperatureK: Double = ReferencePlant.COLD_LEG_T_K,
    val subcoolingMarginK: Double = 20.0,
    // Average core surface flux multiplied by the configured 1.55 peak factor;
    // this is not a resolved local hot-channel quantity and is not DNBR.
    val estimatedPeakFactorHeatFluxMwM2: Double = 0.0,

    // Primary system / RCPs
    val primaryPressureMpa: Double = ReferencePlant.PRIMARY_PRESSURE_MPA,
    val totalPrimaryFlowKgPerS: Double = ReferencePlant.CORE_FLOW_KG_PER_S,
    val loopFlowKgPerS: List<Double> = List(ReferencePlant.LOOP_COUNT) { ReferencePlant.FLOW_PER_LOOP_KG_PER_S },
    val loopPumpRpm: List<Double> = List(ReferencePlant.LOOP_COUNT) { ReferencePlant.RCP_RATED_RPM },
    val loopPressureLossMpa: List<Double> = List(ReferencePlant.LOOP_COUNT) { 0.0 },

    // Pressurizer
    val pressurizerTemperatureK: Double = 617.9,
    val pressurizerLevelFraction: Double = 0.60,
    val pressurizerHeaterFraction: Double = 0.0,
    val pressurizerSprayFraction: Double = 0.0,
    val pressurizerSurgeKgPerS: Double = 0.0,
    val pressurizerSprayKgPerS: Double = 0.0,
    val pressurizerReliefKgPerS: Double = 0.0,

    // Steam generators / main steam
    val steamGeneratorPressureMpa: List<Double> = List(ReferencePlant.LOOP_COUNT) { ReferencePlant.SG_PRESSURE_MPA },
    val steamGeneratorLevelFraction: List<Double> = List(ReferencePlant.LOOP_COUNT) { ReferencePlant.SG_REFERENCE_LEVEL },
    val steamGeneratorSteamFlowKgPerS: List<Double> = List(ReferencePlant.LOOP_COUNT) { 0.0 },
    val steamGeneratorFeedwaterFlowKgPerS: List<Double> = List(ReferencePlant.LOOP_COUNT) { 0.0 },
    val steamGeneratorHeatTransferMw: List<Double> = List(ReferencePlant.LOOP_COUNT) { ReferencePlant.RATED_THERMAL_POWER_MW / ReferencePlant.LOOP_COUNT },
    val mainSteamPressureMpa: Double = ReferencePlant.STEAM_HEADER_PRESSURE_MPA,
    val mainSteamTemperatureK: Double = 550.0,
    val secondaryHeatRemovalMw: Double = ReferencePlant.RATED_THERMAL_POWER_MW,

    // Turbine / generator / condenser / feedwater
    val turbineLoad: Double = 1.0,
    val turbineValvePosition: Double = 0.90,
    val turbineSteamFlowKgPerS: Double = 0.0,
    val turbineRpm: Double = ReferencePlant.SYNCHRONOUS_RPM,
    val generatorGrossPowerMw: Double = ReferencePlant.RATED_GROSS_ELECTRIC_MW,
    val generatorPowerMw: Double = ReferencePlant.RATED_NET_ELECTRIC_MW,
    val generatorReactivePowerMvar: Double = 0.0,
    val generatorBreakerClosed: Boolean = true,
    val condenserPressureKpa: Double = ReferencePlant.CONDENSER_PRESSURE_MPA * 1000.0,
    val condenserLevelFraction: Double = ReferencePlant.CONDENSER_REFERENCE_LIQUID_VOLUME_M3 / ReferencePlant.CONDENSER_VOLUME_M3,
    val condenserHeatRejectionMw: Double = 0.0,
    val feedwaterFlowKgPerS: Double = 0.0,
    val feedwaterTemperatureK: Double = ReferencePlant.FEEDWATER_T_K,
    val auxiliaryPowerMw: Double = ReferencePlant.RATED_GROSS_ELECTRIC_MW - ReferencePlant.RATED_NET_ELECTRIC_MW,

    // Controls / protection
    val rodInsertion: Double = ReferencePlant.REFERENCE_ROD_INSERTION,
    val rodCommandInsertion: Double = ReferencePlant.REFERENCE_ROD_INSERTION,
    val tripped: Boolean = false,
    val tripReasons: List<String> = emptyList(),

    // Verification / long-term state
    val primaryMassResidualKg: Double = 0.0,
    val plantEnergyResidualMw: Double = 0.0,
    val massConservationErrorKg: Double = 0.0,
    val energyConservationErrorMj: Double = 0.0,
    val diagnostic: String? = null,
)
