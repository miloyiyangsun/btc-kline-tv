import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.MarketFilter
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.PROVIDER_BINANCE
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.hub.CandleStore
import com.milolab.btctv.data.hub.Mover
import com.milolab.btctv.data.hub.MoversRepository
import com.milolab.btctv.data.hub.OkxPublicApi
import com.milolab.btctv.data.hub.QuoteHub
import com.milolab.btctv.data.hub.RateLimiter
import com.milolab.btctv.data.hub.parseStockSwapMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 只读联网探针：验证 QuoteHub / CandleStore / MoversRepository / Binance 修复。
 * 跑法：gradle -p preview run -PmainClass=HubProbeKt
 * 失败自动等 30 秒重试；不写任何文件、不提交任何订单。
 */

private val clockFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)

fun main(): Unit = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val client = MarketClient()
    val api = OkxPublicApi(client.http)
    var hub: QuoteHub? = null

    try {
        val started = System.currentTimeMillis()
        section("0. 环境")
        println("OKX REST 全局令牌桶：10 req/s，burst 20；当前可用 ${"%.1f".format(RateLimiter.okxGlobal.available())}")

        // ---------------------------------------------------- 1. 标的准备
        section("1. OKX 美股永续元数据")
        val meta = retry30("拉取 SWAP 元数据") {
            parseStockSwapMeta(api.instrumentsJson("SWAP"))
        } ?: emptyMap()
        println("已上市美股永续 ${meta.size} 个：${meta.values.sorted().take(30).joinToString(", ")}")

        val cryptos = listOf(
            Instruments.BTC, Instruments.ETH, Instruments.SOL, Instruments.DOGE,
            Instruments.BNB, Instruments.XRP, Instruments.ADA, Instruments.AVAX, Instruments.LINK
        )
        val cryptoBases = cryptos.mapTo(HashSet()) { it.baseCurrency.uppercase() }
        val wantedStocks = listOf("NVDA", "TSLA", "AAPL", "MSFT", "META", "AMZN", "COIN")
        val stockTargets = wantedStocks.mapNotNull { ticker ->
            val instId = meta.entries.firstOrNull { it.value == ticker }?.key ?: return@mapNotNull null
            Instruments.stock(
                ticker = ticker,
                priceDecimals = 2,
                symbols = mapOf(PROVIDER_OKX to instId),
                kind = StockKind.PERP,
                cryptoBases = cryptoBases
            ) to instId
        }
        stockTargets.forEach { (inst, instId) -> println("股票永续：${inst.baseCurrency} ($instId)") }

        val targets = ArrayList<Instrument>()
        targets.addAll(cryptos.take(6))
        targets.addAll(stockTargets.take(4).map { it.first })
        for (extra in cryptos.drop(6)) {
            if (targets.size >= 9) break
            targets.add(extra)
        }
        val nine = targets.distinctBy { it.id }.take(9)
        println("探针标的（${nine.size} 个）：${nine.joinToString(", ") { it.baseCurrency }}")

        // ---------------------------------------------------- 2. QuoteHub
        section("2. QuoteHub：一条 WS 同时订阅 ${nine.size} 个标的")
        val quoteHub = QuoteHub(client, scope, maxSubscriptions = 12)
        hub = quoteHub
        quoteHub.setInstruments(nine)

        val quoteDeadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < quoteDeadline && nine.any { quoteHub.quote(it.id) == null }) {
            delay(200)
        }
        // 报价可能先由 REST 兜底返回；再等 WS 真正握手，确认长连接可用
        val liveDeadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < liveDeadline && quoteHub.conn.value != Conn.LIVE) {
            delay(200)
        }
        delay(2_000) // 让 WS 推几轮，确认是真的实时流而不是订阅确认
        println("连接状态：${quoteHub.conn.value}   已收到报价：${nine.count { quoteHub.quote(it.id) != null }}/${nine.size}   WS tickers 推送：${quoteHub.wsTickCount()} 条")
        var quoteMissing = 0
        for (inst in nine) {
            val q = quoteHub.quote(inst.id)
            if (q == null) {
                quoteMissing++
                println("  ${inst.baseCurrency.padEnd(6)} ——  无报价")
                continue
            }
            val pct = q.changePct?.let { "%+.2f%%".format(Locale.US, it) } ?: "  n/a "
            println(
                "  ${inst.baseCurrency.padEnd(6)} ${priceText(q.price, inst.priceDecimals).padStart(12)} " +
                    "$pct  24h量=${compact(q.quoteVol24h ?: 0.0)}  ${inst.marketType}${inst.stockKind?.let { "/${it.label}" } ?: ""}"
            )
        }

        // 增/减订阅：先移除一个、再补一个，验证只发增量而不是重连
        section("3. QuoteHub：增量订阅测试")
        val removable = nine.firstOrNull { it.marketType == MarketType.CRYPTO } ?: nine.last()
        val extra = cryptos.firstOrNull { c -> nine.none { it.id == c.id } }
        quoteHub.removeInstrument(removable.id)
        println("remove ${removable.baseCurrency} -> quote(${removable.id}) = ${quoteHub.quote(removable.id)}")
        if (extra != null) {
            quoteHub.addInstrument(extra)
            val deadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < deadline && quoteHub.quote(extra.id) == null) delay(200)
            println("add ${extra.baseCurrency} -> ${quoteHub.quote(extra.id)?.price ?: "等待超时"}；当前订阅 ${quoteHub.instruments().size} 个")
        }
        val active = quoteHub.instruments()

        // ---------------------------------------------------- 4. CandleStore
        section("4. CandleStore：1m 迷你走势（每个标的 48 点）")
        val store = CandleStore(client)
        var sparkOk = 0
        for (inst in active) {
            val spark = retry30("${inst.baseCurrency} 迷你走势", attempts = 2) {
                store.spark(inst, TF.M1, 48)
            }
            if (spark == null || spark.isEmpty()) {
                println("  ${inst.baseCurrency.padEnd(6)} 失败")
                continue
            }
            sparkOk++
            println(
                "  ${inst.baseCurrency.padEnd(6)} ${spark.size} 点  " +
                    "首=${spark.first()} 末=${spark.last()}  低=${spark.minOrNull()} 高=${spark.maxOrNull()}"
            )
        }
        println("迷你走势成功 $sparkOk/${active.size}")

        // ---------------------------------------------------- 5. Movers
        section("5. MoversRepository：OKX SPOT + 美股永续涨跌榜")
        val movers = MoversRepository(client)
        var snapshot = movers.refresh(force = true)
        if (snapshot == null) {
            println("  首次拉取失败，30 秒后重试…")
            delay(30_000)
            snapshot = movers.refresh(force = true)
        }
        if (snapshot == null) {
            println("  涨跌榜不可用（网络失败）")
        } else {
            println("  共 ${snapshot.rows.size} 行，updated=${clockFmt.format(Instant.ofEpochMilli(snapshot.updatedAtMs))}Z stale=${snapshot.stale}")
            printMovers("涨幅前 5", snapshot.gainers(5))
            printMovers("跌幅前 5", snapshot.losers(5))
            printMovers("美股涨幅前 5", snapshot.gainers(5, MarketFilter.STOCK))
            printMovers("美股跌幅前 5", snapshot.losers(5, MarketFilter.STOCK))
            println("  按市场过滤：加密 ${snapshot.rows.count { it.marketType == MarketType.CRYPTO }} 行，美股 ${snapshot.rows.count { it.marketType == MarketType.STOCK }} 行")
        }

        // ---------------------------------------------------- 6. Binance
        section("6. Binance 现货 K 线：数字/字符串解析修复验证（BTCUSDT）")
        var binanceOk = 0
        for (tf in listOf(TF.M30, TF.H12, TF.W1, TF.MO1)) {
            val candles = retry30("Binance BTCUSDT ${tf.label}") {
                client.fetchCandles(PROVIDER_BINANCE, Instruments.BTC, tf, 24)
            }
            if (candles == null || candles.isEmpty()) {
                println("  ${tf.label.padEnd(4)} 失败")
                continue
            }
            binanceOk++
            val last = candles.last()
            println(
                "  ${tf.label.padEnd(4)} ${candles.size} 根  " +
                    "首=${clockFmt.format(Instant.ofEpochMilli(candles.first().t))}  " +
                    "末=${clockFmt.format(Instant.ofEpochMilli(last.t))}  收=${"% .2f".format(Locale.US, last.c)}  " +
                    "区间=%.2f..%.2f".format(Locale.US, candles.minOf { it.l }, candles.maxOf { it.h })
            )
        }
        println("Binance 周期 4/4 成功：$binanceOk/4")

        section("完成")
        println("耗时 ${(System.currentTimeMillis() - started) / 1000}s；缺失报价 $quoteMissing 个；迷你走势 $sparkOk/${active.size}；Binance $binanceOk/4")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("探针异常：${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        hub?.close()
        scope.cancel()
    }
    exitProcess(0)
}

private suspend fun <T> retry30(
    label: String,
    attempts: Int = 3,
    block: suspend () -> T
): T? {
    var last: Exception? = null
    repeat(attempts) { index ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            println("  [重试] $label 失败 (${index + 1}/$attempts)：${e.message ?: e::class.simpleName}")
            if (index < attempts - 1) {
                println("  等待 30 秒后重试…")
                delay(30_000)
            }
        }
    }
    println("  [放弃] $label：${last?.message ?: "未知错误"}")
    return null
}

private fun printMovers(title: String, rows: List<Mover>) {
    println("  $title")
    if (rows.isEmpty()) {
        println("    （空）")
        return
    }
    for (row in rows) {
        println(
            "    ${row.symbol.padEnd(6)} ${"% .6f".format(Locale.US, row.price).padStart(13)} " +
                "%+7.2f%%  量=%s  %s".format(
                    Locale.US,
                    row.changePct,
                    compact(row.quoteVolume24h),
                    if (row.marketType == MarketType.STOCK) "股票永续" else "现货"
                )
        )
    }
}

private fun priceText(price: Double, decimals: Int): String =
    "%,.${decimals.coerceIn(0, 8)}f".format(Locale.US, price)

private fun compact(value: Double): String = when {
    value >= 1_000_000_000.0 -> "%.2fB".format(Locale.US, value / 1_000_000_000.0)
    value >= 1_000_000.0 -> "%.2fM".format(Locale.US, value / 1_000_000.0)
    value >= 1_000.0 -> "%.2fK".format(Locale.US, value / 1_000.0)
    else -> "%.2f".format(Locale.US, value)
}

private fun section(title: String) {
    println()
    println("=".repeat(64))
    println(title)
    println("=".repeat(64))
}
