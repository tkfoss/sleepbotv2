package com.sleepbot.app.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.sleepbot.app.R
import com.sleepbot.app.ui.theme.SB
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Chart kinds of the legacy GraphView (type ids in brackets). */
enum class GraphType {
    /** [0] daily duration line with optimal baseline, dashed gaps, markers, optional ratings. */
    TREND,
    /** [1,2,3] vertical bars with markers (LENGTH / SLEEP / WAKE tallies). */
    BARS,
    /** [4] per-day sleep intervals, 00:00 at top. */
    PATTERN,
    /** [6] smoothed movement line, no markers. */
    SMOOTH,
    /** [7] sound bars, optional highlighted slot range. */
    SOUND,
}

/** One sleep interval on the PATTERN chart. Hours are 0..24 within that day. */
data class PatternSegment(val dayIndex: Int, val startHour: Float, val endHour: Float)

data class GraphSpec(
    val type: GraphType,
    val title: String = "",
    val xLabels: List<String>,
    val yLabels: List<String>,
    val values: FloatArray = FloatArray(0),
    val max: Float = 12f,
    val diff: Float = 12f,
    val ratings: FloatArray? = null,
    /** TREND only: draw the dotted goal line at this many hours. */
    val optimal: Float? = null,
    val disconnectedLines: Boolean = true,
    val markers: Boolean = true,
    val pattern: List<PatternSegment> = emptyList(),
    val titleCentered: Boolean = false,
    val titleSize: Dp = 24.dp,
    val background: Color = SB.PlotBg,
    val lineColor: Color = SB.GraphLine,
    /** SOUND: slot indices drawn in the highlight colour (clip being played). */
    val highlight: IntRange? = null,
    val yFade: Boolean = type != GraphType.SMOOTH && type != GraphType.SOUND,
)

/**
 * Compose port of the 3.2.8 `views/GraphView`. Legacy geometry was in raw px on an xhdpi (2x)
 * reference device; [u] converts those constants so charts look the same at any density.
 */
@Composable
fun GraphView(spec: GraphSpec, modifier: Modifier = Modifier) {
    val marker = ImageBitmap.imageResource(R.drawable.graphmarker)
    val context = LocalContext.current
    val thin = remember { ResourcesCompat.getFont(context, R.font.roboto_thin) }
    Canvas(modifier) {
        drawGraph(spec, marker, thin)
    }
}

private fun DrawScope.drawGraph(spec: GraphSpec, marker: ImageBitmap, thin: android.graphics.Typeface?) {
    val density = this.density
    val u = density / 2f
    val w = size.width
    val h = size.height
    val contentH = h - 50 * u
    val contentW = w - 50 * u
    val left = 30 * u
    val n = maxOf(spec.xLabels.size, spec.values.size, 2)
    val px = FloatArray(n) { i -> left + i * contentW / (n - 1) }
    val l = spec.yLabels.size
    val yStep = if (l > 1) floor(contentH / (l - 1)) else contentH
    val yx = FloatArray(l) { i -> 16 * u + i * yStep }
    val bot = h - 30 * u
    fun yOf(v: Float, max: Float = spec.max, diff: Float = spec.diff): Float =
        20 * u + contentH - (min(v, max) / (if (diff == 0f) 1f else diff)) * contentH
    val stroke = 3 * u + (2 * density).toInt()

    drawRect(spec.background)

    when (spec.type) {
        GraphType.TREND -> {
            spec.optimal?.let { opt ->
                val hour = 12 - opt
                val hi = hour.roundToInt().coerceIn(0, l - 1)
                val y = if (hi > hour && hi > 0) (yx[hi] + yx[hi - 1]) / 2 else yx[hi]
                drawLine(SB.Baseline, Offset(left, y), Offset(w - 10 * u, y), strokeWidth = 5 * u,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * u, 16 * u)))
            }
            drawTrend(spec, px, { yOf(it) }, bot, stroke, u)
            spec.ratings?.let { r -> drawRatings(r, px, { yOf(it, 12f, 12f) }, stroke, u) }
            if (spec.markers) drawMarkers(spec.values, px, { yOf(it) }, bot, marker)
        }
        GraphType.BARS -> {
            spec.values.forEachIndexed { i, v ->
                val y = yOf(v)
                if (y < bot) drawLine(spec.lineColor, Offset(px[i], bot), Offset(px[i], y - 2 * u), strokeWidth = stroke)
            }
            if (spec.markers) drawMarkers(spec.values, px, { yOf(it) }, bot, marker)
        }
        GraphType.PATTERN -> {
            var i = 2
            while (i < l - 1) {
                drawLine(SB.Baseline, Offset(left, yx[i]), Offset(w - 10 * u, yx[i]), strokeWidth = 4 * u)
                i += 3
            }
            val f = contentH / 24f
            spec.pattern.forEach { s ->
                if (s.dayIndex !in 0 until n) return@forEach
                val x = px[s.dayIndex]
                val y1 = 20 * u + contentH - (24 - s.startHour) * f - 2 * u
                val y2 = 20 * u + contentH - (24 - s.endHour) * f - 2 * u
                drawLine(spec.lineColor, Offset(x, y1), Offset(x, y2), strokeWidth = 7 * u, cap = StrokeCap.Butt)
            }
        }
        GraphType.SMOOTH -> {
            val v = spec.values
            if (v.isNotEmpty()) {
                val path = Path()
                path.moveTo(px[0], yOf(v[0]))
                for (i in 1 until v.size) {
                    val x0 = px[i - 1]; val y0 = yOf(v[i - 1])
                    val x1 = px[i]; val y1 = yOf(v[i])
                    path.quadraticTo(x0, y0, (x0 + 3 * x1) / 4, (y0 + 3 * y1) / 4)
                }
                path.lineTo(px[v.size - 1], yOf(v[v.size - 1]))
                drawPath(path, spec.lineColor, style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
            }
        }
        GraphType.SOUND -> {
            spec.values.forEachIndexed { i, v ->
                val y = yOf(v)
                if (y < bot) {
                    val c = if (spec.highlight?.contains(i) == true) SB.Highlighted else spec.lineColor
                    drawLine(c, Offset(px[i], bot), Offset(px[i], y + 3 * u), strokeWidth = stroke)
                }
            }
        }
    }

    // Title, x labels, y-fade, y labels (drawn on top, as in onDraw).
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        if (spec.title.isNotEmpty()) {
            paint.typeface = thin
            paint.color = SB.GraphTitle.toArgb()
            paint.textSize = spec.titleSize.toPx()
            if (spec.titleCentered) {
                paint.textAlign = android.graphics.Paint.Align.CENTER
                nc.drawText(spec.title, contentW / 2 + left, 25 * density + (spec.titleSize.toPx() - 24 * density).coerceAtLeast(0f), paint)
            } else {
                paint.textAlign = android.graphics.Paint.Align.LEFT
                nc.drawText(spec.title, 40 * density, 25 * density, paint)
            }
        }
        paint.typeface = null
        paint.textSize = 10 * density
        paint.color = SB.GraphX.toArgb()
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val nonEmpty = spec.xLabels.count { it.isNotEmpty() }
        val mod = maxOf(1, ceil(nonEmpty / 13.0).toInt())
        var k = 0
        spec.xLabels.forEachIndexed { i, s ->
            if (s.isEmpty() || i >= n) return@forEachIndexed
            if (k % mod == 0) nc.drawText(s, px[i], h - 10 * u, paint)
            k++
        }
        if (spec.yFade) {
            val fw = 40 * density
            nc.drawRect(0f, 0f, fw, h, android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, fw, 0f, 0xCD020C1E.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
            })
        }
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = android.graphics.Paint.Align.LEFT
        spec.yLabels.forEachIndexed { i, s -> if (s.isNotEmpty()) nc.drawText(s, 10 * u, yx[i] + 3 * u + 4 * u, paint) }
    }
}

private fun DrawScope.drawTrend(spec: GraphSpec, px: FloatArray, yOf: (Float) -> Float, bot: Float, stroke: Float, u: Float) {
    val v = spec.values
    if (v.isEmpty()) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(8 * u, 16 * u))
    fun y(i: Int) = yOf(v[i])
    fun present(i: Int) = y(i) < bot
    var lastValid = -1
    for (i in v.indices) {
        if (!present(i)) continue
        if (lastValid >= 0) {
            val a = Offset(px[lastValid], y(lastValid))
            val b = Offset(px[i], y(i))
            if (lastValid == i - 1) {
                drawLine(spec.lineColor, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
            } else if (spec.disconnectedLines) {
                drawLine(spec.lineColor, a, b, strokeWidth = stroke, pathEffect = dash)
            }
        } else if (i > 0 && spec.disconnectedLines) {
            // Legacy: a dashed lead-in from the bottom-left when the first day is empty.
            drawLine(spec.lineColor, Offset(px[0], bot), Offset(px[i], y(i)), strokeWidth = stroke, pathEffect = dash)
        }
        val isolated = (i == 0 || !present(i - 1)) && (i == v.size - 1 || !present(i + 1))
        if (isolated && !spec.markers) drawCircle(spec.lineColor, stroke, Offset(px[i], y(i)))
        lastValid = i
    }
}

private fun DrawScope.drawRatings(r: FloatArray, px: FloatArray, yOf: (Float) -> Float, stroke: Float, u: Float) {
    fun y(i: Int) = yOf(r[i])
    fun present(i: Int) = i in r.indices && r[i] > 0f
    for (i in r.indices) {
        if (!present(i)) continue
        if (present(i - 1)) {
            drawLine(SB.RatingOverlay, Offset(px[i - 1], y(i - 1)), Offset(px[i], y(i)), strokeWidth = stroke, cap = StrokeCap.Round)
        } else if (!present(i + 1)) {
            drawCircle(SB.RatingOverlay, 3 * u + stroke / 2, Offset(px[i], y(i)))
        }
    }
}

private fun DrawScope.drawMarkers(v: FloatArray, px: FloatArray, yOf: (Float) -> Float, bot: Float, marker: ImageBitmap) {
    if (v.size > 30) return
    val mw = 13.dp.toPx()
    v.indices.forEach { i ->
        val y = yOf(v[i])
        if (y < bot) {
            drawImage(
                marker,
                srcSize = IntSize(marker.width, marker.height),
                dstOffset = IntOffset((px[i] - mw / 2).roundToInt(), (y - mw / 2).roundToInt()),
                dstSize = IntSize(mw.roundToInt(), mw.roundToInt()),
            )
        }
    }
}
