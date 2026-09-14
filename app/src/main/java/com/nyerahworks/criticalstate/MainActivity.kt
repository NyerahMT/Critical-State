package com.nyerahworks.criticalstate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nyerahworks.criticalstate.sim.PlantState
import com.nyerahworks.criticalstate.sim.ReactorSimulator
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private val simulator = ReactorSimulator()
    private val handler = Handler(Looper.getMainLooper())

    private var running = true
    private var timeScale = 1.0
    private val rcpRunning = BooleanArray(4) { true }

    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var powerText: TextView
    private lateinit var coreText: TextView
    private lateinit var rcsText: TextView
    private lateinit var loopText: TextView
    private lateinit var pressureText: TextView
    private lateinit var sgText: TextView
    private lateinit var turbineText: TextView
    private lateinit var condenserText: TextView
    private lateinit var reactivityText: TextView
    private lateinit var poisonText: TextView
    private lateinit var periodText: TextView
    private lateinit var conservationText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var rodValueText: TextView
    private lateinit var turbineValueText: TextView
    private lateinit var pauseButton: Button
    private lateinit var breakerButton: Button
    private val rcpButtons = mutableListOf<Button>()

    private val tick = object : Runnable {
        override fun run() {
            if (running) simulator.advance(0.05, timeScale)
            render(simulator.snapshot())
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 13, 18)
        window.navigationBarColor = Color.rgb(9, 13, 18)
        setContentView(buildUi())
        render(simulator.snapshot())
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 15, 20))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(28))
        }
        scroll.addView(root)

        root.addView(text("CRITICAL STATE", 27f, Color.rgb(236, 240, 244), true))
        root.addView(text("COUPLED FOUR-LOOP PWR • DEVELOPMENT PANEL", 10f, Color.rgb(130, 145, 158), true))
        statusText = text("AT POWER", 16f, Color.rgb(231, 184, 75), true)
        root.addView(statusText)
        clockText = mono("SIM T+00:00:00", 12f, Color.rgb(174, 187, 198))
        root.addView(clockText)
        root.addView(space(12))

        powerText = metric(root, "NEUTRONICS / HEAT")
        coreText = metric(root, "CORE THERMAL")
        rcsText = metric(root, "REACTOR COOLANT SYSTEM")
        loopText = metric(root, "PRIMARY LOOPS / RCP")
        pressureText = metric(root, "PRESSURIZER")
        sgText = metric(root, "STEAM GENERATORS")
        turbineText = metric(root, "MAIN STEAM / TURBINE / GENERATOR")
        condenserText = metric(root, "CONDENSER / FEEDWATER")
        reactivityText = metric(root, "REACTIVITY")
        poisonText = metric(root, "CHEMISTRY / POISONS")
        periodText = metric(root, "REACTOR PERIOD")
        conservationText = metric(root, "CONSERVATION AUDIT")
        diagnosticText = metric(root, "PROTECTION / DIAGNOSTICS")

        root.addView(space(10))
        root.addView(sectionTitle("REACTOR CONTROL"))
        rodValueText = mono("55.0 %", 13f, Color.WHITE)
        root.addView(controlHeader("CONTROL BANK INSERTION", rodValueText))
        root.addView(SeekBar(this).apply {
            max = 1000
            progress = 550
            setOnSeekBarChangeListener(simpleSeek { progress ->
                val insertion = progress / 1000.0
                simulator.setRodInsertion(insertion)
                rodValueText.text = String.format(Locale.US, "CMD %.1f %%", insertion * 100.0)
            })
        })

        root.addView(sectionTitle("POWER CONVERSION"))
        turbineValueText = mono("100 %", 13f, Color.WHITE)
        root.addView(controlHeader("TURBINE LOAD REFERENCE", turbineValueText))
        root.addView(SeekBar(this).apply {
            max = 80
            progress = 70
            setOnSeekBarChangeListener(simpleSeek { progress ->
                val load = (30 + progress) / 100.0
                simulator.setTurbineLoad(load)
                turbineValueText.text = String.format(Locale.US, "%.0f %%", load * 100.0)
            })
        })

        root.addView(text("REACTOR COOLANT PUMPS", 11f, Color.rgb(174, 187, 198), true))
        val rcpRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rcpRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(4) { index ->
            val button = button("RCP ${index + 1} ON") {
                rcpRunning[index] = !rcpRunning[index]
                simulator.setReactorCoolantPump(index, rcpRunning[index])
                updateRcpButton(index)
            }
            rcpButtons += button
            val target = if (index < 2) rcpRow1 else rcpRow2
            target.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        root.addView(rcpRow1)
        root.addView(rcpRow2)

        breakerButton = button("GEN BREAKER CLOSED") {
            val nowClosed = simulator.snapshot().generatorBreakerClosed
            simulator.setGeneratorBreakerClosed(!nowClosed)
            render(simulator.snapshot())
        }
        val turbineTripButton = button("TURBINE TRIP") {
            simulator.tripTurbine()
            render(simulator.snapshot())
        }.apply { setTextColor(Color.rgb(255, 190, 90)) }
        root.addView(buttonRow(breakerButton, turbineTripButton))

        root.addView(text("CHEMICAL SHIM", 11f, Color.rgb(174, 187, 198), true))
        root.addView(buttonRow(
            button("DILUTE") { simulator.setBoronMakeup(5.0, 0.0) },
            button("HOLD") { simulator.setBoronMakeup(0.0, 900.0) },
            button("BORATE") { simulator.setBoronMakeup(5.0, 2000.0) },
        ))

        root.addView(text("SIMULATION SPEED", 11f, Color.rgb(174, 187, 198), true))
        root.addView(buttonRow(
            button("1×") { timeScale = 1.0 },
            button("10×") { timeScale = 10.0 },
            button("60×") { timeScale = 60.0 },
        ))

        pauseButton = button("PAUSE") {
            running = !running
            pauseButton.text = if (running) "PAUSE" else "RUN"
        }
        val trip = button("REACTOR TRIP") {
            simulator.trip()
            render(simulator.snapshot())
        }.apply { setTextColor(Color.rgb(255, 122, 112)) }
        val reset = button("RESET PLANT") {
            simulator.reset()
            running = true
            timeScale = 1.0
            for (i in rcpRunning.indices) {
                rcpRunning[i] = true
                updateRcpButton(i)
            }
            pauseButton.text = "PAUSE"
            render(simulator.snapshot())
        }
        root.addView(buttonRow(pauseButton, trip, reset))

        root.addView(space(14))
        root.addView(sectionTitle("MODEL BASIS"))
        root.addView(text("Six-group kinetics • I/Xe + Pm/Sm • 11-group decay heat", 12f, Color.rgb(174, 187, 198), false))
        root.addView(text("6 axial core nodes • radial fuel/clad storage • IF97 coolant", 12f, Color.rgb(174, 187, 198), false))
        root.addView(text("4 dynamic RCS loops • RCP inertia • natural circulation • pressurizer", 12f, Color.rgb(174, 187, 198), false))
        root.addView(text("4 dynamic SGs • steam header • turbine/grid • condenser/feedwater", 12f, Color.rgb(174, 187, 198), false))
        root.addView(text("Dynamic instruments • generic protection voting • conservation ledger", 12f, Color.rgb(174, 187, 198), false))
        root.addView(space(12))
        root.addView(text(
            "GENERIC REDUCED-ORDER PWR • ENTERTAINMENT / EDUCATION • NOT OPERATOR TRAINING",
            9f,
            Color.rgb(130, 145, 158),
            false,
        ))
        return scroll
    }

    private fun render(s: PlantState) {
        statusText.text = when {
            s.tripped -> "REACTOR TRIP"
            s.generatorBreakerClosed.not() -> "GENERATOR OFF GRID"
            s.fissionPowerMw < ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 0.02 -> "SUBCRITICAL / LOW POWER"
            else -> "AT POWER"
        }
        statusText.setTextColor(if (s.tripped) Color.rgb(255, 122, 112) else Color.rgb(231, 184, 75))
        clockText.text = "SIM T+${formatDuration(s.simulationSeconds)}"

        powerText.text = String.format(
            Locale.US,
            "Fission  %6.1f %%  %7.0f MW\nCore heat %7.0f MW   Decay %6.1f MW",
            s.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0,
            s.fissionPowerMw,
            s.totalCoreHeatMw,
            s.decayHeatMw,
        )
        coreText.text = String.format(
            Locale.US,
            "Fuel avg/peak  %.0f / %.0f K\nClad avg/peak  %.0f / %.0f K\nSubcooling     %.1f K",
            s.fuelTemperatureK,
            s.fuelPeakTemperatureK,
            s.cladTemperatureK,
            s.cladPeakTemperatureK,
            s.subcoolingMarginK,
        )
        rcsText.text = String.format(
            Locale.US,
            "P %.3f MPa   Flow %.0f kg/s\nT cold/hot %.1f / %.1f K",
            s.primaryPressureMpa,
            s.totalPrimaryFlowKgPerS,
            s.coldLegTemperatureK,
            s.hotLegTemperatureK,
        )
        loopText.text = buildString {
            s.loopFlowKgPerS.indices.forEach { i ->
                append(String.format(Locale.US, "L%d %6.0f kg/s  %4.0f rpm", i + 1, s.loopFlowKgPerS[i], s.loopPumpRpm.getOrElse(i) { 0.0 }))
                if (i != s.loopFlowKgPerS.lastIndex) append('\n')
            }
        }
        pressureText.text = String.format(
            Locale.US,
            "Level %.1f %%   Tsat %.1f °C\nHeat %.0f %%  Spray %.0f %%\nSurge %+.1f  Spray %.1f  Relief %.1f kg/s",
            s.pressurizerLevelFraction * 100.0,
            s.pressurizerTemperatureK - 273.15,
            s.pressurizerHeaterFraction * 100.0,
            s.pressurizerSprayFraction * 100.0,
            s.pressurizerSurgeKgPerS,
            s.pressurizerSprayKgPerS,
            s.pressurizerReliefKgPerS,
        )
        sgText.text = buildString {
            s.steamGeneratorPressureMpa.indices.forEach { i ->
                append(String.format(
                    Locale.US,
                    "SG%d %.2f MPa  L %.1f %%  steam %.0f kg/s",
                    i + 1,
                    s.steamGeneratorPressureMpa[i],
                    s.steamGeneratorLevelFraction.getOrElse(i) { 0.0 } * 100.0,
                    s.steamGeneratorSteamFlowKgPerS.getOrElse(i) { 0.0 },
                ))
                if (i != s.steamGeneratorPressureMpa.lastIndex) append('\n')
            }
        }
        turbineText.text = String.format(
            Locale.US,
            "Header %.2f MPa   Steam %.0f kg/s\nTurbine %.0f rpm   Valve %.0f %%\nGross %.0f MW   Net %.0f MW   Q %+.0f Mvar\nBreaker %s",
            s.mainSteamPressureMpa,
            s.turbineSteamFlowKgPerS,
            s.turbineRpm,
            s.turbineValvePosition * 100.0,
            s.generatorGrossPowerMw,
            s.generatorPowerMw,
            s.generatorReactivePowerMvar,
            if (s.generatorBreakerClosed) "CLOSED" else "OPEN",
        )
        condenserText.text = String.format(
            Locale.US,
            "Condenser %.2f kPa   reject %.0f MW\nFeedwater %.0f kg/s   %.1f K\nAuxiliary %.1f MW",
            s.condenserPressureKpa,
            s.condenserHeatRejectionMw,
            s.feedwaterFlowKgPerS,
            s.feedwaterTemperatureK,
            s.auxiliaryPowerMw,
        )
        reactivityText.text = String.format(
            Locale.US,
            "Total %+.1f pcm\nRod %+.1f  Doppler %+.1f  Mod %+.1f\nBoron %+.1f  Xe %+.1f  Sm %+.1f pcm",
            s.totalReactivityPcm,
            s.rodReactivityPcm,
            s.dopplerReactivityPcm,
            s.moderatorReactivityPcm,
            s.boronReactivityPcm,
            s.xenonReactivityPcm,
            s.samariumReactivityPcm,
        )
        poisonText.text = String.format(
            Locale.US,
            "Boron %.1f ppm   Burnup %.3f MWd/t\nI %.3f  Xe %.3f  Pm %.3f  Sm %.3f",
            s.boronPpm,
            s.burnupMwdPerT,
            s.iodineInventory,
            s.xenonInventory,
            s.promethiumInventory,
            s.samariumInventory,
        )
        periodText.text = s.reactorPeriodSeconds?.let {
            if (abs(it) > 9999.0) "> 9999 s" else String.format(Locale.US, "%+.2f s", it)
        } ?: "STABLE"
        conservationText.text = String.format(
            Locale.US,
            "RCS inventory residual %+.1f kg\nPlant mass residual   %+.1f kg\nEnergy residual       %+.1f MJ  (%+.2f MW drift)",
            s.primaryMassResidualKg,
            s.massConservationErrorKg,
            s.energyConservationErrorMj,
            s.plantEnergyResidualMw,
        )
        diagnosticText.text = when {
            s.tripReasons.isNotEmpty() -> "TRIP: ${s.tripReasons.joinToString(", ")}" +
                (s.diagnostic?.let { "\n$it" } ?: "")
            s.diagnostic != null -> s.diagnostic
            else -> "No active model diagnostic"
        }

        rodValueText.text = String.format(Locale.US, "ACT %.1f %% / CMD %.1f %%", s.rodInsertion * 100.0, s.rodCommandInsertion * 100.0)
        turbineValueText.text = String.format(Locale.US, "%.0f %%", s.turbineLoad * 100.0)
        breakerButton.text = if (s.generatorBreakerClosed) "GEN BREAKER CLOSED" else "GEN BREAKER OPEN"
    }

    private fun updateRcpButton(index: Int) {
        if (index !in rcpButtons.indices) return
        rcpButtons[index].text = "RCP ${index + 1} ${if (rcpRunning[index]) "ON" else "OFF"}"
    }

    private fun metric(parent: LinearLayout, label: String): TextView {
        parent.addView(text(label, 10f, Color.rgb(156, 177, 194), true))
        return mono("—", 15f, Color.rgb(232, 237, 242)).also {
            it.setPadding(0, 0, 0, dp(10))
            parent.addView(it)
        }
    }

    private fun sectionTitle(value: String) = text(value, 14f, Color.rgb(231, 184, 75), true).apply {
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun controlHeader(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(label, 11f, Color.rgb(174, 187, 198), true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        buttons.forEach { button ->
            addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
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

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
