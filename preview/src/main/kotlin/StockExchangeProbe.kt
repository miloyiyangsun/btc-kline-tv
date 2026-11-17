import com.milolab.btctv.data.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * E0 探针（只读）：检测「加密交易所里的美股标的」。
 *
 * 覆盖 Binance TradFi 永续、Bybit 股票永续/xStocks、OKX 股票 SWAP/代币化股票、
 * Bitget RWA 永续/rStocks、Gate 股票永续/代币化股票、Hyperliquid HIP-3 xyz dex，
 * 并确认 Kraken 公开 API 里是否还有 xStocks。
 *
 * 每个源都列出标的数量、符号写法、NVDA 最新价，以及 1m/5m/15m/1H/4H/1D 是否可取。
 * 全部是公开接口，不需要 key。运行：gradlew -p preview run -PmainClass=StockExchangeProbeKt
 */

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val HTTP = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private val TFS = listOf("1m", "5m", "15m", "1H", "4H", "1D")

private data class Venue(
    val name: String,
    val countText: String,
    val naming: String,
    val samples: List<String>,
    val price: Double?,
    val candles: Map<String, List<Candle>>
)

// ---------------------------------------------------------------- HTTP

private suspend fun text(url: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Accept", "application/json")
        .build()
    HTTP.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
        r.body?.string() ?: throw java.io.IOException("empty body")
    }
}

private suspend fun json(url: String): JSONObject = JSONObject(text(url))

private suspend fun postJson(url: String, body: String): JSONObject = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Content-Type", "application/json")
        .post(body.toRequestBody(JSON_MEDIA))
        .build()
    HTTP.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
        JSONObject(r.body?.string() ?: throw java.io.IOException("empty body"))
    }
}

// ---------------------------------------------------------------- JSON helpers

private fun JSONObject.d(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key, Double.NaN)
    return if (!v.isNaN()) v else optString(key).toDoubleOrNull()
}

private fun JSONArray?.d(i: Int): Double? {
    if (this == null || i < 0 || i >= length() || isNull(i)) return null
    val v = optDouble(i, Double.NaN)
    return if (!v.isNaN()) v else optString(i).toDoubleOrNull()
}

private fun ms(raw: Long): Long = if (raw in 1..9_999_999_999L) raw * 1000L else raw

private fun flat(
    a: JSONArray,
    oi: Int, hi: Int, li: Int, ci: Int, vi: Int, ti: Int = 0
): Candle? {
    val t = ms(a.optLong(ti, 0L))
    val o = a.d(oi) ?: return null
    val h = a.d(hi) ?: return null
    val l = a.d(li) ?: return null
    val c = a.d(ci) ?: return null
    if (t <= 0L || o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0) return null
    return Candle(t, o, h, l, c, a.d(vi) ?: 0.0)
}

private fun objCandle(o: JSONObject): Candle? {
    val t = ms(o.optLong("t", 0L))
    val open = o.d("o") ?: return null
    val high = o.d("h") ?: return null
    val low = o.d("l") ?: return null
    val close = o.d("c") ?: return null
    if (t <= 0L) return null
    return Candle(t, open, high, low, close, o.d("v") ?: 0.0)
}

private fun sorted(rows: List<Candle>): List<Candle> = rows.sortedBy { it.t }

private fun arrCandles(root: JSONArray, parse: (JSONArray) -> Candle?): List<Candle> {
    val out = ArrayList<Candle>(root.length())
    for (i in 0 until root.length()) root.optJSONArray(i)?.let { a -> parse(a)?.let(out::add) }
    return sorted(out)
}

private fun objArray(root: JSONArray): List<Candle> {
    val out = ArrayList<Candle>(root.length())
    for (i in 0 until root.length()) root.optJSONObject(i)?.let { o -> objCandle(o)?.let(out::add) }
    return sorted(out)
}

// ---------------------------------------------------------------- shared probes

private suspend fun coverage(fetch: suspend (String) -> List<Candle>): Map<String, List<Candle>> {
    val out = LinkedHashMap<String, List<Candle>>()
    for (tf in TFS) out[tf] = runCatching { fetch(tf) }.getOrDefault(emptyList())
    return out
}

private fun tfBinance(tf: String) = when (tf) {
    "1H" -> "1h"; "4H" -> "4h"; "1D" -> "1d"; else -> tf
}

private fun tfBybit(tf: String) = when (tf) {
    "1m" -> "1"; "5m" -> "5"; "15m" -> "15"; "1H" -> "60"; "4H" -> "240"; "1D" -> "D"; else -> tf
}

private fun tfOkx(tf: String) = tf

private fun tfBitget(tf: String) = when (tf) {
    "1H" -> "1H"; "4H" -> "4H"; "1D" -> "1D"; else -> tf
}

private fun tfGate(tf: String) = when (tf) {
    "1H" -> "1h"; "4H" -> "4h"; "1D" -> "1d"; else -> tf
}

private fun tfHl(tf: String) = when (tf) {
    "1H" -> "1h"; "4H" -> "4h"; "1D" -> "1d"; else -> tf
}

// ---------------------------------------------------------------- venues

private suspend fun binance(): Venue {
    val info = json("https://fapi.binance.com/fapi/v1/exchangeInfo")
    val arr = info.optJSONArray("symbols") ?: JSONArray()
    var equity = 0
    var premarket = 0
    var hk = 0
    val samples = ArrayList<String>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("status") != "TRADING") continue
        when (o.optString("underlyingType")) {
            "EQUITY" -> {
                equity++
                if (samples.size < 8) samples.add(o.optString("symbol"))
            }
            "PREMARKET" -> premarket++
            "HK_EQUITY" -> hk++
        }
    }
    val px = json("https://fapi.binance.com/fapi/v1/ticker/price?symbol=NVDAUSDT").d("price")
    val candles = coverage { tf ->
        val root = JSONArray(
            text("https://fapi.binance.com/fapi/v1/klines?symbol=NVDAUSDT&interval=${tfBinance(tf)}&limit=6")
        )
        arrCandles(root) { a -> flat(a, 1, 2, 3, 4, 5, 0) }
    }
    return Venue(
        "Binance",
        "US 股票永续 $equity 只（另有 pre-IPO $premarket / 港股 $hk）",
        "NVDAUSDT",
        samples,
        px,
        candles
    )
}

private suspend fun bybit(): Venue {
    val lin = json("https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000")
        .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
    var stock = 0
    val samples = ArrayList<String>()
    for (i in 0 until lin.length()) {
        val o = lin.optJSONObject(i) ?: continue
        if (o.optString("symbolType") != "stock") continue
        stock++
        if (samples.size < 8) samples.add("${o.optString("symbol")}(${o.optString("fullName")})")
    }
    val spot = json("https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000")
        .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
    var xstocks = 0
    for (i in 0 until spot.length()) {
        if (spot.optJSONObject(i)?.optString("symbolType") == "xstocks") xstocks++
    }
    val px = json("https://api.bybit.com/v5/market/tickers?category=linear&symbol=NVDAUSDT")
        .optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)?.d("lastPrice")
    val candles = coverage { tf ->
        val root = json("https://api.bybit.com/v5/market/kline?category=linear&symbol=NVDAUSDT&interval=${tfBybit(tf)}&limit=6")
            .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        arrCandles(root) { a -> flat(a, 1, 2, 3, 4, 5) }
    }
    return Venue(
        "Bybit",
        "股票永续 $stock 只；另 xStocks 现货 $xstocks 只",
        "NVDAUSDT / 现货 NVDAXUSDT",
        samples,
        px,
        candles
    )
}

private suspend fun okx(): Venue {
    val swap = json("https://www.okx.com/api/v5/public/instruments?instType=SWAP")
        .optJSONArray("data") ?: JSONArray()
    var swaps = 0
    val samples = ArrayList<String>()
    for (i in 0 until swap.length()) {
        val o = swap.optJSONObject(i) ?: continue
        if (o.optString("instCategory") != "3") continue
        swaps++
        if (samples.size < 8) samples.add(o.optString("instId"))
    }
    val spot = json("https://www.okx.com/api/v5/public/instruments?instType=SPOT")
        .optJSONArray("data") ?: JSONArray()
    var tokens = 0
    for (i in 0 until spot.length()) {
        if (spot.optJSONObject(i)?.optString("instCategory") == "3") tokens++
    }
    val px = json("https://www.okx.com/api/v5/market/ticker?instId=NVDA-USDT-SWAP")
        .optJSONArray("data")?.optJSONObject(0)?.d("last")
    val candles = coverage { tf ->
        val root = json("https://www.okx.com/api/v5/market/candles?instId=NVDA-USDT-SWAP&bar=${tfOkx(tf)}&limit=6")
            .optJSONArray("data") ?: JSONArray()
        arrCandles(root) { a -> flat(a, 1, 2, 3, 4, 5) }
    }
    return Venue(
        "OKX",
        "股票 SWAP $swaps 只；代币化股票现货 $tokens 只",
        "NVDA-USDT-SWAP / 现货 XNVDA-USDT",
        samples,
        px,
        candles
    )
}

private suspend fun bitget(): Venue {
    val spot = json("https://api.bitget.com/api/v2/spot/public/symbols")
        .optJSONArray("data") ?: JSONArray()
    var rstocks = 0
    val rUniverse = HashSet<String>()
    for (i in 0 until spot.length()) {
        val o = spot.optJSONObject(i) ?: continue
        val base = o.optString("baseCoin")
        if (o.optString("status") == "online" &&
            o.optString("quoteCoin") == "USDT" &&
            base.startsWith("r") && base.length > 1 && base.substring(1).all { it.isUpperCase() }
        ) {
            rstocks++
            rUniverse.add(base.substring(1))
        }
    }
    val mix = json("https://api.bitget.com/api/v2/mix/market/contracts?productType=usdt-futures")
        .optJSONArray("data") ?: JSONArray()
    var rwa = 0
    var usApprox = 0
    val samples = ArrayList<String>()
    for (i in 0 until mix.length()) {
        val o = mix.optJSONObject(i) ?: continue
        if (o.optString("isRwa") != "YES") continue
        rwa++
        val base = o.optString("baseCoin")
        val norm = if (base.endsWith("STOCK")) base.dropLast(5) else base
        if (norm in rUniverse) {
            usApprox++
            if (samples.size < 8) samples.add(o.optString("symbol"))
        }
    }
    val pxRow = json("https://api.bitget.com/api/v2/mix/market/ticker?symbol=NVDAUSDT&productType=usdt-futures")
        .optJSONArray("data")?.optJSONObject(0)
    val px = pxRow?.d("lastPr")
    val candles = coverage { tf ->
        val root = json(
            "https://api.bitget.com/api/v2/mix/market/candles?symbol=NVDAUSDT&productType=usdt-futures" +
                "&granularity=${tfBitget(tf)}&limit=6"
        ).optJSONArray("data") ?: JSONArray()
        arrCandles(root) { a -> flat(a, 1, 2, 3, 4, 5) }
    }
    return Venue(
        "Bitget",
        "RWA 永续 $rwa 只（其中美股约 $usApprox，其余为亚太股/商品/FX/指数）；rStocks 现货 $rstocks 只",
        "NVDAUSDT / 现货 RNVDAUSDT",
        samples,
        px,
        candles
    )
}

private suspend fun gate(): Venue {
    val fut = JSONArray(text("https://api.gateio.ws/api/v4/futures/usdt/contracts"))
    var stockFut = 0
    val samples = ArrayList<String>()
    for (i in 0 until fut.length()) {
        val o = fut.optJSONObject(i) ?: continue
        if (o.optString("contract_type") != "stocks") continue
        stockFut++
        if (samples.size < 8) samples.add(o.optString("name"))
    }
    val spot = JSONArray(text("https://api.gateio.ws/api/v4/spot/currency_pairs"))
    var xstock = 0
    var ondo = 0
    for (i in 0 until spot.length()) {
        val o = spot.optJSONObject(i) ?: continue
        if (o.optString("trade_status") != "tradable") continue
        val n = o.optString("base_name")
        if (n.contains("xStock")) xstock++
        if (n.contains("Ondo Tokenized")) ondo++
    }
    val px = JSONArray(text("https://api.gateio.ws/api/v4/futures/usdt/tickers?contract=NVDA_USDT"))
        .optJSONObject(0)?.d("last")
    val candles = coverage { tf ->
        val root = JSONArray(
            text("https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=NVDA_USDT&interval=${tfGate(tf)}&limit=6")
        )
        val out = ArrayList<Candle>(root.length())
        for (i in 0 until root.length()) root.optJSONObject(i)?.let { o -> objCandle(o)?.let(out::add) }
        sorted(out)
    }
    return Venue(
        "Gate",
        "股票永续 $stockFut 只（contract_type=stocks）；现货 xStocks $xstock + Ondo $ondo",
        "NVDA_USDT / 现货 NVDAX_USDT",
        samples,
        px,
        candles
    )
}

private suspend fun hyperliquid(): Venue {
    val meta = postJson("https://api.hyperliquid.xyz/info", """{"type":"meta","dex":"xyz"}""")
    val uni = meta.optJSONArray("universe") ?: JSONArray()
    val samples = ArrayList<String>()
    var i = 0
    while (i < uni.length() && samples.size < 8) {
        uni.optJSONObject(i)?.optString("name")?.let(samples::add)
        i++
    }
    val mids = postJson("https://api.hyperliquid.xyz/info", """{"type":"allMids","dex":"xyz"}""")
    val px = mids.d("xyz:NVDA")
    val now = System.currentTimeMillis()
    val candles = coverage { tf ->
        val body = """{"type":"candleSnapshot","req":{"coin":"xyz:NVDA","interval":"${tfHl(tf)}","startTime":${now - 3 * 86_400_000L},"endTime":$now}}"""
        val root = JSONArray(withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://api.hyperliquid.xyz/info")
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            HTTP.newCall(req).execute().use { r -> r.body?.string() ?: "[]" }
        })
        objArray(root).takeLast(6)
    }
    return Venue(
        "Hyperliquid",
        "HIP-3 xyz dex 共 ${uni.length()} 个标的（约 95 个美股，其余为商品/FX/指数/亚洲股）",
        "xyz:NVDA（HIP-3 dex 前缀）",
        samples,
        px,
        candles
    )
}

private suspend fun kraken(): Venue {
    val root = json("https://api.kraken.com/0/public/AssetPairs")
    val result = root.optJSONObject("result") ?: JSONObject()
    val samples = ArrayList<String>()
    var n = 0
    for (k in result.keys()) {
        val o = result.optJSONObject(k) ?: continue
        val base = o.optString("base")
        // xStocks 的写法是 NVDAx / TSLAx：小写 x 结尾，且不是加密资产
        if (base.endsWith("x") && base.length in 2..8) {
            n++
            if (samples.size < 8) samples.add(o.optString("wsname"))
        }
    }
    val direct = runCatching {
        json("https://api.kraken.com/0/public/AssetPairs?pair=NVDAxUSD").optJSONArray("error")
    }.getOrNull()
    val note = if (direct != null && direct.length() > 0) "，NVDAxUSD -> ${direct.optString(0)}" else ""
    return Venue(
        "Kraken",
        "公开 AssetPairs 中 xStocks $n 个$note",
        "NVDAxUSD（若有）",
        samples,
        null,
        emptyMap()
    )
}

// ---------------------------------------------------------------- Yahoo baseline

private suspend fun yahooNvda(): Double? {
    val root = json("https://query1.finance.yahoo.com/v8/finance/chart/NVDA?interval=1m&range=1d&includePrePost=true")
    val res = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
    val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
    if (closes != null) {
        for (i in closes.length() - 1 downTo 0) closes.d(i)?.let { return it }
    }
    return res.optJSONObject("meta")?.d("regularMarketPrice")
}

// ---------------------------------------------------------------- output

private fun fmt(d: Double?): String = d?.let { String.format("%.2f", it) } ?: "-"

private fun ts(t: Long): String = Instant.ofEpochMilli(t)
    .atZone(ZoneId.of("America/New_York"))
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm z"))

private fun printVenue(v: Venue, yahoo: Double?) {
    println()
    println("=== ${v.name} ===")
    println("标的数量 : ${v.countText}")
    println("符号写法 : ${v.naming}")
    println("样例     : ${v.samples.joinToString(", ")}")
    val diff = if (v.price != null && yahoo != null && yahoo > 0.0) {
        String.format("  (相对 Yahoo 盘后 %s: %+.2f%%)", fmt(yahoo), (v.price - yahoo) / yahoo * 100.0)
    } else ""
    println("NVDA 最新 : ${fmt(v.price)}$diff")
    if (v.candles.isEmpty()) {
        println("K 线      : 无美股交易对")
        return
    }
    for ((tf, list) in v.candles) {
        val last = list.lastOrNull()
        if (last == null) {
            println("  $tf: 0 根")
        } else {
            println("  $tf: ${list.size} 根, 最新 ${ts(last.t)} O=${fmt(last.o)} H=${fmt(last.h)} L=${fmt(last.l)} C=${fmt(last.c)} V=${last.v.toLong()}")
        }
    }
}

fun main() = runBlocking {
    println("=== E0 美股标的 · 交易所实测（只读） ===")
    val probes: List<Pair<String, suspend () -> Venue>> = listOf(
        "Binance" to ::binance,
        "Bybit" to ::bybit,
        "OKX" to ::okx,
        "Bitget" to ::bitget,
        "Gate" to ::gate,
        "Hyperliquid" to ::hyperliquid,
        "Kraken" to ::kraken
    )
    val yahoo = runCatching { yahooNvda() }.getOrNull()
    println("Yahoo NVDA 盘后基准: ${fmt(yahoo)}")

    val ok = ArrayList<Venue>()
    for ((name, probe) in probes) {
        val r = runCatching { probe() }
        r.fold(
            onSuccess = { v -> ok.add(v); printVenue(v, yahoo) },
            onFailure = { e -> println("\n=== $name ===\nFAIL ${e.message}") }
        )
    }

    println()
    println("=== 汇总 ===")
    for (v in ok) println("${v.name.padEnd(12)} ${v.countText}")
    println()
    println("PROBE DONE")
}
