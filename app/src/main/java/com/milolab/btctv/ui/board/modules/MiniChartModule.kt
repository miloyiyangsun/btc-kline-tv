package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.fmtPrice

/**
 * 迷你图槽位：小价头 + 周期标签 + 迷你 K 线/折线。
 * 4 宫格 K 线（CANDLE）与多周期格（LINE）共用；不画坐标/十字光标/指标。
 */
@Composable
fun MiniChartModule(
    data: MiniChartData,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val scale = chartHeaderScale(wDp, hDp) * 0.92f
            val pad = (8f * scale).dp
            val price = data.price ?: data.candles.lastOrNull()?.c
            val hasChart = data.candles.size >= 2
            val closes = remember(data.candles) { data.candles.map { it.c } }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = pad, vertical = (6f * scale).dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(
                        text = data.instrument.displayName.ifBlank { data.instrument.baseCurrency },
                        baseSp = (10.5f * scale).coerceIn(8f, 12.5f),
                        maxWidth = (wDp * 0.46f).dp,
                        color = if (data.stale) C.Dim else C.Text,
                        modifier = Modifier.weight(1f, fill = false),
                        weight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    MiniBadge(
                        data.tf.label,
                        baseSp = (9f * scale).coerceIn(7.5f, 11f),
                        color = Color(data.instrument.accent)
                    )
                    if (data.stale) {
                        Spacer(Modifier.width(4.dp))
                        StaleBadge(
                            data.staleAgoMinutes,
                            compact = true,
                            short = wDp < 380f
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FitText(
                        text = price?.let { fmtPrice(it, data.priceDecimals) } ?: "—",
                        baseSp = (17f * scale).coerceIn(11f, 24f),
                        maxWidth = (wDp * 0.42f).dp,
                        color = if (price == null) C.Dimmer else C.Text,
                        weight = FontWeight.Bold,
                        minScale = 0.55f
                    )
                    Spacer(Modifier.width(4.dp))
                    ChangePill(
                        data.changePct,
                        baseSp = (8.5f * scale).coerceIn(7.5f, 11f),
                        compact = true
                    )
                }
                Spacer(Modifier.height((4f * scale).dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .alpha(if (data.stale) 0.66f else 1f)
                ) {
                    when {
                        hasChart && data.style == ChartStyle.CANDLE -> CompactChart(
                            candles = data.candles,
                            style = ChartStyle.CANDLE,
                            accent = Color(data.instrument.accent),
                            up = C.Up,
                            down = C.Down,
                            priceDecimals = data.priceDecimals,
                            modifier = Modifier.fillMaxSize(),
                            visibleBars = data.candles.size,
                            fontSp = (hDp * 0.05f).coerceIn(7.5f, 10.5f),
                            lineWidthDp = 1.1f
                        )

                        hasChart -> Sparkline(
                            values = closes,
                            color = if ((data.changePct ?: 0.0) >= 0.0) C.Up else C.Down,
                            modifier = Modifier.fillMaxSize(),
                            lineWidth = 1.4.dp,
                            fill = data.style == ChartStyle.AREA
                        )

                        data.phase == ModulePhase.LOADING -> ModulePlaceholder(
                            text = "加载中…",
                            modifier = Modifier.fillMaxSize(),
                            loading = true,
                            baseSp = (9f * scale).coerceIn(8f, 11f)
                        )

                        else -> ModulePlaceholder(
                            text = "暂无走势",
                            modifier = Modifier.fillMaxSize(),
                            baseSp = (9f * scale).coerceIn(8f, 11f)
                        )
                    }
                }
            }
        }
    }
}
