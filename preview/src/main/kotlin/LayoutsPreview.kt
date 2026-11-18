import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.AppTheme
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.board.BoardActions
import com.milolab.btctv.ui.board.BoardHost
import com.milolab.btctv.ui.board.BoardUiState
import com.milolab.btctv.ui.board.LayoutSettingsSection
import com.milolab.btctv.ui.board.SlotContent
import com.milolab.btctv.ui.board.flatCandleSeries
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SlotParamKeys
import com.milolab.btctv.ui.board.layout.StatKind
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.modules.ChartDensity
import com.milolab.btctv.ui.board.modules.MainChartData
import com.milolab.btctv.ui.board.modules.MiniChartData
import com.milolab.btctv.ui.board.modules.MoverRow
import com.milolab.btctv.ui.board.modules.MoversData
import com.milolab.btctv.ui.board.modules.MoversLayout
import com.milolab.btctv.ui.board.modules.MoversMetric
import com.milolab.btctv.ui.board.modules.PriceCardData
import com.milolab.btctv.ui.board.modules.StatTileData
import com.milolab.btctv.ui.board.modules.TickerItem
import com.milolab.btctv.ui.board.modules.TickerSpeed
import com.milolab.btctv.ui.board.modules.TickerStripData
import com.milolab.btctv.ui.board.modules.WatchRow
import com.milolab.btctv.ui.board.modules.WatchlistData
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Random

private val accent = Color(0xFF7FA8FF)

private val basePrice = mapOf(
    "BTC" to 96_432.0, "ETH" to 3_180.0, "SOL" to 168.4, "BNB" to 611.3,
    "DOGE" to 0.382, "XRP" to 2.418, "ADA" to 0.912, "AVAX" to 36.78,
    "LINK" to 18.36, "TON" to 5.42
)

private fun instOf(binding: SlotBinding, index: Int): Instrument = when (binding) {
    is SlotBinding.Fixed -> Instruments.byId(binding.instrumentId)
    SlotBinding.FollowMain -> null
} ?: Instruments.HOT[index % Instruments.HOT.size]

private fun priceOf(inst: Instrument): Double = basePrice[inst.baseCurrency] ?: 100.0

private fun statKind(b0: StatKind): com.milolab.btctv.ui.board.modules.StatKind = when (b0) {
    StatKind.BTCD -> com.milolab.btctv.ui.board.modules.StatKind.BTCD
    StatKind.TOTAL -> com.milolab.btctv.ui.board.modules.StatKind.TOTAL
    StatKind.STABLE_CAP -> com.milolab.btctv.ui.board.modules.StatKind.STABLE_MCAP
    StatKind.STABLE_DOM -> com.milolab.btctv.ui.board.modules.StatKind.STABLE_DOM
}

private fun spark(seed: Long, base: Double): List<Double> {
    val rnd = Random(seed)
    var p = base
    return List(30) {
        p *= 1.0 + (rnd.nextDouble() - 0.48) * 0.012
        p
    }
}

private fun chartData(slot: ModuleType, inst: Instrument, tf: TF, full: Boolean, seed: Long): SlotContent {
    val candles = flatCandleSeries(150, priceOf(inst), seed)
    val price = candles.last().c
    val first = candles.first().o
    val pct = (price / first - 1.0) * 100.0
    return if (slot is ModuleType.MainChart) {
        SlotContent.Main(
            MainChartData(
                instrument = inst,
                tf = tf,
                candles = candles,
                price = price,
                changePct = pct,
                style = if (seed % 3L == 0L) ChartStyle.CANDLE else ChartStyle.AREA,
                showVolume = full,
                priceDecimals = inst.priceDecimals,
                source = "OKX"
            ),
            if (full) ChartDensity.FULL else ChartDensity.COMPACT
        )
    } else {
        SlotContent.Mini(
            MiniChartData(
                instrument = inst,
                tf = tf,
                candles = candles,
                price = price,
                changePct = pct,
                style = ChartStyle.CANDLE,
                priceDecimals = inst.priceDecimals
            )
        )
    }
}

private fun watchRows(): List<WatchRow> = Instruments.HOT.mapIndexed { i, inst ->
    val p = priceOf(inst) * (1.0 + (i - 4) * 0.003)
    WatchRow(
        instrument = inst,
        price = p,
        changePct = listOf(2.34, -0.82, 5.67, 1.12, -2.41, 3.05, -1.36, 4.22, 1.88, 0.52)[i],
        spark = spark(80L + i, p),
        priceDecimals = inst.priceDecimals
    )
}

private fun moverRows(gainers: Boolean): List<MoverRow> = Instruments.HOT.take(8).mapIndexed { i, inst ->
    MoverRow(
        rank = i + 1,
        instrument = inst,
        price = priceOf(inst),
        changePct = if (gainers) 9.8 - i * 0.8 else -8.6 + i * 0.7,
        volumeLabel = "$" + (18 - i) + ".2M",
        priceDecimals = inst.priceDecimals
    )
}

private fun contentFor(spec: LayoutSpec, slotId: String, index: Int): SlotContent {
    val slot = spec.slots.getValue(slotId)
    val inst = instOf(slot.binding, index)
    val tf = slot.timeframe ?: when (slot.module) {
        ModuleType.MainChart -> TF.M15
        ModuleType.MiniChart -> TF.H1
        else -> TF.H1
    }
    val full = slot.params[SlotParamKeys.DETAIL] != SlotParamKeys.DETAIL_COMPACT
    val p = priceOf(inst)
    return when (val m = slot.module) {
        is ModuleType.MainChart, is ModuleType.MiniChart ->
            chartData(m, inst, tf, full, 300L + index * 17)

        ModuleType.PriceCard -> SlotContent.Card(
            PriceCardData(
                instrument = inst,
                price = p,
                changePct = 2.34 - index * 0.7,
                high24h = p * 1.012,
                low24h = p * 0.988,
                volume24h = 8_000.0 + index * 730,
                spark = spark(90L + index, p),
                priceDecimals = inst.priceDecimals
            )
        )

        is ModuleType.StatTile -> SlotContent.Stat(
            StatTileData(
                kind = statKind(m.stat),
                value = when (m.stat) {
                    StatKind.BTCD -> 58.45
                    StatKind.TOTAL -> 2.23e12
                    StatKind.STABLE_CAP -> 292.3e9
                    StatKind.STABLE_DOM -> 7.84
                },
                changePct = if (index % 2 == 0) 1.85 else -0.42,
                spark = spark(70L + index, 100.0),
                source = if (m.stat == StatKind.BTCD || m.stat == StatKind.TOTAL) "CoinGecko" else "DefiLlama"
            )
        )

        ModuleType.Watchlist -> SlotContent.Watch(
            WatchlistData(rows = watchRows(), selectedIndex = 1)
        )

        ModuleType.Movers -> {
            val gainersTab = slot.params[SlotParamKeys.METRIC] == SlotParamKeys.METRIC_GAINERS
            SlotContent.Movers(
                MoversData(
                    gainers = moverRows(true),
                    losers = moverRows(false),
                    layout = if (gainersTab) MoversLayout.TABS else MoversLayout.SPLIT,
                    activeTab = if (gainersTab) MoversMetric.GAINERS else MoversMetric.LOSERS
                )
            )
        }

        ModuleType.TickerStrip -> SlotContent.Ticker(
            TickerStripData(
                items = Instruments.HOT.take(7).map { i ->
                    TickerItem(i.baseCurrency, priceOf(i), i.priceDecimals, 2.34)
                },
                speed = when (slot.params[SlotParamKeys.SPEED]) {
                    SlotParamKeys.SPEED_SLOW -> TickerSpeed.SLOW
                    SlotParamKeys.SPEED_FAST -> TickerSpeed.FAST
                    else -> TickerSpeed.NORMAL
                }
            )
        )
    }
}

private fun contentsFor(spec: LayoutSpec): Map<String, SlotContent> =
    spec.root.leafSlotIds().withIndex().associate { (i, id) -> id to contentFor(spec, id, i) }

private fun render(
    outDir: File,
    name: String,
    w: Int,
    h: Int,
    density: Float,
    content: @Composable () -> Unit
) {
    val scene = ImageComposeScene(w, h, Density(density)) { AppTheme { content() } }
    scene.render()
    val img = scene.render()
    val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed: $name")
    File(outDir, name).writeBytes(data.bytes)
    scene.close()
    println("wrote $name")
}

@Composable
private fun SharedHeader() {
    Row(
        Modifier.fillMaxSize().background(C.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("BTC / USDT", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("96,432.10", color = C.Up, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("+2.34%", color = C.Up, fontSize = 15.sp)
        Text("OKX · 实时", color = C.Dimmer, fontSize = 13.sp)
    }
}

private fun renderLayout(outDir: File, spec: LayoutSpec, w: Int, h: Int, density: Float, tag: String) {
    val contents = contentsFor(spec)
    val first = spec.root.leafSlotIds().firstOrNull()
    render(outDir, "50-layout-${spec.id}-$tag.png", w, h, density) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            BoardHost(
                spec = spec,
                contents = contents,
                focusSlotId = first,
                fullscreenSlotId = null,
                antiBurn = false,
                topHeader = if (spec.topChrome > 0f) ({ SharedHeader() }) else null
            )
        }
    }
}

@Composable
private fun SettingsShot(scale: Float = 1f) {
    val spec = LayoutPresets.byId("grid_4_charts") ?: LayoutPresets.all[1]
    val board = BoardUiState(
        spec = spec,
        customSpec = null,
        focusedSlotId = spec.root.leafSlotIds().firstOrNull()
    )
    Box(Modifier.fillMaxSize().background(C.Bg).padding(24.dp)) {
        androidx.compose.foundation.layout.Column {
            LayoutSettingsSection(board, BoardActions(), accent, scale)
        }
    }
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out" })
    outDir.mkdirs()
    for (spec in LayoutPresets.all) {
        renderLayout(outDir, spec, 1920, 1080, 2f, "1080p")
    }
    for (id in listOf("grid_9", "overview", "multi_tf", "chart_macro")) {
        val spec = LayoutPresets.byId(id) ?: continue
        renderLayout(outDir, spec, 1280, 720, 1f, "720p")
        renderLayout(outDir, spec, 3840, 2160, 4f, "4k")
    }
    render(outDir, "50-layout-settings-1080p.png", 1920, 1080, 2f) { SettingsShot() }
    render(outDir, "50-layout-settings-full.png", 1920, 2600, 2f) { SettingsShot() }
    println("ok -> ${outDir.absolutePath}")
}
