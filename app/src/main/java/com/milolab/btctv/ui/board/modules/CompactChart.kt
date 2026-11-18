package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.fmtAxis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class CompactRange(val lo: Double, val hi: Double)

/**
 * COMPACT 密度的轻量图：K 线/折线 + 2–3 条价格刻度 + 最新价线；
 * 不注册 pointerInput（无十字光标）、不画指标、默认不画时间轴。
 * 与 ui/Chart.kt 的 PriceChart 同级视觉，但避免在小格里跑完整坐标/指标引擎。
 */
@Composable
internal fun CompactChart(
    candles: List<Candle>,
    style: ChartStyle,
    accent: Color,
    up: Color,
    down: Color,
    priceDecimals: Int,
    modifier: Modifier = Modifier,
    visibleBars: Int = 90,
    fontSp: Float = 10f,
    showAxisLabels: Boolean = true,
    lineWidthDp: Float = 1.4f
) {
    if (candles.size < 2) return
    val count = visibleBars.coerceIn(2, candles.size)
    val start = candles.size - count

    val range = remember(candles, count) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in start until candles.size) {
            val c = candles[i]
            if (c.l.isFinite()) lo = min(lo, c.l)
            if (c.h.isFinite()) hi = max(hi, c.h)
        }
        if (hi <= lo) hi = lo + 1.0
        val pad = (hi - lo) * 0.07
        CompactRange(lo - pad, hi + pad)
    }

    val labels = remember(range, priceDecimals) {
        listOf(
            fmtAxis(range.hi, priceDecimals),
            fmtAxis((range.hi + range.lo) / 2.0, priceDecimals),
            fmtAxis(range.lo, priceDecimals)
        )
    }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val axisStyle = TextStyle(
        fontSize = fontSp.sp,
        color = C.Dimmer,
        fontFeatureSettings = "tnum"
    )
    val layouts = remember(labels, axisStyle) {
        labels.map { measurer.measure(AnnotatedString(it), axisStyle) }
    }
    val density = LocalDensity.current
    val axisWidthPx = if (showAxisLabels) {
        layouts.maxOf { it.size.width }.toFloat() + with(density) { 10.dp.toPx() }
    } else {
        0f
    }
    val gapPx = with(density) { 4.dp.toPx() }

    Canvas(modifier) {
        if (size.width <= axisWidthPx + 4f || size.height < 8f) return@Canvas
        val plotRight = size.width - axisWidthPx
        val top = 2.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        val span = (range.hi - range.lo).toFloat().coerceAtLeast(1e-9f)

        fun yOf(v: Double): Float = bottom - ((v - range.lo) / span).toFloat() * (bottom - top)

        // 2–3 条横网格，文字只在右侧
        if (showAxisLabels) {
            for (i in 0..2) {
                val y = top + (bottom - top) * i / 2f
                drawLine(C.Grid.copy(alpha = 0.55f), Offset(0f, y), Offset(plotRight, y), 1f)
                val layout = layouts[i]
                drawText(
                    layout,
                    topLeft = Offset(
                        plotRight + gapPx,
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }
        }

        val slot = plotRight / count
        fun xOf(i: Int): Float = (i + 0.5f) * slot
        val bodyW = min(max(slot * 0.62f, 1f), 12.dp.toPx())
        val last = candles.last().c
        val yLast = yOf(last)

        when (style) {
            ChartStyle.CANDLE -> {
                val wick = max(1f, lineWidthDp.dp.toPx())
                for (i in 0 until count) {
                    val c = candles[start + i]
                    val x = xOf(i)
                    val col = if (c.c >= c.o) up else down
                    drawLine(
                        col.copy(alpha = 0.9f),
                        Offset(x, yOf(c.h)),
                        Offset(x, yOf(c.l)),
                        wick,
                        StrokeCap.Round
                    )
                    val yOpen = yOf(c.o)
                    val yClose = yOf(c.c)
                    val topY = min(yOpen, yClose)
                    val hgt = max(max(yOpen, yClose) - topY, 1.2f)
                    drawRoundRect(
                        color = col,
                        topLeft = Offset(x - bodyW / 2f, topY),
                        size = Size(bodyW, hgt),
                        cornerRadius = CornerRadius(bodyW * 0.2f, bodyW * 0.2f)
                    )
                }
            }

            ChartStyle.LINE, ChartStyle.AREA -> {
                val line = Path()
                for (i in 0 until count) {
                    val x = xOf(i)
                    val y = yOf(candles[start + i].c)
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                if (style == ChartStyle.AREA) {
                    val area = Path().apply {
                        addPath(line)
                        lineTo(xOf(count - 1), bottom)
                        lineTo(xOf(0), bottom)
                        close()
                    }
                    drawPath(
                        area,
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.01f)),
                            startY = top,
                            endY = bottom
                        )
                    )
                }
                drawPath(
                    line,
                    accent,
                    style = Stroke(width = lineWidthDp.dp.toPx().coerceAtLeast(1f), cap = StrokeCap.Round)
                )
            }
        }

        // 最新价虚线 + 端点
        drawLine(
            accent.copy(alpha = 0.85f),
            Offset(0f, yLast),
            Offset(plotRight, yLast),
            1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
        )
        drawCircle(accent, 2.2.dp.toPx(), Offset(plotRight - 1f, yLast))
    }
}
