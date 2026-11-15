package arpa.home.bc200sync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Line chart of one ride value over distance (or time). Gaps (no value) break the line. Touching
 * the chart shows the value at that point. Drawn with plain Canvas, no chart library.
 */
@SuppressLint("ViewConstructor")
class ChartView(
    ctx: Context,
    private val x: FloatArray, // increasing; distance in km or time in minutes
    private val y: FloatArray, // NaN = no value
    private val color: Int,
    private val textColor: Int,
    private val gridColor: Int,
    private val xLabel: (Float) -> String,
    private val yLabel: (Float) -> String,
) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
        this.color = this@ChartView.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = Palette.withAlpha(this@ChartView.color, 0x33)
    }
    private val grid = Paint().apply {
        strokeWidth = density
        this.color = gridColor
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * density
        this.color = textColor
    }
    private val yMin: Float
    private val yMax: Float
    private var cols = FloatArray(0) // per pixel column: average y, NaN = none
    private var touchX = -1f

    init {
        val valid = y.filter { !it.isNaN() }
        var lo = valid.minOrNull() ?: 0f
        var hi = valid.maxOrNull() ?: 1f
        if (hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        yMin = lo
        yMax = hi
        contentDescription = "Chart from ${yLabel(lo)} to ${yLabel(hi)}"
    }

    private val top get() = 18 * density
    private val bottom get() = height - 18 * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Average the samples falling into each pixel column.
        val sum = FloatArray(max(w, 1))
        val n = IntArray(sum.size)
        val x0 = x.firstOrNull() ?: 0f
        val span = max((x.lastOrNull() ?: 1f) - x0, 1e-6f)
        for (i in y.indices) {
            if (y[i].isNaN() || x[i].isNaN()) continue
            val c = ((x[i] - x0) / span * (sum.size - 1)).toInt().coerceIn(0, sum.size - 1)
            sum[c] += y[i]
            n[c]++
        }
        cols = FloatArray(sum.size) { if (n[it] > 0) sum[it] / n[it] else Float.NaN }
    }

    private fun py(v: Float) = bottom - (v - yMin) / (yMax - yMin) * (bottom - top)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        // Grid: top, middle and bottom line.
        for (g in listOf(top, (top + bottom) / 2, bottom)) c.drawLine(0f, g, w, g, grid)
        // Line and the area under it; a gap of more than a few pixels breaks both.
        val path = Path()
        val area = Path()
        var open = false
        var startX = 0f
        var lastX = 0f
        var missing = 0
        fun close() {
            if (!open) return
            c.drawPath(path, line)
            area.lineTo(lastX, bottom); area.lineTo(startX, bottom); area.close()
            c.drawPath(area, fill)
            path.reset(); area.reset()
            open = false
        }
        for (i in cols.indices) {
            val v = cols[i]
            if (v.isNaN()) {
                if (++missing > 4 * density) close()
                continue
            }
            missing = 0
            val px = i.toFloat()
            val yy = py(v)
            if (!open) {
                path.moveTo(px, yy); area.moveTo(px, bottom); area.lineTo(px, yy)
                startX = px; open = true
            } else {
                path.lineTo(px, yy); area.lineTo(px, yy)
            }
            lastX = px
        }
        close()
        // Max and min labels.
        c.drawText(yLabel(yMax), 2 * density, top - 4 * density, text)
        c.drawText(yLabel(yMin), 2 * density, height - 4 * density, text)
        val end = xLabel(x.lastOrNull() ?: 0f)
        c.drawText(end, w - text.measureText(end) - 2 * density, height - 4 * density, text)
        // Touched point: vertical line and "x · y".
        if (touchX >= 0 && cols.isNotEmpty()) {
            val i = touchX.toInt().coerceIn(0, cols.size - 1)
            c.drawLine(touchX, top, touchX, bottom, text)
            val x0 = x.firstOrNull() ?: 0f
            val xv = x0 + (x.lastOrNull()?.minus(x0) ?: 0f) * i / max(cols.size - 1, 1)
            val v = nearest(i)
            val label = xLabel(xv) + if (v.isNaN()) "" else "  ·  " + yLabel(v)
            val tw = text.measureText(label)
            c.drawText(label, (touchX - tw / 2).coerceIn(0f, max(w - tw, 0f)), top - 4 * density, text)
        }
    }

    private fun nearest(i: Int): Float {
        for (d in 0..(8 * density).toInt()) {
            cols.getOrNull(i - d)?.takeIf { !it.isNaN() }?.let { return it }
            cols.getOrNull(i + d)?.takeIf { !it.isNaN() }?.let { return it }
        }
        return Float.NaN
    }

    private var downX = 0f
    private var downY = 0f
    private var reading = false // finger moved sideways: reading values, page does not scroll

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val slop = 8 * density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; reading = false
                touchX = e.x.coerceIn(0f, width.toFloat())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(e.x - downX)
                val dy = kotlin.math.abs(e.y - downY)
                if (!reading && dx > slop && dx > dy) {
                    reading = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                touchX = e.x.coerceIn(0f, width.toFloat())
            }
            else -> touchX = -1f
        }
        invalidate()
        return true
    }
}

/** The ride's route as a line, without a map (the app has no internet access). North is up. */
@SuppressLint("ViewConstructor")
class TrackView(
    ctx: Context,
    lat: DoubleArray,
    lon: DoubleArray,
    color: Int,
    private val startColor: Int,
    private val endColor: Int,
) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val pts: List<Pair<Double, Double>> // x east, y north, in metres from the first point
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val idx = lat.indices.filter { !lat[it].isNaN() && !lon[it].isNaN() }
        val lat0 = idx.firstOrNull()?.let { lat[it] } ?: 0.0
        val lon0 = idx.firstOrNull()?.let { lon[it] } ?: 0.0
        val k = cos(Math.toRadians(lat0))
        pts = idx.map { Pair((lon[it] - lon0) * k * 111_320.0, (lat[it] - lat0) * 111_320.0) }
        contentDescription = "Route of the ride"
    }

    override fun onDraw(c: Canvas) {
        if (pts.size < 2) return
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        val pad = 12 * density
        val scale = min(
            (width - 2 * pad) / max(maxX - minX, 1.0),
            (height - 2 * pad) / max(maxY - minY, 1.0),
        )
        // Centre the route in the view.
        val offX = (width - (maxX - minX) * scale) / 2
        val offY = (height - (maxY - minY) * scale) / 2
        fun sx(p: Pair<Double, Double>) = (offX + (p.first - minX) * scale).toFloat()
        fun sy(p: Pair<Double, Double>) = (height - offY - (p.second - minY) * scale).toFloat()
        val path = Path()
        path.moveTo(sx(pts[0]), sy(pts[0]))
        // Skip points closer than half a pixel to the previous one drawn.
        var lx = sx(pts[0])
        var ly = sy(pts[0])
        for (p in pts) {
            val x = sx(p)
            val y = sy(p)
            if ((x - lx) * (x - lx) + (y - ly) * (y - ly) < 0.25f) continue
            path.lineTo(x, y)
            lx = x; ly = y
        }
        c.drawPath(path, line)
        dot.color = endColor
        c.drawCircle(sx(pts.last()), sy(pts.last()), 6 * density, dot)
        dot.color = startColor
        c.drawCircle(sx(pts[0]), sy(pts[0]), 6 * density, dot)
    }
}
