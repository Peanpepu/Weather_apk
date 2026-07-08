package com.example.aemet_tiempo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Chart primitives. Direct Kotlin port of the SVG-based chart components
 * in `web_version/src/App.tsx`. The geometric constants below are kept
 * in dp so the layout looks consistent across device densities.
 */

// Layout constants — chosen so the same `currentIdx` lands at the same
// x position in every chart type. The web's reference value is `PX_PER_HOUR
// = 64`; we use a wider 72dp here so hour labels on phone screens have a
// bit more breathing room between them.
private val PX_PER_HOUR = 72.dp
private val CHART_H = 200.dp
private val CHART_PAD_TOP = 32.dp
private val CHART_PAD_BOTTOM = 44.dp
private val CHART_PAD_LEFT = 22.dp
private val CHART_PAD_RIGHT = 22.dp
private val CHART_SUB_EXTRA = 22.dp

// Gap between the chart's baseline and the hour-label text below it.
// Density-aware (kept in dp) so it's the same visual size on every phone.
private val HOUR_LABEL_OFFSET = 18.dp
private val SUB_LABEL_OFFSET = 14.dp

// Colours (kept inline to mirror the web's CSS exactly).
private val NeutralBaseline = Color(0xFFEEEEEE)
private val NowYellow = Color(0xFFF2B400)
private val DaySepColor = Color(0xFFCFD2DA)
private val HourLabel = Color(0xFF2B63FF)
private val HourLabelActive = Color(0xFF111111)
private val ValueLabel = Color(0xFF1D1D1D)
private val SubLabel = Color(0xFF4A6FB8)
private val SubLabelActive = Color(0xFF1E6CFF)
private val DayLabelColor = Color(0xFF6B7280)
private val NowLabelColor = Color(0xFFB08300)

/** One point in any chart series. */
data class ChartPoint(
    val label: String,
    val value: Double,
    val sub: String? = null,
    val dayOffset: Int? = null,
)

private data class DaySep(val xDp: Float, val label: String)

private fun collectDaySeparators(points: List<ChartPoint>, xs: List<Float>): List<DaySep> {
    if (points.size < 2) return emptyList()
    val out = ArrayList<DaySep>()
    for (i in 1 until points.size) {
        val prev = points[i - 1].dayOffset
        val cur = points[i].dayOffset
        if (prev != null && cur != null && cur != prev) {
            val label = when (cur) {
                1 -> "Mañ."
                2 -> "+2d"
                else -> "+${cur}d"
            }
            out += DaySep((xs[i - 1] + xs[i]) / 2f, label)
        }
    }
    return out
}

/* ───────────────────── Scrollable line chart (temperature) ───────────────────── */

@Composable
fun ScrollLineChart(
    points: List<ChartPoint>,
    lineColor: Color,
    fillColor: Color,
    currentIdx: Int,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        EmptyChart(modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val pxPerHour = with(density) { PX_PER_HOUR.toPx() }
    val padLeft = with(density) { CHART_PAD_LEFT.toPx() }
    val padRight = with(density) { CHART_PAD_RIGHT.toPx() }
    val padTop = with(density) { CHART_PAD_TOP.toPx() }
    val padBottom = with(density) { CHART_PAD_BOTTOM.toPx() }
    val hourLabelOffset = with(density) { HOUR_LABEL_OFFSET.toPx() }

    val widthDp = CHART_PAD_LEFT + CHART_PAD_RIGHT + PX_PER_HOUR * maxOf(1, points.size - 1)
    val widthPx = with(density) { widthDp.toPx() }
    val usableW = (widthPx - padLeft - padRight).coerceAtLeast(1f)
    val chartHpx = with(density) { CHART_H.toPx() }
    val usableH = (chartHpx - padTop - padBottom).coerceAtLeast(1f)

    val xs = points.indices.map { i ->
        if (points.size == 1) padLeft + usableW / 2f
        else padLeft + usableW * (i.toFloat() / (points.size - 1f))
    }

    val values = points.map { it.value }
    val min = values.min()
    val max = values.max()
    val span = (max - min).coerceAtLeast(1.0)
    val yMin = min - span * 0.2
    val yMax = max + span * 0.3
    val yRange = (yMax - yMin).coerceAtLeast(1.0)
    val ys = values.map { v ->
        padTop + ((1.0 - (v - yMin) / yRange) * usableH).toFloat()
    }

    val seps = remember(points) { collectDaySeparators(points, xs) }
    val scrollState = rememberScrollState()
    LaunchedEffect(currentIdx, points.size) {
        if (currentIdx in xs.indices) {
            val target = (xs[currentIdx] - padLeft).toInt().coerceAtLeast(0)
            scrollState.scrollTo(target)
        } else {
            scrollState.scrollTo(0)
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = ValueLabel.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val nowPaint = remember {
        android.graphics.Paint().apply {
            color = NowLabelColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val hourPaint = remember {
        android.graphics.Paint().apply {
            color = HourLabel.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val hourPaintActive = remember {
        android.graphics.Paint().apply {
            color = HourLabelActive.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val dayPaint = remember {
        android.graphics.Paint().apply {
            color = DayLabelColor.toArgb()
            textAlign = android.graphics.Paint.Align.LEFT
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    val valueTextSizePx = with(density) { 11.sp.toPx() }
    val hourTextSizePx = with(density) { 11.sp.toPx() }
    val hourActiveTextSizePx = with(density) { 12.sp.toPx() }
    val dayTextSizePx = with(density) { 10.sp.toPx() }

    labelPaint.textSize = valueTextSizePx
    nowPaint.textSize = dayTextSizePx
    hourPaint.textSize = hourTextSizePx
    hourPaintActive.textSize = hourActiveTextSizePx
    dayPaint.textSize = dayTextSizePx

    Box(modifier = modifier.horizontalScroll(scrollState)) {
        Canvas(
            modifier = Modifier
                .width(widthDp)
                .height(CHART_H),
        ) {
            // Baseline.
            drawLine(
                color = NeutralBaseline,
                start = Offset(padLeft, chartHpx - padBottom),
                end = Offset(widthPx - padRight, chartHpx - padBottom),
                strokeWidth = 1f,
            )

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))

            // Day separators.
            for (s in seps) {
                drawLine(
                    color = DaySepColor,
                    start = Offset(s.xDp, padTop - 12f),
                    end = Offset(s.xDp, chartHpx - padBottom),
                    strokeWidth = 1f,
                    pathEffect = dashEffect,
                )
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        s.label,
                        s.xDp + 6f,
                        padTop - 18f,
                        dayPaint,
                    )
                }
            }

            // Current-hour marker.
            if (currentIdx in xs.indices) {
                val cx = xs[currentIdx]
                drawLine(
                    color = NowYellow,
                    start = Offset(cx, padTop - 14f),
                    end = Offset(cx, chartHpx - padBottom),
                    strokeWidth = 2.5f,
                    pathEffect = dashEffect,
                )
                drawIntoCanvas {
                    it.nativeCanvas.drawText("Ahora", cx, padTop - 20f, nowPaint)
                }
            }

            // Area + line.
            val linePath = Path().apply {
                xs.forEachIndexed { i, x ->
                    if (i == 0) moveTo(x, ys[i]) else lineTo(x, ys[i])
                }
            }
            val areaPath = Path().apply {
                xs.forEachIndexed { i, x ->
                    if (i == 0) moveTo(x, ys[i]) else lineTo(x, ys[i])
                }
                lineTo(xs.last(), chartHpx - padBottom)
                lineTo(xs.first(), chartHpx - padBottom)
                close()
            }
            drawPath(areaPath, color = fillColor)
            drawPath(
                linePath,
                color = lineColor,
                style = Stroke(width = 7f, cap = StrokeCap.Round),
            )

            // Per-point dots + value labels + hour labels.
            xs.forEachIndexed { i, x ->
                val active = i == currentIdx
                val r = if (active) 8f else 5f
                drawCircle(color = if (active) lineColor else Color.White, radius = r, center = Offset(x, ys[i]))
                drawCircle(
                    color = lineColor,
                    radius = r,
                    center = Offset(x, ys[i]),
                    style = Stroke(width = 3f),
                )
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        valueFormatter(points[i].value),
                        x,
                        ys[i] - 14f,
                        labelPaint,
                    )
                    it.nativeCanvas.drawText(
                        points[i].label,
                        x,
                        chartHpx - padBottom + hourLabelOffset,
                        if (active) hourPaintActive else hourPaint,
                    )
                }
                drawLine(
                    color = Color(0xFFCCCCCC),
                    start = Offset(x, chartHpx - padBottom),
                    end = Offset(x, chartHpx - padBottom + 5f),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

/* ───────────────────── Scrollable bar chart (precipitation) ───────────────────── */

@Composable
fun ScrollBarChart(
    points: List<ChartPoint>,
    barColor: Color,
    mutedColor: Color,
    currentIdx: Int,
    valueFormatter: (Double) -> String,
    yMax: Double? = null,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        EmptyChart(modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val pxPerHour = with(density) { PX_PER_HOUR.toPx() }
    val padLeft = with(density) { CHART_PAD_LEFT.toPx() }
    val padRight = with(density) { CHART_PAD_RIGHT.toPx() }
    val padTop = with(density) { CHART_PAD_TOP.toPx() }
    val padBottom = with(density) { CHART_PAD_BOTTOM.toPx() }
    val subExtra = with(density) { CHART_SUB_EXTRA.toPx() }
    val hourLabelOffset = with(density) { HOUR_LABEL_OFFSET.toPx() }
    val subLabelOffset = with(density) { SUB_LABEL_OFFSET.toPx() }

    val hasSubs = points.any { !it.sub.isNullOrBlank() }
    val totalHpx = with(density) { (CHART_H + if (hasSubs) CHART_SUB_EXTRA else 0.dp).toPx() }
    val widthDp = CHART_PAD_LEFT + CHART_PAD_RIGHT + PX_PER_HOUR * maxOf(1, points.size - 1)
    val widthPx = with(density) { widthDp.toPx() }
    val usableW = (widthPx - padLeft - padRight).coerceAtLeast(1f)
    val chartHpx = with(density) { CHART_H.toPx() }
    val usableH = (chartHpx - padTop - padBottom).coerceAtLeast(1f)
    val baseY = totalHpx - padBottom - if (hasSubs) subExtra else 0f

    val xs = points.indices.map { i ->
        if (points.size == 1) padLeft + usableW / 2f
        else padLeft + usableW * (i.toFloat() / (points.size - 1f))
    }
    val maxY = (yMax ?: maxOf(10.0, points.maxOf { it.value })).coerceAtLeast(1.0)
    val barWidth = (pxPerHour * 0.55f).coerceAtLeast(20f)

    val seps = remember(points) { collectDaySeparators(points, xs) }
    val scrollState = rememberScrollState()
    LaunchedEffect(currentIdx, points.size) {
        if (currentIdx in xs.indices) {
            val target = (xs[currentIdx] - padLeft).toInt().coerceAtLeast(0)
            scrollState.scrollTo(target)
        } else {
            scrollState.scrollTo(0)
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = ValueLabel.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val hourPaint = remember {
        android.graphics.Paint().apply {
            color = HourLabel.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val hourPaintActive = remember {
        android.graphics.Paint().apply {
            color = HourLabelActive.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val subPaint = remember {
        android.graphics.Paint().apply {
            color = SubLabel.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val subPaintActive = remember {
        android.graphics.Paint().apply {
            color = SubLabelActive.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val dayPaint = remember {
        android.graphics.Paint().apply {
            color = DayLabelColor.toArgb()
            textAlign = android.graphics.Paint.Align.LEFT
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    labelPaint.textSize = with(density) { 11.sp.toPx() }
    hourPaint.textSize = with(density) { 11.sp.toPx() }
    hourPaintActive.textSize = with(density) { 12.sp.toPx() }
    subPaint.textSize = with(density) { 11.sp.toPx() }
    subPaintActive.textSize = with(density) { 11.sp.toPx() }
    dayPaint.textSize = with(density) { 10.sp.toPx() }

    Box(modifier = modifier.horizontalScroll(scrollState)) {
        Canvas(
            modifier = Modifier
                .width(widthDp)
                .height(CHART_H + if (hasSubs) CHART_SUB_EXTRA else 0.dp),
        ) {
            drawLine(
                color = NeutralBaseline,
                start = Offset(padLeft, baseY),
                end = Offset(widthPx - padRight, baseY),
                strokeWidth = 1f,
            )
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            for (s in seps) {
                drawLine(
                    color = DaySepColor,
                    start = Offset(s.xDp, padTop - 12f),
                    end = Offset(s.xDp, baseY),
                    strokeWidth = 1f,
                    pathEffect = dashEffect,
                )
                drawIntoCanvas {
                    it.nativeCanvas.drawText(s.label, s.xDp + 6f, padTop - 18f, dayPaint)
                }
            }
            // The "Ahora" vertical marker is intentionally omitted from the
            // precipitation chart: the bars themselves already encode the
            // current-hour value, and the dashed overlay just clutters the
            // narrow bar columns. The marker is still drawn on the
            // temperature line chart.
            points.forEachIndexed { i, p ->
                val ratio = (p.value / maxY).coerceIn(0.0, 1.0).toFloat()
                val barH = (ratio * usableH).coerceAtLeast(2f)
                val bx = xs[i] - barWidth / 2f
                val by = baseY - barH
                val active = i == currentIdx
                drawRoundRect(
                    color = if (active) barColor else mutedColor,
                    topLeft = Offset(bx, by),
                    size = Size(barWidth, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        valueFormatter(p.value),
                        xs[i],
                        by - 8f,
                        labelPaint,
                    )
                    it.nativeCanvas.drawText(
                        p.label,
                        xs[i],
                        baseY + hourLabelOffset,
                        if (active) hourPaintActive else hourPaint,
                    )
                    val sub = p.sub
                    if (!sub.isNullOrBlank()) {
                        it.nativeCanvas.drawText(
                            sub,
                            xs[i],
                            baseY + subExtra + subLabelOffset,
                            if (active) subPaintActive else subPaint,
                        )
                    }
                }
            }
        }
    }
}

/* ───────────────────── Wind cells ───────────────────── */

data class WindItem(
    val periodo: String,
    val speed: Int,
    val dir: String,
    val dayOffset: Int?,
)

@Composable
fun WindScroll(
    items: List<WindItem>,
    currentIdx: Int,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyChart(modifier = modifier)
        return
    }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val cellWidthDp = 74.dp
    val cellWidthPx = with(density) { cellWidthDp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    LaunchedEffect(currentIdx, items.size) {
        if (currentIdx in items.indices) {
            scrollState.scrollTo((currentIdx * (cellWidthPx + gapPx)).toInt().coerceAtLeast(0))
        } else {
            scrollState.scrollTo(0)
        }
    }

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { i, it ->
            val prev = items.getOrNull(i - 1)
            val isDayBreak = prev != null && prev.dayOffset != null && it.dayOffset != null && prev.dayOffset != it.dayOffset
            val cellColor = if (i == currentIdx) Color(0xFFFFF5D6) else Color(0xFFF7F9FC)
            val borderColor = if (i == currentIdx) NowYellow else Color(0xFFECEEF3)
            val hourTextColor = if (i == currentIdx) Color(0xFFB08300) else HourLabel
            if (isDayBreak) {
                Spacer(modifier = Modifier.width(6.dp))
            }
            Column(
                modifier = Modifier
                    .width(cellWidthDp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cellColor)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fmtHourLabel(it.periodo),
                    color = hourTextColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // Arrow shows wind's "going to" direction (=opposite of where
                // it comes from), so add 180° to the meteorological angle.
                val angle = dirToDeg(it.dir) + 180f
                Text(
                    text = "↑",
                    fontSize = 22.sp,
                    color = Color(0xFF555555),
                    modifier = Modifier.rotate(angle),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = it.dir.ifBlank { "—" },
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = it.speed.toString(),
                        color = Color(0xFF111111),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "km/h",
                        color = Color(0xFF777777),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/* ───────────────────── Empty state ───────────────────── */

@Composable
private fun EmptyChart(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Sin datos",
            color = Color(0xFF888888),
            fontSize = 13.sp,
        )
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

