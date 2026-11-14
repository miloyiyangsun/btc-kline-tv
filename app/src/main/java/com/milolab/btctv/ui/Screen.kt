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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.UiState
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Coin
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.TF
import kotlinx.coroutines.delay
import kotlin.math.sin
@Composable
fun TvScreenInner(st: UiState, act: UiActions) {
    val accent = Color(st.coin.accent)
    val rootFocus = remember { FocusRequester() }

    val clock by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 设置关闭后把焦点还给主界面，遥控器才能继续控制周期/缩放
    LaunchedEffect(st.settingsOpen) {
        if (!st.settingsOpen) {
            delay(60)
            runCatching { rootFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (val intent = routeKey(st.settingsOpen, ev.key)) {
                    is KeyIntent.ChangeTf -> { act.setTf(st.tf.step(intent.delta)); true }
                    is KeyIntent.Zoom -> { act.zoom(intent.delta); true }
                    KeyIntent.OpenSettings -> { act.openSettings(); true }
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
            Header(st, accent, s, clock)
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
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun Header(st: UiState, accent: Color, s: Float, clock: Long) {
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
                st.coin.glyph,
                color = accent,
                fontSize = (26f * s).sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width((14f * s).dp))
        Column {
            Text(
                st.coin.base + " / USDT",
                color = C.Text,
                fontSize = (23f * s).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (1.6f * s).sp
            )
            Text(
                "实时行情 · " + (st.provider ?: "接入中"),
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
        BigPrice(st.price, st.prevPrice, st.flash, s)
        Spacer(Modifier.width((18f * s).dp))
        ChangePill(st, s, Modifier.padding(bottom = (36f * s).dp))
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = (4f * s).dp)) {
            StatLine("24H 最高", st.quote?.high24h?.let { fmtPrice(it) }, s)
            Spacer(Modifier.height((8f * s).dp))
            StatLine("24H 最低", st.quote?.low24h?.let { fmtPrice(it) }, s)
            Spacer(Modifier.height((8f * s).dp))
            StatLine("24H 成交", st.quote?.vol24h?.let { fmtVol(it) + " BTC" }, s)
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
                fmtSigned(abs),
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
private fun BigPrice(price: Double, prev: Double, flashOn: Boolean, s: Float) {
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
            "$",
            color = C.Dimmer,
            fontSize = (38f * s).sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.alignByBaseline().padding(end = (8f * s).dp)
        )
        Text(
            if (price > 0) fmtPrice(price) else "— — —",
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
                candles = st.candles.takeLast(st.zoom.coerceAtMost(st.candles.size)),
                style = st.style,
                showVolume = st.showVolume,
                tf = st.tf,
                accent = accent,
                up = C.Up,
                down = C.Down,
                s = s,
                modifier = Modifier.fillMaxSize()
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
                st.coin.base + " / USDT",
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
            "◀ ▶ 周期      ▲ ▼ 缩放      OK 设置",
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
                .width((760f * s).dp)
                .clip(shape)
                .background(C.Panel)
                .border(1.dp, Color(0xFF20283A), shape)
                .focusGroup()
                .pointerInput(Unit) { detectTapGestures { } }
                .padding((28f * s).dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", color = C.Text, fontSize = (24f * s).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("按 BACK 关闭", color = C.Dimmer, fontSize = (12f * s).sp)
            }
            Spacer(Modifier.height((18f * s).dp))

            SectionLabel("图表样式", s)
            Row {
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    // 面板刚出现时节点可能还没挂上，重试几次，保证遥控器一进来就在面板里
                    repeat(8) {
                        delay(60)
                        if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    }
                }
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
            Spacer(Modifier.height((18f * s).dp))

            ToggleRow("价格跳动闪烁", st.flash, s) { act.toggleFlash() }
            ToggleRow("防烧屏微位移", st.antiBurn, s) { act.toggleAntiBurn() }
            ToggleRow("显示时钟", st.showClock, s) { act.toggleClock() }
            ToggleRow("显示成交量", st.showVolume, s) { act.toggleVolume() }
            ToggleRow("开机自动启动（部分设备有效）", st.autoStart, s) { act.toggleAutoStart() }

            Spacer(Modifier.height((18f * s).dp))
            Row {
                ActionButton("立即重连", accent, s) { act.reconnect() }
                ActionButton("关闭", accent, s) { act.closeSettings() }
            }
            Spacer(Modifier.height((14f * s).dp))
            Text(
                "数据源：OKX / Binance / Bybit　自动切换最优线路　　方向键移动焦点 · OK 确认 · 返回键关闭",
                color = C.Dimmer,
                fontSize = (12f * s).sp
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, accent: Color, s: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((9f * s).dp)
    Box(
        Modifier
            .padding(end = (10f * s).dp)
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
    val shape = RoundedCornerShape((9f * s).dp)
    Box(
        Modifier
            .padding(end = (8f * s).dp)
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
    Row(
        Modifier
            .fillMaxWidth()
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
