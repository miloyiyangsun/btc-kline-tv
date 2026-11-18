package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C

/** 与设置面板/搜索弹层一致的焦点暖色。 */
internal val SlotFocusBorder = Color(0xFFFFD79A)

/** 过期提示的琥珀色；深色底上对比度足够。 */
internal val StaleAmber = Color(0xFFE6B566)

/**
 * 所有模板共用的卡片外壳：深色底 + 圆角 + 焦点橙边 + 轻微放大。
 * 焦点环画在自身边界内，不参与测量，也不依赖系统 focus（focused 由集成者传入）。
 */
@Composable
internal fun SlotSurface(
    focused: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    background: Color = C.Panel.copy(alpha = 0.88f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .graphicsLayer {
                val k = if (focused) 1.012f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (focused) C.PanelHigh.copy(alpha = 0.94f) else background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) SlotFocusBorder else C.Grid,
                shape = shape
            ),
        content = content
    )
}

/**
 * 单行自动缩放：先按基础字号测一遍，超宽就按比例缩小（下限 minScale），
 * 仍然放不下时由调用方的 Text 省略。测量结果按 (文本, 字号, 宽度) 缓存。
 */
@Composable
internal fun fitTextSp(
    text: String,
    baseSp: Float,
    maxWidth: Dp,
    weight: FontWeight = FontWeight.SemiBold,
    minScale: Float = 0.62f
): Float {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
    return remember(text, baseSp, widthPx, weight, minScale) {
        if (text.isEmpty() || baseSp <= 0f) {
            baseSp
        } else {
            val style = TextStyle(
                fontSize = baseSp.sp,
                fontWeight = weight,
                fontFeatureSettings = "tnum"
            )
            val measured = measurer.measure(text, style, maxLines = 1)
            val w = measured.size.width
            if (w <= widthPx || w <= 0) {
                baseSp
            } else {
                (baseSp * widthPx / w * 0.98f).coerceAtLeast(baseSp * minScale)
            }
        }
    }
}

/** 自动缩放的单行文本；超长时省略，绝不换行/溢出。 */
@Composable
internal fun FitText(
    text: String,
    baseSp: Float,
    maxWidth: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.SemiBold,
    minScale: Float = 0.62f
) {
    Text(
        text = text,
        color = color,
        fontSize = fitTextSp(text, baseSp, maxWidth, weight, minScale).sp,
        fontWeight = weight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** 静态占位（加载中/空数据）：没有无限动画，所以不会闪烁。 */
@Composable
internal fun ModulePlaceholder(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    baseSp: Float = 11f
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(width = 18.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(C.Grid)
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(width = 0.dp, height = 8.dp))
            }
            Text(
                text,
                color = C.Dimmer,
                fontSize = baseSp.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 过期角标：琥珀色小胶囊；不改变布局尺寸（随内容自适应）。 */
@Composable
internal fun StaleBadge(
    agoMinutes: Long?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    short: Boolean = false
) {
    val fs = if (compact) 8.5f else 9.5f
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(StaleAmber.copy(alpha = 0.16f))
            .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = 1.dp)
    ) {
        Text(
            if (short) "过期" else staleLabel(agoMinutes),
            color = StaleAmber,
            fontSize = fs.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

internal fun changeColor(v: Double?): Color = when {
    v == null -> C.Dim
    v >= 0.0 -> C.Up
    else -> C.Down
}

/** 涨跌幅色块：正绿负红；null 显示中性 —。 */
@Composable
internal fun ChangePill(
    changePct: Double?,
    baseSp: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val color = changeColor(changePct)
    val bg = if (changePct == null) C.Grid.copy(alpha = 0.35f) else color.copy(alpha = 0.15f)
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(
                horizontal = if (compact) 4.dp else 7.dp,
                vertical = if (compact) 1.dp else 2.dp
            )
    ) {
        Text(
            fmtPctSigned(changePct),
            color = color,
            fontSize = baseSp.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

/** 类型/周期小徽标（永续、代币现货、加密、1H…）。 */
@Composable
internal fun MiniBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = C.Dim,
    baseSp: Float = 8.5f
) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = baseSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}
