package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.PriceChart
import com.milolab.btctv.ui.fmtPrice

/**
 * 主图槽位。FULL 直接复用 ui/Chart.kt 的 PriceChart（坐标/成交量/指标/十字光标），
 * COMPACT 使用轻量 CompactChart（无时间轴、只留 2–3 条价格刻度、字号缩小）。
 * 头部（标的名/价格/涨跌）自绘，字号随格子尺寸缩放。
 */
@Composable
fun MainChartModule(
    data: MainChartData,
    density: ChartDensity,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val headerScale = chartHeaderScale(wDp, hDp) * if (density == ChartDensity.COMPACT) 0.88f else 1f
            val padding = (8f * headerScale).dp
            val hasChart = data.candles.size >= 2
            val price = data.price ?: data.candles.lastOrNull()?.c

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = padding, vertical = (6f * headerScale).dp)
            ) {
                ChartHeader(
                    data = data,
                    price = price,
                    scale = headerScale,
                    density = density,
                    widthDp = wDp
                )
                Spacer(Modifier.height((4f * headerScale).dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        hasChart -> {
                            val chartAlpha = if (data.stale) 0.66f else 1f
                            when (density) {
                                ChartDensity.FULL -> PriceChart(
                                    candles = data.candles,
                                    style = data.style,
                                    showVolume = data.showVolume,
                                    tf = data.tf,
                                    accent = Color(data.instrument.accent),
                                    up = C.Up,
                                    down = C.Down,
                                    s = (hDp / 430f).coerceIn(0.7f, 1.35f),
                                    priceDecimals = data.priceDecimals,
                                    modifier = Modifier.fillMaxSize().alpha(chartAlpha),
                                    visibleCount = data.visibleBars ?: data.candles.size,
                                    indicatorSettings = data.indicatorSettings,
                                    indicatorResults = data.indicatorResults
                                )

                                ChartDensity.COMPACT -> CompactChart(
                                    candles = data.candles,
                                    style = data.style,
                                    accent = Color(data.instrument.accent),
                                    up = C.Up,
                                    down = C.Down,
                                    priceDecimals = data.priceDecimals,
                                    modifier = Modifier.fillMaxSize().alpha(chartAlpha),
                                    visibleBars = data.visibleBars ?: 90,
                                    fontSp = (hDp * 0.045f).coerceIn(8.5f, 12f),
                                    lineWidthDp = if (density == ChartDensity.COMPACT) 1.2f else 1.4f
                                )
                            }
                        }

                        data.phase == ModulePhase.LOADING -> ModulePlaceholder(
                            text = "加载 K 线…",
                            modifier = Modifier.fillMaxSize(),
                            loading = true,
                            baseSp = (10f * headerScale).coerceIn(9f, 13f)
                        )

                        else -> ModulePlaceholder(
                            text = "暂无数据",
                            modifier = Modifier.fillMaxSize(),
                            baseSp = (10f * headerScale).coerceIn(9f, 13f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartHeader(
    data: MainChartData,
    price: Double?,
    scale: Float,
    density: ChartDensity,
    widthDp: Float
) {
    val nameSp = (12.5f * scale).coerceIn(9f, 16f)
    val priceSp = (26f * scale).coerceIn(14f, 38f)
    val pctSp = (11f * scale).coerceIn(8.5f, 13f)
    val showSource = data.source.isNotBlank() && density == ChartDensity.FULL && widthDp >= 380f

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText(
                text = data.instrument.displayName.ifBlank { data.instrument.baseCurrency },
                baseSp = nameSp,
                maxWidth = (widthDp * 0.5f).dp,
                color = if (data.stale) C.Dim else C.Text,
                modifier = Modifier.weight(1f, fill = false),
                weight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(5.dp))
            MiniBadge(data.tf.label, baseSp = (8.5f * scale).coerceIn(7.5f, 10f), color = C.Dim)
            if (data.stale) {
                Spacer(Modifier.width(5.dp))
                StaleBadge(data.staleAgoMinutes, compact = true)
            }
            if (showSource) {
                Spacer(Modifier.width(6.dp))
                Text(
                    data.source,
                    color = C.Dimmer,
                    fontSize = (9f * scale).coerceIn(7.5f, 11f).sp,
                    maxLines = 1
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText(
                text = price?.let { fmtPrice(it, data.priceDecimals) } ?: "—",
                baseSp = priceSp,
                maxWidth = (widthDp * 0.52f).dp,
                color = if (price == null) C.Dimmer else C.Text,
                weight = FontWeight.Bold,
                minScale = 0.55f
            )
            Spacer(Modifier.width(7.dp))
            ChangePill(data.changePct, baseSp = pctSp, compact = density == ChartDensity.COMPACT)
            Spacer(Modifier.weight(1f))
            if (data.candles.isNotEmpty()) {
                Text(
                    "${data.candles.size} 根",
                    color = C.Dimmer,
                    fontSize = (8.5f * scale).coerceIn(7f, 10f).sp,
                    maxLines = 1
                )
            }
        }
    }
}
