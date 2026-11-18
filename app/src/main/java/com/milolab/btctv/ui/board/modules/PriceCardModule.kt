package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.fmtPrice
import com.milolab.btctv.ui.fmtVol

/**
 * 价格卡：三档尺寸。
 * LARGE/MEDIUM 显示类型徽标、24H 高低（LARGE 还有量）；SMALL 只保留名称+价格+涨跌+spark，
 * 适配 9 宫格行情墙。
 */
@Composable
fun PriceCardModule(
    data: PriceCardData,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val tier = priceCardTier(wDp, hDp)
            val pad = when (tier) {
                PriceCardTier.LARGE -> 16f
                PriceCardTier.MEDIUM -> 10f
                PriceCardTier.SMALL -> 7f
            }.dp
            val nameSp = when (tier) {
                PriceCardTier.LARGE -> 15f
                PriceCardTier.MEDIUM -> 11.5f
                PriceCardTier.SMALL -> 9.5f
            }
            val priceSp = when (tier) {
                PriceCardTier.LARGE -> (wDp * 0.115f).coerceIn(32f, 64f)
                PriceCardTier.MEDIUM -> (wDp * 0.09f).coerceIn(20f, 36f)
                PriceCardTier.SMALL -> (wDp * 0.075f).coerceIn(15f, 26f)
            }
            val pctSp = when (tier) {
                PriceCardTier.LARGE -> 14f
                PriceCardTier.MEDIUM -> 10.5f
                PriceCardTier.SMALL -> 9f
            }
            val detailSp = when (tier) {
                PriceCardTier.LARGE -> 11f
                PriceCardTier.MEDIUM -> 9.5f
                PriceCardTier.SMALL -> 8f
            }
            val sparkH = when (tier) {
                PriceCardTier.LARGE -> hDp * 0.26f
                PriceCardTier.MEDIUM -> hDp * 0.24f
                PriceCardTier.SMALL -> hDp * 0.20f
            }.coerceAtLeast(16f)
            val hasSpark = data.spark.size >= 2
            val hasQuote = data.price != null
            val sparkColor = if (data.sparkUp ?: ((data.changePct ?: 0.0) >= 0.0)) C.Up else C.Down
            val showDetailed = tier != PriceCardTier.SMALL
            val contentAlpha = if (data.stale) 0.66f else 1f

            when {
                hasQuote -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(pad)
                            .alpha(contentAlpha)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FitText(
                                text = if (tier == PriceCardTier.SMALL) {
                                    data.instrument.baseCurrency
                                } else {
                                    data.instrument.displayName.ifBlank { data.instrument.baseCurrency }
                                },
                                baseSp = nameSp,
                                maxWidth = (wDp * if (showDetailed) 0.62f else 0.9f).dp,
                                color = if (data.stale) C.Dim else C.Text,
                                modifier = Modifier.weight(1f, fill = false),
                                weight = FontWeight.SemiBold
                            )
                            if (showDetailed) {
                                Spacer(Modifier.width(5.dp))
                                MiniBadge(
                                    marketBadge(data.instrument),
                                    baseSp = (8f * (if (tier == PriceCardTier.LARGE) 1.2f else 1f)),
                                    color = C.Dim
                                )
                            }
                            if (data.stale) {
                                Spacer(Modifier.width(5.dp))
                                StaleBadge(data.staleAgoMinutes, compact = tier == PriceCardTier.SMALL)
                            }
                        }
                        Spacer(Modifier.height((4f * (if (tier == PriceCardTier.LARGE) 2f else 1f)).dp))
                        FitText(
                            text = fmtPrice(data.price ?: 0.0, data.priceDecimals),
                            baseSp = priceSp,
                            maxWidth = (wDp - pad.value * 2f).dp,
                            color = C.Text,
                            weight = FontWeight.Bold,
                            minScale = 0.45f
                        )
                        Spacer(Modifier.height((if (tier == PriceCardTier.LARGE) 8f else 4f).dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChangePill(
                                data.changePct,
                                baseSp = pctSp,
                                compact = tier == PriceCardTier.SMALL
                            )
                            if (showDetailed) {
                                Spacer(Modifier.width(8.dp))
                                if (data.high24h != null && data.low24h != null) {
                                    TextPair(
                                        label = "24H 高",
                                        value = fmtPrice(data.high24h, data.priceDecimals),
                                        valueColor = C.Text.copy(alpha = 0.86f),
                                        baseSp = detailSp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextPair(
                                        label = "24H 低",
                                        value = fmtPrice(data.low24h, data.priceDecimals),
                                        valueColor = C.Text.copy(alpha = 0.86f),
                                        baseSp = detailSp
                                    )
                                }
                                if (tier == PriceCardTier.LARGE && data.volume24h != null) {
                                    Spacer(Modifier.width(8.dp))
                                    TextPair(
                                        label = "24H 量",
                                        value = fmtVol(data.volume24h),
                                        valueColor = C.Text.copy(alpha = 0.86f),
                                        baseSp = detailSp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (hasSpark) {
                            Sparkline(
                                values = data.spark,
                                color = sparkColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(sparkH.dp),
                                lineWidth = if (tier == PriceCardTier.SMALL) 1.1.dp else 1.6.dp,
                                fill = true
                            )
                        }
                    }
                }

                data.phase == ModulePhase.LOADING -> ModulePlaceholder(
                    text = "加载中…",
                    modifier = Modifier.fillMaxSize(),
                    loading = true,
                    baseSp = if (tier == PriceCardTier.SMALL) 9f else 11f
                )

                else -> ModulePlaceholder(
                    text = "暂无行情",
                    modifier = Modifier.fillMaxSize(),
                    baseSp = if (tier == PriceCardTier.SMALL) 9f else 11f
                )
            }
        }
    }
}

@Composable
private fun TextPair(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    baseSp: Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            label,
            color = C.Dimmer,
            fontSize = (baseSp * 0.78f).sp,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.width(3.dp))
        androidx.compose.material3.Text(
            value,
            color = valueColor,
            fontSize = baseSp.sp,
            maxLines = 1,
            softWrap = false,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
    }
}
