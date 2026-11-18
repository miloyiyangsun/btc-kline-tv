package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.Mono
import com.milolab.btctv.ui.fmtPrice
import kotlin.math.ceil

private data class TickerSeg(
    val layout: TextLayoutResult,
    val color: Color,
    val gapAfterPx: Float
)

private class TickerContent(
    val segs: List<TickerSeg>,
    val loopWidthPx: Float,
    val lineHeightPx: Float
)

/**
 * 底栏跑马灯：匀速横向滚动（withFrameNanos），涨绿跌红沿用 Theme。
 * - [lowPower] 为 true 时每约 66ms 才更新一次偏移（15fps），降低低端设备的绘制压力；
 * - [antiBurn] 打开时内容随滚动进度做 ±1.2dp 的极慢纵向漂移，缓解 OLED 烧屏；
 * - [fixedOffsetPx] 用于确定性预览/截图：非 null 时不启动动画循环。
 */
@Composable
fun TickerStripModule(
    data: TickerStripData,
    focused: Boolean,
    modifier: Modifier = Modifier,
    lowPower: Boolean = false,
    antiBurn: Boolean = true,
    fixedOffsetPx: Float? = null
) {
    SlotSurface(
        focused = focused,
        modifier = modifier,
        cornerRadius = 8.dp,
        background = C.PanelHigh.copy(alpha = 0.92f)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hDp = maxHeight.value
            val fontSp = tickerFontSp(hDp)
            val contentAlpha = if (data.stale) 0.72f else 1f
            val measurer = rememberTextMeasurer(cacheSize = 128)
            val localDensity = LocalDensity.current
            val segmentGap = with(localDensity) { (fontSp * 0.42f).dp.toPx() }
            val itemGap = with(localDensity) { 14.dp.toPx() }

            val nameStyle = remember(fontSp) {
                TextStyle(fontSize = fontSp.sp, fontWeight = FontWeight.Medium)
            }
            val valueStyle = remember(fontSp) {
                TextStyle(
                    fontSize = fontSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = Mono,
                    fontFeatureSettings = "tnum"
                )
            }
            val changeStyle = remember(fontSp) {
                TextStyle(
                    fontSize = fontSp.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono,
                    fontFeatureSettings = "tnum"
                )
            }

            val content = remember(
                data.items,
                fontSp,
                contentAlpha,
                localDensity.density,
                measurer
            ) {
                buildTickerContent(
                    items = data.items,
                    measurer = measurer,
                    nameStyle = nameStyle,
                    valueStyle = valueStyle,
                    changeStyle = changeStyle,
                    textColor = C.Text.copy(alpha = contentAlpha),
                    dimColor = C.Dimmer.copy(alpha = contentAlpha),
                    segmentGapPx = segmentGap,
                    itemGapPx = itemGap
                )
            }

            var elapsedMs by remember { mutableLongStateOf(0L) }

            LaunchedEffect(content.loopWidthPx, data.speed, lowPower, fixedOffsetPx) {
                if (fixedOffsetPx != null || content.loopWidthPx <= 0f) return@LaunchedEffect
                val minStepNs = if (lowPower) 66_000_000L else 16_000_000L
                var last = withFrameNanos { it }
                var acc = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    acc += now - last
                    last = now
                    if (acc >= minStepNs) {
                        elapsedMs += acc / 1_000_000L
                        acc %= 1_000_000L
                    }
                }
            }

            if (content.segs.isEmpty()) {
                ModulePlaceholder(
                    text = if (data.phase == ModulePhase.LOADING) "加载行情…" else "暂无行情",
                    modifier = Modifier.fillMaxSize(),
                    loading = data.phase == ModulePhase.LOADING,
                    baseSp = fontSp
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val loop = content.loopWidthPx
                    if (loop <= 0f) return@Canvas
                    val offset = fixedOffsetPx
                        ?: tickerOffsetPx(elapsedMs, data.speed, density, loop)
                    val shift = if (antiBurn) {
                        antiBurnShiftDp(offset, loop).dp.toPx()
                    } else {
                        0f
                    }
                    val baseY = (size.height - content.lineHeightPx) / 2f + shift
                    val passes = ceil((size.width + loop) / loop).toInt().coerceAtLeast(1)
                    clipRect(0f, 0f, size.width, size.height) {
                        var x = -offset
                        repeat(passes) {
                            for (seg in content.segs) {
                                val w = seg.layout.size.width.toFloat()
                                if (x + w >= 0f && x <= size.width) {
                                    drawText(
                                        seg.layout,
                                        topLeft = androidx.compose.ui.geometry.Offset(
                                            x,
                                            baseY + (content.lineHeightPx - seg.layout.size.height) / 2f
                                        ),
                                        color = seg.color
                                    )
                                }
                                x += w + seg.gapAfterPx
                            }
                        }
                    }
                }
            }

            if (data.stale && content.segs.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.38f to C.PanelHigh,
                                1f to C.PanelHigh
                            )
                        )
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StaleBadge(data.staleAgoMinutes, compact = true, short = true)
                }
            }
        }
    }
}

private fun buildTickerContent(
    items: List<TickerItem>,
    measurer: TextMeasurer,
    nameStyle: TextStyle,
    valueStyle: TextStyle,
    changeStyle: TextStyle,
    textColor: Color,
    dimColor: Color,
    segmentGapPx: Float,
    itemGapPx: Float
): TickerContent {
    if (items.isEmpty()) return TickerContent(emptyList(), 0f, 0f)

    val segs = ArrayList<TickerSeg>(items.size * 4)

    fun add(text: String, style: TextStyle, color: Color, gapAfter: Float) {
        if (text.isBlank()) return
        segs += TickerSeg(
            layout = measurer.measure(text = text, style = style, softWrap = false, maxLines = 1),
            color = color,
            gapAfterPx = gapAfter
        )
    }

    for (item in items) {
        add(item.name, nameStyle, textColor, segmentGapPx)
        when {
            item.valueText != null -> add(item.valueText, valueStyle, textColor, segmentGapPx)
            item.price != null -> add(
                fmtPrice(item.price, item.priceDecimals),
                valueStyle,
                textColor,
                segmentGapPx
            )
        }
        if (item.changePct != null) {
            add(fmtPctSigned(item.changePct), changeStyle, changeColor(item.changePct), segmentGapPx)
        }
        add("·", nameStyle, dimColor, itemGapPx)
    }

    var width = 0f
    var lineHeight = 0f
    for (seg in segs) {
        width += seg.layout.size.width + seg.gapAfterPx
        if (seg.layout.size.height > lineHeight) lineHeight = seg.layout.size.height.toFloat()
    }
    return TickerContent(segs, width, lineHeight)
}
