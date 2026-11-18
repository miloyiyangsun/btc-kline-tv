package com.milolab.btctv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.indicators.IndicatorLevel
import com.milolab.btctv.data.indicators.IndicatorResult
import com.milolab.btctv.data.indicators.IndicatorSeries
import com.milolab.btctv.data.indicators.IndicatorSpec
import com.milolab.btctv.data.indicators.IndicatorType
import com.milolab.btctv.data.indicators.LevelStyle
import com.milolab.btctv.data.indicators.SeriesStyle
import com.milolab.btctv.data.indicators.ValueRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun PriceChart(
    candles: List<Candle>,
    style: ChartStyle,
    showVolume: Boolean,
    tf: TF,
    accent: Color,
    up: Color,
    down: Color,
    s: Float,
    priceDecimals: Int = 2,
    modifier: Modifier = Modifier,
    visibleCount: Int = candles.size,
    indicatorSettings: IndicatorSettings = IndicatorSettings.DEFAULT,
    indicatorResults: Map<String, IndicatorResult> = emptyMap()
) {
    val measurer = rememberTextMeasurer(cacheSize = 192)
    var cursor by remember { mutableStateOf<Int?>(null) }
    val padRight = (74f * s).dp

    val overlays = remember(indicatorSettings) {
        indicatorSettings.enabledSpecs(IndicatorType.OVERLAY)
    }
    val oscillators = remember(indicatorSettings) {
        indicatorSettings.enabledSpecs(IndicatorType.OSCILLATOR).take(IndicatorSettings.MAX_OSCILLATORS)
    }
    val levels = remember(indicatorSettings) {
        indicatorSettings.enabledSpecs(IndicatorType.LEVELS)
    }
    val timeFormat = remember(tf) {
        SimpleDateFormat(tf.axisPattern, Locale.getDefault())
    }
    val tooltipFormat = remember(tf) {
        SimpleDateFormat(tf.tooltipPattern, Locale.getDefault())
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(candles.size, visibleCount, s) {
                    fun hit(x: Float): Int? {
                        val count = visibleCount.coerceIn(2, candles.size)
                        if (candles.size < 2) return null
                        val right = size.width - padRight.toPx()
                        if (right <= 0f) return null
                        return (x / right * count).toInt().coerceIn(0, count - 1)
                    }
                    detectTapGestures(
                        onTap = { off -> cursor = hit(off.x) },
                        onLongPress = { cursor = null }
                    )
                }
                .pointerInput(candles.size, visibleCount, s) {
                    fun hit(x: Float): Int? {
                        val count = visibleCount.coerceIn(2, candles.size)
                        if (candles.size < 2) return null
                        val right = size.width - padRight.toPx()
                        if (right <= 0f) return null
                        return (x / right * count).toInt().coerceIn(0, count - 1)
                    }
                    detectDragGestures(
                        onDragEnd = { cursor = null },
                        onDragCancel = { cursor = null },
                        onDragStart = { off -> cursor = hit(off.x) },
                        onDrag = { change, _ ->
                            change.consume()
                            cursor = hit(change.position.x)
                        }
                    )
                }
        ) {
            drawChart(
                candles = candles,
                style = style,
                showVolume = showVolume,
                accent = accent,
                up = up,
                down = down,
                measurer = measurer,
                cursor = cursor,
                s = s,
                priceDecimals = priceDecimals,
                visibleCount = visibleCount,
                overlays = overlays,
                oscillators = oscillators,
                levels = levels,
                results = indicatorResults,
                timeFormat = timeFormat,
                tooltipFormat = tooltipFormat
            )
        }
    }
}

private data class Pane(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

private fun DrawScope.drawChart(
    candles: List<Candle>,
    style: ChartStyle,
    showVolume: Boolean,
    accent: Color,
    up: Color,
    down: Color,
    measurer: TextMeasurer,
    cursor: Int?,
    s: Float,
    priceDecimals: Int,
    visibleCount: Int,
    overlays: List<IndicatorSpec>,
    oscillators: List<IndicatorSpec>,
    levels: List<IndicatorSpec>,
    results: Map<String, IndicatorResult>,
    timeFormat: SimpleDateFormat,
    tooltipFormat: SimpleDateFormat
) {
    if (candles.size < 2) return
    val total = candles.size
    val count = visibleCount.coerceIn(2, total)
    val start = total - count

    val padRight = (74f * s).dp.toPx()
    val padTop = (12f * s).dp.toPx()
    val padBottom = (24f * s).dp.toPx()
    val plotLeft = 0f
    val plotRight = size.width - padRight
    if (plotRight <= 0f) return

    val contentBottom = size.height - padBottom
    val volH = if (showVolume) size.height * 0.11f else 0f
    val volGap = if (showVolume) (12f * s).dp.toPx() else 0f
    val volTop = if (showVolume) contentBottom - volH else contentBottom

    val oscCount = oscillators.size
    val oscPaneH = when (oscCount) {
        1 -> size.height * 0.22f
        2 -> size.height * 0.18f
        3 -> size.height * 0.15f
        else -> 0f
    }
    val oscGap = if (oscCount > 0) (10f * s).dp.toPx() else 0f

    var cursorY = if (showVolume) volTop - volGap else contentBottom
    val oscPanes = ArrayList<Pane>(oscCount)
    if (oscCount > 0) {
        val rects = Array(oscCount) { Pane(0f, 0f) }
        var bottom = cursorY
        for (i in oscCount - 1 downTo 0) {
            val top = bottom - oscPaneH
            rects[i] = Pane(top, bottom)
            bottom = top - oscGap
        }
        oscPanes.addAll(rects)
        cursorY = bottom
    }
    val priceTop = padTop
    val priceBottom = cursorY
    if (priceBottom <= priceTop + 8f) return

    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (i in 0 until count) {
        val c = candles[start + i]
        lo = min(lo, c.l)
        hi = max(hi, c.h)
    }
    if (hi <= lo) hi = lo + 1.0
    val padding = (hi - lo) * 0.07
    lo -= padding
    hi += padding
    val span = (hi - lo).toFloat()

    fun yOf(p: Double): Float =
        priceBottom - ((p - lo) / span).toFloat() * (priceBottom - priceTop)

    val plotW = plotRight - plotLeft
    val slot = plotW / count
    // K线很少时（例如年线只有 9 根）slot 会非常大，给蜡烛宽度封顶，避免撑满全屏
    val bodyW = min(max(slot * 0.66f, 1f), (24f * s).dp.toPx())
    val xOf: (Int) -> Float = { i -> plotLeft + (i + 0.5f) * slot }

    val axisStyle = TextStyle(fontSize = (12f * s).sp, color = C.Dimmer, fontFeatureSettings = "tnum")
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f * s, 7f * s), 0f)

    // 横向网格 + 右侧价格刻度
    for (i in 0..4) {
        val y = priceTop + (priceBottom - priceTop) * i / 4f
        drawLine(C.Grid.copy(alpha = 0.5f), Offset(plotLeft, y), Offset(plotRight, y), 1f)
        val p = hi - (hi - lo) * i / 4.0
        val layout = measurer.measure(AnnotatedString(fmtAxis(p, priceDecimals)), axisStyle)
        drawText(
            layout,
            topLeft = Offset(
                plotRight + (10f * s).dp.toPx(),
                (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
            )
        )
    }

    val overlayResults = overlays.mapNotNull { spec -> results[spec.id]?.let { spec to it } }
    val levelResults = levels.mapNotNull { spec -> results[spec.id]?.let { spec to it } }

    // 横向价格带（价值区等）
    clipRect(plotLeft, priceTop, plotRight, priceBottom) {
        for ((_, result) in overlayResults + levelResults) {
            for (region in result.regions) {
                if (!region.low.isFinite() || !region.high.isFinite()) continue
                val y1 = yOf(region.low)
                val y2 = yOf(region.high)
                if (max(y1, y2) < priceTop || min(y1, y2) > priceBottom) continue
                drawRect(
                    color = Color(region.color).copy(alpha = region.alpha.coerceIn(0.02f, 0.5f)),
                    topLeft = Offset(plotLeft, min(y1, y2)),
                    size = Size(plotW, abs(y2 - y1))
                )
            }
        }
    }

    // 纵向色带（布林带、通道、云图）
    clipRect(plotLeft, priceTop, plotRight, priceBottom) {
        for ((_, result) in overlayResults) {
            for (zone in result.zones) {
                val upper = result.series(zone.upperKey) ?: continue
                val lower = result.series(zone.lowerKey) ?: continue
                var runStart = -1
                var runEnd = -1
                fun flush() {
                    if (runStart < 0 || runEnd < runStart) return
                    val path = Path()
                    var started = false
                    for (i in runStart..runEnd) {
                        val v = upper.valueAt(start + i)
                        if (!v.isFinite()) continue
                        val x = xOf(i)
                        val y = yOf(v)
                        if (!started) {
                            path.moveTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    if (!started) return
                    for (i in runEnd downTo runStart) {
                        val v = lower.valueAt(start + i)
                        if (!v.isFinite()) continue
                        path.lineTo(xOf(i), yOf(v))
                    }
                    path.close()
                    drawPath(path, Color(zone.color).copy(alpha = zone.alpha.coerceIn(0.02f, 0.5f)))
                }

                for (i in 0 until count) {
                    val ok = upper.valueAt(start + i).isFinite() && lower.valueAt(start + i).isFinite()
                    if (ok) {
                        if (runStart < 0) runStart = i
                        runEnd = i
                    } else {
                        flush()
                        runStart = -1
                        runEnd = -1
                    }
                }
                flush()
            }
        }
    }

    // 成交量
    if (showVolume) {
        var maxV = 1e-9
        for (i in 0 until count) maxV = max(maxV, candles[start + i].v)
        for (i in 0 until count) {
            val c = candles[start + i]
            val x = xOf(i)
            val vh = ((c.v / maxV) * (contentBottom - volTop)).toFloat()
            val col = if (c.c >= c.o) up else down
            drawRect(
                color = col.copy(alpha = 0.20f),
                topLeft = Offset(x - bodyW / 2f, contentBottom - vh),
                size = Size(bodyW, vh)
            )
        }
    }

    // K 线 / 折线 / 面积
    when (style) {
        ChartStyle.CANDLE -> {
            val wick = max(1f, 1.4f * s)
            for (i in 0 until count) {
                val c = candles[start + i]
                val x = xOf(i)
                val col = if (c.c >= c.o) up else down
                drawLine(col.copy(alpha = 0.9f), Offset(x, yOf(c.h)), Offset(x, yOf(c.l)), wick, StrokeCap.Round)
                val yOpen = yOf(c.o)
                val yClose = yOf(c.c)
                val top = min(yOpen, yClose)
                val hgt = max(max(yOpen, yClose) - top, 1.2f * s)
                drawRoundRect(
                    color = col,
                    topLeft = Offset(x - bodyW / 2f, top),
                    size = Size(bodyW, hgt),
                    cornerRadius = CornerRadius(bodyW * 0.2f, bodyW * 0.2f)
                )
            }
        }

        ChartStyle.LINE, ChartStyle.AREA -> {
            val line = Path()
            for (i in 0 until count) {
                val c = candles[start + i]
                val y = yOf(c.c)
                if (i == 0) line.moveTo(xOf(i), y) else line.lineTo(xOf(i), y)
            }
            if (style == ChartStyle.AREA) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xOf(count - 1), priceBottom)
                    lineTo(xOf(0), priceBottom)
                    close()
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.01f)),
                        startY = priceTop,
                        endY = priceBottom
                    )
                )
            }
            drawPath(line, accent.copy(alpha = 0.16f), style = Stroke(width = 7f * s, cap = StrokeCap.Round))
            drawPath(line, accent, style = Stroke(width = 2f * s, cap = StrokeCap.Round))
        }
    }

    // 主图指标序列（在 K 线之上，NaN 断线）
    clipRect(plotLeft, priceTop, plotRight, priceBottom) {
        for ((_, result) in overlayResults) {
            for (series in result.series) {
                drawIndicatorSeries(
                    series = series,
                    start = start,
                    count = count,
                    plotLeft = plotLeft,
                    slot = slot,
                    yOf = { v -> yOf(v) },
                    baselineY = priceBottom,
                    s = s,
                    lo = lo,
                    hi = hi
                )
            }
        }
    }

    // 最新价水平线 + 右侧价签
    val last = candles.last().c
    val yLast = yOf(last)
    drawLine(accent.copy(alpha = 0.13f), Offset(plotLeft, yLast), Offset(plotRight, yLast), 7f * s)
    drawLine(accent.copy(alpha = 0.8f), Offset(plotLeft, yLast), Offset(plotRight, yLast), 1.2f * s, pathEffect = dash)
    drawCircle(accent, 3f * s, Offset(plotRight - 2f * s, yLast))

    run {
        val tagStyle = TextStyle(
            fontSize = (12f * s).sp,
            color = Color(0xFF0A0C12),
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        )
        val layout = measurer.measure(AnnotatedString(fmtPrice(last, priceDecimals)), tagStyle)
        val tagW = layout.size.width + (16f * s).dp.toPx()
        val tagH = (22f * s).dp.toPx()
        val tagTop = (yLast - tagH / 2f).coerceIn(0f, size.height - tagH)
        drawRoundRect(
            color = accent,
            topLeft = Offset(plotRight + (6f * s).dp.toPx(), tagTop),
            size = Size(tagW, tagH),
            cornerRadius = CornerRadius(5f * s)
        )
        drawText(
            layout,
            topLeft = Offset(
                plotRight + (6f * s).dp.toPx() + (8f * s).dp.toPx(),
                tagTop + (tagH - layout.size.height) / 2f
            )
        )
    }

    // 水平价位（只画当前价格轴范围内的；主图与价位类指标合并后统一避让）
    run {
        val allLevels = ArrayList<IndicatorLevel>()
        for ((_, result) in levelResults) {
            allLevels.addAll(result.levels)
            allLevels.addAll(result.guides)
        }
        for ((_, result) in overlayResults) {
            allLevels.addAll(result.levels)
            allLevels.addAll(result.guides)
        }
        drawLevelSet(
            levels = allLevels,
            yOf = { v -> yOf(v) },
            areaTop = priceTop,
            areaBottom = priceBottom,
            plotLeft = plotLeft,
            plotRight = plotRight,
            measurer = measurer,
            s = s,
            textSize = 10f,
            minGap = (13f * s).dp.toPx()
        )
    }

    // 副图
    for (i in oscillators.indices) {
        val spec = oscillators[i]
        val pane = oscPanes[i]
        drawLine(C.Grid.copy(alpha = 0.7f), Offset(plotLeft, pane.top), Offset(plotRight, pane.top), 1f)
        val result = results[spec.id]
        val range = oscillatorRange(spec, result, start, count)
        fun oy(v: Double): Float =
            pane.bottom - ((v - range.min) / (range.max - range.min)).toFloat() * pane.height

        for (g in 0..2) {
            val v = range.max - (range.max - range.min) * g / 2.0
            val y = pane.top + pane.height * g / 2f
            drawLine(C.Grid.copy(alpha = 0.22f), Offset(plotLeft, y), Offset(plotRight, y), 1f)
            val layout = measurer.measure(AnnotatedString(fmtIndicator(v)), axisStyle)
            drawText(
                layout,
                topLeft = Offset(
                    plotRight + (10f * s).dp.toPx(),
                    (y - layout.size.height / 2f).coerceIn(pane.top, pane.bottom - layout.size.height)
                )
            )
        }

        if (result != null) {
            clipRect(plotLeft, pane.top, plotRight, pane.bottom) {
                if (result.guides.isNotEmpty()) {
                    drawLevelSet(
                        levels = result.guides,
                        yOf = { v -> oy(v) },
                        areaTop = pane.top,
                        areaBottom = pane.bottom,
                        plotLeft = plotLeft,
                        plotRight = plotRight,
                        measurer = measurer,
                        s = s,
                        textSize = 9.5f,
                        minGap = (11f * s).dp.toPx()
                    )
                }
                val zeroInRange = range.min <= 0.0 && range.max >= 0.0
                for (series in result.series) {
                    drawIndicatorSeries(
                        series = series,
                        start = start,
                        count = count,
                        plotLeft = plotLeft,
                        slot = slot,
                        yOf = { v -> oy(v) },
                        baselineY = if (zeroInRange) oy(0.0) else pane.bottom,
                        s = s,
                        lo = range.min,
                        hi = range.max
                    )
                }
            }
            drawOscillatorLegend(spec, result, start, count, pane, plotLeft, measurer, s)
        } else {
            drawOscillatorLegend(spec, null, start, count, pane, plotLeft, measurer, s)
        }
    }

    // 底部时间刻度
    val marks = intArrayOf(0, count / 3, (2 * count) / 3, count - 1)
    for ((k, idxRaw) in marks.withIndex()) {
        val idx = idxRaw.coerceIn(0, count - 1)
        val c = candles[start + idx]
        val layout = measurer.measure(AnnotatedString(timeFormat.format(Date(c.t))), axisStyle)
        val x = xOf(idx)
        val tx = when (k) {
            0 -> x
            marks.size - 1 -> x - layout.size.width
            else -> x - layout.size.width / 2f
        }
        drawText(
            layout,
            topLeft = Offset(
                tx.coerceIn(0f, size.width - layout.size.width),
                contentBottom + (6f * s).dp.toPx()
            )
        )
    }

    // 十字光标：竖线贯穿所有窗格，横线只在主图
    if (cursor != null) {
        val idx = cursor.coerceIn(0, count - 1)
        val c = candles[start + idx]
        val x = xOf(idx)
        drawLine(C.Dim.copy(alpha = 0.40f), Offset(x, priceTop), Offset(x, contentBottom), 1f, pathEffect = dash)
        val yC = yOf(c.c)
        drawLine(C.Dim.copy(alpha = 0.30f), Offset(plotLeft, yC), Offset(plotRight, yC), 1f, pathEffect = dash)

        val tipStyle = TextStyle(fontSize = (12f * s).sp, color = C.Text, fontFeatureSettings = "tnum")
        val indStyle = TextStyle(fontSize = (11.5f * s).sp, color = C.Text.copy(alpha = 0.82f), fontFeatureSettings = "tnum")
        val texts = ArrayList<AnnotatedString>()
        texts.add(AnnotatedString(tooltipFormat.format(Date(c.t))))
        texts.add(
            AnnotatedString(
                "开 " + fmtPrice(c.o, priceDecimals) + "   高 " + fmtPrice(c.h, priceDecimals) +
                    "   低 " + fmtPrice(c.l, priceDecimals) + "   收 " + fmtPrice(c.c, priceDecimals)
            )
        )
        val index = start + idx
        for (spec in overlays + oscillators) {
            val result = results[spec.id] ?: continue
            val values = result.series.take(4).mapNotNull { series ->
                val v = series.valueAt(index)
                if (v.isFinite()) "${series.label} ${fmtIndicator(v)}" else null
            }
            if (values.isNotEmpty()) {
                texts.add(AnnotatedString(spec.name + "  " + values.joinToString("  ")))
            }
        }
        val measured = texts.mapIndexed { i, text ->
            measurer.measure(text, if (i <= 1) tipStyle else indStyle)
        }
        val bw = measured.maxOf { it.size.width } + (22f * s).dp.toPx()
        val bh = measured.sumOf { it.size.height } + (16f * s).dp.toPx()
        val bx = (x - bw / 2f).coerceIn(0f, size.width - bw)
        val by = priceTop + (4f * s).dp.toPx()
        drawRoundRect(
            color = C.PanelHigh.copy(alpha = 0.96f),
            topLeft = Offset(bx, by),
            size = Size(bw, bh),
            cornerRadius = CornerRadius(8f * s)
        )
        drawRoundRect(
            color = C.Grid,
            topLeft = Offset(bx, by),
            size = Size(bw, bh),
            cornerRadius = CornerRadius(8f * s),
            style = Stroke(1f)
        )
        var ty = by + (7f * s).dp.toPx()
        for (layout in measured) {
            drawText(layout, topLeft = Offset(bx + (10f * s).dp.toPx(), ty))
            ty += layout.size.height + (1f * s).dp.toPx()
        }
    }
}

private fun IndicatorSeries.valueAt(index: Int): Double =
    if (index in values.indices) values[index] else Double.NaN

private fun latestValue(series: IndicatorSeries, start: Int, count: Int): Double {
    for (i in count - 1 downTo 0) {
        val v = series.valueAt(start + i)
        if (v.isFinite()) return v
    }
    return Double.NaN
}

private fun oscillatorRange(
    spec: IndicatorSpec,
    result: IndicatorResult?,
    start: Int,
    count: Int
): ValueRange {
    var lo = spec.suggestedRange?.min ?: Double.POSITIVE_INFINITY
    var hi = spec.suggestedRange?.max ?: Double.NEGATIVE_INFINITY
    if (result != null) {
        for (series in result.series) {
            for (i in 0 until count) {
                val v = series.valueAt(start + i)
                if (!v.isFinite()) continue
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
    }
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = 0.0
        hi = 1.0
    }
    if (hi <= lo) {
        val c = if (lo.isFinite()) lo else 0.0
        lo = c - 1.0
        hi = c + 1.0
    }
    if (spec.suggestedRange == null) {
        val pad = (hi - lo) * 0.08
        lo -= pad
        hi += pad
    }
    return ValueRange(lo, hi)
}

private fun DrawScope.drawOscillatorLegend(
    spec: IndicatorSpec,
    result: IndicatorResult?,
    start: Int,
    count: Int,
    pane: Pane,
    plotLeft: Float,
    measurer: TextMeasurer,
    s: Float
) {
    val parts = result?.series?.take(3)?.mapNotNull { series ->
        val v = latestValue(series, start, count)
        if (v.isFinite()) "${series.label} ${fmtIndicator(v)}" else null
    }.orEmpty()
    val text = if (parts.isEmpty()) "${spec.name}  —" else spec.name + "  " + parts.joinToString("  ")
    val style = TextStyle(
        fontSize = (9.5f * s).sp,
        color = C.Text.copy(alpha = 0.68f),
        fontFeatureSettings = "tnum"
    )
    val layout = measurer.measure(AnnotatedString(text), style)
    val x = plotLeft + (6f * s).dp.toPx()
    val y = pane.top + (4f * s).dp.toPx()
    drawRoundRect(
        color = C.Bg.copy(alpha = 0.42f),
        topLeft = Offset(x - (3f * s).dp.toPx(), y - (1f * s).dp.toPx()),
        size = Size(
            layout.size.width + (6f * s).dp.toPx(),
            layout.size.height + (2f * s).dp.toPx()
        ),
        cornerRadius = CornerRadius(3f * s)
    )
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.drawIndicatorSeries(
    series: IndicatorSeries,
    start: Int,
    count: Int,
    plotLeft: Float,
    slot: Float,
    yOf: (Double) -> Float,
    baselineY: Float,
    s: Float,
    lo: Double,
    hi: Double
) {
    val width = (series.width * 1.4f * s).coerceAtLeast(1f)
    val dashEffect = if (series.dash) {
        PathEffect.dashPathEffect(floatArrayOf(5f * s, 4f * s), 0f)
    } else {
        null
    }
    val baseColor = Color(series.color)

    fun xAt(i: Int): Float = plotLeft + (i + 0.5f) * slot

    when (series.style) {
        SeriesStyle.HISTOGRAM -> {
            val zeroInRange = lo <= 0.0 && hi >= 0.0
            val zeroY = if (zeroInRange) yOf(0.0) else baselineY
            val w = max(slot * 0.62f, 1f)
            for (i in 0 until count) {
                val v = series.valueAt(start + i)
                if (!v.isFinite()) continue
                val y = yOf(v)
                val top = min(y, zeroY)
                val h = max(abs(zeroY - y), 1f)
                val color = series.colors?.let { Color(it[start + i]) }
                    ?: baseColor.copy(alpha = 0.62f)
                if (color.alpha <= 0f) continue
                drawRect(color, Offset(xAt(i) - w / 2f, top), Size(w, h))
            }
        }

        SeriesStyle.DOTS -> {
            val radius = (1.7f * series.width * s).coerceAtLeast(1.3f)
            for (i in 0 until count) {
                val v = series.valueAt(start + i)
                if (!v.isFinite()) continue
                val color = series.colors?.let { Color(it[start + i]) } ?: baseColor
                drawCircle(color, radius, Offset(xAt(i), yOf(v)))
            }
        }

        SeriesStyle.AREA, SeriesStyle.LINE, SeriesStyle.STEP -> {
            if (series.style == SeriesStyle.AREA && series.fillAlpha > 0f) {
                var runStart = -1
                var runEnd = -1
                fun flush() {
                    if (runStart < 0 || runEnd < runStart) return
                    val path = Path()
                    var began = false
                    for (i in runStart..runEnd) {
                        val v = series.valueAt(start + i)
                        if (!v.isFinite()) continue
                        val x = xAt(i)
                        val y = yOf(v)
                        if (!began) {
                            path.moveTo(x, y)
                            began = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    if (began) {
                        path.lineTo(xAt(runEnd), baselineY)
                        path.lineTo(xAt(runStart), baselineY)
                        path.close()
                        drawPath(path, baseColor.copy(alpha = series.fillAlpha.coerceIn(0f, 1f)))
                    }
                }

                for (i in 0 until count) {
                    val ok = series.valueAt(start + i).isFinite()
                    if (ok) {
                        if (runStart < 0) runStart = i
                        runEnd = i
                    } else {
                        flush()
                        runStart = -1
                        runEnd = -1
                    }
                }
                flush()
            }

            if (series.colors != null) {
                val pointColors = series.colors ?: LongArray(0)
                var prevV = Double.NaN
                var prevX = 0f
                var prevY = 0f
                for (i in 0 until count) {
                    val v = series.valueAt(start + i)
                    if (!v.isFinite()) {
                        prevV = Double.NaN
                        continue
                    }
                    val x = xAt(i)
                    val y = if (series.style == SeriesStyle.STEP && prevV.isFinite()) yOf(prevV) else yOf(v)
                    if (prevV.isFinite()) {
                        val c = Color(pointColors[start + i])
                        if (c.alpha > 0f) {
                            drawLine(
                                c,
                                Offset(prevX, prevY),
                                Offset(x, y),
                                width,
                                StrokeCap.Round
                            )
                            if (series.style == SeriesStyle.STEP) {
                                drawLine(c, Offset(x, y), Offset(x, yOf(v)), width, StrokeCap.Round)
                            }
                        }
                    }
                    prevV = v
                    prevX = x
                    prevY = if (series.style == SeriesStyle.STEP) yOf(v) else y
                }
            } else {
                val path = Path()
                var began = false
                var prevY = 0f
                for (i in 0 until count) {
                    val v = series.valueAt(start + i)
                    if (!v.isFinite()) {
                        began = false
                        continue
                    }
                    val x = xAt(i)
                    val y = yOf(v)
                    if (!began) {
                        path.moveTo(x, y)
                        began = true
                    } else if (series.style == SeriesStyle.STEP) {
                        path.lineTo(x, prevY)
                        path.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                    prevY = y
                }
                drawPath(
                    path,
                    baseColor,
                    style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = dashEffect)
                )
            }
        }
    }
}

private fun levelPathEffect(style: LevelStyle, s: Float): PathEffect? = when (style) {
    LevelStyle.SOLID -> null
    LevelStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(6f * s, 5f * s), 0f)
    LevelStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1.2f * s, 3.2f * s), 0f)
}

private fun DrawScope.drawLevelSet(
    levels: List<IndicatorLevel>,
    yOf: (Double) -> Float,
    areaTop: Float,
    areaBottom: Float,
    plotLeft: Float,
    plotRight: Float,
    measurer: TextMeasurer,
    s: Float,
    textSize: Float,
    minGap: Float
) {
    if (levels.isEmpty()) return
    val visible = ArrayList<Pair<IndicatorLevel, Float>>(levels.size)
    for (level in levels) {
        if (!level.value.isFinite()) continue
        val y = yOf(level.value)
        if (y < areaTop - 0.5f || y > areaBottom + 0.5f) continue
        visible.add(level to y)
    }
    if (visible.isEmpty()) return

    for ((level, y) in visible) {
        drawLine(
            Color(level.color).copy(alpha = 0.75f),
            Offset(plotLeft, y),
            Offset(plotRight, y),
            (level.width * s).coerceAtLeast(1f),
            pathEffect = levelPathEffect(level.style, s)
        )
    }

    val labelStyle = TextStyle(
        fontSize = (textSize * s).sp,
        color = C.Text,
        fontFeatureSettings = "tnum"
    )
    val layouts = visible.map { (level, _) ->
        val text = if (level.label.isBlank()) fmtIndicator(level.value) else level.label
        measurer.measure(AnnotatedString(text), labelStyle)
    }
    val boxPadY = (2f * s).dp.toPx()
    val maxH = layouts.maxOf { it.size.height } + boxPadY
    val gap = maxOf(minGap, maxH)
    val chosen = selectLabelIndices(
        desired = visible.map { it.second },
        top = areaTop,
        bottom = areaBottom,
        minGap = gap
    )
    val positions = layoutLabelPositions(
        desired = chosen.map { visible[it].second },
        top = areaTop,
        bottom = areaBottom,
        minGap = gap
    )
    for (j in chosen.indices) {
        val level = visible[chosen[j]].first
        val layout = layouts[chosen[j]]
        val w = layout.size.width + (8f * s).dp.toPx()
        val h = layout.size.height + boxPadY
        val left = plotRight - w - (6f * s).dp.toPx()
        val top = (positions[j] - h / 2f).coerceIn(areaTop, areaBottom - h)
        drawRoundRect(
            color = C.Bg.copy(alpha = 0.72f),
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(3f * s)
        )
        drawText(
            layout,
            topLeft = Offset(left + (4f * s).dp.toPx(), top + (h - layout.size.height) / 2f),
            color = Color(level.color)
        )
    }
}

/**
 * 价位标签太密时按纵向位置均匀抽稀，宁可少画几个标签，也不让它们互相压住。
 * 返回的索引按 y 升序。
 */
internal fun selectLabelIndices(
    desired: List<Float>,
    top: Float,
    bottom: Float,
    minGap: Float
): List<Int> {
    if (desired.isEmpty()) return emptyList()
    val sorted = desired.indices.sortedBy { desired[it] }
    if (minGap <= 0f) return sorted
    val height = (bottom - top).coerceAtLeast(0f)
    val fit = (height / minGap).toInt().coerceAtLeast(1)
    if (sorted.size <= fit) return sorted
    if (fit <= 1) return listOf(sorted[sorted.size / 2])
    return List(fit) { k ->
        sorted[((k.toLong() * (sorted.size - 1)) / (fit - 1)).toInt()]
    }.distinct()
}

/** 标签纵向避让：从上往下推开，再从下往上压回，保证不重叠（空间不足时尽量少重叠）。 */
internal fun layoutLabelPositions(
    desired: List<Float>,
    top: Float,
    bottom: Float,
    minGap: Float
): List<Float> {
    if (desired.isEmpty()) return emptyList()
    val out = desired.toFloatArray()
    val order = desired.indices.sortedBy { desired[it] }

    var cursor = top
    for (index in order) {
        out[index] = max(out[index], cursor)
        cursor = out[index] + minGap
    }
    for (k in order.indices.reversed()) {
        val index = order[k]
        val limit = if (k == order.lastIndex) bottom else out[order[k + 1]] - minGap
        out[index] = min(out[index], limit)
    }
    cursor = top
    for (index in order) {
        out[index] = max(out[index], cursor)
        cursor = out[index] + minGap
    }
    return out.toList()
}
