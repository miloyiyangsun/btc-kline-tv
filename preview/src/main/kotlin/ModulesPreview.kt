import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.AppTheme
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.board.modules.ChartDensity
import com.milolab.btctv.ui.board.modules.MainChartData
import com.milolab.btctv.ui.board.modules.MainChartModule
import com.milolab.btctv.ui.board.modules.MiniChartData
import com.milolab.btctv.ui.board.modules.MiniChartModule
import com.milolab.btctv.ui.board.modules.ModulePhase
import com.milolab.btctv.ui.board.modules.MoverRow
import com.milolab.btctv.ui.board.modules.MoversData
import com.milolab.btctv.ui.board.modules.MoversLayout
import com.milolab.btctv.ui.board.modules.MoversMetric
import com.milolab.btctv.ui.board.modules.MoversModule
import com.milolab.btctv.ui.board.modules.PriceCardData
import com.milolab.btctv.ui.board.modules.PriceCardModule
import com.milolab.btctv.ui.board.modules.StatKind
import com.milolab.btctv.ui.board.modules.StatTileData
import com.milolab.btctv.ui.board.modules.StatTileModule
import com.milolab.btctv.ui.board.modules.TickerItem
import com.milolab.btctv.ui.board.modules.TickerSpeed
import com.milolab.btctv.ui.board.modules.TickerStripData
import com.milolab.btctv.ui.board.modules.TickerStripModule
import com.milolab.btctv.ui.board.modules.WatchRow
import com.milolab.btctv.ui.board.modules.WatchlistData
import com.milolab.btctv.ui.board.modules.WatchlistModule
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun moduleCandles(
    n: Int,
    start: Double,
    seed: Long,
    drift: Double,
    vol: Double
): List<Candle> {
    val rnd = Random(seed)
    val out = ArrayList<Candle>(n)
    var p = start
    val nowMin = System.currentTimeMillis() / 60_000
    for (i in n downTo 1) {
        val t = (nowMin - i) * 60_000
        val o = p
        val c = max(100.0, o + drift + (rnd.nextDouble() - 0.5) * vol)
        val h = max(o, c) + rnd.nextDouble() * vol * 0.6
        val l = min(o, c) - rnd.nextDouble() * vol * 0.6
        val v = 8 + rnd.nextDouble() * 60 + abs(c - o) * 0.5
        out.add(Candle(t, o, h, l, c, v))
        p = c
    }
    return out
}

private fun sparkSeries(seed: Long, n: Int, base: Double, amp: Double): List<Double> {
    val rnd = Random(seed)
    var p = base
    return List(n) {
        p += (rnd.nextDouble() - 0.47) * amp
        p
    }
}

private val btcCandles = moduleCandles(220, 71_180.0, 20261118L, 24.0, 30.0)
private val ethCandles = moduleCandles(160, 3_180.0, 7L, 0.8, 4.2)
private val solCandles = moduleCandles(140, 168.0, 11L, 0.35, 1.6)
private val bnbCandles = moduleCandles(120, 610.0, 23L, 0.6, 3.0)
private val btcPrice = btcCandles.last().c

private val btcMain = MainChartData(
    instrument = Instruments.BTC,
    tf = TF.M1,
    candles = btcCandles,
    price = btcPrice,
    changePct = 2.34,
    style = ChartStyle.CANDLE,
    showVolume = true,
    source = "OKX"
)

private val ethMainCompact = MainChartData(
    instrument = Instruments.ETH,
    tf = TF.H1,
    candles = ethCandles,
    price = ethCandles.last().c,
    changePct = -1.18,
    style = ChartStyle.LINE,
    showVolume = false,
    source = "Binance"
)

private val aapl = Instruments.stock("AAPL", "Apple Inc.", 2, kind = StockKind.PERP)
private val nvda = Instruments.stock("NVDA", "NVIDIA Corp.", 2, kind = StockKind.TOKENIZED)

private val btcCard = PriceCardData(
    instrument = Instruments.BTC,
    price = btcPrice,
    changePct = 2.34,
    high24h = btcPrice + 640.0,
    low24h = btcPrice - 910.0,
    volume24h = 12_430.0,
    spark = sparkSeries(31L, 40, btcPrice, 90.0)
)

private val ethCard = PriceCardData(
    instrument = Instruments.ETH,
    price = 3_180.55,
    changePct = -0.82,
    high24h = 3_242.0,
    low24h = 3_128.0,
    volume24h = 92_400.0,
    spark = sparkSeries(32L, 36, 3_180.0, 14.0)
)

private val aaplCard = PriceCardData(
    instrument = aapl,
    price = 228.52,
    changePct = 0.64,
    high24h = 230.10,
    low24h = 226.80,
    volume24h = 41_800.0,
    spark = sparkSeries(33L, 30, 228.0, 1.1)
)

private fun watchRow(
    base: String,
    price: Double,
    pct: Double,
    seed: Long,
    stale: Boolean = false
): WatchRow {
    val inst = Instruments.byBase(base) ?: Instruments.BTC
    return WatchRow(
        instrument = inst,
        price = price,
        changePct = pct,
        spark = sparkSeries(seed, 26, price, price * 0.008),
        priceDecimals = inst.priceDecimals,
        stale = stale
    )
}

private val watchRows = listOf(
    watchRow("BTC", btcPrice, 2.34, 41L),
    watchRow("ETH", 3_180.55, -0.82, 42L),
    watchRow("SOL", 168.42, 5.67, 43L),
    watchRow("BNB", 611.30, 1.12, 44L),
    watchRow("DOGE", 0.38214, -2.41, 45L),
    watchRow("XRP", 2.4180, 3.05, 46L),
    watchRow("ADA", 0.9124, -1.36, 47L),
    watchRow("AVAX", 36.78, 4.22, 48L)
)

private fun moverRows(gainers: Boolean): List<MoverRow> {
    val bases = if (gainers) {
        listOf("SOL", "DOGE", "AVAX", "LINK", "XRP", "ADA", "TON", "BNB")
    } else {
        listOf("ETH", "BTC", "BNB", "ADA", "XRP", "TON", "LINK", "AVAX")
    }
    return bases.mapIndexed { i, base ->
        val inst = Instruments.byBase(base) ?: Instruments.BTC
        val price = when (base) {
            "BTC" -> btcPrice
            "ETH" -> 3_180.55
            "SOL" -> 168.42
            "BNB" -> 611.30
            "DOGE" -> 0.38214
            "XRP" -> 2.4180
            "ADA" -> 0.9124
            "AVAX" -> 36.78
            "LINK" -> 18.36
            else -> 5.42
        }
        val pct = if (gainers) 9.8 - i * 0.7 else -8.6 + i * 0.6
        MoverRow(
            rank = i + 1,
            instrument = inst,
            price = price,
            changePct = pct,
            volumeLabel = "$" + (18.2 - i * 1.3).toFloat() + "M",
            priceDecimals = inst.priceDecimals
        )
    }
}

private val gainers = moverRows(true)
private val losers = moverRows(false)

private val tickerItems = listOf(
    TickerItem("BTC", 96_432.10, 2, 2.34),
    TickerItem("ETH", 3_180.55, 2, -0.82),
    TickerItem("BTCD", valueText = "58.45%", changePct = 0.31),
    TickerItem("SOL", 168.42, 2, 5.67),
    TickerItem("TOTAL", valueText = "$2.23T", changePct = 1.85),
    TickerItem("DOGE", 0.38214, 5, -2.41),
    TickerItem("AAPL", 228.52, 2, 0.64),
    TickerItem("STABLE", valueText = "$292.3B", changePct = -0.42)
)

@Composable
private fun Board(paddingDp: Int = 12, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .padding(paddingDp.dp)
    ) {
        content()
    }
}

@Composable
private fun Cell(w: Int, h: Int, content: @Composable () -> Unit) {
    Box(Modifier.size(w.dp, h.dp)) {
        content()
    }
}

private fun render(
    outDir: File,
    name: String,
    w: Int = 1920,
    h: Int = 1080,
    density: Float = 2f,
    content: @Composable () -> Unit
) {
    val scene = ImageComposeScene(w, h, Density(density)) {
        AppTheme { content() }
    }
    scene.render()
    val img = scene.render()
    val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed: $name")
    File(outDir, name).writeBytes(data.bytes)
    scene.close()
    println("wrote $name")
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out" })
    outDir.mkdirs()
    render(outDir, "40-modules-main.png") { MainBoard() }
    render(outDir, "40-modules-mini.png") { MiniBoard() }
    render(outDir, "40-modules-price.png") { PriceBoard() }
    render(outDir, "40-modules-price-wall.png") { PriceWallBoard() }
    render(outDir, "40-modules-stat.png") { StatBoard() }
    render(outDir, "40-modules-watchlist.png") { WatchlistBoard() }
    render(outDir, "40-modules-movers.png") { MoversBoard() }
    render(outDir, "40-modules-ticker.png") { TickerBoard() }
    render(outDir, "40-modules-720p.png", w = 1280, h = 720, density = 1f) { SmallScreenBoard() }
    render(outDir, "40-modules-4k.png", w = 3840, h = 2160, density = 4f) { K4Board() }
    println("ok -> ${outDir.absolutePath}")
}

private val miniBtc = MiniChartData(
    instrument = Instruments.BTC,
    tf = TF.M1,
    candles = btcCandles,
    price = btcPrice,
    changePct = 2.34,
    style = ChartStyle.CANDLE
)

private val miniEth = MiniChartData(
    instrument = Instruments.ETH,
    tf = TF.H1,
    candles = ethCandles,
    price = ethCandles.last().c,
    changePct = -1.18,
    style = ChartStyle.LINE
)

private val miniSol = MiniChartData(
    instrument = Instruments.SOL,
    tf = TF.D1,
    candles = solCandles,
    price = solCandles.last().c,
    changePct = 3.42,
    style = ChartStyle.AREA
)

private val miniBnb = MiniChartData(
    instrument = Instruments.BNB,
    tf = TF.W1,
    candles = bnbCandles,
    price = bnbCandles.last().c,
    changePct = 0.84,
    style = ChartStyle.CANDLE
)

private val nvdaCard = PriceCardData(
    instrument = nvda,
    price = 124.60,
    changePct = -1.42,
    high24h = 126.80,
    low24h = 122.90,
    volume24h = 22_400.0,
    spark = sparkSeries(34L, 32, 124.0, 0.8)
)

private fun priceCard(base: String, price: Double, pct: Double, seed: Long): PriceCardData {
    val inst = Instruments.byBase(base) ?: Instruments.BTC
    return PriceCardData(
        instrument = inst,
        price = price,
        changePct = pct,
        high24h = price * 1.012,
        low24h = price * 0.988,
        volume24h = 8_000.0 + seed,
        spark = sparkSeries(seed, 30, price, price * 0.01)
    )
}

private val wallCards = listOf(
    priceCard("BTC", btcPrice, 2.34, 61L),
    priceCard("ETH", 3_180.55, -0.82, 62L),
    priceCard("SOL", 168.42, 5.67, 63L),
    priceCard("BNB", 611.30, 1.12, 64L),
    priceCard("DOGE", 0.38214, -2.41, 65L),
    priceCard("XRP", 2.4180, 3.05, 66L),
    priceCard("ADA", 0.9124, -1.36, 67L),
    priceCard("AVAX", 36.78, 4.22, 68L),
    priceCard("LINK", 18.36, 1.88, 69L)
)

@Composable
private fun MainBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(590, 330) {
                    MainChartModule(btcMain, ChartDensity.FULL, focused = false)
                }
                Column(
                    modifier = Modifier.width(336.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Cell(336, 160) {
                        MainChartModule(ethMainCompact, ChartDensity.COMPACT, focused = true)
                    }
                    Cell(336, 160) {
                        MainChartModule(
                            ethMainCompact.copy(stale = true, staleAgoMinutes = 6),
                            ChartDensity.COMPACT,
                            focused = false
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(300, 150) {
                    MainChartModule(
                        btcMain.copy(
                            phase = ModulePhase.LOADING,
                            candles = emptyList(),
                            price = null,
                            changePct = null
                        ),
                        ChartDensity.FULL,
                        focused = false
                    )
                }
                Cell(300, 150) {
                    MainChartModule(
                        btcMain.copy(
                            phase = ModulePhase.EMPTY,
                            candles = emptyList(),
                            price = null,
                            changePct = null
                        ),
                        ChartDensity.COMPACT,
                        focused = false
                    )
                }
                Cell(300, 150) {
                    MainChartModule(
                        btcMain.copy(
                            tf = TF.H4,
                            candles = bnbCandles,
                            price = bnbCandles.last().c,
                            changePct = 0.84,
                            source = "Bybit"
                        ),
                        ChartDensity.COMPACT,
                        focused = false
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 170) { MiniChartModule(miniBtc, focused = false) }
                Cell(225, 170) { MiniChartModule(miniEth, focused = true) }
                Cell(225, 170) {
                    MiniChartModule(miniSol.copy(stale = true, staleAgoMinutes = 14), focused = false)
                }
                Cell(225, 170) {
                    MiniChartModule(
                        miniBnb.copy(
                            phase = ModulePhase.LOADING,
                            candles = emptyList(),
                            price = null,
                            changePct = null
                        ),
                        focused = false
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 120) { MiniChartModule(miniBtc.copy(tf = TF.M15), focused = false) }
                Cell(225, 120) { MiniChartModule(miniEth.copy(stale = true, staleAgoMinutes = 2), focused = false) }
                Cell(225, 120) {
                    MiniChartModule(miniSol.copy(phase = ModulePhase.EMPTY, candles = emptyList()), focused = false)
                }
                Cell(225, 120) { MiniChartModule(miniBnb, focused = true) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(460, 150) { MiniChartModule(miniBtc.copy(tf = TF.H1, style = ChartStyle.LINE), focused = false) }
                Cell(225, 150) { MiniChartModule(miniEth.copy(style = ChartStyle.CANDLE), focused = false) }
                Cell(225, 150) {
                    MiniChartModule(
                        miniSol.copy(phase = ModulePhase.EMPTY, candles = emptyList()),
                        focused = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(600, 300) { PriceCardModule(btcCard, focused = false) }
                Cell(320, 300) { PriceCardModule(aaplCard, focused = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 200) { PriceCardModule(ethCard, focused = false) }
                Cell(225, 200) { PriceCardModule(nvdaCard, focused = true) }
                Cell(225, 200) {
                    PriceCardModule(aaplCard.copy(stale = true, staleAgoMinutes = 18), focused = false)
                }
                Cell(225, 200) {
                    PriceCardModule(
                        ethCard.copy(
                            phase = ModulePhase.LOADING,
                            price = null,
                            changePct = null,
                            spark = emptyList()
                        ),
                        focused = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceWallBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) { col ->
                        val i = row * 3 + col
                        val data = when (i) {
                            4 -> wallCards[4].copy(stale = true, staleAgoMinutes = 4)
                            8 -> wallCards[8].copy(
                                phase = ModulePhase.LOADING,
                                price = null,
                                changePct = null,
                                spark = emptyList()
                            )
                            else -> wallCards[i]
                        }
                        Cell(300, 165) {
                            PriceCardModule(data, focused = i == 0)
                        }
                    }
                }
            }
        }
    }
}

private val btcdStat = StatTileData(
    kind = StatKind.BTCD,
    value = 58.45,
    changePct = 0.31,
    spark = sparkSeries(51L, 30, 58.0, 0.22),
    source = "CoinGecko"
)

private val totalStat = StatTileData(
    kind = StatKind.TOTAL,
    value = 2.23e12,
    changePct = 1.85,
    spark = sparkSeries(52L, 30, 2.2e12, 1.0e10),
    source = "CoinGecko"
)

private val stableMcapStat = StatTileData(
    kind = StatKind.STABLE_MCAP,
    value = 292.3e9,
    changePct = -0.42,
    spark = sparkSeries(53L, 28, 2.9e11, 1.0e9),
    source = "DefiLlama"
)

private val stableDomStat = StatTileData(
    kind = StatKind.STABLE_DOM,
    value = 7.84,
    changePct = -0.12,
    spark = emptyList(),
    source = "DefiLlama"
)

private val watchTall = WatchlistData(rows = watchRows, selectedIndex = 2)

private val watchStale = WatchlistData(
    rows = watchRows.mapIndexed { i, row ->
        if (i == 1 || i == 4) row.copy(stale = true) else row
    },
    title = "自选",
    selectedIndex = 0,
    stale = true,
    staleAgoMinutes = 5
)

private val moversSplit = MoversData(
    gainers = gainers,
    losers = losers,
    layout = MoversLayout.SPLIT
)

private val moversSplitStale = moversSplit.copy(stale = true, staleAgoMinutes = 9)
private val moversTabsUp = moversSplit.copy(layout = MoversLayout.TABS, activeTab = MoversMetric.GAINERS)
private val moversTabsDown = moversSplit.copy(layout = MoversLayout.TABS, activeTab = MoversMetric.LOSERS)

@Composable
private fun StatBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 130) { StatTileModule(btcdStat, focused = false) }
                Cell(225, 130) { StatTileModule(totalStat, focused = false) }
                Cell(225, 130) { StatTileModule(stableMcapStat, focused = false) }
                Cell(225, 130) { StatTileModule(stableDomStat, focused = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 80) { StatTileModule(btcdStat, focused = false) }
                Cell(225, 80) { StatTileModule(totalStat, focused = false) }
                Cell(225, 80) { StatTileModule(stableMcapStat, focused = false) }
                Cell(225, 80) { StatTileModule(stableDomStat, focused = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(146, 64) { StatTileModule(btcdStat, focused = false) }
                Cell(146, 64) { StatTileModule(totalStat, focused = false) }
                Cell(146, 64) { StatTileModule(stableMcapStat, focused = false) }
                Cell(146, 64) { StatTileModule(stableDomStat, focused = false) }
                Cell(146, 64) { StatTileModule(btcdStat.copy(stale = true, staleAgoMinutes = 2), focused = false) }
                Cell(146, 64) {
                    StatTileModule(StatTileData(StatKind.BTCD, phase = ModulePhase.LOADING), focused = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(225, 130) {
                    StatTileModule(btcdStat.copy(stale = true, staleAgoMinutes = 3), focused = false)
                }
                Cell(225, 130) {
                    StatTileModule(
                        StatTileData(StatKind.TOTAL, phase = ModulePhase.LOADING, source = "CoinGecko"),
                        focused = false
                    )
                }
                Cell(225, 130) {
                    StatTileModule(
                        StatTileData(StatKind.STABLE_MCAP, phase = ModulePhase.EMPTY, source = "DefiLlama"),
                        focused = false
                    )
                }
                Cell(225, 130) { StatTileModule(totalStat, focused = true) }
            }
        }
    }
}

@Composable
private fun WatchlistBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(315, 400) { WatchlistModule(watchTall, focused = true) }
                Cell(295, 400) { WatchlistModule(watchStale, focused = false) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cell(295, 195) {
                        WatchlistModule(
                            WatchlistData(phase = ModulePhase.LOADING),
                            focused = false
                        )
                    }
                    Cell(295, 195) {
                        WatchlistModule(
                            WatchlistData(phase = ModulePhase.EMPTY),
                            focused = false
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(600, 100) {
                    WatchlistModule(
                        WatchlistData(rows = watchRows.take(4), selectedIndex = 1),
                        focused = false
                    )
                }
                Cell(320, 100) {
                    WatchlistModule(
                        WatchlistData(rows = watchRows.take(3), selectedIndex = null),
                        focused = false
                    )
                }
            }
        }
    }
}

@Composable
private fun MoversBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(300, 330) { MoversModule(moversSplit, focused = false) }
                Cell(270, 330) { MoversModule(moversSplitStale, focused = true) }
                Cell(330, 330) { MoversModule(moversTabsUp, focused = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(300, 150) { MoversModule(moversTabsDown, focused = false) }
                Cell(220, 150) {
                    MoversModule(MoversData(phase = ModulePhase.LOADING), focused = false)
                }
                Cell(220, 150) {
                    MoversModule(MoversData(phase = ModulePhase.EMPTY), focused = false)
                }
            }
        }
    }
}

private val tickerNormal = TickerStripData(items = tickerItems, speed = TickerSpeed.NORMAL)
private val tickerStale = tickerNormal.copy(stale = true, staleAgoMinutes = 12)

@Composable
private fun TickerBoard() {
    Board {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(900, 64) {
                TickerStripModule(tickerNormal, focused = false, fixedOffsetPx = 0f)
            }
            Cell(900, 56) {
                TickerStripModule(
                    tickerNormal.copy(speed = TickerSpeed.SLOW),
                    focused = false,
                    fixedOffsetPx = 260f
                )
            }
            Cell(900, 48) {
                TickerStripModule(
                    tickerNormal.copy(speed = TickerSpeed.FAST),
                    focused = false,
                    fixedOffsetPx = 180f
                )
            }
            Cell(900, 40) {
                TickerStripModule(tickerStale, focused = true, fixedOffsetPx = 420f)
            }
            Cell(900, 32) {
                TickerStripModule(TickerStripData(phase = ModulePhase.LOADING), focused = false)
            }
            Cell(900, 28) {
                TickerStripModule(TickerStripData(phase = ModulePhase.EMPTY), focused = false)
            }
        }
    }
}

@Composable
private fun SmallScreenBoard() {
    Board(paddingDp = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cell(780, 380) { MainChartModule(btcMain, ChartDensity.FULL, focused = false) }
                Column(
                    modifier = Modifier.width(456.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Cell(456, 240) { PriceCardModule(btcCard, focused = false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Cell(223, 128) { StatTileModule(btcdStat, focused = false) }
                        Cell(223, 128) { StatTileModule(totalStat, focused = false) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cell(300, 170) { MiniChartModule(miniEth, focused = false) }
                Cell(300, 170) {
                    WatchlistModule(
                        WatchlistData(rows = watchRows.take(6), selectedIndex = 1),
                        focused = false
                    )
                }
                Cell(300, 170) { MoversModule(moversSplit, focused = false) }
                Cell(300, 170) { PriceCardModule(ethCard, focused = true) }
            }
            Cell(1248, 48) {
                TickerStripModule(tickerNormal, focused = false, fixedOffsetPx = 120f)
            }
        }
    }
}

@Composable
private fun K4Board() {
    Board(paddingDp = 12) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(600, 300) { MainChartModule(btcMain, ChartDensity.FULL, focused = false) }
                Column(
                    modifier = Modifier.width(324.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Cell(324, 220) { PriceCardModule(ethCard, focused = false) }
                    Cell(324, 72) { StatTileModule(btcdStat, focused = false) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell(300, 136) { MiniChartModule(miniEth, focused = false) }
                Cell(300, 136) {
                    WatchlistModule(
                        WatchlistData(rows = watchRows.take(4), selectedIndex = 0),
                        focused = false
                    )
                }
                Cell(300, 136) { MoversModule(moversSplit, focused = false) }
            }
            Cell(936, 40) {
                TickerStripModule(
                    tickerNormal.copy(speed = TickerSpeed.SLOW),
                    focused = false,
                    fixedOffsetPx = 200f
                )
            }
        }
    }
}



