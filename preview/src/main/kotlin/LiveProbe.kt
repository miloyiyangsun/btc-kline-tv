import com.milolab.btctv.data.Coin
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.TF
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * 真机之外的端到端验证：直接用 App 里的 MarketClient 打真实交易所接口，
 * 跑一遍 REST 引导 + WebSocket 推流，确认解析和故障转移都对。
 */
fun main() = runBlocking {
    val client = MarketClient()
    println("=== 1) bootstrap (自动选源) ===")
    val snap = client.bootstrap(null, Coin.BTC, TF.M1)
    println("provider    = ${snap.provider}")
    println("candles     = ${snap.candles.size} 根")
    println("first       = ${snap.candles.first()}")
    println("last        = ${snap.candles.last()}")
    println("quote       = ${snap.quote}")
    println("changePct   = ${snap.quote.changePct}")

    println()
    println("=== 2) 三个交易所 REST 逐一体检 ===")
    for (p in listOf("OKX", "Binance", "Bybit")) {
        val r = runCatching { client.fetchCandles(p, Coin.BTC, TF.M5, 60) }
        println("$p => " + if (r.isSuccess) "OK ${r.getOrNull()?.size} 根, 收盘 ${r.getOrNull()?.last()?.c}" else "FAIL ${r.exceptionOrNull()?.message}")
    }

    println()
    println("=== 3) WebSocket 实时推流 (每个源取 3 笔) ===")
    for (p in listOf(snap.provider, "OKX", "Bybit", "Binance")) {
        val ticks = withTimeoutOrNull(12_000) {
            client.liveStream(p, Coin.BTC).take(3).toList()
        }
        if (ticks == null) {
            println("$p => 12s 内没有推送")
        } else {
            ticks.forEach { println("$p tick -> ${it.price}  (24h ${it.high24h} / ${it.low24h}, open ${it.open24h})") }
        }
    }
    println()
    println("PROBE DONE")
}
