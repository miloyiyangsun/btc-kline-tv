import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.aggregateCandles
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 只读探针：用真实网络验证新周期。
 * 重点看 OKX 的 30m / 12Hutc / 1Wutc / 1Mutc 是否可用，以及月线聚合出的年线边界。
 */
fun main() = runBlocking {
    val client = MarketClient()
    val tfs = listOf(TF.M30, TF.H12, TF.W1, TF.MO1, TF.Y1)

    println("=== 1) OKX BTC-USDT 新周期 ===")
    for (tf in tfs) {
        val r = runCatching { client.fetchCandles("OKX", Instruments.BTC, tf, 120) }
        r.fold(
            onSuccess = { cs -> printResult("OKX", tf, cs) },
            onFailure = { println("OKX ${tf.label} => FAIL ${it.message}") }
        )
    }

    println()
    println("=== 2) Binance / Bybit 同周期（年线为三家共用聚合） ===")
    for (p in listOf("Binance", "Bybit")) {
        for (tf in tfs) {
            val r = runCatching { client.fetchCandles(p, Instruments.BTC, tf, 120) }
            r.fold(
                onSuccess = { cs -> printResult(p, tf, cs) },
                onFailure = { println("$p ${tf.label} => FAIL ${it.message}") }
            )
        }
    }

    println()
    println("=== 3) 年线聚合自检（月线 -> 年线） ===")
    val months = client.fetchCandles("OKX", Instruments.BTC, TF.MO1, 500)
    val aggregated = aggregateCandles(months, TF.Y1)
    val direct = client.fetchCandles("OKX", Instruments.BTC, TF.Y1, 120)
    println("月线 ${months.size} 根 -> 聚合年线 ${aggregated.size} 根；直接拉年线 ${direct.size} 根")
    val overlap = direct.map { it.t }.toSet()
    var mismatches = 0
    for (bar in aggregated.filter { it.t in overlap }) {
        val ref = direct.first { it.t == bar.t }
        val same = kotlin.math.abs(bar.o - ref.o) < 1e-6 &&
            kotlin.math.abs(bar.h - ref.h) < 1e-6 &&
            kotlin.math.abs(bar.l - ref.l) < 1e-6 &&
            kotlin.math.abs(bar.c - ref.c) < 1e-6
        if (!same) {
            mismatches++
            println("MISMATCH ${fmt(bar.t)} agg=${bar.o}/${bar.h}/${bar.l}/${bar.c} direct=${ref.o}/${ref.h}/${ref.l}/${ref.c}")
        }
    }
    println("OHLC 不一致年数 = $mismatches（当前未走完的年，最后一根可能因数据截止不同略有差异）")
    println("PROBE DONE")
}

private fun printResult(provider: String, tf: TF, cs: List<Candle>) {
    if (cs.isEmpty()) {
        println("$provider ${tf.label} => 空")
        return
    }
    val first = cs.first()
    val last = cs.last()
    val aligned = cs.all { it.t == tf.bucketStart(it.t) }
    println(
        "$provider ${tf.label} => ${cs.size} 根  " +
            "${fmt(first.t)} .. ${fmt(last.t)}  " +
            "收盘 ${last.c}  桶起点合法=$aligned"
    )
}

private fun fmt(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(ms)) + "Z"
