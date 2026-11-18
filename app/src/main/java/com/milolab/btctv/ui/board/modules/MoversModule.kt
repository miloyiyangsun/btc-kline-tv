package com.milolab.btctv.ui.board.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.fmtPrice

/**
 * 涨跌榜：SPLIT = 上下两个分区；TABS = 顶部标签页切换。
 * 行样式与 Watchlist 一致（名次/名称/价格/涨跌），宽格额外显示成交额。
 */
@Composable
fun MoversModule(
    data: MoversData,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    SlotSurface(focused = focused, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = maxWidth.value
            val hDp = maxHeight.value
            val padH = (wDp * 0.02f).coerceIn(5f, 12f).dp
            val padV = (hDp * 0.015f).coerceIn(3f, 8f).dp
            val empty = data.gainers.isEmpty() && data.losers.isEmpty()

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = padH, vertical = padV)
            ) {
                when {
                    empty && data.phase == ModulePhase.LOADING -> ModulePlaceholder(
                        text = "加载榜单…",
                        modifier = Modifier.fillMaxSize(),
                        loading = true,
                        baseSp = (hDp * 0.05f).coerceIn(8.5f, 12f)
                    )

                    empty -> ModulePlaceholder(
                        text = "暂无榜单",
                        modifier = Modifier.fillMaxSize(),
                        baseSp = (hDp * 0.05f).coerceIn(8.5f, 12f)
                    )

                    data.layout == MoversLayout.SPLIT -> SplitLayout(
                        data = data,
                        widthDp = wDp,
                        heightDp = hDp
                    )

                    else -> TabsLayout(
                        data = data,
                        widthDp = wDp,
                        heightDp = hDp
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitLayout(
    data: MoversData,
    widthDp: Float,
    heightDp: Float
) {
    val sectionH = heightDp / 2f
    val headerH = (sectionH * 0.18f).coerceIn(14f, 24f)
    val rowsArea = (sectionH - headerH).coerceAtLeast(1f)
    val desiredRowH = (rowsArea / 4.2f).coerceIn(19f, 36f)
    val fitRows = (rowsArea / desiredRowH).toInt().coerceAtLeast(1)
    val rowH = (rowsArea / fitRows).coerceAtMost(44f)
    val listAlpha = if (data.stale) 0.72f else 1f

    Column(Modifier.fillMaxSize()) {
        SectionHeader(
            label = MoversMetric.GAINERS.label,
            count = data.gainers.size,
            color = C.Up,
            heightDp = headerH,
            stale = data.stale,
            staleAgo = data.staleAgoMinutes
        )
        Column(Modifier.fillMaxWidth().alpha(listAlpha)) {
            if (data.gainers.isEmpty()) {
                EmptySectionRow("暂无上涨标的", rowH)
            } else {
                data.gainers.take(fitRows).forEach { row ->
                    MoverRowView(row, rowH, widthDp, 0f, C.Up)
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .height(1.dp)
                .background(C.Grid.copy(alpha = 0.65f))
        )
        SectionHeader(
            label = MoversMetric.LOSERS.label,
            count = data.losers.size,
            color = C.Down,
            heightDp = headerH,
            stale = false,
            staleAgo = null
        )
        Column(Modifier.fillMaxWidth().alpha(listAlpha)) {
            if (data.losers.isEmpty()) {
                EmptySectionRow("暂无下跌标的", rowH)
            } else {
                data.losers.take(fitRows).forEach { row ->
                    MoverRowView(row, rowH, widthDp, 0f, C.Down)
                }
            }
        }
    }
}

@Composable
private fun TabsLayout(
    data: MoversData,
    widthDp: Float,
    heightDp: Float
) {
    val tabH = (heightDp * 0.13f).coerceIn(17f, 30f)
    val rowsArea = (heightDp - tabH).coerceAtLeast(1f)
    val desiredRowH = (rowsArea / 5.5f).coerceIn(20f, 38f)
    val visibleRows = (rowsArea / desiredRowH).toInt().coerceAtLeast(1)
    val rowH = (rowsArea / visibleRows).coerceAtMost(46f)
    val rows = if (data.activeTab == MoversMetric.GAINERS) data.gainers else data.losers
    val accent = changeColor(if (data.activeTab == MoversMetric.GAINERS) 1.0 else -1.0)
    val listState = rememberLazyListState()
    val listAlpha = if (data.stale) 0.72f else 1f

    LaunchedEffect(data.activeTab) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(tabH.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabLabel(
                text = MoversMetric.GAINERS.label,
                count = data.gainers.size,
                active = data.activeTab == MoversMetric.GAINERS,
                accent = C.Up,
                heightDp = tabH
            )
            Spacer(Modifier.width((tabH * 0.55f).coerceIn(6f, 14f).dp))
            TabLabel(
                text = MoversMetric.LOSERS.label,
                count = data.losers.size,
                active = data.activeTab == MoversMetric.LOSERS,
                accent = C.Down,
                heightDp = tabH
            )
            Spacer(Modifier.weight(1f))
            if (data.stale) {
                StaleBadge(
                    data.staleAgoMinutes,
                    compact = true,
                    short = widthDp < 300f
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(C.Grid.copy(alpha = 0.65f))
        )
        if (rows.isEmpty()) {
            ModulePlaceholder(
                text = "暂无榜单",
                modifier = Modifier.fillMaxSize(),
                baseSp = (rowH * 0.34f).coerceIn(8.5f, 11.5f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().alpha(listAlpha)
            ) {
                itemsIndexed(rows) { _, row ->
                    MoverRowView(row, rowH, widthDp, 0f, accent)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    color: Color,
    heightDp: Float,
    stale: Boolean,
    staleAgo: Long?
) {
    val fs = (heightDp * 0.56f).coerceIn(8f, 11.5f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size((fs * 0.34f).coerceIn(3.5f, 5.5f).dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = color,
            fontSize = fs.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.width(4.dp))
        Text(
            count.toString(),
            color = C.Dimmer,
            fontSize = (fs * 0.85f).sp,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.weight(1f))
        if (stale) StaleBadge(staleAgo, compact = true)
    }
}

@Composable
private fun TabLabel(
    text: String,
    count: Int,
    active: Boolean,
    accent: Color,
    heightDp: Float
) {
    val fs = (heightDp * 0.48f).coerceIn(8.5f, 12f)
    Row(
        Modifier
            .height(heightDp.dp)
            .drawBehind {
                if (active) {
                    val t = 2.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, size.height - t),
                        size = Size(size.width, t)
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (active) accent else C.Dim,
            fontSize = fs.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.width(3.dp))
        Text(
            count.toString(),
            color = if (active) accent.copy(alpha = 0.75f) else C.Dimmer,
            fontSize = (fs * 0.85f).sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun MoverRowView(
    row: MoverRow,
    rowHeightDp: Float,
    widthDp: Float,
    extraStartPad: Float,
    accent: Color
) {
    val fontSp = listRowFontSp(rowHeightDp)
    val priceSp = (fontSp * 0.92f).coerceAtLeast(8f)
    val changeSp = (fontSp * 0.80f).coerceAtLeast(7.5f)
    val rankW = (rowHeightDp * 0.86f).coerceIn(14f, 26f)
    val showPrice = widthDp >= 136f
    val volW = (widthDp * 0.16f).coerceIn(44f, 92f)
    val pillW = (rowHeightDp * 3.0f).coerceIn(if (widthDp < 180f) 44f else 52f, 88f)
    val gapDp = 6f
    val priceWWithVol = (widthDp * 0.22f).coerceIn(40f, 120f)
    val priceWNoVol = (widthDp * 0.30f).coerceIn(40f, 120f)
    val nameWWithVol = widthDp - extraStartPad * 2f - rankW - 4f -
        volW - 4f - priceWWithVol - 4f - pillW - gapDp
    val showVolume = widthDp >= 300f && row.volumeLabel != null && nameWWithVol >= 30f
    val priceW = if (showVolume) priceWWithVol else priceWNoVol
    val nameW = (
        widthDp - extraStartPad * 2f - rankW - 4f -
            (if (showVolume) volW + 4f else 0f) -
            (if (showPrice) priceW + 4f else 0f) -
            pillW - gapDp
        ).coerceAtLeast(22f)

    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp)
            .padding(horizontal = extraStartPad.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(rankW.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                row.rank.toString(),
                color = if (row.rank <= 3) accent else C.Dimmer,
                fontSize = (fontSp * 0.82f).coerceAtLeast(7.5f).sp,
                fontWeight = if (row.rank <= 3) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false
            )
        }
        FitText(
            text = row.instrument.baseCurrency.ifBlank { row.instrument.displayName },
            baseSp = fontSp,
            maxWidth = (nameW - 2f).dp.coerceAtLeast(16.dp),
            color = C.Text.copy(alpha = 0.9f),
            weight = FontWeight.Medium,
            modifier = Modifier.width(nameW.dp)
        )
        if (showVolume) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(volW.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    row.volumeLabel ?: "",
                    color = C.Dimmer,
                    fontSize = (fontSp * 0.78f).coerceAtLeast(7f).sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (showPrice) {
            Box(Modifier.width(priceW.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    fmtPrice(row.price ?: 0.0, row.priceDecimals),
                    color = if (row.price == null) C.Dimmer else C.Text,
                    fontSize = priceSp.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.width(pillW.dp), contentAlignment = Alignment.CenterEnd) {
            ChangePill(
                row.changePct,
                baseSp = changeSp,
                compact = rowHeightDp < 32f
            )
        }
    }
}

@Composable
private fun EmptySectionRow(text: String, rowHeightDp: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            color = C.Dimmer,
            fontSize = listRowFontSp(rowHeightDp).sp,
            maxLines = 1,
            softWrap = false
        )
    }
}
