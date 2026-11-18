import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.UiState
import com.milolab.btctv.data.indicators.IndicatorEngine
import com.milolab.btctv.ui.AppTheme
import com.milolab.btctv.ui.TvScreenInner
import com.milolab.btctv.ui.UiActions
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun candles(n: Int, start: Double, seed: Long, driftPerStep: Double = 1.0, vol: Double = 26.0): List<Candle> {
    val rnd = Random(seed)
    val out = ArrayList<Candle>(n)
    var p = start
    val nowMin = System.currentTimeMillis() / 60_000
    for (i in n downTo 1) {
        val t = (nowMin - i) * 60_000
        val o = p
        val trend = driftPerStep + (rnd.nextDouble() - 0.5) * vol
        val c = max(100.0, o + trend)
        val h = max(o, c) + rnd.nextDouble() * vol * 0.8
        val l = min(o, c) - rnd.nextDouble() * vol * 0.8
        val v = 8 + rnd.nextDouble() * 60 + abs(c - o) * 0.5
        out.add(Candle(t, o, h, l, c, v))
        p = c
    }
    return out
}

/** 按周期的 UTC 桶边界生成K线，用于 1W/1Y 这种桶长度不固定的周期截图 */
private fun bucketCandles(
    tf: TF,
    n: Int,
    start: Double,
    seed: Long,
    driftPerBar: Double,
    vol: Double
): List<Candle> {
    val rnd = Random(seed)
    val times = ArrayList<Long>(n)
    var t = tf.bucketStart(System.currentTimeMillis())
    repeat(n) {
        times.add(t)
        t = tf.bucketStart(t - 1)
    }
    times.reverse()
    var p = start
    val out = ArrayList<Candle>(n)
    for (ts in times) {
        val o = p
        val c = max(100.0, o + driftPerBar + (rnd.nextDouble() - 0.5) * vol)
        val h = max(o, c) + rnd.nextDouble() * vol * 0.8
        val l = min(o, c) - rnd.nextDouble() * vol * 0.8
        val v = 8 + rnd.nextDouble() * 60 + abs(c - o) * 0.5
        out.add(Candle(ts, o, h, l, c, v))
        p = c
    }
    return out
}

private fun withQuote(st: UiState, candles: List<Candle>): UiState {
    val last = candles.last()
    return st.copy(
        candles = candles,
        price = last.c,
        prevPrice = last.c - (last.c - last.o) * 0.4,
        quote = Quote(last.c, candles.first().o, candles.maxOf { it.h }, candles.minOf { it.l }, 12_430.0)
    )
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out" })
    outDir.mkdirs()
    val cs = candles(150, 71_180.0, 20261112L, driftPerStep = 3.4, vol = 24.0)
    val last = cs.last().c
    val low = cs.minOf { it.l }
    val high = cs.maxOf { it.h }
    val open24 = cs.first().o

    val base = UiState(
        instrument = Instruments.BTC,
        tf = TF.M1,
        candles = cs,
        price = last,
        prevPrice = last - 12.0,
        quote = Quote(
            price = last,
            open24h = open24,
            high24h = high + 42,
            low24h = low - 35,
            vol24h = 12_430.0
        ),
        provider = "OKX",
        conn = Conn.LIVE,
        firstLoad = false
    )

    render(outDir, "01-main.png", base)
    render(outDir, "02-area.png", base.copy(style = ChartStyle.AREA))
    render(outDir, "03-line.png", base.copy(style = ChartStyle.LINE))
    render(outDir, "04-settings.png", base.copy(settingsOpen = true))
    render(
        outDir, "05-eth.png",
        base.copy(
            instrument = Instruments.ETH,
            candles = candles(150, 3_180.0, 99L, 0.6, 3.2),
            price = 0.0,
            quote = null
        ).let { s ->
            val c2 = s.candles
            s.copy(
                price = c2.last().c,
                prevPrice = c2.last().c - 4.0,
                quote = Quote(c2.last().c, c2.first().o, c2.maxOf { it.h }, c2.minOf { it.l }, 92_400.0)
            )
        }
    )
    render(outDir, "06-loading.png", UiState(conn = Conn.CONNECTING))
    render(outDir, "10-720p.png", base, w = 1280, h = 720, density = 1f)
    render(outDir, "11-4k.png", base, w = 3840, h = 2160, density = 2f)
    render(outDir, "07-style-minimal.png", base, custom = { StyleMinimal(base) })
    render(outDir, "08-style-neon.png", base, custom = { StyleNeon(base) })

    // ---- 指标验收：默认全关的干净画面 + 1/2/3 个副图 ----
    render(outDir, "20-all-off.png", withIndicators(base, IndicatorSettings.DEFAULT))

    val overlayOnly = IndicatorSettings.DEFAULT
        .withToggled("ema")
        .withToggled("boll")
        .withToggled("vwap")
        .withToggled("pivot")
        .withParam("pivot", "basis", 0.0)

    val oneOsc = overlayOnly.withToggled("rsi")
    val twoOsc = oneOsc.withToggled("macd")
    val threeOsc = twoOsc.withToggled("kdj")

    render(outDir, "21-indicators-osc1.png", withIndicators(base, oneOsc))
    render(outDir, "22-indicators-osc2.png", withIndicators(base, twoOsc))
    render(outDir, "23-indicators-osc3.png", withIndicators(base, threeOsc))
    render(
        outDir,
        "24-indicator-settings.png",
        withIndicators(base, twoOsc).copy(
            settingsOpen = true,
            expandedIndicator = "rsi",
            // 把上面的三组折起来，让 1080p 截图能同时看到副图开关和展开的参数行。
            collapsedIndicatorGroups = setOf("OVERLAY", "REGRESSION", "LEVELS")
        ),
        scrollEvents = 60,
        scrollStep = 300f
    )

    // ---- 长周期验收：年线只有 9 根、周线约一年半 ----
    render(
        outDir,
        "30-tf-1y.png",
        withQuote(base, bucketCandles(TF.Y1, 9, 3_800.0, 2026L, 7_200.0, 9_000.0)).copy(tf = TF.Y1)
    )
    render(
        outDir,
        "31-tf-1w.png",
        withQuote(base, bucketCandles(TF.W1, 78, 52_000.0, 77L, 180.0, 4_200.0)).copy(tf = TF.W1)
    )

    println("ok -> ${outDir.absolutePath}")
}

/** 离屏渲染没有 ViewModel，指标结果在渲染前一次算好，和真机 UiState 的结构一致。 */
private fun withIndicators(
    st: UiState,
    settings: IndicatorSettings
): UiState {
    val results = IndicatorEngine().compute(settings, st.candles, revision = 1L)
    return st.copy(indicatorSettings = settings, indicatorResults = results)
}

private fun render(
    dir: File,
    name: String,
    st: UiState,
    custom: (@Composable () -> Unit)? = null,
    w: Int = 1920,
    h: Int = 1080,
    density: Float = 2f,
    scrollEvents: Int = 0,
    scrollStep: Float = 1f
) {
    val scene = ImageComposeScene(w, h, Density(density)) {
        AppTheme {
            if (custom != null) custom() else TvScreenInner(st = st, act = UiActions())
        }
    }
    scene.render()
    if (scrollEvents > 0) {
        val at = Offset(w / 2f, h / 2f)
        scene.sendPointerEvent(PointerEventType.Move, at)
        repeat(scrollEvents) {
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = at,
                scrollDelta = Offset(0f, scrollStep)
            )
            scene.render()
        }
    }
    val img = scene.render()
    val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
    File(dir, name).writeBytes(data.bytes)
    scene.close()
}
