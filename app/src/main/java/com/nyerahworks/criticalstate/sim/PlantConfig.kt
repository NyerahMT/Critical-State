package com.nyerahworks.criticalstate.sim

/**
 * Data-driven definition for Critical State's generic four-loop reference PWR.
 *
 * Values sourced directly from public benchmarks are marked BENCHMARK_DERIVED.
 * Values that define reduced component geometry or control tuning remain
 * CALIBRATION_REQUIRED until checked against a public reference transient.
 */
internal enum class ModelStatus {
    REFERENCE_DEFINED,
    BENCHMARK_DERIVED,
    CALIBRATION_REQUIRED,
    ESTIMATED,
    DEFERRED,
}

internal data class ParameterProvenance(
    val value: Double,
    val unit: String,
    val status: ModelStatus,
    val source: String,
    val notes: String = "",
)

internal object ReferencePlant {
    // MIT BEAVRS benchmark scale.
    const val LOOP_COUNT = 4
    const val RATED_THERMAL_POWER_MW = 3411.0
    const val PRIMARY_PRESSURE_MPA = 15.51
    const val CORE_FLOW_KG_PER_S = 61.5e6 / 3600.0
    const val FLOW_PER_LOOP_KG_PER_S = CORE_FLOW_KG_PER_S / LOOP_COUNT
    const val FUEL_ASSEMBLIES = 193
    const val FUEL_RODS_PER_ASSEMBLY = 264
    const val ACTIVE_FUEL_LENGTH_M = 3.6576

    // Reference operating point completed with compatible public PWR data.
    const val COLD_LEG_T_K = 565.15
    const val HOT_LEG_T_K = 598.15
    const val SG_PRESSURE_MPA = 6.20
    const val STEAM_HEADER_PRESSURE_MPA = 6.00
    const val CONDENSER_PRESSURE_MPA = 0.0080
    const val FEEDWATER_T_K = 493.15
    const val COOLING_WATER_INLET_K = 291.15
    const val SYNCHRONOUS_RPM = 1800.0
    const val GRID_HZ = 60.0
    const val RATED_GROSS_ELECTRIC_MW = 1150.0
    const val RATED_NET_ELECTRIC_MW = 1115.0

    // Core / RCS reduced-order geometry.
    const val CORE_AXIAL_NODES = 6
    const val CORE_COOLANT_VOLUME_M3 = 15.30
    const val CORE_FLOW_AREA_M2 = 4.65
    const val CORE_HYDRAULIC_DIAMETER_M = 0.012
    const val CORE_EFFECTIVE_HEAT_AREA_M2 = 2800.0
    const val LOOP_LIQUID_VOLUME_M3 = 70.0
    const val LOOP_EQUIVALENT_LENGTH_M = 70.0
    const val LOOP_EQUIVALENT_DIAMETER_M = 0.90
    const val LOOP_ROUGHNESS_M = 4.5e-5
    const val LOOP_EFFECTIVE_ELEVATION_M = 12.0

    // RCP reference model.
    const val RCP_RATED_RPM = 1188.0
    const val RCP_SHUTOFF_HEAD_M = 140.0
    const val RCP_RATED_HEAD_M = 105.0
    const val RCP_EFFICIENCY = 0.86
    const val RCP_ROTOR_INERTIA_KG_M2 = 7200.0

    // Pressurizer reduced equilibrium vessel.
    const val PZR_VOLUME_M3 = 51.0
    const val PZR_REFERENCE_LIQUID_VOLUME_M3 = 30.6
    const val PZR_MAX_HEATER_MW = 1.872
    const val PZR_MAX_SPRAY_KG_S = 39.2

    // Steam generator reduced segmented model.
    const val SG_PRIMARY_VOLUME_M3 = 20.0
    const val SG_SECONDARY_VOLUME_M3 = 100.0
    const val SG_REFERENCE_LIQUID_FRACTION = 0.65
    const val SG_SEGMENTS = 4
    const val SG_WALL_HEAT_CAPACITY_MJ_PER_K = 420.0
    const val SG_REFERENCE_LEVEL = 0.65

    // Main steam / condenser volumes.
    const val STEAM_HEADER_VOLUME_M3 = 150.0
    const val CONDENSER_VOLUME_M3 = 4000.0
    const val CONDENSER_REFERENCE_LIQUID_VOLUME_M3 = 120.0
    const val CONDENSER_COOLING_FLOW_KG_S = 55_000.0

    // Fuel thermal inventory. Public PWR-scale mass/cp data converted to SI.
    const val TOTAL_FUEL_HEAT_CAPACITY_MJ_PER_K = 25.0
    const val TOTAL_CLAD_HEAT_CAPACITY_MJ_PER_K = 8.0
    const val FUEL_CENTER_MID_CONDUCTANCE_MW_PER_K = 24.0
    const val FUEL_MID_SURFACE_CONDUCTANCE_MW_PER_K = 18.0
    const val FUEL_GAP_CONDUCTANCE_MW_PER_K = 12.0

    // Neutronics / chemistry calibration state.
    const val REFERENCE_ROD_INSERTION = 0.55
    const val TOTAL_CONTROL_BANK_WORTH = 0.0040
    const val PROMPT_GENERATION_TIME_S = 2.0e-5
    const val DOPPLER_COEFF_PER_K = -1.4e-5
    const val MODERATOR_DENSITY_COEFF_PER_KG_M3 = 2.0e-5
    const val REFERENCE_BORON_PPM = 900.0
    const val BORON_WORTH_PER_PPM = -7.0e-5
    const val HEAVY_METAL_MASS_TONNES = 100.0

    // Numerical hierarchy. ReactorSimulator still performs two coupled half
    // steps for each plant step, so the effective accelerated coupling step is
    // at most 0.125 s. The normal 1x runtime publishes 0.10 s batches and is
    // therefore unchanged at 0.05 s coupled half-steps.
    const val MAX_THERMAL_HYDRAULIC_STEP_S = 0.25
    const val MIN_NEUTRON_POPULATION = 1.0e-15

    val provenance: Map<String, ParameterProvenance> = mapOf(
        "core.thermal_power" to ParameterProvenance(
            RATED_THERMAL_POWER_MW,
            "MWth",
            ModelStatus.BENCHMARK_DERIVED,
            "MIT BEAVRS",
        ),
        "primary.pressure" to ParameterProvenance(
            PRIMARY_PRESSURE_MPA,
            "MPa",
            ModelStatus.BENCHMARK_DERIVED,
            "MIT BEAVRS",
        ),
        "primary.core_flow" to ParameterProvenance(
            CORE_FLOW_KG_PER_S,
            "kg/s",
            ModelStatus.BENCHMARK_DERIVED,
            "MIT BEAVRS",
        ),
        "primary.loop_geometry" to ParameterProvenance(
            LOOP_LIQUID_VOLUME_M3,
            "m3/loop",
            ModelStatus.CALIBRATION_REQUIRED,
            "Reduced conservation model",
            "Equivalent geometry chosen to reproduce a four-loop PWR-scale operating point.",
        ),
        "pressurizer.volume" to ParameterProvenance(
            PZR_VOLUME_M3,
            "m3",
            ModelStatus.CALIBRATION_REQUIRED,
            "Public four-loop PWR-scale design data",
        ),
        "steam_generator.secondary_volume" to ParameterProvenance(
            SG_SECONDARY_VOLUME_M3,
            "m3/SG",
            ModelStatus.CALIBRATION_REQUIRED,
            "Reduced segmented SG model",
        ),
        "condenser.volume" to ParameterProvenance(
            CONDENSER_VOLUME_M3,
            "m3",
            ModelStatus.ESTIMATED,
            "Reduced condenser control volume",
        ),
    )
}
