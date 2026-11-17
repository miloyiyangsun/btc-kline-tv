@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.milolab.btctv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.IndicatorGroup
import com.milolab.btctv.data.UiState
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.indicators.IndicatorSpec
import com.milolab.btctv.data.paramValueLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
@Composable
fun TvScreenInner(st: UiState, act: UiActions) {
    val accent = Color(st.instrument.accent)
    val rootFocus = remember { FocusRequester() }

    val clock by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 设置/搜索面板关闭后把焦点还给主界面，遥控器才能继续控制周期/缩放
    LaunchedEffect(st.settingsOpen, st.searchOpen) {
        if (!st.settingsOpen && !st.searchOpen) {
            delay(60)
            runCatching { rootFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .focusRequester(rootFocus)
            .focusProperties { canFocus = !st.settingsOpen && !st.searchOpen }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (val intent = routeKey(st.settingsOpen, st.searchOpen, ev.key)) {
                    is KeyIntent.ChangeTf -> { act.setTf(st.tf.step(intent.delta)); true }
                    is KeyIntent.Zoom -> { act.zoom(intent.delta); true }
                    KeyIntent.OpenSettings -> { act.openSettings(); true }
                    KeyIntent.OpenSearch -> { act.openSearch(); true }
                    KeyIntent.None -> false
                }
            }
    ) {
        val s = (maxHeight.value / 540f).coerceIn(0.72f, 2.6f)
        val driftX = if (st.antiBurn) (sin(clock / 210000.0) * 5.0 * s).dp else 0.dp
        val driftY = if (st.antiBurn) (sin(clock / 173000.0) * 3.5 * s).dp else 0.dp

        // 背景：深色渐变 + 币种色氛围光
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(C.BgTop, C.Bg), startY = 0f, endY = size.height))
            drawRect(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.34f),
                    radius = size.height * 0.9f
                )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .offset(driftX, driftY)
                .padding(horizontal = (42f * s).dp, vertical = (24f * s).dp)
        ) {
            Header(st, accent, s, clock, act)
            Spacer(Modifier.height((10f * s).dp))
            PriceBlock(st, accent, s)
            Spacer(Modifier.height((10f * s).dp))
            ChartCard(st, accent, s, Modifier.weight(1f))
            Spacer(Modifier.height((10f * s).dp))
            Footer(st, accent, s, act)
        }

        if (st.settingsOpen) {
            SettingsOverlay(st, accent, s, act)
        }
        if (st.searchOpen) {
            SearchOverlay(st, accent, s, act)
        }
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun Header(st: UiState, accent: Color, s: Float, clock: Long, act: UiActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size((48f * s).dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.13f))
                .border((1.4f * s).dp, accent.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                st.instrument.glyph,
                color = accent,
                fontSize = (26f * s).sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width((14f * s).dp))
        Column(
            Modifier
                // 面板打开时禁止焦点跑回标题入口，避免按键漏到下层
                .focusProperties { canFocus = !st.settingsOpen && !st.searchOpen }
                .clip(RoundedCornerShape((10f * s).dp))
                .clickable { act.openSearch() }
                .padding(horizontal = (8f * s).dp, vertical = (3f * s).dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.instrument.displayName,
                    color = C.Text,
                    fontSize = (23f * s).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (1.6f * s).sp
                )
                Spacer(Modifier.width((8f * s).dp))
                Text(
                    "▾",
                    color = accent,
                    fontSize = (15f * s).sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                if (st.instrument.marketType == MarketType.STOCK) {
                    "美股行情 · OKX　MENU 切换标的"
                } else {
                    "实时行情 · " + (st.provider ?: "接入中") + "　MENU 切换标的"
                },
                color = C.Dim,
                fontSize = (12f * s).sp,
                letterSpacing = (0.8f * s).sp
            )
        }
        Spacer(Modifier.weight(1f))
        ConnBadge(st.conn, s)
        if (st.showClock) {
            Spacer(Modifier.width((18f * s).dp))
            Text(
                timeHms(clock),
                color = C.Dim,
                fontSize = (20f * s).sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (1.2f * s).sp
            )
        }
    }
}

@Composable
private fun ConnBadge(conn: Conn, s: Float) {
    val label = when (conn) {
        Conn.LIVE -> "实时"
        Conn.POLLING -> "轮询"
        Conn.CONNECTING -> "连接中"
        Conn.OFFLINE -> "离线"
    }
    val color = when (conn) {
        Conn.LIVE -> C.Up
        Conn.POLLING -> Color(0xFFFFC46B)
        Conn.CONNECTING -> C.Dim
        Conn.OFFLINE -> C.Down
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(50))
            .padding(horizontal = (12f * s).dp, vertical = (6f * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size((8f * s).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (conn == Conn.LIVE) pulse else 0.85f))
        )
        Spacer(Modifier.width((8f * s).dp))
        Text(
            label,
            color = color,
            fontSize = (12.5f * s).sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (1f * s).sp
        )
    }
}

// ------------------------------------------------------------------ 价格

@Composable
private fun PriceBlock(st: UiState, accent: Color, s: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BigPrice(st.price, st.prevPrice, st.flash, st.instrument.pricePrefix, st.instrument.priceDecimals, s)
        Spacer(Modifier.width((18f * s).dp))
        ChangePill(st, s, Modifier.padding(bottom = (36f * s).dp))
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = (4f * s).dp)) {
            StatLine("24H 最高", st.quote?.high24h?.let { fmtPrice(it, st.instrument.priceDecimals) }, s)
            Spacer(Modifier.height((8f * s).dp))
            StatLine("24H 最低", st.quote?.low24h?.let { fmtPrice(it, st.instrument.priceDecimals) }, s)
            Spacer(Modifier.height((8f * s).dp))
            StatLine(
                "24H 成交",
                st.quote?.vol24h?.let { fmtVol(it) + " " + st.instrument.baseCurrency },
                s
            )
        }
    }
}

@Composable
private fun ChangePill(st: UiState, s: Float, modifier: Modifier = Modifier) {
    val pct = st.quote?.changePct
    val abs = st.quote?.changeAbs
    if (pct != null && abs != null) {
        val col = if (pct >= 0) C.Up else C.Down
        Row(
            modifier
                .clip(RoundedCornerShape(50))
                .background(col.copy(alpha = 0.13f))
                .border(1.dp, col.copy(alpha = 0.26f), RoundedCornerShape(50))
                .padding(horizontal = (14f * s).dp, vertical = (7f * s).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (pct >= 0) "▲ " else "▼ ") + fmtPct(pct),
                color = col,
                fontSize = (18f * s).sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(Modifier.width((10f * s).dp))
            Text(
                fmtSigned(abs, st.instrument.priceDecimals),
                color = col.copy(alpha = 0.7f),
                fontSize = (15f * s).sp,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    } else {
        Text(
            if (st.conn == Conn.OFFLINE) "连接失败，正在重试…" else "正在同步 24H 行情…",
            color = C.Dimmer,
            fontSize = (14f * s).sp,
            modifier = modifier
        )
    }
}

@Composable
private fun StatLine(label: String, value: String?, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = C.Dimmer,
            fontSize = (12f * s).sp,
            letterSpacing = (0.9f * s).sp
        )
        Spacer(Modifier.width((12f * s).dp))
        Text(
            value ?: "—",
            color = C.Text.copy(alpha = 0.88f),
            fontSize = (16f * s).sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BigPrice(price: Double, prev: Double, flashOn: Boolean, prefix: String, decimals: Int, s: Float) {
    val anim = remember { Animatable(0f) }
    var dir by remember { mutableStateOf(1) }

    LaunchedEffect(price) {
        if (price > prev) dir = 1 else if (price < prev) dir = -1
        if (flashOn && prev > 0.0 && price != prev) {
            anim.snapTo(1f)
            anim.animateTo(0f, tween(700, easing = LinearOutSlowInEasing))
        }
    }

    val color = lerp(C.Text, if (dir > 0) C.Up else C.Down, anim.value * 0.92f)
    val scale = 1f + anim.value * 0.010f

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            prefix,
            color = C.Dimmer,
            fontSize = (38f * s).sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.alignByBaseline().padding(end = (8f * s).dp)
        )
        Text(
            if (price > 0) fmtPrice(price, decimals) else "— — —",
            color = color,
            fontSize = (96f * s).sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            letterSpacing = (-2.5f * s).sp,
            maxLines = 1,
            modifier = Modifier
                .alignByBaseline()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.85f)
                }
        )
    }
}

// ------------------------------------------------------------------ 图表

@Composable
private fun ChartCard(st: UiState, accent: Color, s: Float, modifier: Modifier) {
    val shape = RoundedCornerShape((16f * s).dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0C0F17), Color(0xFF070910))))
            .border(1.dp, Color(0xFF161C29), shape)
            .padding(start = (10f * s).dp, top = (6f * s).dp, bottom = (4f * s).dp, end = (2f * s).dp)
    ) {
        if (st.candles.size < 2) {
            LoadingChart(st, accent, s)
        } else {
            PriceChart(
                candles = st.candles,
                style = st.style,
                showVolume = st.showVolume,
                tf = st.tf,
                accent = accent,
                up = C.Up,
                down = C.Down,
                s = s,
                priceDecimals = st.instrument.priceDecimals,
                modifier = Modifier.fillMaxSize(),
                visibleCount = st.zoom,
                indicatorSettings = st.indicatorSettings,
                indicatorResults = st.indicatorResults
            )
        }
    }
}

@Composable
private fun LoadingChart(st: UiState, accent: Color, s: Float) {
    val pulse by rememberInfiniteTransition(label = "load").animateFloat(
        0.25f, 0.9f,
        infiniteRepeatable(tween(1100, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "loadAlpha"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                st.instrument.displayName,
                color = accent.copy(alpha = 0.55f),
                fontSize = (26f * s).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (3f * s).sp
            )
            Spacer(Modifier.height((10f * s).dp))
            Text(
                if (st.conn == Conn.OFFLINE) "行情源连接失败，正在自动重试" else "正在拉取K线…",
                color = C.Dim.copy(alpha = pulse),
                fontSize = (15f * s).sp,
                letterSpacing = (1f * s).sp
            )
        }
    }
}

// ------------------------------------------------------------------ 底部

@Composable
private fun Footer(st: UiState, accent: Color, s: Float, act: UiActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TF.entries.forEach { tf ->
            val sel = tf == st.tf
            Box(
                Modifier
                    .padding(end = (8f * s).dp)
                    .clip(RoundedCornerShape((9f * s).dp))
                    .background(if (sel) accent else C.Panel)
                    .border(1.dp, if (sel) accent else C.Grid, RoundedCornerShape((9f * s).dp))
                    .pointerInput(tf) { detectTapGestures { act.setTf(tf) } }
                    .padding(horizontal = (16f * s).dp, vertical = (7f * s).dp)
            ) {
                Text(
                    tf.label,
                    color = if (sel) Color(0xFF0A0C12) else C.Dim,
                    fontSize = (14f * s).sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
        Text(
            "K线 " + st.zoom,
            color = C.Dimmer,
            fontSize = (12f * s).sp,
            letterSpacing = (0.8f * s).sp,
            modifier = Modifier.padding(start = (6f * s).dp, end = (18f * s).dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            "◀ ▶ 周期      ▲ ▼ 缩放      MENU 切换标的      OK 设置",
            color = C.Dimmer,
            fontSize = (12f * s).sp,
            letterSpacing = (0.6f * s).sp
        )
    }
}

// ------------------------------------------------------------------ 设置

@Composable
private fun SettingsOverlay(st: UiState, accent: Color, s: Float, act: UiActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { act.closeSettings() } },
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape((20f * s).dp)
        Column(
            Modifier
                .width((840f * s).dp)
                .fillMaxHeight(0.92f)
                .clip(shape)
                .background(C.Panel)
                .border(1.dp, Color(0xFF20283A), shape)
                .focusGroup()
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(
                Modifier.padding(
                    start = (26f * s).dp,
                    end = (20f * s).dp,
                    top = (20f * s).dp,
                    bottom = (12f * s).dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设置", color = C.Text, fontSize = (24f * s).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width((14f * s).dp))
                Text(
                    "指标已开启 ${st.indicatorSettings.enabledCount}",
                    color = C.Dimmer,
                    fontSize = (12f * s).sp
                )
                Spacer(Modifier.weight(1f))
                MiniActionButton("全部关闭", accent, s) { act.allIndicatorsOff() }
                MiniActionButton("恢复默认", accent, s) { act.resetIndicators() }
            }

            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = (26f * s).dp)
            ) {
                SectionLabel("图表样式", s)
                Row {
                    val firstFocus = remember { FocusRequester() }
                    // 只请求一次；定时重试会在离屏渲染里触发 Skiko 崩溃。
                    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
                    ChartStyle.entries.forEachIndexed { i, style ->
                        Chip(
                            style.label,
                            style == st.style,
                            accent,
                            s,
                            focusRequester = if (i == 0) firstFocus else null
                        ) { act.setStyle(style) }
                    }
                }
                Spacer(Modifier.height((14f * s).dp))

                ToggleRow("价格跳动闪烁", st.flash, s) { act.toggleFlash() }
                ToggleRow("防烧屏微位移", st.antiBurn, s) { act.toggleAntiBurn() }
                ToggleRow("显示时钟", st.showClock, s) { act.toggleClock() }
                ToggleRow("显示成交量", st.showVolume, s) { act.toggleVolume() }
                ToggleRow("开机自动启动（部分设备有效）", st.autoStart, s) { act.toggleAutoStart() }

                Spacer(Modifier.height((14f * s).dp))
                Row {
                    ActionButton("立即重连", accent, s) { act.reconnect() }
                    ActionButton("关闭", accent, s) { act.closeSettings() }
                }

                Spacer(Modifier.height((22f * s).dp))
                IndicatorSettingsSection(st, accent, s, act)

                Spacer(Modifier.height((18f * s).dp))
                Row {
                    ActionButton("全部关闭", accent, s) { act.allIndicatorsOff() }
                    ActionButton("恢复默认", accent, s) { act.resetIndicators() }
                }
                Spacer(Modifier.height((10f * s).dp))
                Text(
                    "数据源：OKX（加密币 + 美股永续/代币现货） · Binance / Bybit（加密）　　" +
                        "方向键移动焦点 · OK 开关 · 长按 OK 或按 ▶ 调参数 · 返回键收起参数",
                    color = C.Dimmer,
                    fontSize = (11.5f * s).sp,
                    modifier = Modifier.padding(bottom = (16f * s).dp)
                )
            }
        }
    }
}

@Composable
private fun IndicatorSettingsSection(st: UiState, accent: Color, s: Float, act: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("指标", color = C.Text, fontSize = (18f * s).sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width((10f * s).dp))
        Text(
            "已开启 ${st.indicatorSettings.enabledCount}",
            color = accent.copy(alpha = 0.85f),
            fontSize = (11.5f * s).sp
        )
    }
    Spacer(Modifier.height((4f * s).dp))
    Text(
        "默认全部关闭。开启后主图/副图才会出现，关掉立即收回。",
        color = C.Dimmer,
        fontSize = (11.5f * s).sp
    )
    val notice = st.indicatorNotice
    if (notice != null) {
        Spacer(Modifier.height((6f * s).dp))
        Text(notice, color = Color(0xFFFFC46B), fontSize = (12f * s).sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height((10f * s).dp))

    for (group in IndicatorGroup.entries) {
        val collapsed = group.name in st.collapsedIndicatorGroups
        val specs = group.specs()
        val enabledInGroup = specs.count { st.indicatorSettings.isEnabled(it.id) }
        IndicatorGroupHeader(
            label = group.label,
            count = enabledInGroup,
            collapsed = collapsed,
            accent = accent,
            s = s
        ) { act.toggleIndicatorGroup(group) }

        if (!collapsed) {
            for (spec in specs) {
                val enabled = st.indicatorSettings.isEnabled(spec.id)
                IndicatorToggleRow(
                    spec = spec,
                    enabled = enabled,
                    summary = st.indicatorSettings.summaryFor(spec),
                    expanded = st.expandedIndicator == spec.id,
                    accent = accent,
                    s = s,
                    act = act
                )
                if (st.expandedIndicator == spec.id) {
                    if (spec.params.isEmpty()) {
                        Text(
                            "无可调参数",
                            color = C.Dimmer,
                            fontSize = (11.5f * s).sp,
                            modifier = Modifier.padding(start = (28f * s).dp, top = (4f * s).dp, bottom = (6f * s).dp)
                        )
                    } else {
                        val params = st.indicatorSettings.paramsFor(spec)
                        for (ps in spec.params) {
                            val value = params.d(ps.key, ps.default)
                            ParamRow(
                                label = ps.label,
                                value = paramValueLabel(ps, value),
                                s = s,
                                onStep = { delta -> act.stepIndicatorParam(spec.id, ps.key, delta) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height((8f * s).dp))
    }
}

@Composable
private fun IndicatorGroupHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    accent: Color,
    s: Float,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((8f * s).dp)
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(if (focused) Color(0xFF232A3A) else Color(0xFF151A25))
            .border(
                if (focused) (2f * s).dp else 1.dp,
                if (focused) Color(0xFFFFD79A) else C.Grid,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (10f * s).dp, vertical = (8f * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (collapsed) "▸" else "▾",
            color = if (focused) accent else C.Dim,
            fontSize = (14f * s).sp,
            modifier = Modifier.width((22f * s).dp)
        )
        Text(
            label,
            color = if (focused) C.Text else C.Text.copy(alpha = 0.88f),
            fontSize = (14f * s).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            "已开 $count",
            color = C.Dimmer,
            fontSize = (11f * s).sp
        )
    }
}

@Composable
private fun IndicatorToggleRow(
    spec: IndicatorSpec,
    enabled: Boolean,
    summary: String,
    expanded: Boolean,
    accent: Color,
    s: Float,
    act: UiActions
) {
    var focused by remember { mutableStateOf(false) }
    var centerDown by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((9f * s).dp)

    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFF232A3A)
                    enabled -> Color(0xFF141C25)
                    else -> Color(0x00000000)
                }
            )
            .border(
                if (focused) (2f * s).dp else if (expanded) 1.dp else 0.dp,
                if (focused) Color(0xFFFFD79A) else if (expanded) accent.copy(alpha = 0.5f) else Color(0x00000000),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                when {
                    ev.type == KeyEventType.KeyDown && ok -> {
                        if (!centerDown) {
                            centerDown = true
                            longPressed = false
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(620)
                                if (centerDown) {
                                    longPressed = true
                                    act.expandIndicator(spec.id)
                                }
                            }
                        }
                        true
                    }

                    ev.type == KeyEventType.KeyUp && ok -> {
                        holdJob?.cancel()
                        val wasLong = longPressed
                        centerDown = false
                        longPressed = false
                        if (!wasLong) act.toggleIndicator(spec.id)
                        true
                    }

                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight -> {
                        act.expandIndicator(spec.id)
                        true
                    }

                    else -> false
                }
            }
            .clickable { act.toggleIndicator(spec.id) }
            .padding(horizontal = (10f * s).dp, vertical = (7f * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                spec.name,
                color = if (focused || enabled) C.Text else C.Text.copy(alpha = 0.82f),
                fontSize = (14.5f * s).sp,
                fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                summary,
                color = C.Dimmer,
                fontSize = (10.5f * s).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width((8f * s).dp))
        Toggle(enabled, s)
    }
}

@Composable
private fun ParamRow(label: String, value: String, s: Float, onStep: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((7f * s).dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (26f * s).dp, end = (6f * s).dp, top = (2f * s).dp)
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(if (focused) Color(0xFF232A3A) else Color(0x00000000))
            .border(
                if (focused) (2f * s).dp else 0.dp,
                if (focused) Color(0xFFFFD79A) else Color(0x00000000),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> {
                        onStep(-1)
                        true
                    }

                    Key.DirectionRight -> {
                        onStep(1)
                        true
                    }

                    else -> false
                }
            }
            .clickable { onStep(1) }
            .padding(horizontal = (10f * s).dp, vertical = (5f * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (focused) C.Text else C.Dim,
            fontSize = (12.5f * s).sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            "◀  $value  ▶",
            color = if (focused) Color(0xFFFFD79A) else C.Text.copy(alpha = 0.78f),
            fontSize = (12.5f * s).sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@Composable
private fun MiniActionButton(label: String, accent: Color, s: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((8f * s).dp)
    Box(
        Modifier
            .padding(start = (8f * s).dp)
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(if (focused) accent else C.PanelHigh)
            .border(if (focused) (2f * s).dp else 1.dp, if (focused) Color(0xFFFFD79A) else C.Grid, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (12f * s).dp, vertical = (7f * s).dp)
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF0A0C12) else C.Text,
            fontSize = (12f * s).sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ActionButton(label: String, accent: Color, s: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((9f * s).dp)
    Box(
        Modifier
            .padding(end = (10f * s).dp)
            .bringIntoViewRequester(bring)
            .graphicsLayer {
                val k = if (focused) 1.06f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (focused) accent else C.PanelHigh)
            .border(if (focused) (2f * s).dp else 1.dp, if (focused) Color(0xFFFFD79A) else C.Grid, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (20f * s).dp, vertical = (10f * s).dp)
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF0A0C12) else C.Text,
            fontSize = (14f * s).sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun SectionLabel(text: String, s: Float) {
    Text(
        text,
        color = C.Dimmer,
        fontSize = (12f * s).sp,
        letterSpacing = (1.4f * s).sp,
        modifier = Modifier.padding(bottom = (8f * s).dp)
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    accent: Color,
    s: Float,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val shape = RoundedCornerShape((9f * s).dp)
    Box(
        Modifier
            .padding(end = (8f * s).dp)
            .bringIntoViewRequester(bring)
            .graphicsLayer {
                val k = if (focused) 1.06f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (selected) accent else if (focused) Color(0xFF232A3A) else C.PanelHigh)
            .border(
                if (focused) (2f * s).dp else 1.dp,
                if (focused) Color(0xFFFFD79A) else if (selected) accent else C.Grid,
                shape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (18f * s).dp, vertical = (9f * s).dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0A0C12) else if (focused) C.Text else C.Dim,
            fontSize = (14f * s).sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, s: Float, onChange: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .clip(RoundedCornerShape((9f * s).dp))
            .background(if (focused) Color(0xFF232A3A) else Color(0x00000000))
            .border(
                if (focused) (2f * s).dp else 0.dp,
                if (focused) Color(0xFFFFD79A) else Color(0x00000000),
                RoundedCornerShape((9f * s).dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onChange() }
            .padding(horizontal = (10f * s).dp, vertical = (9f * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (focused) C.Text else C.Text.copy(alpha = 0.86f),
            fontSize = (15f * s).sp,
            modifier = Modifier.weight(1f)
        )
        Toggle(on, s)
    }
}

@Composable
private fun Toggle(on: Boolean, s: Float) {
    val w = (48f * s).dp
    val h = (28f * s).dp
    Box(
        Modifier
            .size(w, h)
            .clip(RoundedCornerShape(50))
            .background(if (on) C.Up.copy(alpha = 0.9f) else C.Grid)
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding((3f * s).dp)
                .size((22f * s).dp)
                .clip(CircleShape)
                .background(if (on) Color(0xFF06120F) else C.Dim)
        )
    }
}
