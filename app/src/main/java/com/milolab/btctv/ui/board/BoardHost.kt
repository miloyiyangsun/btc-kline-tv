package com.milolab.btctv.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Candle
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.board.layout.LayoutGeometry
import com.milolab.btctv.ui.board.layout.LayoutRect
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.modules.ChartDensity
import com.milolab.btctv.ui.board.modules.MainChartData
import com.milolab.btctv.ui.board.modules.MainChartModule
import com.milolab.btctv.ui.board.modules.MiniChartModule
import com.milolab.btctv.ui.board.modules.MoversModule
import com.milolab.btctv.ui.board.modules.PriceCardModule
import com.milolab.btctv.ui.board.modules.StatTileModule
import com.milolab.btctv.ui.board.modules.TickerStripModule
import com.milolab.btctv.ui.board.modules.WatchlistModule
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sin

/**
 * 看板渲染器：只负责摆放与绘制，不拉数据、不处理按键（由 ViewModel / BoardScreen 负责）。
 * 尺寸全部走 LayoutGeometry，所以 720p / 1080p / 4K 的比例一致。
 */
@Composable
fun BoardHost(
    spec: LayoutSpec,
    contents: Map<String, SlotContent>,
    focusSlotId: String?,
    fullscreenSlotId: String?,
    antiBurn: Boolean,
    hint: String? = null,
    modifier: Modifier = Modifier,
    topHeader: (@Composable () -> Unit)? = null,
    onSlotTap: (String) -> Unit = {}
) {
    val clock by produceState(initialValue = System.currentTimeMillis(), antiBurn) {
        if (!antiBurn) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val scale = min(widthDp / 1920f, heightDp / 1080f)
        val driftX = if (antiBurn) (sin(clock / 210000.0) * 5.0 * scale).dp else 0.dp
        val driftY = if (antiBurn) (sin(clock / 173000.0) * 3.5 * scale).dp else 0.dp

        Box(
            Modifier
                .fillMaxSize()
                .offset(driftX, driftY)
        ) {
            val rects = LayoutGeometry.solve(
                spec,
                LayoutRect(0f, 0f, widthDp, heightDp)
            )
            val fullscreen = fullscreenSlotId?.let { id ->
                spec.slots[id]?.let { id to LayoutRect(0f, 0f, widthDp, heightDp) }
            }

            if (fullscreen != null) {
                SlotView(
                    content = contents[fullscreen.first],
                    fullscreen = true,
                    focused = true,
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxSize()
                )
            } else {
                if (topHeader != null && spec.topChrome > 0f) {
                    val chromeDp = spec.topChrome * scale
                    val marginDp = 24f * scale
                    Box(
                        Modifier
                            .offset(marginDp.dp, marginDp.dp)
                            .size(
                                (widthDp - marginDp * 2f).coerceAtLeast(1f).dp,
                                chromeDp.dp
                            )
                    ) {
                        topHeader()
                    }
                }
                for (slotId in spec.root.leafSlotIds()) {
                    val rect = rects[slotId] ?: continue
                    if (rect.width <= 0f || rect.height <= 0f) continue
                    SlotView(
                        content = contents[slotId],
                        fullscreen = false,
                        focused = slotId == focusSlotId,
                        modifier = Modifier
                            .offset(rect.x.dp, rect.y.dp)
                            .size(rect.width.dp, rect.height.dp)
                            .pointerInput(slotId) {
                                detectTapGestures { onSlotTap(slotId) }
                            }
                    )
                }
            }

            if (hint != null) {
                Text(
                    hint,
                    color = Color(0xFFFFD08A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .widthIn(max = (widthDp * 0.6f).dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE6171A22))
                        .border(1.dp, Color(0x66FFD08A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun SlotView(
    content: SlotContent?,
    focused: Boolean,
    fullscreen: Boolean,
    modifier: Modifier = Modifier
) {
    if (content == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("加载看板…", color = C.Dimmer, fontSize = 12.sp)
        }
        return
    }
    when (content) {
        is SlotContent.Main -> MainChartModule(
            data = content.data,
            density = if (fullscreen) ChartDensity.FULL else content.density,
            focused = focused,
            modifier = modifier
        )

        is SlotContent.Mini -> if (fullscreen) {
            MainChartModule(
                data = MainChartData(
                    instrument = content.data.instrument,
                    tf = content.data.tf,
                    candles = content.data.candles,
                    price = content.data.price,
                    changePct = content.data.changePct,
                    style = content.data.style,
                    showVolume = false,
                    priceDecimals = content.data.priceDecimals,
                    phase = content.data.phase,
                    stale = content.data.stale,
                    staleAgoMinutes = content.data.staleAgoMinutes
                ),
                density = ChartDensity.FULL,
                focused = focused,
                modifier = modifier
            )
        } else {
            MiniChartModule(content.data, focused, modifier)
        }

        is SlotContent.Card -> PriceCardModule(content.data, focused, modifier)
        is SlotContent.Stat -> StatTileModule(content.data, focused, modifier)
        is SlotContent.Watch -> WatchlistModule(content.data, focused, modifier)
        is SlotContent.Movers -> MoversModule(content.data, focused, modifier)
        is SlotContent.Ticker -> TickerStripModule(
            data = content.data,
            focused = focused,
            modifier = modifier,
            lowPower = true
        )
    }
}

/** 供预览使用的简单蜡烛序列工具（不依赖网络）。 */
fun flatCandleSeries(count: Int, start: Double, seed: Long = 1L, vol: Double = 0.01): List<Candle> {
    val rnd = java.util.Random(seed)
    val out = ArrayList<Candle>(count)
    var price = start
    val nowMin = System.currentTimeMillis() / 60_000
    for (i in count downTo 1) {
        val open = price
        val close = (open * (1.0 + (rnd.nextDouble() - 0.48) * vol))
        val high = maxOf(open, close) * (1.0 + rnd.nextDouble() * vol * 0.3)
        val low = minOf(open, close) * (1.0 - rnd.nextDouble() * vol * 0.3)
        out += Candle((nowMin - i) * 60_000, open, high, low, close, 10.0 + rnd.nextDouble() * 40)
        price = close
    }
    return out
}
