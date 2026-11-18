package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.fmtPrice

/**
 * 自选/排行列表：名称、价格、涨跌色块、可选 spark。
 * 行高随格子高度变化；选中行左侧暖色条 + 淡底；行数超出可滚动。
 * 选中项由 data.selectedIndex 提供，模块本身不持有业务状态。
 */
@Composable
fun WatchlistModule(
    data: WatchlistData,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val headerH = (hDp * 0.11f).coerceIn(18f, 30f)
            val showHeader = hDp >= 88f
            val headerSp = (headerH * 0.56f).coerceIn(9f, 13f)
            val padH = (wDp * 0.018f).coerceIn(5f, 11f)
            val listH = (hDp - if (showHeader) headerH else 0f).coerceAtLeast(1f)
            val rowH = (listH / 5.2f).coerceIn(24f, 46f)
            val listState = rememberLazyListState()

            LaunchedEffect(data.selectedIndex, data.rows.size) {
                val i = data.selectedIndex ?: return@LaunchedEffect
                if (i in data.rows.indices) listState.scrollToItem(i)
            }

            Column(Modifier.fillMaxSize().padding(horizontal = padH.dp)) {
                if (showHeader) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(headerH.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            data.title,
                            color = C.Text,
                            fontSize = headerSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (data.rows.isNotEmpty()) {
                            Spacer(Modifier.width(5.dp))
                            MiniBadge(
                                data.rows.size.toString(),
                                color = C.Dim,
                                baseSp = (headerSp * 0.74f).coerceAtLeast(7f)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (data.stale) {
                            StaleBadge(data.staleAgoMinutes, compact = true)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(C.Grid.copy(alpha = 0.6f))
                    )
                }

                when {
                    data.rows.isNotEmpty() -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        itemsIndexed(data.rows) { index, row ->
                            WatchRowView(
                                row = row,
                                selected = data.selectedIndex == index,
                                rowHeightDp = rowH,
                                widthDp = wDp,
                                padH = padH
                            )
                        }
                    }

                    data.phase == ModulePhase.LOADING -> ModulePlaceholder(
                        text = "加载自选…",
                        modifier = Modifier.fillMaxSize(),
                        loading = true,
                        baseSp = (headerSp * 0.9f).coerceIn(8.5f, 12f)
                    )

                    else -> ModulePlaceholder(
                        text = "暂无自选",
                        modifier = Modifier.fillMaxSize(),
                        baseSp = (headerSp * 0.9f).coerceIn(8.5f, 12f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchRowView(
    row: WatchRow,
    selected: Boolean,
    rowHeightDp: Float,
    widthDp: Float,
    padH: Float
) {
    val fontSp = listRowFontSp(rowHeightDp)
    val priceSp = (fontSp * 0.92f).coerceAtLeast(8f)
    val changeSp = (fontSp * 0.80f).coerceAtLeast(7.5f)
    val showSpark = row.spark.size >= 2 && widthDp >= 250f && rowHeightDp >= 30f
    val showPrice = widthDp >= 220f
    val sparkW = (widthDp * 0.15f).coerceIn(26f, 64f)
    val priceW = (widthDp * if (showSpark) 0.28f else 0.36f).coerceIn(48f, 150f)
    val pillW = minOf(
        (rowHeightDp * 3.1f).coerceIn(52f, 92f),
        widthDp * if (showPrice) 0.30f else 0.44f
    ).coerceAtLeast(44f)
    val nameW = (
        widthDp - 2.5f - 5f - padH * 0.5f -
            (if (showSpark) sparkW + 4f else 0f) -
            (if (showPrice) priceW + 5f else 0f) -
            pillW
        ).coerceAtLeast(24f)
    val rowAlpha = if (row.stale) 0.62f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SlotFocusBorder.copy(alpha = 0.10f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .height((rowHeightDp * 0.58f).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) SlotFocusBorder else Color.Transparent)
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 5.dp, end = (padH * 0.5f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.width(nameW.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FitText(
                    text = row.instrument.baseCurrency.ifBlank { row.instrument.displayName },
                    baseSp = fontSp,
                    maxWidth = (nameW - 8f).dp.coerceAtLeast(16.dp),
                    color = if (selected) C.Text else C.Text.copy(alpha = 0.9f),
                    weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.stale) {
                    Spacer(Modifier.width(3.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(StaleAmber.copy(alpha = 0.85f))
                    )
                }
            }
            if (showSpark) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .width(sparkW.dp)
                        .height((rowHeightDp * 0.62f).dp)
                ) {
                    Sparkline(
                        values = row.spark,
                        color = changeColor(row.changePct).copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxSize(),
                        lineWidth = 1.1.dp,
                        fill = false,
                        endDot = false
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (showPrice) {
                Box(
                    Modifier.width(priceW.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = fmtPrice(row.price ?: 0.0, row.priceDecimals),
                        color = if (row.price == null) C.Dimmer else C.Text.copy(alpha = rowAlpha),
                        fontSize = priceSp.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                    )
                }
                Spacer(Modifier.width(5.dp))
            }
            Box(
                Modifier.width(pillW.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                ChangePill(
                    row.changePct,
                    baseSp = changeSp,
                    compact = rowHeightDp < 34f
                )
            }
        }
    }
}
