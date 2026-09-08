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

    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var powerText: TextView
    private lateinit var generatorText: TextView
    private lateinit var coolantText: TextView
    private lateinit var pressureText: TextView
    private lateinit var reactivityText: TextView
    private lateinit var periodText: TextView
    private lateinit var rodValueText: TextView
    private lateinit var turbineValueText: TextView
    private lateinit var pauseButton: Button

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
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(text("CRITICAL STATE", 28f, Color.rgb(236, 240, 244), true))

        statusText = text("AT POWER", 17f, Color.rgb(231, 184, 75), true)
        root.addView(statusText)

        clockText = mono("SIM T+00:00:00", 13f, Color.rgb(174, 187, 198))
        root.addView(clockText)
        root.addView(space(16))

        powerText = metric(root, "REACTOR POWER")
        generatorText = metric(root, "GENERATOR")
        coolantText = metric(root, "THERMAL")
        pressureText = metric(root, "PRIMARY")
        reactivityText = metric(root, "REACTIVITY")
        periodText = metric(root, "REACTOR PERIOD")

        root.addView(space(12))
        root.addView(sectionTitle("PLANT CONTROL"))

        rodValueText = mono("55.0 %", 14f, Color.WHITE)
        root.addView(controlHeader("CONTROL BANK INSERTION", rodValueText))
        root.addView(SeekBar(this).apply {
            max = 1000
            progress = 550
            setOnSeekBarChangeListener(simpleSeek { progress ->
                val insertion = progress / 1000.0
                simulator.setRodInsertion(insertion)
                rodValueText.text = String.format(Locale.US, "%.1f %%", insertion * 100.0)
                render(simulator.snapshot())
            })
        })

        turbineValueText = mono("100 %", 14f, Color.WHITE)
        root.addView(controlHeader("TURBINE LOAD DEMAND", turbineValueText))
        root.addView(SeekBar(this).apply {
            max = 80
            progress = 70
            setOnSeekBarChangeListener(simpleSeek { progress ->
                val load = (30 + progress) / 100.0
                simulator.setTurbineLoad(load)
                turbineValueText.text = String.format(Locale.US, "%.0f %%", load * 100.0)
                render(simulator.snapshot())
            })
        })

        root.addView(text("SIMULATION SPEED", 12f, Color.rgb(174, 187, 198), true))
        root.addView(buttonRow(
            button("1×") { timeScale = 1.0 },
            button("10×") { timeScale = 10.0 },
            button("60×") { timeScale = 60.0 },
        ))

        pauseButton = button("PAUSE") {
            running = !running
            pauseButton.text = if (running) "PAUSE" else "RUN"
        }
        val trip = button("TRIP") {
            simulator.trip()
            render(simulator.snapshot())
        }.apply { setTextColor(Color.rgb(255, 122, 112)) }
        val reset = button("RESET") {
            simulator.reset()
            running = true
            pauseButton.text = "PAUSE"
            render(simulator.snapshot())
        }
        root.addView(buttonRow(pauseButton, trip, reset))

        root.addView(space(16))
        root.addView(sectionTitle("MODEL STATUS"))
        root.addView(text("Neutronics  •  six-group point kinetics", 13f, Color.rgb(232, 237, 242), false))
        root.addView(text("Rod worth    •  S-curve fallback / calibration required", 13f, Color.rgb(174, 187, 198), false))
        root.addView(text("Thermal      •  lumped fuel/coolant energy balance", 13f, Color.rgb(174, 187, 198), false))
        root.addView(text("Pressure     •  reference value held; pressurizer deferred", 13f, Color.rgb(174, 187, 198), false))

        root.addView(space(18))
        root.addView(text(
            "GENERIC REDUCED-ORDER PWR • ENTERTAINMENT / EDUCATION • NOT OPERATOR TRAINING",
            10f,
            Color.rgb(130, 145, 158),
            false,
        ))

        return scroll
    }

    private fun render(state: PlantState) {
        statusText.text = when {
            state.tripped -> "REACTOR TRIP"
            state.fissionPowerMw < ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 0.02 -> "SUBCRITICAL / LOW POWER"
            else -> "AT POWER"
        }
        statusText.setTextColor(if (state.tripped) Color.rgb(255, 122, 112) else Color.rgb(231, 184, 75))
        clockText.text = "SIM T+${formatDuration(state.simulationSeconds)}"

        powerText.text = String.format(
            Locale.US,
            "%.1f %%   •   %.0f MWth",
            state.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0,
            state.fissionPowerMw,
        )
        generatorText.text = String.format(Locale.US, "%.0f MW   •   load %.0f %%", state.generatorPowerMw, state.turbineLoad * 100.0)
        coolantText.text = String.format(Locale.US, "Coolant %.1f K   •   Fuel %.0f K", state.coolantTemperatureK, state.fuelTemperatureK)
        pressureText.text = String.format(Locale.US, "%.2f MPa", state.primaryPressureMpa)
        reactivityText.text = String.format(Locale.US, "%+.1f pcm", state.totalReactivityPcm)
        periodText.text = state.reactorPeriodSeconds?.let {
            if (abs(it) > 9999.0) "> 9999 s" else String.format(Locale.US, "%+.1f s", it)
        } ?: "STABLE"
        rodValueText.text = String.format(Locale.US, "%.1f %%", state.rodInsertion * 100.0)
        turbineValueText.text = String.format(Locale.US, "%.0f %%", state.turbineLoad * 100.0)
    }

    private fun metric(parent: LinearLayout, label: String): TextView {
        parent.addView(text(label, 11f, Color.rgb(156, 177, 194), true))
        return mono("—", 19f, Color.rgb(232, 237, 242)).also {
            it.setPadding(0, 0, 0, dp(12))
            parent.addView(it)
        }
    }

    private fun sectionTitle(value: String) = text(value, 15f, Color.rgb(231, 184, 75), true).apply {
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun controlHeader(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(label, 12f, Color.rgb(174, 187, 198), true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        buttons.forEach { button ->
            addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                setMargins(dp(3), dp(6), dp(3), dp(6))
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
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    }
}
