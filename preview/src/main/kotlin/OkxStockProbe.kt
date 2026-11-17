import com.milolab.btctv.data.InstrumentSearch
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.OkxProvider
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.TF
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 实测 OKX 股票目录、K 线、报价与本地中文/前缀搜索。
 * 运行：gradlew -p preview run -PmainClass=OkxStockProbeKt
 */
fun main() = runBlocking {
    val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val okx = OkxProvider(http, http)

    println("== OKX instruments ==")
    val all = okx.search()
    val stocks = all.filter { it.stockKind != null }
    println("total=${all.size} stocks=${stocks.size} perp=${stocks.count { it.stockKind == StockKind.PERP }} tokenized=${stocks.count { it.stockKind == StockKind.TOKENIZED }}")

    for (kind in listOf(StockKind.PERP, StockKind.TOKENIZED)) {
        val inst = stocks.firstOrNull { it.baseCurrency == "NVDA" && it.stockKind == kind }
        println("-- NVDA ${kind.label}: ${inst?.id} ${inst?.symbols}")
        if (inst == null) continue
        val symbol = inst.symbols.getValue("OKX")
        val candles = runCatching { okx.fetchCandles(inst, TF.M1, 20) }
            .onFailure { println("candles FAILED: ${it.message}") }
            .getOrDefault(emptyList())
        println("candles=${candles.size} first=${candles.firstOrNull()?.t} last=${candles.lastOrNull()} ")
        val quote = runCatching { okx.fetchQuote(inst) }
            .onFailure { println("quote FAILED: ${it.message}") }
            .getOrNull()
        println("quote=$quote")
        println("symbolCheck=$symbol")
    }

    println("== local search ==")
    for (q in listOf("nvd", "英伟达")) {
        val hits = InstrumentSearch.search(q, all).take(5)
        println("query=$q -> " + hits.joinToString { "${it.id}/${it.baseCurrency}/${it.stockKind?.label}/${it.symbols["OKX"]}" })
    }

    println("== bootstrap (preferred=Bybit, stock must stay OKX) ==")
    val client = MarketClient()
    for (kind in listOf(StockKind.PERP, StockKind.TOKENIZED)) {
        val inst = stocks.firstOrNull { it.baseCurrency == "NVDA" && it.stockKind == kind } ?: continue
        val snap = runCatching { client.bootstrap("Bybit", inst, TF.M5, 30) }
            .onFailure { println("${inst.id} FAILED: ${it.message}") }
            .getOrNull() ?: continue
        println("${inst.id} provider=${snap.provider} candles=${snap.candles.size} quote=${snap.quote.price}")
    }

    val merged = Instruments.merge(all)
    println("merged=${merged.size} hot=${merged.count { it.hot }}")
    println("hotStocks=" + merged.filter { it.hot && it.marketType == com.milolab.btctv.data.MarketType.STOCK }
        .joinToString { it.baseCurrency })
}
