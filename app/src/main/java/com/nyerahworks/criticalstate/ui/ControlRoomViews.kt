package com.nyerahworks.criticalstate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nyerahworks.criticalstate.sim.PlantState
import kotlin.math.max
import kotlin.math.min

/**
 * Retro-industrial palette: intentionally limited, high-contrast, and closer to
 * phosphor/painted-panel instrumentation than a modern Material card stack.
 * Alarm semantics stay conventional: green normal, amber caution, red trip.
 */
object ControlPalette {
    val Background = Color.rgb(8, 11, 9)
    val Surface = Color.rgb(15, 21, 17)
    val SurfaceRaised = Color.rgb(22, 30, 24)
    val Border = Color.rgb(61, 77, 63)
    val Edge = Color.rgb(90, 108, 88)
    val Grid = Color.rgb(31, 42, 34)
    val Shadow = Color.rgb(3, 5, 4)
    val Text = Color.rgb(226, 234, 215)
    val Muted = Color.rgb(132, 147, 124)
    val Green = Color.rgb(99, 218, 113)
    val Amber = Color.rgb(235, 183, 78)
    val Red = Color.rgb(237, 91, 75)
    val Blue = Color.rgb(89, 148, 204)
    val Cyan = Color.rgb(72, 196, 183)
}

/**
 * Lightweight, allocation-free-at-draw-time panel chrome with clipped corners.
 * The 45-degree corner cuts and double edge are deliberate "16-bit control
 * console" cues without bitmap assets or shader work on the target phone.
 */
private class PixelPanelDrawable(
    fillColor: Int,
    borderColor: Int,
    edgeColor: Int,
    private val density: Float,
    private val emphasized: Boolean,
) : Drawable() {
    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = fillColor
        isAntiAlias = false
    }
    private val border = Paint().apply {
        style = Paint.Style.STROKE
        color = borderColor
        isAntiAlias = false
    }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE
        color = edgeColor
        isAntiAlias = false
    }
    private val shadow = Paint().apply {
        style = Paint.Style.STROKE
        color = ControlPalette.Shadow
        isAntiAlias = false
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val px = max(1f, density)
        val cut = max(2f, 3f * density)
        val left = b.left.toFloat() + px
        val top = b.top.toFloat() + px
        val right = b.right.toFloat() - px
        val bottom = b.bottom.toFloat() - px

        path.reset()
        path.moveTo(left + cut, top)
        path.lineTo(right - cut, top)
        path.lineTo(right, top + cut)
        path.lineTo(right, bottom - cut)
        path.lineTo(right - cut, bottom)
        path.lineTo(left + cut, bottom)
        path.lineTo(left, bottom - cut)
        path.lineTo(left, top + cut)
        path.close()

        canvas.drawPath(path, fill)
        border.strokeWidth = if (emphasized) 2f * px else px
        canvas.drawPath(path, border)

        edge.strokeWidth = px
        canvas.drawLine(left + cut + px, top + px, right - cut - px, top + px, edge)
        canvas.drawLine(left + px, top + cut + px, left + px, bottom - cut - px, edge)

        shadow.strokeWidth = px
        canvas.drawLine(left + cut + px, bottom - px, right - cut - px, bottom - px, shadow)
        canvas.drawLine(right - px, top + cut + px, right - px, bottom - cut - px, shadow)

        if (emphasized && right - left > 12f * px && bottom - top > 12f * px) {
            border.strokeWidth = px
            val inset = 3f * px
            canvas.drawRect(left + inset, top + inset, right - inset, bottom - inset, border)
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        border.alpha = alpha
        edge.alpha = alpha
        shadow.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        border.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        shadow.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

@Suppress("UNUSED_PARAMETER")
fun panelBackground(radiusDp: Float, density: Float, emphasized: Boolean = false): Drawable =
    PixelPanelDrawable(
        fillColor = if (emphasized) ControlPalette.SurfaceRaised else ControlPalette.Surface,
        borderColor = ControlPalette.Border,
        edgeColor = ControlPalette.Edge,
        density = density,
        emphasized = emphasized,
    )

fun buttonBackground(accent: Int, density: Float, selected: Boolean = false): Drawable {
    val normalFill = if (selected) blend(ControlPalette.SurfaceRaised, accent, 0.10f) else ControlPalette.SurfaceRaised
    val normalBorder = if (selected) accent else ControlPalette.Border
    val pressedFill = blend(ControlPalette.SurfaceRaised, accent, 0.23f)

    return StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            PixelPanelDrawable(pressedFill, accent, accent, density, true),
        )
        addState(
            intArrayOf(),
            PixelPanelDrawable(normalFill, normalBorder, if (selected) accent else ControlPalette.Edge, density, selected),
        )
    }
}

private fun blend(base: Int, overlay: Int, ratio: Float): Int {
    val r = ratio.coerceIn(0f, 1f)
    fun channel(a: Int, b: Int): Int = (a + (b - a) * r).toInt().coerceIn(0, 255)
    return Color.rgb(
        channel(Color.red(base), Color.red(overlay)),
        channel(Color.green(base), Color.green(overlay)),
        channel(Color.blue(base), Color.blue(overlay)),
    )
}

class MetricTile(context: Context) : LinearLayout(context) {
    private val labelView = TextView(context)
    private val valueView = TextView(context)
    private val detailView = TextView(context)
    private val accentPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = ControlPalette.Text
    }
    private var accentColor = ControlPalette.Text
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPadding((9 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        background = panelBackground(0f, density)

        labelView.apply {
            textSize = 8.5f
            setTextColor(ControlPalette.Muted)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isSingleLine = true
            letterSpacing = 0.04f
        }
        valueView.apply {
            textSize = 16.5f
            setTextColor(ControlPalette.Text)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isSingleLine = true
        }
        detailView.apply {
            textSize = 8.25f
            setTextColor(ControlPalette.Muted)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(valueView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(detailView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        accentPaint.color = accentColor
        val px = max(1f, density)
        canvas.drawRect(2f * px, 5f * px, 4f * px, height - 5f * px, accentPaint)
    }

    fun setMetric(label: String, value: String, detail: String = "", accent: Int = ControlPalette.Text) {
        if (labelView.text != label) labelView.text = label
        if (valueView.text != value) valueView.text = value
        if (detailView.text != detail) detailView.text = detail
        if (valueView.currentTextColor != accent) valueView.setTextColor(accent)
        if (accentColor != accent) {
            accentColor = accent
            invalidate()
        }
    }
}

class LevelBarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint().apply {
        color = ControlPalette.Grid
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val fill = Paint().apply {
        color = ControlPalette.Blue
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val frame = Paint().apply {
        color = ControlPalette.Border
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        isAntiAlias = false
    }
    private val marker = Paint().apply {
        color = ControlPalette.Text
        strokeWidth = max(1f, density)
        isAntiAlias = false
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
        val px = max(1f, density)
        val top = height * 0.30f
        val bottom = height * 0.70f
        val segmentCount = 16
        val gap = px
        val segmentWidth = (width - gap * (segmentCount - 1)) / segmentCount.toFloat()
        val activeSegments = (fraction * segmentCount).toInt().coerceIn(0, segmentCount)

        fill.color = when {
            alarm -> ControlPalette.Red
            fraction < 0.35f || fraction > 0.85f -> ControlPalette.Amber
            else -> ControlPalette.Blue
        }
        for (i in 0 until segmentCount) {
            val left = i * (segmentWidth + gap)
            val right = left + segmentWidth
            canvas.drawRect(left, top, right, bottom, if (i < activeSegments) fill else track)
        }
        canvas.drawRect(0f, top, width.toFloat(), bottom, frame)
        val x = width * target
        canvas.drawLine(x, top - 3f * px, x, bottom + 3f * px, marker)
    }
}

/** A cheap, fixed-buffer trend renderer intended for a low-end Android target. */
class TrendStripView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val framePaint = Paint().apply {
        color = ControlPalette.Border
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        isAntiAlias = false
    }
    private val gridPaint = Paint().apply {
        color = ControlPalette.Grid
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        isAntiAlias = false
    }
    private val linePaint = Paint().apply {
        color = ControlPalette.Cyan
        style = Paint.Style.STROKE
        strokeWidth = max(1f, 1.25f * density)
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        isAntiAlias = false
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Muted
        textSize = 8.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val latestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Text
        textSize = 8.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val values = FloatArray(240)
    private val plot = RectF()
    private val path = Path()
    private var count = 0
    private var head = 0
    private var label = "TREND"
    private var unit = ""
    private var fixedMin: Float? = null
    private var fixedMax: Float? = null

    init {
        background = panelBackground(0f, density)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 8f * density
        val top = 18f * density
        plot.set(pad, top, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plot.width() <= 0f || plot.height() <= 0f) return

        for (i in 1 until 4) {
            val y = plot.top + plot.height() * i / 4f
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
        }
        for (i in 1 until 8) {
            val x = plot.left + plot.width() * i / 8f
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        }
        canvas.drawRect(plot, framePaint)
        canvas.drawText(label, plot.left, 12f * density, textPaint)
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

        path.reset()
        var previousY = 0f
        for (i in 0 until count) {
            val x = plot.left + plot.width() * i / max(1, count - 1).toFloat()
            val normalized = ((valueAt(i) - low) / (high - low)).coerceIn(0f, 1f)
            val y = plot.bottom - plot.height() * normalized
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, previousY)
                path.lineTo(x, y)
            }
            previousY = y
        }
        canvas.drawPath(path, linePaint)
        val latest = valueAt(count - 1)
        val latestText = if (unit.isBlank()) "%.2f".format(latest) else "%.2f %s".format(latest, unit)
        canvas.drawText(latestText, plot.right - 4f * density, plot.top + 12f * density, latestPaint)
    }

    private fun valueAt(indexFromOldest: Int): Float {
        val oldest = if (count == values.size) head else 0
        return values[(oldest + indexFromOldest) % values.size]
    }
}

/**
 * Simplified process mimic: reactor -> four SGs -> turbine/generator ->
 * condenser -> feedwater return. It stays diagrammatic rather than copying a
 * literal plant P&ID, but uses the same limited retro-industrial visual grammar
 * as the rest of the console.
 */
class PlantSchematicView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val box = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val border = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = ControlPalette.Border
        isAntiAlias = false
    }
    private val grid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = ControlPalette.Grid
        isAntiAlias = false
    }
    private val line = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(2f, 2f * density)
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        isAntiAlias = false
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Text
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val subtext = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Muted
        textAlign = Paint.Align.CENTER
        textSize = 7.25f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ControlPalette.Muted
        textSize = 6.8f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val reactor = RectF()
    private val steam = RectF()
    private val turbine = RectF()
    private val condenser = RectF()
    private val boxPath = Path()
    private var centerY = 0f
    private var state: PlantState? = null

    fun setState(value: PlantState) {
        state = value
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        centerY = hf * 0.52f
        reactor.set(wf * 0.03f, hf * 0.27f, wf * 0.19f, hf * 0.77f)
        steam.set(wf * 0.27f, hf * 0.22f, wf * 0.43f, hf * 0.82f)
        turbine.set(wf * 0.52f, hf * 0.30f, wf * 0.70f, hf * 0.66f)
        condenser.set(wf * 0.78f, hf * 0.55f, wf * 0.97f, hf * 0.82f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val h = height.toFloat()
        val w = width.toFloat()

        for (i in 1 until 8) {
            val x = w * i / 8f
            canvas.drawLine(x, 0f, x, h, grid)
        }
        for (i in 1 until 4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, grid)
        }

        line.color = if (s.tripped) ControlPalette.Red else ControlPalette.Blue
        connect(canvas, reactor.right, centerY, steam.left, centerY)
        line.color = ControlPalette.Cyan
        connect(canvas, steam.right, centerY, turbine.left, centerY)
        connect(canvas, turbine.right, centerY, condenser.left, centerY)
        line.color = ControlPalette.Blue
        canvas.drawLine(condenser.centerX(), condenser.bottom, condenser.centerX(), h * 0.91f, line)
        canvas.drawLine(condenser.centerX(), h * 0.91f, steam.centerX(), h * 0.91f, line)
        canvas.drawLine(steam.centerX(), h * 0.91f, steam.centerX(), steam.bottom, line)

        drawBox(canvas, reactor, if (s.tripped) blend(ControlPalette.SurfaceRaised, ControlPalette.Red, 0.20f) else ControlPalette.SurfaceRaised)
        drawBox(canvas, steam, ControlPalette.SurfaceRaised)
        drawBox(canvas, turbine, if (s.generatorBreakerClosed) ControlPalette.SurfaceRaised else blend(ControlPalette.SurfaceRaised, ControlPalette.Amber, 0.14f))
        drawBox(canvas, condenser, ControlPalette.SurfaceRaised)

        canvas.drawText("RCS", reactor.left, 10f * density, legend)
        canvas.drawText("SECONDARY", steam.left, 10f * density, legend)
        canvas.drawText("POWER", turbine.left, 10f * density, legend)

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
        val cut = 4f * density
        boxPath.reset()
        boxPath.moveTo(rect.left + cut, rect.top)
        boxPath.lineTo(rect.right - cut, rect.top)
        boxPath.lineTo(rect.right, rect.top + cut)
        boxPath.lineTo(rect.right, rect.bottom - cut)
        boxPath.lineTo(rect.right - cut, rect.bottom)
        boxPath.lineTo(rect.left + cut, rect.bottom)
        boxPath.lineTo(rect.left, rect.bottom - cut)
        boxPath.lineTo(rect.left, rect.top + cut)
        boxPath.close()
        box.color = color
        canvas.drawPath(boxPath, box)
        canvas.drawPath(boxPath, border)
    }

    private fun connect(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(x1, y1, x2, y2, line)
        val arrow = 4f * density
        canvas.drawLine(x2, y2, x2 - arrow, y2 - arrow, line)
        canvas.drawLine(x2, y2, x2 - arrow, y2 + arrow, line)
    }
}
