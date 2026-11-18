package com.milolab.btctv.ui.board.modules

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C

/**
 * 大盘指标卡：BTCD / TOTAL / 稳定币市值 / 稳定币占比。
 * 值缺失显示 —（不是 0）；stale 时保留旧值、淡化内容并显示「更新于 X 分钟前」。
 * 三档布局：标题行（源） / 大数字（+spark） / 底行（24h 变化 + 过期角标）。
 * 数字可用宽度由实际列宽扣除 spark 后算出，避免大数字被省略。
 */
@Composable
fun StatTileModule(
    data: StatTileData,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val tier = statTileTier(wDp, hDp)
            val padHf = when (tier) {
                StatTileTier.LARGE -> 16f
                StatTileTier.MEDIUM -> 10f
                StatTileTier.SMALL -> 7f
            }
            val padVf = when (tier) {
                StatTileTier.LARGE -> 14f
                StatTileTier.MEDIUM -> 7f
                StatTileTier.SMALL -> 5f
            }
            val titleSp = when (tier) {
                StatTileTier.LARGE -> 14f
                StatTileTier.MEDIUM -> 9.5f
                StatTileTier.SMALL -> 8.5f
            }
            val valueSp = when (tier) {
                StatTileTier.LARGE -> (wDp * 0.11f).coerceIn(34f, 60f)
                StatTileTier.MEDIUM -> (wDp * 0.14f).coerceIn(17f, 27f)
                StatTileTier.SMALL -> (wDp * 0.12f).coerceIn(13f, 20f)
            }
            val changeSp = when (tier) {
                StatTileTier.LARGE -> 12.5f
                StatTileTier.MEDIUM -> 9.5f
                StatTileTier.SMALL -> 8.5f
            }
            val footerSp = when (tier) {
                StatTileTier.LARGE -> 10f
                StatTileTier.MEDIUM -> 8f
                StatTileTier.SMALL -> 7.5f
            }
            val titleGap = when (tier) {
                StatTileTier.LARGE -> 10f
                StatTileTier.MEDIUM -> 3f
                StatTileTier.SMALL -> 2f
            }
            val footerGap = titleGap
            val innerW = (wDp - padHf * 2f).coerceAtLeast(1f)
            val hasValue = data.value != null
            val showSpark = data.spark.size >= 2 && wDp >= 190f && hDp >= 64f
            val sparkW = (wDp * 0.30f).coerceIn(40f, 120f)
            val valueMaxW = (innerW - if (showSpark) sparkW + 6f else 0f).coerceAtLeast(24f)
            val contentAlpha = if (data.stale) 0.64f else 1f

            if (!hasValue && data.phase == ModulePhase.LOADING) {
                ModulePlaceholder(
                    text = "加载中…",
                    modifier = Modifier.fillMaxSize(),
                    loading = true,
                    baseSp = if (tier == StatTileTier.SMALL) 8.5f else 10f
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = padHf.dp, vertical = padVf.dp)
                        .alpha(contentAlpha)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FitText(
                            text = if (wDp < 170f) data.kind.shortTitle else data.kind.title,
                            baseSp = titleSp,
                            maxWidth = (innerW * 0.72f).dp,
                            color = C.Dim,
                            modifier = Modifier.weight(1f, fill = false),
                            weight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        when {
                            !hasValue -> MiniBadge("暂无", color = C.Dimmer, baseSp = footerSp)
                            data.source.isNotBlank() -> Text(
                                data.source,
                                color = C.Dimmer,
                                fontSize = footerSp.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = (innerW * 0.42f).dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(titleGap.dp))
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FitText(
                            text = fmtStatValue(data.kind, data.value),
                            baseSp = valueSp,
                            maxWidth = valueMaxW.dp,
                            color = if (hasValue) C.Text else C.Dimmer,
                            weight = FontWeight.Bold,
                            minScale = 0.5f,
                            modifier = Modifier.weight(1f)
                        )
                        if (showSpark) {
                            Spacer(Modifier.width(6.dp))
                            Sparkline(
                                values = data.spark,
                                color = if ((data.changePct ?: 0.0) >= 0.0) C.Up else C.Down,
                                modifier = Modifier
                                    .width(sparkW.dp)
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp),
                                lineWidth = 1.2.dp,
                                fill = true
                            )
                        }
                    }
                    Spacer(Modifier.height(footerGap.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (data.changePct != null) {
                            Text(
                                "24h",
                                color = C.Dimmer,
                                fontSize = (changeSp * 0.8f).sp,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                fmtPctSigned(data.changePct),
                                color = changeColor(data.changePct),
                                fontSize = changeSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (data.stale && hasValue) {
                            StaleBadge(
                                data.staleAgoMinutes,
                                compact = tier != StatTileTier.LARGE,
                                short = tier != StatTileTier.LARGE
                            )
                        }
                    }
                }
            }
        }
    }
}
