package com.nyerahworks.criticalstate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.nyerahworks.criticalstate.SimulationRuntime.PerformanceSnapshot
import com.nyerahworks.criticalstate.sim.PlantState
import com.nyerahworks.criticalstate.sim.ReactorSimulator
import com.nyerahworks.criticalstate.ui.ControlPalette
import com.nyerahworks.criticalstate.ui.MetricTile
import com.nyerahworks.criticalstate.ui.PlantSchematicView
import com.nyerahworks.criticalstate.ui.TrendStripView
import com.nyerahworks.criticalstate.ui.panelBackground
import java.util.Locale
import kotlin.math.abs

/**
 * Fixed-screen operating console for Critical State.
 *
 * The old development panel placed the plant, controls and diagnostics in one
 * ScrollView and advanced the physics from the main Handler.  This activity is
 * deliberately much closer to an industrial HMI: persistent status/alarm bars,
 * five fixed pages, a process mimic, compact instrument tiles and controls that
 * remain reachable without vertical scrolling.
 */
class MainActivity : Activity() {
    private enum class Screen { OVERVIEW, PRIMARY, STEAM, GRID, TRENDS }

    private lateinit var runtime: SimulationRuntime
    private var state = PlantState()
    private var performance = PerformanceSnapshot(1.0, 0.0, 0.0, 100.0, true)
    private var screen = Screen.OVERVIEW
    private var desiredScale = 1.0
    private val rcpCommands = BooleanArray(4) { true }

    private lateinit var pageHost: FrameLayout
    private val pages = mutableMapOf<Screen, View>()
    private val navButtons = mutableMapOf<Screen, Button>()
    private val metrics = mutableMapOf<String, MetricTile>()
    private val rcpButtons = mutableListOf<Button>()

    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var performanceText: TextView
    private lateinit var alarmText: TextView
    private lateinit var speedButton: Button
    private lateinit var pauseButton: Button
    private lateinit var schematic: PlantSchematicView
    private lateinit var rodSeek: SeekBar
    private lateinit var rodValue: TextView
    private lateinit var loadSeek: SeekBar
    private lateinit var loadValue: TextView
    private lateinit var breakerButton: Button
    private lateinit var tripDetailText: TextView
    private lateinit var auditDetailText: TextView

    private lateinit var powerTrend: TrendStripView
    private lateinit var pressureTrend: TrendStripView
    private lateinit var electricTrend: TrendStripView
    private var lastTrendSimulationSeconds = -1.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ControlPalette.Background
        window.navigationBarColor = ControlPalette.Background
        runtime = SimulationRuntime { newState, perf ->
            state = newState
            performance = perf
            sampleTrends(newState)
            render(newState, perf)
        }
        setContentView(buildUi())
        render(state, performance)
    }

    override fun onStart() {
        super.onStart()
        runtime.start()
    }

    override fun onStop() {
        runtime.stop()
        super.onStop()
    }

    override fun onDestroy() {
        runtime.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ControlPalette.Background)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))

        alarmText = text("NO ACTIVE ALARMS", 10f, ControlPalette.Green, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.rgb(11, 25, 22), ControlPalette.Border, 6f)
        }
        root.addView(alarmText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)).apply {
            setMargins(0, dp(3), 0, dp(5))
        })

        pageHost = FrameLayout(this)
        pages[Screen.OVERVIEW] = buildOverviewPage()
        pages[Screen.PRIMARY] = buildPrimaryPage()
        pages[Screen.STEAM] = buildSteamPage()
        pages[Screen.GRID] = buildGridPage()
        pages[Screen.TRENDS] = buildTrendsPage()
        pages.forEach { (key, page) ->
            page.visibility = if (key == screen) View.VISIBLE else View.GONE
            pageHost.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(pageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildNavigation(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(0, dp(5), 0, 0)
        })
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = panelBackground(8f, resources.displayMetrics.density, emphasized = true)
        setPadding(dp(10), dp(5), dp(6), dp(5))

        val identity = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("CRITICAL STATE", 19f, ControlPalette.Text, true))
            statusText = text("AT POWER", 11f, ControlPalette.Amber, true)
            addView(statusText)
        }
        addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val timing = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clockText = mono("T+00:00:00", 10f, ControlPalette.Text)
            performanceText = mono("SIM 1.0×", 8.5f, ControlPalette.Muted)
            addView(clockText)
            addView(performanceText)
        }
        addView(timing, LinearLayout.LayoutParams(dp(116), LinearLayout.LayoutParams.MATCH_PARENT))

        speedButton = controlButton("1×", ControlPalette.Cyan) { cycleSpeed() }
        addView(speedButton, LinearLayout.LayoutParams(dp(48), dp(42)).apply { setMargins(dp(4), 0, 0, 0) })
    }

    private fun buildOverviewPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        schematic = PlantSchematicView(this).apply {
            background = panelBackground(8f, resources.displayMetrics.density)
        }
        root.addView(schematic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.30f).apply {
            setMargins(0, 0, 0, dp(5))
        })

        val grid = metricGrid(
            listOf(
                Triple("ov_power", "REACTOR POWER", ""),
                Triple("ov_pressure", "RCS PRESSURE", ""),
                Triple("ov_pzr", "PRESSURIZER", ""),
                Triple("ov_sg", "STEAM GENERATORS", ""),
                Triple("ov_mw", "NET OUTPUT", ""),
                Triple("ov_cond", "CONDENSER", ""),
                Triple("ov_flow", "PRIMARY FLOW", ""),
                Triple("ov_rods", "CONTROL BANK", ""),
            ),
            columns = 2,
        )
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f).apply {
            setMargins(0, 0, 0, dp(4))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(controlButton("RODS −", ControlPalette.Blue) { nudgeRods(+0.02) }, weightedButtonParams())
        controls.addView(controlButton("RODS +", ControlPalette.Blue) { nudgeRods(-0.02) }, weightedButtonParams())
        controls.addView(controlButton("SCRAM", ControlPalette.Red) { runtime.tripReactor() }, weightedButtonParams())
        pauseButton = controlButton("PAUSE", ControlPalette.Amber) { runtime.setRunning(!performance.running) }
        controls.addView(pauseButton, weightedButtonParams())
        root.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.15f))
        return root
    }

    private fun buildPrimaryPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(metricGrid(
            listOf(
                Triple("pr_power", "NEUTRONICS", ""),
                Triple("pr_rho", "REACTIVITY", ""),
                Triple("pr_core", "CORE THERMAL", ""),
                Triple("pr_pzr", "PRESSURIZER", ""),
            ), 2,
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.30f))

        val rcpPanel = panel("REACTOR COOLANT LOOPS")
        val rcpGrid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        repeat(4) { i ->
            val button = controlButton("RCP ${i + 1}", ControlPalette.Green) {
                rcpCommands[i] = !rcpCommands[i]
                runtime.setRcpRunning(i, rcpCommands[i])
                renderPrimary(state)
            }.apply {
                gravity = Gravity.CENTER
                textSize = 10f
            }
            rcpButtons += button
            rcpGrid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                rowSpec = GridLayout.spec(i / 2, 1f)
                columnSpec = GridLayout.spec(i % 2, 1f)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        rcpPanel.addView(rcpGrid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(rcpPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.32f).apply {
            setMargins(0, dp(5), 0, dp(5))
        })

        val controls = panel("REACTIVITY CONTROL")
        val rodHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("ROD BANK INSERTION", 9f, ControlPalette.Muted, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rodValue = mono("55.0%", 10f, ControlPalette.Text)
            addView(rodValue)
        }
        controls.addView(rodHeader)
        rodSeek = SeekBar(this).apply {
            max = 1000
            progress = 550
            setOnSeekBarChangeListener(simpleSeek { progress -> runtime.setRodInsertion(progress / 1000.0) })
        }
        controls.addView(rodSeek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
        val chemistry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chemistry.addView(controlButton("DILUTE", ControlPalette.Blue) { runtime.setBoronMakeup(5.0, 0.0) }, weightedButtonParams(40))
        chemistry.addView(controlButton("HOLD", ControlPalette.Muted) { runtime.setBoronMakeup(0.0, state.boronPpm) }, weightedButtonParams(40))
        chemistry.addView(controlButton("BORATE", ControlPalette.Cyan) { runtime.setBoronMakeup(5.0, 2000.0) }, weightedButtonParams(40))
        chemistry.addView(controlButton("SCRAM", ControlPalette.Red) { runtime.tripReactor() }, weightedButtonParams(40))
        controls.addView(chemistry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.38f))
        return root
    }

    private fun buildSteamPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(metricGrid(
            listOf(
                Triple("sg_0", "STEAM GENERATOR 1", ""),
                Triple("sg_1", "STEAM GENERATOR 2", ""),
                Triple("sg_2", "STEAM GENERATOR 3", ""),
                Triple("sg_3", "STEAM GENERATOR 4", ""),
            ), 2,
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.56f))
        root.addView(metricGrid(
            listOf(
                Triple("st_header", "MAIN STEAM", ""),
                Triple("st_feed", "FEEDWATER", ""),
                Triple("st_cond", "CONDENSER", ""),
                Triple("st_heat", "HEAT BALANCE", ""),
            ), 2,
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.44f).apply {
            setMargins(0, dp(5), 0, 0)
        })
        return root
    }

    private fun buildGridPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(metricGrid(
            listOf(
                Triple("gr_rpm", "TURBINE SPEED", ""),
                Triple("gr_valve", "GOVERNOR", ""),
                Triple("gr_net", "GENERATOR", ""),
                Triple("gr_reactive", "REACTIVE POWER", ""),
                Triple("gr_aux", "AUXILIARY LOAD", ""),
                Triple("gr_breaker", "GRID BREAKER", ""),
            ), 2,
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.57f))

        val loadPanel = panel("POWER CONVERSION CONTROL")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("TURBINE LOAD REFERENCE", 9f, ControlPalette.Muted, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            loadValue = mono("100%", 10f, ControlPalette.Text)
            addView(loadValue)
        }
        loadPanel.addView(header)
        loadSeek = SeekBar(this).apply {
            max = 80
            progress = 70
            setOnSeekBarChangeListener(simpleSeek { progress -> runtime.setTurbineLoad((30 + progress) / 100.0) })
        }
        loadPanel.addView(loadSeek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        breakerButton = controlButton("BREAKER", ControlPalette.Green) {
            runtime.setGeneratorBreakerClosed(!state.generatorBreakerClosed)
        }
        actions.addView(breakerButton, weightedButtonParams(42))
        actions.addView(controlButton("TURBINE TRIP", ControlPalette.Red) { runtime.tripTurbine() }, weightedButtonParams(42))
        actions.addView(controlButton("RESET TURB", ControlPalette.Amber) { runtime.resetTurbineTrip() }, weightedButtonParams(42))
        loadPanel.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(loadPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.43f).apply {
            setMargins(0, dp(5), 0, 0)
        })
        return root
    }

    private fun buildTrendsPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        powerTrend = TrendStripView(this).apply { configure("REACTOR POWER", "%", ControlPalette.Amber, 0f, 150f) }
        pressureTrend = TrendStripView(this).apply { configure("PRIMARY PRESSURE", "MPa", ControlPalette.Blue, 12f, 18f) }
        electricTrend = TrendStripView(this).apply { configure("NET GENERATION", "MW", ControlPalette.Green, 0f, 1200f) }
        listOf(powerTrend, pressureTrend, electricTrend).forEach { chart ->
            root.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.23f).apply {
                setMargins(0, 0, 0, dp(4))
            })
        }
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = panelBackground(8f, resources.displayMetrics.density)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        auditDetailText = mono("CONSERVATION", 8.5f, ControlPalette.Muted).apply { gravity = Gravity.TOP }
        tripDetailText = mono("DIAGNOSTICS", 8.5f, ControlPalette.Muted).apply { gravity = Gravity.TOP }
        detail.addView(auditDetailText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(0, 0, dp(5), 0) })
        detail.addView(tripDetailText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.31f))
        return root
    }

    private fun buildNavigation(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = panelBackground(8f, resources.displayMetrics.density, emphasized = true)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val items = listOf(
            Screen.OVERVIEW to "OVERVIEW",
            Screen.PRIMARY to "PRIMARY",
            Screen.STEAM to "STEAM",
            Screen.GRID to "GRID",
            Screen.TRENDS to "TRENDS",
        )
        items.forEach { (target, label) ->
            val b = controlButton(label, ControlPalette.Muted) { showScreen(target) }.apply { textSize = 9f }
            navButtons[target] = b
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        updateNavigation()
        return row
    }

    private fun metricGrid(items: List<Triple<String, String, String>>, columns: Int): View {
        val rows = (items.size + columns - 1) / columns
        return GridLayout(this).apply {
            columnCount = columns
            rowCount = rows
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            items.forEachIndexed { index, item ->
                val tile = MetricTile(this@MainActivity)
                tile.setMetric(item.second, "—", item.third)
                metrics[item.first] = tile
                addView(tile, GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    rowSpec = GridLayout.spec(index / columns, 1f)
                    columnSpec = GridLayout.spec(index % columns, 1f)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
    }

    private fun panel(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground(8f, resources.displayMetrics.density)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        addView(text(title, 9f, ControlPalette.Muted, true))
    }

    private fun showScreen(target: Screen) {
        if (target == screen) return
        pages[screen]?.visibility = View.GONE
        screen = target
        pages[screen]?.visibility = View.VISIBLE
        updateNavigation()
        render(state, performance)
    }

    private fun updateNavigation() {
        navButtons.forEach { (target, button) ->
            val selected = target == screen
            button.setTextColor(if (selected) ControlPalette.Text else ControlPalette.Muted)
            button.background = rounded(
                if (selected) Color.rgb(31, 48, 61) else Color.TRANSPARENT,
                if (selected) ControlPalette.Cyan else Color.TRANSPARENT,
                6f,
            )
        }
    }

    private fun render(s: PlantState, perf: PerformanceSnapshot) {
        val powerPct = s.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0
        val status = when {
            s.tripped -> "REACTOR TRIP"
            !s.generatorBreakerClosed -> "GENERATOR OFF GRID"
            powerPct < 2.0 -> "LOW POWER"
            else -> "AT POWER"
        }
        statusText.text = status
        statusText.setTextColor(when {
            s.tripped -> ControlPalette.Red
            !s.generatorBreakerClosed -> ControlPalette.Amber
            else -> ControlPalette.Green
        })
        clockText.text = "T+${formatDuration(s.simulationSeconds)}"
        performanceText.text = String.format(
            Locale.US,
            "ACT %.1f×  CPU %.0fms",
            perf.effectiveTimeScale,
            perf.computeMillis,
        )
        val perfRatio = if (perf.requestedTimeScale > 0.0) perf.effectiveTimeScale / perf.requestedTimeScale else 1.0
        performanceText.setTextColor(when {
            !perf.running -> ControlPalette.Muted
            perfRatio < 0.70 -> ControlPalette.Red
            perfRatio < 0.92 -> ControlPalette.Amber
            else -> ControlPalette.Green
        })
        desiredScale = perf.requestedTimeScale
        speedButton.text = String.format(Locale.US, "%.0f×", desiredScale)
        if (::pauseButton.isInitialized) pauseButton.text = if (perf.running) "PAUSE" else "RUN"

        val alarm = when {
            s.tripReasons.isNotEmpty() -> "TRIP • ${s.tripReasons.joinToString(" • ")}"
            s.diagnostic != null -> "MODEL ADVISORY • ${s.diagnostic}"
            powerPct > 105.0 -> "CAUTION • REACTOR POWER HIGH"
            s.primaryPressureMpa !in 14.5..16.5 -> "CAUTION • RCS PRESSURE"
            else -> "NO ACTIVE ALARMS"
        }
        alarmText.text = alarm
        alarmText.setTextColor(when {
            s.tripReasons.isNotEmpty() -> ControlPalette.Red
            s.diagnostic != null || powerPct > 105.0 || s.primaryPressureMpa !in 14.5..16.5 -> ControlPalette.Amber
            else -> ControlPalette.Green
        })

        when (screen) {
            Screen.OVERVIEW -> renderOverview(s)
            Screen.PRIMARY -> renderPrimary(s)
            Screen.STEAM -> renderSteam(s)
            Screen.GRID -> renderGrid(s)
            Screen.TRENDS -> renderTrends(s)
        }
    }

    private fun renderOverview(s: PlantState) {
        schematic.setState(s)
        val powerPct = s.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0
        metric("ov_power", "REACTOR POWER", "%.1f %%".us(powerPct), "%.0f MWth • decay %.0f MW".us(s.totalCoreHeatMw, s.decayHeatMw), accentForPower(powerPct))
        metric("ov_pressure", "RCS PRESSURE", "%.3f MPa".us(s.primaryPressureMpa), "hot %.1f K • cold %.1f K".us(s.hotLegTemperatureK, s.coldLegTemperatureK), accentForPressure(s.primaryPressureMpa))
        metric("ov_pzr", "PRESSURIZER", "%.1f %%".us(s.pressurizerLevelFraction * 100.0), "heat %.0f%% • spray %.0f%%".us(s.pressurizerHeaterFraction * 100.0, s.pressurizerSprayFraction * 100.0), ControlPalette.Blue)
        val minSg = s.steamGeneratorLevelFraction.minOrNull() ?: 0.0
        metric("ov_sg", "STEAM GENERATORS", "MIN %.1f %%".us(minSg * 100.0), "header %.2f MPa".us(s.mainSteamPressureMpa), if (minSg < 0.4) ControlPalette.Amber else ControlPalette.Cyan)
        metric("ov_mw", "NET OUTPUT", "%.0f MW".us(s.generatorPowerMw), "gross %.0f • %.0f rpm".us(s.generatorGrossPowerMw, s.turbineRpm), if (s.generatorBreakerClosed) ControlPalette.Green else ControlPalette.Amber)
        metric("ov_cond", "CONDENSER", "%.2f kPa".us(s.condenserPressureKpa), "reject %.0f MW".us(s.condenserHeatRejectionMw), ControlPalette.Cyan)
        metric("ov_flow", "PRIMARY FLOW", "%.0f kg/s".us(s.totalPrimaryFlowKgPerS), "4-loop total", ControlPalette.Blue)
        metric("ov_rods", "CONTROL BANK", "%.1f %%".us(s.rodInsertion * 100.0), "cmd %.1f%% • rho %+.0f pcm".us(s.rodCommandInsertion * 100.0, s.rodReactivityPcm), if (s.tripped) ControlPalette.Red else ControlPalette.Text)
    }

    private fun renderPrimary(s: PlantState) {
        val powerPct = s.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0
        metric("pr_power", "NEUTRONICS", "%.1f %%".us(powerPct), "period ${periodText(s.reactorPeriodSeconds)} • Xe %.3f".us(s.xenonInventory), accentForPower(powerPct))
        metric("pr_rho", "REACTIVITY", "%+.1f pcm".us(s.totalReactivityPcm), "rod %+.0f • Dopp %+.0f • B %+.0f".us(s.rodReactivityPcm, s.dopplerReactivityPcm, s.boronReactivityPcm), if (abs(s.totalReactivityPcm) > 100.0) ControlPalette.Amber else ControlPalette.Text)
        metric("pr_core", "CORE THERMAL", "%.0f / %.0f K".us(s.fuelTemperatureK, s.cladTemperatureK), "peak %.0f / %.0f • subcool %.1f K".us(s.fuelPeakTemperatureK, s.cladPeakTemperatureK, s.subcoolingMarginK), ControlPalette.Amber)
        metric("pr_pzr", "PRESSURIZER", "%.3f MPa".us(s.primaryPressureMpa), "level %.1f%% • surge %+.0f kg/s".us(s.pressurizerLevelFraction * 100.0, s.pressurizerSurgeKgPerS), accentForPressure(s.primaryPressureMpa))

        rcpButtons.forEachIndexed { i, button ->
            val flow = s.loopFlowKgPerS.getOrElse(i) { 0.0 }
            val rpm = s.loopPumpRpm.getOrElse(i) { 0.0 }
            button.text = "RCP ${i + 1} ${if (rcpCommands[i]) "ON" else "OFF"}\n%.0f kg/s • %.0f rpm".us(flow, rpm)
            button.setTextColor(if (rcpCommands[i] && rpm > 500.0) ControlPalette.Green else ControlPalette.Amber)
        }
        if (!rodSeek.isPressed) rodSeek.progress = (s.rodCommandInsertion * 1000.0).toInt().coerceIn(0, 1000)
        rodValue.text = "ACT %.1f%% / CMD %.1f%% • B %.0f ppm".us(s.rodInsertion * 100.0, s.rodCommandInsertion * 100.0, s.boronPpm)
    }

    private fun renderSteam(s: PlantState) {
        for (i in 0 until 4) {
            val p = s.steamGeneratorPressureMpa.getOrElse(i) { 0.0 }
            val level = s.steamGeneratorLevelFraction.getOrElse(i) { 0.0 }
            val steam = s.steamGeneratorSteamFlowKgPerS.getOrElse(i) { 0.0 }
            val feed = s.steamGeneratorFeedwaterFlowKgPerS.getOrElse(i) { 0.0 }
            val q = s.steamGeneratorHeatTransferMw.getOrElse(i) { 0.0 }
            metric("sg_$i", "STEAM GENERATOR ${i + 1}", "%.2f MPa • %.1f%%".us(p, level * 100.0), "steam %.0f • feed %.0f kg/s • %.0f MW".us(steam, feed, q), if (level !in 0.4..0.85) ControlPalette.Amber else ControlPalette.Cyan)
        }
        metric("st_header", "MAIN STEAM", "%.3f MPa".us(s.mainSteamPressureMpa), "%.1f K • %.0f kg/s".us(s.mainSteamTemperatureK, s.turbineSteamFlowKgPerS), ControlPalette.Cyan)
        metric("st_feed", "FEEDWATER", "%.0f kg/s".us(s.feedwaterFlowKgPerS), "%.1f K".us(s.feedwaterTemperatureK), ControlPalette.Blue)
        metric("st_cond", "CONDENSER", "%.2f kPa".us(s.condenserPressureKpa), "level %.1f%% • reject %.0f MW".us(s.condenserLevelFraction * 100.0, s.condenserHeatRejectionMw), ControlPalette.Cyan)
        metric("st_heat", "HEAT BALANCE", "%.0f MW".us(s.secondaryHeatRemovalMw), "core %.0f • residual %+.2f MW".us(s.totalCoreHeatMw, s.plantEnergyResidualMw), if (abs(s.plantEnergyResidualMw) > 50.0) ControlPalette.Amber else ControlPalette.Text)
    }

    private fun renderGrid(s: PlantState) {
        metric("gr_rpm", "TURBINE SPEED", "%.0f rpm".us(s.turbineRpm), "sync 1800 rpm", if (abs(s.turbineRpm - 1800.0) > 45.0) ControlPalette.Amber else ControlPalette.Green)
        metric("gr_valve", "GOVERNOR", "%.1f %%".us(s.turbineValvePosition * 100.0), "load ref %.0f%%".us(s.turbineLoad * 100.0), ControlPalette.Cyan)
        metric("gr_net", "GENERATOR", "%.0f MW".us(s.generatorPowerMw), "gross %.0f MW".us(s.generatorGrossPowerMw), if (s.generatorBreakerClosed) ControlPalette.Green else ControlPalette.Amber)
        metric("gr_reactive", "REACTIVE POWER", "%+.0f Mvar".us(s.generatorReactivePowerMvar), "grid-coupled model", ControlPalette.Cyan)
        metric("gr_aux", "AUXILIARY LOAD", "%.1f MW".us(s.auxiliaryPowerMw), "RCP + feedwater", ControlPalette.Text)
        metric("gr_breaker", "GRID BREAKER", if (s.generatorBreakerClosed) "CLOSED" else "OPEN", if (s.generatorBreakerClosed) "generator synchronized" else "generator isolated", if (s.generatorBreakerClosed) ControlPalette.Green else ControlPalette.Amber)
        if (!loadSeek.isPressed) loadSeek.progress = ((s.turbineLoad * 100.0).toInt() - 30).coerceIn(0, 80)
        loadValue.text = "%.0f%%".us(s.turbineLoad * 100.0)
        breakerButton.text = if (s.generatorBreakerClosed) "OPEN BREAKER" else "CLOSE BREAKER"
        breakerButton.setTextColor(if (s.generatorBreakerClosed) ControlPalette.Amber else ControlPalette.Green)
    }

    private fun renderTrends(s: PlantState) {
        auditDetailText.text = "CONSERVATION\nRCS mass  %+.1f kg\nPlant mass %+.1f kg\nEnergy     %+.1f MJ\nDrift      %+.2f MW".us(
            s.primaryMassResidualKg,
            s.massConservationErrorKg,
            s.energyConservationErrorMj,
            s.plantEnergyResidualMw,
        )
        tripDetailText.text = buildString {
            append("PROTECTION / MODEL\n")
            if (s.tripReasons.isNotEmpty()) append("TRIP: ${s.tripReasons.joinToString(", ")}\n")
            append(s.diagnostic ?: "No active model diagnostic")
            append("\n\nPERFORMANCE\n")
            append("requested %.0f× • actual %.2f×\n".us(performance.requestedTimeScale, performance.effectiveTimeScale))
            append("compute %.1f ms / 100 ms budget".us(performance.computeMillis))
        }
    }

    private fun sampleTrends(s: PlantState) {
        if (s.simulationSeconds < lastTrendSimulationSeconds) {
            powerTrend.clear()
            pressureTrend.clear()
            electricTrend.clear()
            lastTrendSimulationSeconds = -1.0
        }
        if (lastTrendSimulationSeconds < 0.0 || s.simulationSeconds - lastTrendSimulationSeconds >= 1.0) {
            powerTrend.add(s.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0)
            pressureTrend.add(s.primaryPressureMpa)
            electricTrend.add(s.generatorPowerMw)
            lastTrendSimulationSeconds = s.simulationSeconds
        }
    }

    private fun cycleSpeed() {
        desiredScale = when (desiredScale) {
            1.0 -> 10.0
            10.0 -> 60.0
            else -> 1.0
        }
        runtime.setTimeScale(desiredScale)
        speedButton.text = "%.0f×".us(desiredScale)
    }

    private fun nudgeRods(deltaInsertion: Double) {
        runtime.setRodInsertion((state.rodCommandInsertion + deltaInsertion).coerceIn(0.0, 1.0))
    }

    private fun metric(key: String, label: String, value: String, detail: String, accent: Int = ControlPalette.Text) {
        metrics[key]?.setMetric(label, value, detail, accent)
    }

    private fun accentForPower(powerPct: Double): Int = when {
        powerPct > 110.0 -> ControlPalette.Red
        powerPct > 103.0 -> ControlPalette.Amber
        else -> ControlPalette.Green
    }

    private fun accentForPressure(pressureMpa: Double): Int = when {
        pressureMpa !in 13.5..17.5 -> ControlPalette.Red
        pressureMpa !in 14.5..16.5 -> ControlPalette.Amber
        else -> ControlPalette.Blue
    }

    private fun periodText(period: Double?): String = when {
        period == null -> "stable"
        abs(period) > 9999.0 -> ">9999 s"
        else -> "%+.1f s".us(period)
    }

    private fun weightedButtonParams(heightDp: Int = 44) = LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun controlButton(label: String, accent: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setTextColor(accent)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minHeight = 0
        minWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(Color.rgb(20, 29, 38), ControlPalette.Border, 6f)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp)
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun mono(value: String, size: Float, color: Int) = text(value, size, color, false).apply {
        typeface = Typeface.MONOSPACE
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun String.us(vararg values: Any): String = String.format(Locale.US, this, *values)

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (days > 0) {
            String.format(Locale.US, "%dd %02d:%02d:%02d", days, hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        }
    }
}
