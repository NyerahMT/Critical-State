package com.nyerahworks.criticalstate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nyerahworks.criticalstate.sim.PlantState
import kotlin.math.max
import kotlin.math.min

object ControlPalette {
    val Background = Color.rgb(7, 11, 15)
    val Surface = Color.rgb(15, 22, 29)
    val SurfaceRaised = Color.rgb(20, 29, 38)
    val Border = Color.rgb(45, 61, 75)
    val Text = Color.rgb(232, 239, 244)
    val Muted = Color.rgb(133, 151, 164)
    val Green = Color.rgb(70, 201, 128)
    val Amber = Color.rgb(236, 184, 75)
    val Red = Color.rgb(247, 104, 96)
    val Blue = Color.rgb(77, 155, 230)
    val Cyan = Color.rgb(70, 203, 215)
}

fun panelBackground(radiusDp: Float, density: Float, emphasized: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(if (emphasized) ControlPalette.SurfaceRaised else ControlPalette.Surface)
        setStroke(max(1, density.toInt()), ControlPalette.Border)
    }

class MetricTile(context: Context) : LinearLayout(context) {
    private val labelView = TextView(context)
    private val valueView = TextView(context)
    private val detailView = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
        background = panelBackground(8f, d)

        labelView.apply {
            textSize = 9f
            setTextColor(ControlPalette.Muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
        }
        valueView.apply {
            textSize = 17f
            setTextColor(ControlPalette.Text)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        detailView.apply {
            textSize = 8.5f
            setTextColor(ControlPalette.Muted)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(valueView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(detailView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setMetric(label: String, value: String, detail: String = "", accent: Int = ControlPalette.Text) {
        if (labelView.text != label) labelView.text = label
        if (valueView.text != value) valueView.text = value
        if (detailView.text != detail) detailView.text = detail
        if (valueView.currentTextColor != accent) valueView.setTextColor(accent)
    }
}

class LevelBarView(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 39, 48) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ControlPalette.Blue }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Text
        strokeWidth = resources.displayMetrics.density
    }
    private var fraction = 0.5f
    private var target = 0.5f
    private var alarm = false

    fun setLevel(value: Double, reference: Double = 0.65, alarmed: Boolean = false) {
        fraction = value.toFloat().coerceIn(0f, 1f)
        target = reference.toFloat().coerceIn(0f, 1f)
        alarm = alarmed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = height * 0.28f
        val rect = RectF(0f, height * 0.28f, width.toFloat(), height * 0.72f)
        canvas.drawRoundRect(rect, r, r, track)
        fill.color = when {
            alarm -> ControlPalette.Red
            fraction < 0.35f || fraction > 0.85f -> ControlPalette.Amber
            else -> ControlPalette.Blue
        }
        val filled = RectF(rect.left, rect.top, rect.left + rect.width() * fraction, rect.bottom)
        canvas.drawRoundRect(filled, r, r, fill)
        val x = rect.left + rect.width() * target
        canvas.drawLine(x, rect.top - height * 0.12f, x, rect.bottom + height * 0.12f, marker)
    }
}

/** A cheap, fixed-buffer trend renderer intended for a low-end Android target. */
class TrendStripView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Border
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Muted
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val values = FloatArray(240)
    private var count = 0
    private var head = 0
    private var label = "TREND"
    private var unit = ""
    private var fixedMin: Float? = null
    private var fixedMax: Float? = null

    init {
        setBackgroundColor(ControlPalette.Surface)
    }

    fun configure(label: String, unit: String, accent: Int, minValue: Float? = null, maxValue: Float? = null) {
        this.label = label
        this.unit = unit
        this.fixedMin = minValue
        this.fixedMax = maxValue
        linePaint.color = accent
        invalidate()
    }

    fun add(value: Double) {
        if (!value.isFinite()) return
        values[head] = value.toFloat()
        head = (head + 1) % values.size
        if (count < values.size) count++
        invalidate()
    }

    fun clear() {
        count = 0
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f * density
        val top = 18f * density
        val plot = RectF(pad, top, width - pad, height - pad)
        canvas.drawRect(plot, framePaint)
        canvas.drawText(label, pad, 12f * density, textPaint)
        if (count < 2) return

        var low = fixedMin ?: Float.POSITIVE_INFINITY
        var high = fixedMax ?: Float.NEGATIVE_INFINITY
        if (fixedMin == null || fixedMax == null) {
            for (i in 0 until count) {
                val v = valueAt(i)
                if (fixedMin == null) low = min(low, v)
                if (fixedMax == null) high = max(high, v)
            }
        }
        if (!low.isFinite()) low = 0f
        if (!high.isFinite()) high = low + 1f
        if (high - low < 1.0e-6f) high = low + 1f
        val margin = if (fixedMin == null || fixedMax == null) (high - low) * 0.08f else 0f
        low -= margin
        high += margin

        val path = Path()
        for (i in 0 until count) {
            val x = plot.left + plot.width() * i / max(1, count - 1).toFloat()
            val normalized = ((valueAt(i) - low) / (high - low)).coerceIn(0f, 1f)
            val y = plot.bottom - plot.height() * normalized
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        val latest = valueAt(count - 1)
        val latestText = if (unit.isBlank()) "%.2f".format(latest) else "%.2f %s".format(latest, unit)
        canvas.drawText(latestText, plot.left + 4f * density, plot.top + 12f * density, textPaint)
    }

    private fun valueAt(indexFromOldest: Int): Float {
        val oldest = if (count == values.size) head else 0
        return values[(oldest + indexFromOldest) % values.size]
    }
}

/**
 * Simplified process mimic: reactor -> four SGs -> turbine/generator ->
 * condenser -> feedwater return. It is intentionally diagrammatic rather than
 * a literal plant piping drawing so the whole operating chain is readable on a
 * phone at a glance.
 */
class PlantSchematicView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ControlPalette.Border
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Text
        textAlign = Paint.Align.CENTER
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtext = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Muted
        textAlign = Paint.Align.CENTER
        textSize = 7.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private var state: PlantState? = null

    fun setState(value: PlantState) {
        state = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val h = height.toFloat()
        val w = width.toFloat()
        val cy = h * 0.52f
        val reactor = RectF(w * 0.03f, h * 0.27f, w * 0.19f, h * 0.77f)
        val steam = RectF(w * 0.27f, h * 0.22f, w * 0.43f, h * 0.82f)
        val turbine = RectF(w * 0.52f, h * 0.30f, w * 0.70f, h * 0.66f)
        val condenser = RectF(w * 0.78f, h * 0.55f, w * 0.97f, h * 0.82f)

        line.color = if (s.tripped) ControlPalette.Red else ControlPalette.Blue
        connect(canvas, reactor.right, cy, steam.left, cy)
        line.color = ControlPalette.Cyan
        connect(canvas, steam.right, cy, turbine.left, cy)
        connect(canvas, turbine.right, cy, condenser.left, cy)
        line.color = ControlPalette.Blue
        canvas.drawLine(condenser.centerX(), condenser.bottom, condenser.centerX(), h * 0.92f, line)
        canvas.drawLine(condenser.centerX(), h * 0.92f, steam.centerX(), h * 0.92f, line)
        canvas.drawLine(steam.centerX(), h * 0.92f, steam.centerX(), steam.bottom, line)

        drawBox(canvas, reactor, if (s.tripped) ControlPalette.Red else ControlPalette.SurfaceRaised)
        drawBox(canvas, steam, ControlPalette.SurfaceRaised)
        drawBox(canvas, turbine, if (s.generatorBreakerClosed) ControlPalette.SurfaceRaised else Color.rgb(35, 31, 24))
        drawBox(canvas, condenser, ControlPalette.SurfaceRaised)

        canvas.drawText("REACTOR", reactor.centerX(), reactor.centerY() - 5f * density, text)
        canvas.drawText("%.0f%%".format(s.fissionPowerMw / 3411.0 * 100.0), reactor.centerX(), reactor.centerY() + 9f * density, subtext)
        canvas.drawText("4 × SG", steam.centerX(), steam.centerY() - 5f * density, text)
        canvas.drawText("%.2f MPa".format(s.mainSteamPressureMpa), steam.centerX(), steam.centerY() + 9f * density, subtext)
        canvas.drawText("TURBINE", turbine.centerX(), turbine.centerY() - 5f * density, text)
        canvas.drawText("%.0f MW".format(s.generatorPowerMw), turbine.centerX(), turbine.centerY() + 9f * density, subtext)
        canvas.drawText("COND", condenser.centerX(), condenser.centerY() - 4f * density, text)
        canvas.drawText("%.1f kPa".format(s.condenserPressureKpa), condenser.centerX(), condenser.centerY() + 9f * density, subtext)
    }

    private fun drawBox(canvas: Canvas, rect: RectF, color: Int) {
        box.color = color
        canvas.drawRoundRect(rect, 7f * density, 7f * density, box)
        canvas.drawRoundRect(rect, 7f * density, 7f * density, border)
    }

    private fun connect(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(x1, y1, x2, y2, line)
        val arrow = 4f * density
        canvas.drawLine(x2, y2, x2 - arrow, y2 - arrow, line)
        canvas.drawLine(x2, y2, x2 - arrow, y2 + arrow, line)
    }
}
