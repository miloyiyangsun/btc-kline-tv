package com.milolab.btctv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.MarketFilter
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.UiState
import kotlinx.coroutines.delay

private val FocusBorder = Color(0xFFFFD79A)
private val StarColor = Color(0xFFFFC15E)

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchOverlay(
    st: UiState,
    accent: Color,
    s: Float,
    act: UiActions,
    targetLabel: String? = null,
    targetCurrent: String? = null,
    onFollowMain: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape((20f * s).dp)
    val firstFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var queryFocused by remember { mutableStateOf(false) }

    // 打开时先落在"全部/字母"上，避免电视端一进来就弹软键盘
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(60)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { act.closeSearch() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width((980f * s).dp)
                .fillMaxHeight(0.9f)
                .clip(shape)
                .background(C.Panel)
                .border(1.dp, Color(0xFF20283A), shape)
                .focusGroup()
                .pointerInput(Unit) { detectTapGestures { } }
                .padding((26f * s).dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (targetLabel != null) "为${targetLabel}选择标的" else "切换标的",
                    color = C.Text,
                    fontSize = (24f * s).sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width((12f * s).dp))
                Text(
                    if (targetLabel != null && targetCurrent != null) {
                        "当前 $targetCurrent"
                    } else {
                        "当前 " + st.instrument.displayName
                    },
                    color = accent,
                    fontSize = (14f * s).sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                if (st.catalogLoading) {
                    Text("目录更新中…", color = C.Dimmer, fontSize = (12f * s).sp)
                    Spacer(Modifier.width((14f * s).dp))
                }
                Text("按 BACK 关闭", color = C.Dimmer, fontSize = (12f * s).sp)
            }
            Spacer(Modifier.height((16f * s).dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height((50f * s).dp)
                        .clip(RoundedCornerShape((10f * s).dp))
                        .background(C.PanelHigh)
                        .border(
                            (if (queryFocused) 2f * s else 1f).dp,
                            if (queryFocused) accent else C.Grid,
                            RoundedCornerShape((10f * s).dp)
                        )
                        .padding(horizontal = (16f * s).dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = st.searchQuery,
                        onValueChange = act.setQuery,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = C.Text,
                            fontSize = (17f * s).sp,
                            fontFeatureSettings = "tnum"
                        ),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup()
                            .onFocusChanged {
                                queryFocused = it.isFocused
                                if (it.isFocused) keyboard?.show()
                            }
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                                    keyboard?.hide()
                                    runCatching { firstFocus.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (st.searchQuery.isEmpty()) {
                                    Text(
                                        "输入币种 / 美股代码或中文名，例如 SOL / NVDA / 英伟达",
                                        color = C.Dimmer,
                                        fontSize = (16f * s).sp
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width((12f * s).dp))
                SearchAction(
                    label = if (st.favorites.any { it.id == st.instrument.id }) "★ 已收藏" else "☆ 收藏当前",
                    accent = accent,
                    s = s
                ) { act.toggleFavorite(st.instrument) }
                if (onFollowMain != null) {
                    Spacer(Modifier.width((10f * s).dp))
                    SearchAction(label = "跟随主标的", accent = accent, s = s, onClick = onFollowMain)
                }
            }
            Spacer(Modifier.height((14f * s).dp))

            AlphabetRow(
                query = st.searchQuery,
                accent = accent,
                s = s,
                firstFocus = firstFocus,
                onQuery = act.setQuery
            )
            Spacer(Modifier.height((10f * s).dp))
            MarketFilterRow(
                selected = st.searchFilter,
                accent = accent,
                s = s,
                onSelect = act.setFilter
            )
            Spacer(Modifier.height((12f * s).dp))

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy((8f * s).dp)
            ) {
                if (st.searchQuery.isBlank()) {
                    val recent = st.recent.inFilter(st.searchFilter)
                    val favorites = st.favorites.inFilter(st.searchFilter)
                    val hot = st.hot.inFilter(st.searchFilter)
                    if (recent.isNotEmpty()) {
                        item { SectionTitle("最近使用", s) }
                        item { InstrumentFlow(recent, st, accent, s, act) }
                    }
                    if (favorites.isNotEmpty()) {
                        item { SectionTitle("收藏", s) }
                        item { InstrumentFlow(favorites, st, accent, s, act) }
                    }
                    if (hot.isNotEmpty()) {
                        item { SectionTitle("热门", s) }
                        item { InstrumentFlow(hot, st, accent, s, act) }
                    }
                    if (!st.catalogReady) {
                        item {
                            Text(
                                if (st.catalogLoading) "正在从 OKX 加载全部标的…"
                                else "标的目录未加载，热门列表仍可直接选择",
                                color = C.Dimmer,
                                fontSize = (12f * s).sp,
                                modifier = Modifier.padding(top = (4f * s).dp)
                            )
                        }
                    }
                } else {
                    val results = st.searchResults.inFilter(st.searchFilter)
                    item {
                        SectionTitle(
                            "搜索结果 · " + results.size + if (st.searching) " · 搜索中…" else "",
                            s
                        )
                    }
                    if (results.isEmpty()) {
                        item {
                            Text(
                                when {
                                    st.searching -> "正在搜索…"
                                    st.catalogLoading -> "目录加载中，请稍候…"
                                    else -> "没有匹配的标的，换个关键词或过滤试试"
                                },
                                color = C.Dimmer,
                                fontSize = (14f * s).sp
                            )
                        }
                    } else {
                        item { InstrumentFlow(results, st, accent, s, act) }
                    }
                }
                item {
                    Text(
                        "方向键选择 · OK 切换 · ☆ 收藏 · 输入框内按 OK 呼叫系统键盘",
                        color = C.Dimmer,
                        fontSize = (11f * s).sp,
                        modifier = Modifier.padding(top = (2f * s).dp)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 市场过滤

@Composable
private fun MarketFilterRow(
    selected: MarketFilter,
    accent: Color,
    s: Float,
    onSelect: (MarketFilter) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy((8f * s).dp)) {
        MarketFilter.entries.forEach { filter ->
            val sel = filter == selected
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape((7f * s).dp)
            Box(
                Modifier
                    .graphicsLayer {
                        val k = if (focused) 1.06f else 1f
                        scaleX = k
                        scaleY = k
                    }
                    .clip(shape)
                    .background(if (sel) accent else if (focused) Color(0xFF232A3A) else C.PanelHigh)
                    .border(
                        if (focused) (2f * s).dp else 1.dp,
                        if (focused) FocusBorder else if (sel) accent else C.Grid,
                        shape
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(filter) }
                    .padding(horizontal = (14f * s).dp, vertical = (6f * s).dp)
            ) {
                Text(
                    filter.label,
                    color = if (sel) Color(0xFF0A0C12) else if (focused) C.Text else C.Dim,
                    fontSize = (12.5f * s).sp,
                    fontWeight = if (sel || focused) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun List<Instrument>.inFilter(filter: MarketFilter): List<Instrument> = when (filter) {
    MarketFilter.ALL -> this
    MarketFilter.CRYPTO -> filter { it.marketType == MarketType.CRYPTO }
    MarketFilter.STOCK -> filter { it.marketType == MarketType.STOCK }
}

// ------------------------------------------------------------------ 字母索引

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlphabetRow(
    query: String,
    accent: Color,
    s: Float,
    firstFocus: FocusRequester,
    onQuery: (String) -> Unit
) {
    val labels = remember { listOf("全部") + ('A'..'Z').map { it.toString() } }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        labels.forEachIndexed { index, label ->
            val letters = label != "全部"
            val selected = if (letters) query.equals(label, ignoreCase = true) else query.isEmpty()
            LetterChip(
                label = label,
                selected = selected,
                accent = accent,
                s = s,
                focusRequester = if (index == 0) firstFocus else null
            ) { onQuery(if (letters) label else "") }
        }
    }
}

@Composable
private fun LetterChip(
    label: String,
    selected: Boolean,
    accent: Color,
    s: Float,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((7f * s).dp)
    Box(
        Modifier
            .graphicsLayer {
                val k = if (focused) 1.08f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (selected) accent else if (focused) Color(0xFF232A3A) else C.PanelHigh)
            .border(
                if (focused) (2f * s).dp else 1.dp,
                if (focused) FocusBorder else if (selected) accent else C.Grid,
                shape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (12f * s).dp, vertical = (5f * s).dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0A0C12) else if (focused) C.Text else C.Dim,
            fontSize = (12.5f * s).sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ------------------------------------------------------------------ 标的列表

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstrumentFlow(
    items: List<Instrument>,
    st: UiState,
    accent: Color,
    s: Float,
    act: UiActions
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((8f * s).dp),
        verticalArrangement = Arrangement.spacedBy((8f * s).dp)
    ) {
        items.forEach { inst ->
            InstrumentEntry(
                inst = inst,
                selected = inst.id == st.instrument.id,
                favorite = st.favorites.any { it.id == inst.id },
                accent = accent,
                s = s,
                onSelect = { act.selectInstrument(inst) },
                onToggleFavorite = { act.toggleFavorite(inst) }
            )
        }
    }
}

@Composable
private fun InstrumentEntry(
    inst: Instrument,
    selected: Boolean,
    favorite: Boolean,
    accent: Color,
    s: Float,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var selectFocused by remember(inst.id) { mutableStateOf(false) }
        val leftShape = RoundedCornerShape(
            topStart = (10f * s).dp,
            bottomStart = (10f * s).dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )
        Box(
            Modifier
                .graphicsLayer {
                    val k = if (selectFocused) 1.04f else 1f
                    scaleX = k
                    scaleY = k
                }
                .clip(leftShape)
                .background(
                    if (selected) accent.copy(alpha = 0.18f)
                    else if (selectFocused) Color(0xFF232A3A) else C.PanelHigh
                )
                .border(
                    if (selectFocused) (2f * s).dp else 1.dp,
                    if (selectFocused) FocusBorder else if (selected) accent.copy(alpha = 0.65f) else C.Grid,
                    leftShape
                )
                .onFocusChanged { selectFocused = it.isFocused }
                .clickable { onSelect() }
                .padding(start = (14f * s).dp, end = (10f * s).dp, top = (6f * s).dp, bottom = (6f * s).dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        inst.displayName,
                        color = if (selected) accent else C.Text,
                        fontSize = (14.5f * s).sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width((6f * s).dp))
                    TypeBadge(inst.marketType, s)
                    inst.stockKind?.let { kind ->
                        Spacer(Modifier.width((4f * s).dp))
                        StockKindBadge(kind, s)
                    }
                }
                Text(
                    if (inst.marketType == MarketType.STOCK) stockSubtitle(inst)
                    else if (inst.name.isNotBlank()) inst.name
                    else inst.symbols.keys.joinToString(" · "),
                    color = C.Dimmer,
                    fontSize = (10f * s).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        var starFocused by remember(inst.id) { mutableStateOf(false) }
        val rightShape = RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = (10f * s).dp,
            bottomEnd = (10f * s).dp
        )
        Box(
            Modifier
                .graphicsLayer {
                    val k = if (starFocused) 1.08f else 1f
                    scaleX = k
                    scaleY = k
                }
                .clip(rightShape)
                .background(
                    if (favorite) StarColor.copy(alpha = 0.14f)
                    else if (starFocused) Color(0xFF232A3A) else C.PanelHigh
                )
                .border(
                    if (starFocused) (2f * s).dp else 1.dp,
                    if (starFocused) FocusBorder else if (favorite) StarColor.copy(alpha = 0.5f) else C.Grid,
                    rightShape
                )
                .onFocusChanged { starFocused = it.isFocused }
                .clickable { onToggleFavorite() }
                .padding(horizontal = (9f * s).dp, vertical = (12f * s).dp)
        ) {
            Text(
                if (favorite) "★" else "☆",
                color = if (favorite) StarColor else C.Dim,
                fontSize = (15f * s).sp
            )
        }
    }
}

@Composable
private fun TypeBadge(type: MarketType, s: Float) {
    val stock = type == MarketType.STOCK
    val col = if (stock) Color(0xFF7FB0FF) else Color(0xFFFFB454)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(col.copy(alpha = 0.14f))
            .border(1.dp, col.copy(alpha = 0.32f), shape)
            .padding(horizontal = (6f * s).dp, vertical = (1f * s).dp)
    ) {
        Text(
            if (stock) "美股" else "加密",
            color = col,
            fontSize = (9.5f * s).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StockKindBadge(kind: StockKind, s: Float) {
    val col = if (kind == StockKind.PERP) Color(0xFF8AE1C1) else Color(0xFFC9A0FF)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(col.copy(alpha = 0.13f))
            .border(1.dp, col.copy(alpha = 0.32f), shape)
            .padding(horizontal = (6f * s).dp, vertical = (1f * s).dp)
    ) {
        Text(
            kind.label,
            color = col,
            fontSize = (9.5f * s).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 永续和代币现货条目靠交易代码 + 类型 + OKX instId 区分 */
private fun stockSubtitle(inst: Instrument): String = buildList {
    add(inst.baseCurrency)
    inst.stockKind?.let { add(it.label) }
    inst.symbols[PROVIDER_OKX]?.let { add(it) }
}.joinToString(" · ")

@Composable
private fun SectionTitle(text: String, s: Float) {
    Text(
        text,
        color = C.Dimmer,
        fontSize = (12f * s).sp,
        letterSpacing = (1.4f * s).sp,
        modifier = Modifier.padding(top = (4f * s).dp)
    )
}

@Composable
private fun SearchAction(label: String, accent: Color, s: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((10f * s).dp)
    Box(
        Modifier
            .graphicsLayer {
                val k = if (focused) 1.05f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (focused) accent else C.PanelHigh)
            .border(
                if (focused) (2f * s).dp else 1.dp,
                if (focused) FocusBorder else C.Grid,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (16f * s).dp, vertical = (14f * s).dp)
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF0A0C12) else C.Text,
            fontSize = (13f * s).sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}
