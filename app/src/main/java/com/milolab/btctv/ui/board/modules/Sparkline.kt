package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纯折线 sparkline：40–60 点，无坐标/文字。
 * 归一化后的 FloatArray 按数据缓存；绘制路径只在数据或尺寸变化时重建。
 */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 1.5.dp,
    fill: Boolean = true,
    endDot: Boolean = true
) {
    val points = remember(values) { FloatArray(values.size) { values[it].toFloat() } }
    Canvas(modifier) {
        if (points.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in points) {
            if (!v.isFinite()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (lo > hi) return@Canvas
        if (hi - lo < 1e-9f) {
            val mid = (hi + lo) / 2f
            lo = mid - 1f
            hi = mid + 1f
        }
        val pad = size.height * 0.08f
        val usable = (size.height - pad * 2f).coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1)

        val line = Path()
        var started = false
        var lastX = 0f
        var lastY = 0f
        for (i in points.indices) {
            val v = points[i]
            if (!v.isFinite()) {
                started = false
                continue
            }
            val x = i * stepX
            val y = pad + (hi - v) / (hi - lo) * usable
            if (!started) {
                line.moveTo(x, y)
                started = true
            } else {
                line.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }

        if (fill) {
            val area = Path()
            area.addPath(line)
            area.lineTo(lastX, size.height)
            area.lineTo(0f, size.height)
            area.close()
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.01f)),
                    startY = 0f,
                    endY = size.height
                )
            )
        }
        drawPath(
            line,
            color,
            style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round)
        )
        if (endDot) {
            drawCircle(color, (lineWidth.toPx() * 0.9f).coerceAtLeast(1.2f), Offset(lastX, lastY))
        }
    }
}

/** 归一化 sparkline 幅度（0..1），用于卡片的迷你背景/测试。 */
internal fun sparkAmplitude(values: List<Double>): Float {
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (v in values) {
        if (!v.isFinite()) continue
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (lo > hi) return 0f
    val base = kotlin.math.abs(lo).coerceAtLeast(1e-9)
    return ((hi - lo) / base).toFloat().coerceIn(0f, 1f)
}
