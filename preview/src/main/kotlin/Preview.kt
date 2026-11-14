import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Coin
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.UiState
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

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "shots" })
    outDir.mkdirs()
    val cs = candles(150, 71_180.0, 20261112L, driftPerStep = 3.4, vol = 24.0)
    val last = cs.last().c
    val low = cs.minOf { it.l }
    val high = cs.maxOf { it.h }
    val open24 = cs.first().o

    val base = UiState(
        coin = Coin.BTC,
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
            coin = Coin.ETH,
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
    println("ok")
}

private fun render(
    dir: File,
    name: String,
    st: UiState,
    custom: (@Composable () -> Unit)? = null,
    w: Int = 1920,
    h: Int = 1080,
    density: Float = 2f
) {
    val scene = ImageComposeScene(w, h, Density(density)) {
        AppTheme {
            if (custom != null) custom() else TvScreenInner(st = st, act = UiActions())
        }
    }
    val img = scene.render()
    val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
    File(dir, name).writeBytes(data.bytes)
    scene.close()
}
