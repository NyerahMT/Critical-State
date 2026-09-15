package com.nyerahworks.criticalstate.sim

import kotlin.math.max

/**
 * Coupled reduced-order four-loop PWR plant.
 *
 * This class is only the orchestrator. Governing equations live in the subsystem
 * models; UI commands move actuators and never write thermodynamic state.
 */
class ReactorSimulator {
    companion object {
        const val REFERENCE_THERMAL_POWER_MW = ReferencePlant.RATED_THERMAL_POWER_MW
        const val REFERENCE_PRIMARY_PRESSURE_MPA = ReferencePlant.PRIMARY_PRESSURE_MPA
        private const val MAX_PLANT_STEP_S = ReferencePlant.MAX_THERMAL_HYDRAULIC_STEP_S
        private const val MAX_KINETICS_STEP_S = 0.01
    }

    private val water: WaterProperties = If97WaterProperties()
    private val kinetics = PointKineticsModel()
    private val rods = RodDriveModel()
    private val poison = PoisonModel()
    private val decayHeat = DecayHeatModel()
    private val core = CoreThermalModel(water)
    private val loops = List(ReferencePlant.LOOP_COUNT) { PrimaryLoopModel(it, water) }
    private val steamGenerators = List(ReferencePlant.LOOP_COUNT) { SteamGeneratorModel(it, water) }

    private val referenceCoreSnapshot = core.snapshot()
    private val referenceFuelTemperatureK = referenceCoreSnapshot.fuelAverageK
    private val referenceModeratorDensityKgM3 = referenceCoreSnapshot.moderatorDensityKgM3
    private val referencePrimaryLiquidMassKg = calculatePrimaryLiquidMass(ReferencePlant.PRIMARY_PRESSURE_MPA)

    private val pressurizer = PressurizerModel(water, referencePrimaryLiquidMassKg)
    private val chemistry = ChemicalShimModel(
        referenceRcsMassKg = referencePrimaryLiquidMassKg + pressurizer.snapshot().totalMassKg,
    )
    private val steamHeader = MainSteamHeaderModel(water)
    private val condenser = CondenserModel(water)
    private val feedwater = FeedwaterTrainModel(water)
    private val turbine = TurbineGeneratorModel(water)
    private val instrumentation = InstrumentationModel()
    private val protection = ProtectionSystem()
    private val componentCondition = ComponentConditionModel()
    private val economics = EconomicsModel()
    private val audit = ConservationAudit()

    private var simulationSeconds = 0.0
    private var turbineLoadCommand = 1.0
    private var burnupMwdPerT = 0.0
    private var lastInstrument = InstrumentSnapshot(
        reactorPowerFraction = 1.0,
        primaryPressureMpa = ReferencePlant.PRIMARY_PRESSURE_MPA,
        totalPrimaryFlowKgS = ReferencePlant.CORE_FLOW_KG_PER_S,
        hotLegTemperatureK = ReferencePlant.HOT_LEG_T_K,
        coldLegTemperatureK = ReferencePlant.COLD_LEG_T_K,
        sgLevelFraction = ReferencePlant.SG_REFERENCE_LEVEL,
        sgPressureMpa = ReferencePlant.SG_PRESSURE_MPA,
        turbineRpm = ReferencePlant.SYNCHRONOUS_RPM,
        generatorMw = ReferencePlant.RATED_GROSS_ELECTRIC_MW,
        condenserPressureKpa = ReferencePlant.CONDENSER_PRESSURE_MPA * 1000.0,
    )
    private var lastProtection = protection.snapshot()
    private var lastAudit = ConservationSnapshot(0.0, 0.0, 0.0, 0.0)
    private var state = composeState()

    fun snapshot(): PlantState = state

    fun setRodInsertion(fraction: Double) {
        if (!lastProtection.reactorTrip) rods.commandInsertion(fraction)
    }

    fun setTurbineLoad(fraction: Double) {
        turbineLoadCommand = fraction.coerceIn(0.30, 1.10)
        turbine.setLoadCommand(turbineLoadCommand)
    }

    fun setReactorCoolantPump(loopIndex: Int, running: Boolean) {
        loops.getOrNull(loopIndex)?.setPumpRunning(running)
    }

    fun setAllReactorCoolantPumps(running: Boolean) {
        loops.forEach { it.setPumpRunning(running) }
    }

    fun setBoronMakeup(flowKgS: Double, boronPpm: Double) {
        chemistry.setMakeup(flowKgS, boronPpm)
    }

    fun setGeneratorBreakerClosed(closed: Boolean): Boolean = turbine.setBreakerClosed(closed)

    fun tripTurbine() {
        turbine.trip()
    }

    fun resetTurbineTrip() {
        turbine.resetTrip()
    }

    fun performMaintenance(component: String, recovery: Double) {
        componentCondition.restore(component, recovery)
    }

    fun trip() {
        protection.manualTrip()
        rods.scram()
        lastProtection = protection.snapshot()
        state = composeState()
    }

    fun reset() {
        simulationSeconds = 0.0
        turbineLoadCommand = 1.0
        burnupMwdPerT = 0.0
        kinetics.reset()
        rods.reset()
        poison.reset()
        decayHeat.reset()
        core.reset()
        loops.forEach { it.reset() }
        steamGenerators.forEach { it.reset() }
        pressurizer.reset()
        chemistry.reset()
        steamHeader.reset()
        condenser.reset()
        feedwater.reset()
        turbine.reset()
        instrumentation.reset()
        protection.reset()
        componentCondition.reset()
        economics.reset()
        audit.reset()
        lastProtection = protection.snapshot()
        lastAudit = ConservationSnapshot(0.0, 0.0, 0.0, 0.0)
        lastInstrument = InstrumentSnapshot(
            1.0,
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            ReferencePlant.CORE_FLOW_KG_PER_S,
            ReferencePlant.HOT_LEG_T_K,
            ReferencePlant.COLD_LEG_T_K,
            ReferencePlant.SG_REFERENCE_LEVEL,
            ReferencePlant.SG_PRESSURE_MPA,
            ReferencePlant.SYNCHRONOUS_RPM,
            ReferencePlant.RATED_GROSS_ELECTRIC_MW,
            ReferencePlant.CONDENSER_PRESSURE_MPA * 1000.0,
        )
        state = composeState()
    }

    fun advance(wallSeconds: Double, timeScale: Double): PlantState {
        var remaining = max(0.0, wallSeconds) * max(0.0, timeScale)
        while (remaining > 1.0e-12) {
            val dt = minOf(MAX_PLANT_STEP_S, remaining)
            // Two coupled half-steps reduce one-pass feedback lag without requiring
            // a monolithic nonlinear plant solve.
            advanceCoupled(dt * 0.5)
            advanceCoupled(dt * 0.5)
            remaining -= dt
        }
        state = composeState()
        return state
    }

    private fun advanceCoupled(dt: Double) {
        if (dt <= 0.0) return
        val pzrBefore = pressurizer.snapshot()
        val pressure = pzrBefore.pressureMpa
        val conditionBefore = componentCondition.snapshot()

        val rod = rods.advance(dt)
        val coreBefore = core.snapshot(pressure)
        val chemistryBefore = chemistry.snapshot()
        val poisonBefore = poison.snapshot()

        val rhoRod = rods.reactivity()
        val rhoDoppler = ReferencePlant.DOPPLER_COEFF_PER_K *
            (coreBefore.fuelAverageK - referenceFuelTemperatureK)
        val rhoModerator = ReferencePlant.MODERATOR_DENSITY_COEFF_PER_KG_M3 *
            (coreBefore.moderatorDensityKgM3 - referenceModeratorDensityKgM3)
        val totalRho = rhoRod + rhoDoppler + chemistryBefore.boronReactivity +
            poisonBefore.xenonReactivity + poisonBefore.samariumReactivity

        var kineticsRemaining = dt
        var kineticsSnapshot = kinetics.snapshot()
        while (kineticsRemaining > 1.0e-12) {
            val kdt = minOf(MAX_KINETICS_STEP_S, kineticsRemaining)
            kineticsSnapshot = kinetics.advance(totalRho, kdt)
            kineticsRemaining -= kdt
        }
        val fissionPowerMw = kineticsSnapshot.neutronPopulation * ReferencePlant.RATED_THERMAL_POWER_MW
        val decay = decayHeat.advance(fissionPowerMw, dt)
        val totalCoreHeatMw = DecayHeatModel.PROMPT_DEPOSIT_FRACTION * fissionPowerMw + decay.powerMw

        val loopBefore = loops.mapIndexed { i, loop ->
            loop.snapshot(pressure, conditionBefore.rcp[i])
        }
        val totalFlow = loopBefore.sumOf { max(0.0, it.massFlowKgS) }
        val coldInletH = weightedEnthalpy(
            loopBefore.map { max(0.0, it.massFlowKgS) to it.coldLegEnthalpyKjKg },
            water.statePT(pressure, ReferencePlant.COLD_LEG_T_K).enthalpyKjKg,
        )
        val coreNow = core.advance(
            totalCoreHeatMw = totalCoreHeatMw,
            inletEnthalpyKjKg = coldInletH,
            totalMassFlowKgS = totalFlow,
            primaryPressureMpa = pressure,
            dt = dt,
        )

        val sgBefore = steamGenerators.map { it.snapshot(pressure) }
        loops.forEachIndexed { i, loop ->
            loop.advanceTransport(
                coreOutletEnthalpyKjKg = coreNow.coolantOutletEnthalpyKjKg,
                sgOutletEnthalpyKjKg = sgBefore[i].primaryOutletEnthalpyKjKg,
                pressureMpa = pressure,
                dt = dt,
            )
        }
        val transportedLoops = loops.mapIndexed { i, loop ->
            loop.snapshot(pressure, conditionBefore.rcp[i])
        }

        val headerBefore = steamHeader.snapshot()
        val feedwaterBefore = feedwater.snapshot()
        val feedwaterFlows = feedwaterBefore.perSgFlowKgS
        val sgNow = steamGenerators.mapIndexed { i, sg ->
            sg.advance(
                primaryInletEnthalpyKjKg = transportedLoops[i].hotLegEnthalpyKjKg,
                primaryMassFlowKgS = max(0.0, transportedLoops[i].massFlowKgS),
                primaryPressureMpa = pressure,
                headerPressureMpa = headerBefore.pressureMpa,
                feedwaterFlowKgS = feedwaterFlows.getOrElse(i) { 0.0 },
                feedwaterEnthalpyKjKg = feedwaterBefore.enthalpyKjKg,
                heatExchangerCondition = conditionBefore.steamGenerator[i],
                dt = dt,
            )
        }

        val loopNow = loops.mapIndexed { i, loop ->
            loop.advanceHydraulics(
                pressureMpa = pressure,
                condition = conditionBefore.rcp[i],
                dt = dt,
            )
        }

        val primaryLiquidMass = coreNow.coolantMassKg +
            loopNow.sumOf { it.liquidMassKg } + sgNow.sumOf { it.primaryMassKg }
        val surgeH = weightedEnthalpy(
            loopNow.map { max(0.0, it.massFlowKgS) to it.hotLegEnthalpyKjKg },
            coreNow.coolantOutletEnthalpyKjKg,
        )
        val sprayH = weightedEnthalpy(
            loopNow.map { max(0.0, it.massFlowKgS) to it.coldLegEnthalpyKjKg },
            coldInletH,
        )
        val pzrNow = pressurizer.advance(
            primaryLiquidMassKg = primaryLiquidMass,
            surgeSourceEnthalpyKjKg = surgeH,
            spraySourceEnthalpyKjKg = sprayH,
            dt = dt,
        )

        val condenserBefore = condenser.snapshot()
        val auxiliaryBefore = loopNow.sumOf { it.pump.shaftPowerMw } +
            feedwaterBefore.auxiliaryPowerMw
        val turbineNow = turbine.advance(
            header = headerBefore,
            condenser = condenserBefore,
            totalFeedwaterFlowKgS = feedwaterBefore.totalFlowKgS,
            auxiliaryPowerMw = auxiliaryBefore,
            turbineCondition = conditionBefore.turbine,
            dt = dt,
        )
        val headerNow = steamHeader.advance(
            inflows = sgNow.map { it.steamFlowKgS to steamGenerators[it.index].steamOutletEnthalpyKjKg() },
            turbineFlowKgS = turbineNow.steamFlowKgS,
            dt = dt,
        )
        val condenserNow = condenser.advance(
            turbineExhaustFlowKgS = turbineNow.exhaustFlowKgS,
            turbineExhaustEnthalpyKjKg = turbineNow.exhaustEnthalpyKjKg,
            condensateOutflowKgS = feedwaterBefore.condensateRequiredKgS,
            condenserCondition = conditionBefore.condenser,
            dt = dt,
        )

        turbine.setLoadCommand(turbineLoadCommand)
        feedwater.setExtraction(turbineNow.extractionFlowKgS, turbineNow.extractionEnthalpyKjKg)
        val feedwaterNow = feedwater.advance(
            sgDemandsKgS = sgNow.map { it.feedwaterDemandKgS },
            sgPressuresMpa = sgNow.map { it.pressureMpa },
            condenserPressureMpa = condenserNow.pressureMpa,
            condenserLiquidEnthalpyKjKg = condenserNow.liquidEnthalpyKjKg,
            pumpCondition = conditionBefore.feedwaterPump,
            dt = dt,
        )

        val poisonNow = poison.advance(
            powerFraction = fissionPowerMw / ReferencePlant.RATED_THERMAL_POWER_MW,
            dt = dt,
        )
        val chemistryNow = chemistry.advance(dt)

        val hotLegAverage = loopNow.map { it.hotLegTemperatureK }.average()
        val coldLegAverage = loopNow.map { it.coldLegTemperatureK }.average()
        val avgSgLevel = sgNow.map { it.levelFraction }.average()
        val avgSgPressure = sgNow.map { it.pressureMpa }.average()
        val totalPrimaryFlow = loopNow.sumOf { max(0.0, it.massFlowKgS) }

        lastInstrument = instrumentation.advance(
            reactorPowerFraction = fissionPowerMw / ReferencePlant.RATED_THERMAL_POWER_MW,
            primaryPressureMpa = pzrNow.pressureMpa,
            totalFlowKgS = totalPrimaryFlow,
            hotLegK = hotLegAverage,
            coldLegK = coldLegAverage,
            averageSgLevel = avgSgLevel,
            averageSgPressureMpa = avgSgPressure,
            turbineRpm = turbineNow.rpm,
            generatorMw = turbineNow.generatorGrossMw,
            condenserPressureMpa = condenserNow.pressureMpa,
            dt = dt,
        )
        lastProtection = protection.advance(
            truePowerFraction = fissionPowerMw / ReferencePlant.RATED_THERMAL_POWER_MW,
            truePressureMpa = pzrNow.pressureMpa,
            trueFlowFraction = totalPrimaryFlow / ReferencePlant.CORE_FLOW_KG_PER_S,
            hotLegTemperatureK = hotLegAverage,
            minimumSgLevel = sgNow.minOf { it.levelFraction },
            turbineTrip = turbineNow.tripped,
            dt = dt,
        )
        if (lastProtection.reactorTrip && !rod.scramActive) rods.scram()

        componentCondition.advance(
            loopSnapshots = loopNow,
            steamGenerators = sgNow,
            turbineSnapshot = turbineNow,
            condenserSnapshot = condenserNow,
            feedwaterSnapshot = feedwaterNow,
            hotLegTemperatureK = hotLegAverage,
            dt = dt,
        )
        economics.advance(turbineNow.generatorNetMw, dt)
        burnupMwdPerT += fissionPowerMw * dt /
            (86400.0 * ReferencePlant.HEAVY_METAL_MASS_TONNES)

        val totalWaterMass = primaryLiquidMass + pzrNow.totalMassKg +
            sgNow.sumOf { it.secondaryMassKg } + headerNow.massKg + condenserNow.totalMassKg
        val totalStoredEnergy = coreNow.storedEnergyMj +
            loopNow.sumOf { it.storedEnergyMj } +
            sgNow.sumOf { it.storedEnergyMj } +
            pzrNow.internalEnergyKj / 1000.0 +
            headerNow.internalEnergyKj / 1000.0 +
            condenserNow.internalEnergyKj / 1000.0 +
            turbineNow.storedRotationalEnergyMj + decay.storedEnergyMj
        lastAudit = audit.advance(
            totalWaterMassKg = totalWaterMass,
            totalStoredEnergyMj = totalStoredEnergy,
            fissionPowerMw = fissionPowerMw,
            generatorNetMw = turbineNow.generatorNetMw,
            condenserHeatRejectionMw = condenserNow.heatRejectionMw,
            reliefFlowKgS = pzrNow.reliefFlowKgPerS,
            dt = dt,
        )

        simulationSeconds += dt
        // Consume these snapshots so Kotlin does not optimize away the explicit
        // slow-state updates in future refactors.
        @Suppress("UNUSED_VARIABLE")
        val slowStateCheck = chemistryNow.boronPpm + poisonNow.xenon + headerNow.pressureMpa
    }

    private fun composeState(): PlantState {
        val pressure = pressurizer.snapshot().pressureMpa
        val condition = componentCondition.snapshot()
        val coreS = core.snapshot(pressure)
        val loopS = loops.mapIndexed { i, loop -> loop.snapshot(pressure, condition.rcp[i]) }
        val sgS = steamGenerators.map { it.snapshot(pressure) }
        val pzr = pressurizer.snapshot()
        val header = steamHeader.snapshot()
        val cond = condenser.snapshot()
        val fw = feedwater.snapshot()
        val turb = turbine.snapshot()
        val kin = kinetics.snapshot()
        val pois = poison.snapshot()
        val chem = chemistry.snapshot()
        val decay = decayHeat.snapshot()
        val rod = rods.snapshot()

        val rodRho = rods.reactivity()
        val dopplerRho = ReferencePlant.DOPPLER_COEFF_PER_K *
            (coreS.fuelAverageK - referenceFuelTemperatureK)
        val moderatorRho = ReferencePlant.MODERATOR_DENSITY_COEFF_PER_KG_M3 *
            (coreS.moderatorDensityKgM3 - referenceModeratorDensityKgM3)
        val totalRho = rodRho + dopplerRho + chem.boronReactivity +
            pois.xenonReactivity + pois.samariumReactivity
        val fission = kin.neutronPopulation * ReferencePlant.RATED_THERMAL_POWER_MW
        val totalCoreHeat = DecayHeatModel.PROMPT_DEPOSIT_FRACTION * fission + decay.powerMw
        val totalFlow = loopS.sumOf { max(0.0, it.massFlowKgS) }
        val hotAvg = loopS.map { it.hotLegTemperatureK }.average()
        val coldAvg = loopS.map { it.coldLegTemperatureK }.average()
        val sgHeat = sgS.sumOf { it.heatTransferMw }
        val auxiliary = loopS.sumOf { it.pump.shaftPowerMw } + fw.auxiliaryPowerMw

        val diagnostics = buildList {
            coreS.diagnostic?.let(::add)
            pzr.diagnostic?.let(::add)
            sgS.mapNotNull { it.diagnostic }.forEach(::add)
            header.diagnostic?.let(::add)
            cond.diagnostic?.let(::add)
            turb.diagnostic?.let(::add)
        }.distinct()

        return PlantState(
            simulationSeconds = simulationSeconds,
            neutronPopulation = kin.neutronPopulation,
            fissionPowerMw = fission,
            decayHeatMw = decay.powerMw,
            totalCoreHeatMw = totalCoreHeat,
            totalReactivityPcm = totalRho * 100_000.0,
            rodReactivityPcm = rodRho * 100_000.0,
            dopplerReactivityPcm = dopplerRho * 100_000.0,
            moderatorReactivityPcm = moderatorRho * 100_000.0,
            boronReactivityPcm = chem.boronReactivity * 100_000.0,
            xenonReactivityPcm = pois.xenonReactivity * 100_000.0,
            samariumReactivityPcm = pois.samariumReactivity * 100_000.0,
            reactorPeriodSeconds = kin.periodSeconds,
            boronPpm = chem.boronPpm,
            iodineInventory = pois.iodine,
            xenonInventory = pois.xenon,
            promethiumInventory = pois.promethium,
            samariumInventory = pois.samarium,
            burnupMwdPerT = burnupMwdPerT,
            fuelTemperatureK = coreS.fuelAverageK,
            fuelPeakTemperatureK = coreS.fuelPeakK,
            cladTemperatureK = coreS.cladAverageK,
            cladPeakTemperatureK = coreS.cladPeakK,
            coolantTemperatureK = coreS.coolantAverageK,
            hotLegTemperatureK = hotAvg,
            coldLegTemperatureK = coldAvg,
            subcoolingMarginK = coreS.subcoolingMarginK,
            estimatedPeakFactorHeatFluxMwM2 = coreS.estimatedPeakFactorHeatFluxMwM2,
            primaryPressureMpa = pzr.pressureMpa,
            totalPrimaryFlowKgPerS = totalFlow,
            loopFlowKgPerS = loopS.map { it.massFlowKgS },
            loopPumpRpm = loopS.map { it.pump.rpm },
            loopPressureLossMpa = loopS.map { it.pressureLossMpa },
            pressurizerTemperatureK = pzr.saturationTemperatureK,
            pressurizerLevelFraction = pzr.levelFraction,
            pressurizerHeaterFraction = pzr.heaterFraction,
            pressurizerSprayFraction = pzr.sprayFraction,
            pressurizerSurgeKgPerS = pzr.surgeFlowKgPerS,
            pressurizerSprayKgPerS = pzr.sprayFlowKgPerS,
            pressurizerReliefKgPerS = pzr.reliefFlowKgPerS,
            steamGeneratorPressureMpa = sgS.map { it.pressureMpa },
            steamGeneratorLevelFraction = sgS.map { it.levelFraction },
            steamGeneratorSteamFlowKgPerS = sgS.map { it.steamFlowKgS },
            steamGeneratorFeedwaterFlowKgPerS = sgS.map { it.feedwaterFlowKgS },
            steamGeneratorHeatTransferMw = sgS.map { it.heatTransferMw },
            mainSteamPressureMpa = header.pressureMpa,
            mainSteamTemperatureK = header.temperatureK,
            secondaryHeatRemovalMw = sgHeat,
            turbineLoad = turbineLoadCommand,
            turbineValvePosition = turb.governorValvePosition,
            turbineSteamFlowKgPerS = turb.steamFlowKgS,
            turbineRpm = turb.rpm,
            generatorGrossPowerMw = turb.generatorGrossMw,
            generatorPowerMw = turb.generatorNetMw,
            generatorReactivePowerMvar = turb.reactivePowerMvar,
            generatorBreakerClosed = turb.breakerClosed,
            condenserPressureKpa = cond.pressureMpa * 1000.0,
            condenserLevelFraction = cond.levelFraction,
            condenserHeatRejectionMw = cond.heatRejectionMw,
            feedwaterFlowKgPerS = fw.totalFlowKgS,
            feedwaterTemperatureK = fw.temperatureK,
            auxiliaryPowerMw = auxiliary,
            rodInsertion = rod.actualInsertion,
            rodCommandInsertion = rod.commandedInsertion,
            tripped = lastProtection.reactorTrip,
            tripReasons = lastProtection.tripReasons,
            primaryMassResidualKg = pzr.inventoryResidualKg,
            plantEnergyResidualMw = lastAudit.energyResidualMw,
            massConservationErrorKg = lastAudit.massResidualKg,
            energyConservationErrorMj = lastAudit.energyResidualMj,
            diagnostic = diagnostics.takeIf { it.isNotEmpty() }?.joinToString(" • "),
        )
    }

    private fun calculatePrimaryLiquidMass(pressureMpa: Double): Double {
        val c = core.snapshot(pressureMpa)
        val l = loops.sumOf { it.snapshot(pressureMpa, 1.0).liquidMassKg }
        val sg = steamGenerators.sumOf { it.snapshot(pressureMpa).primaryMassKg }
        return c.coolantMassKg + l + sg
    }

    private fun weightedEnthalpy(
        streams: List<Pair<Double, Double>>,
        fallback: Double,
    ): Double {
        val flow = streams.sumOf { max(0.0, it.first) }
        if (flow <= 1.0e-9) return fallback
        return streams.sumOf { max(0.0, it.first) * it.second } / flow
    }
}
