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
import com.milolab.btctv.data.TF
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
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer(cacheSize = 128)
    var cursor by remember { mutableStateOf<Int?>(null) }
    val padRight = (74f * s).dp

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(candles.size, s) {
                    fun hit(x: Float): Int? {
                        if (candles.size < 2) return null
                        val right = size.width - padRight.toPx()
                        if (right <= 0f) return null
                        return (x / right * candles.size).toInt().coerceIn(0, candles.size - 1)
                    }
                    detectTapGestures(
                        onTap = { off -> cursor = hit(off.x) },
                        onLongPress = { cursor = null }
                    )
                }
                .pointerInput(candles.size, s) {
                    fun hit(x: Float): Int? {
                        if (candles.size < 2) return null
                        val right = size.width - padRight.toPx()
                        if (right <= 0f) return null
                        return (x / right * candles.size).toInt().coerceIn(0, candles.size - 1)
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
            drawChart(candles, style, showVolume, tf, accent, up, down, measurer, cursor, s)
        }
    }
}

private fun DrawScope.drawChart(
    candles: List<Candle>,
    style: ChartStyle,
    showVolume: Boolean,
    tf: TF,
    accent: Color,
    up: Color,
    down: Color,
    measurer: TextMeasurer,
    cursor: Int?,
    s: Float
) {
    if (candles.size < 2) return
    val n = candles.size

    val padRight = (74f * s).dp.toPx()
    val padTop = (12f * s).dp.toPx()
    val padBottom = (24f * s).dp.toPx()
    val volH = if (showVolume) size.height * 0.11f else 0f
    val volGap = if (showVolume) (12f * s).dp.toPx() else 0f

    val plotLeft = 0f
    val plotRight = size.width - padRight
    val priceTop = padTop
    val priceBottom = size.height - padBottom - volH - volGap
    if (plotRight <= 0f || priceBottom <= priceTop) return

    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (c in candles) {
        lo = min(lo, c.l)
        hi = max(hi, c.h)
    }
    if (hi <= lo) hi = lo + 1.0
    val padding = (hi - lo) * 0.07
    lo -= padding
    hi += padding
    val span = (hi - lo).toFloat()

    fun yOf(p: Double): Float = priceBottom - ((p - lo) / span).toFloat() * (priceBottom - priceTop)

    val plotW = plotRight - plotLeft
    val slot = plotW / n
    val bodyW = max(slot * 0.66f, 1f)

    val axisStyle = TextStyle(fontSize = (12f * s).sp, color = C.Dimmer, fontFeatureSettings = "tnum")
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f * s, 7f * s), 0f)

    // 横向网格 + 右侧价格刻度
    for (i in 0..4) {
        val y = priceTop + (priceBottom - priceTop) * i / 4f
        drawLine(C.Grid.copy(alpha = 0.5f), Offset(plotLeft, y), Offset(plotRight, y), 1f)
        val p = hi - (hi - lo) * i / 4.0
        val layout = measurer.measure(AnnotatedString(fmtAxis(p)), axisStyle)
        drawText(
            layout,
            topLeft = Offset(
                plotRight + (10f * s).dp.toPx(),
                (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
            )
        )
    }

    // 成交量
    if (showVolume) {
        val volTop = priceBottom + volGap
        val volBottom = size.height - padBottom
        var maxV = 1e-9
        for (c in candles) maxV = max(maxV, c.v)
        for (i in 0 until n) {
            val c = candles[i]
            val x = plotLeft + (i + 0.5f) * slot
            val vh = ((c.v / maxV) * (volBottom - volTop)).toFloat()
            val col = if (c.c >= c.o) up else down
            drawRect(
                color = col.copy(alpha = 0.20f),
                topLeft = Offset(x - bodyW / 2f, volBottom - vh),
                size = Size(bodyW, vh)
            )
        }
    }

    when (style) {
        ChartStyle.CANDLE -> {
            val wick = max(1f, 1.4f * s)
            for (i in 0 until n) {
                val c = candles[i]
                val x = plotLeft + (i + 0.5f) * slot
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
            val path = Path()
            for (i in 0 until n) {
                val c = candles[i]
                val x = plotLeft + (i + 0.5f) * slot
                val y = yOf(c.c)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (style == ChartStyle.AREA) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(plotLeft + (n - 0.5f) * slot, priceBottom)
                    lineTo(plotLeft + 0.5f * slot, priceBottom)
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
            drawPath(path, accent.copy(alpha = 0.16f), style = Stroke(width = 7f * s, cap = StrokeCap.Round))
            drawPath(path, accent, style = Stroke(width = 2f * s, cap = StrokeCap.Round))
        }
    }

    // EMA20：一条细线，让画面有"专业图"的味道但不抢戏
    if (style == ChartStyle.CANDLE && n > 24) {
        val k = 2.0 / 21.0
        var ema = candles[0].c
        val p = Path()
        for (i in 0 until n) {
            ema = candles[i].c * k + ema * (1 - k)
            val x = plotLeft + (i + 0.5f) * slot
            val y = yOf(ema)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, accent.copy(alpha = 0.45f), style = Stroke(width = 1.4f * s))
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
        val layout = measurer.measure(AnnotatedString(fmtPrice(last)), tagStyle)
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

    // 底部时间刻度
    val timeFmt = if (tf.seconds >= 86_400) "MM-dd" else "HH:mm"
    val sdf = java.text.SimpleDateFormat(timeFmt, java.util.Locale.getDefault())
    val marks = intArrayOf(0, n / 3, (2 * n) / 3, n - 1)
    for ((k, idxRaw) in marks.withIndex()) {
        val idx = idxRaw.coerceIn(0, n - 1)
        val c = candles[idx]
        val layout = measurer.measure(AnnotatedString(sdf.format(java.util.Date(c.t))), axisStyle)
        val x = plotLeft + (idx + 0.5f) * slot
        val tx = when (k) {
            0 -> x
            marks.size - 1 -> x - layout.size.width
            else -> x - layout.size.width / 2f
        }
        drawText(
            layout,
            topLeft = Offset(tx.coerceIn(0f, size.width - layout.size.width), size.height - padBottom + (6f * s).dp.toPx())
        )
    }

    // 触摸/鼠标十字光标
    if (cursor != null) {
        val idx = cursor.coerceIn(0, n - 1)
        val c = candles[idx]
        val x = plotLeft + (idx + 0.5f) * slot
        drawLine(C.Dim.copy(alpha = 0.45f), Offset(x, priceTop), Offset(x, priceBottom), 1f, pathEffect = dash)
        val yC = yOf(c.c)
        drawLine(C.Dim.copy(alpha = 0.3f), Offset(plotLeft, yC), Offset(plotRight, yC), 1f, pathEffect = dash)

        val tipStyle = TextStyle(fontSize = (12f * s).sp, color = C.Text, fontFeatureSettings = "tnum")
        val line1 = measurer.measure(AnnotatedString(dateTime(c.t)), axisStyle)
        val line2 = measurer.measure(
            AnnotatedString(
                "开 " + fmtPrice(c.o) + "   高 " + fmtPrice(c.h) + "   低 " + fmtPrice(c.l) + "   收 " + fmtPrice(c.c)
            ),
            tipStyle
        )
        val bw = max(line1.size.width, line2.size.width) + (22f * s).dp.toPx()
        val bh = line1.size.height + line2.size.height + (18f * s).dp.toPx()
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
        drawText(line1, topLeft = Offset(bx + (10f * s).dp.toPx(), by + (6f * s).dp.toPx()))
        drawText(line2, topLeft = Offset(bx + (10f * s).dp.toPx(), by + (8f * s).dp.toPx() + line1.size.height))
    }
}
