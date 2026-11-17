import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 美股数据源探针（只读）。
 *
 * 数据来自 Yahoo Finance 未公开的 v8 chart / v1 search 接口：
 *  - 不需要 key、不需要 cookie；
 *  - 返回的时间戳是「交易所时区的 epoch 秒」，这里统一换成 app 里的毫秒；
 *  - v7/v10 quote 接口要 crumb，会被 401 拒绝，所以报价一律从 chart 的 meta 取。
 *
 * 注意：这是非官方接口，Yahoo 服务条款禁止未经许可的自动化抓取，
 * 正式产品应换成有授权的数据源。此文件只用于可行性验证。
 */

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val HOSTS = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")

private val US_EXCHANGES = setOf("NMS", "NGM", "NCM", "NYQ", "ASE", "PCX", "BATS", "PNK", "NAS")

data class StockHit(
    val symbol: String,
    val name: String,
    val exchange: String,
    val type: String,
    val score: Double = 0.0
)

/** 美股报价。price 为盘中价（收盘后即收盘价），extendedPrice 为含盘前盘后的最新价。 */
data class StockQuote(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val price: Double,
    val previousClose: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Double?,
    val marketTimeSec: Long?,
    val marketState: String,
    val extendedPrice: Double?,
    val regularStartSec: Long?,
    val regularEndSec: Long?
) {
    fun toQuote(): Quote = Quote(
        price = price,
        open24h = previousClose,
        high24h = dayHigh,
        low24h = dayLow,
        vol24h = volume
    )

    val changePct: Double?
        get() = previousClose?.takeIf { it > 0.0 }?.let { (price - it) / it * 100.0 }
}

class StockApi {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------ 搜索

    suspend fun searchStocks(query: String, limit: Int = 10): List<StockHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val path = "/v1/finance/search?q=" + enc(q) +
            "&quotesCount=" + limit.coerceIn(5, 50) +
            "&newsCount=0&listsCount=0&enableFuzzyQuery=true"
        val arr = getJson(path).optJSONArray("quotes") ?: return@withContext emptyList()
        val hits = ArrayList<StockHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("isYahooFinance", true).not()) continue
            val symbol = o.optString("symbol").orEmpty()
            val type = o.optString("quoteType").orEmpty()
            if (symbol.isBlank() || type !in setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND")) continue
            hits.add(
                StockHit(
                    symbol = symbol,
                    name = o.optString("longname").ifBlank { o.optString("shortname") },
                    exchange = o.optString("exchDisp").ifBlank { o.optString("exchange") },
                    type = type,
                    score = o.optDouble("score", 0.0)
                )
            )
        }
        // Yahoo 会把「代码完全相同的 ETF」排在股票前面（nvd → NVD ETF 先于 NVDA），
        // 所以这里用 ln(原始 score) + 类型/交易所加权重排：先压掉 ETF 的完全匹配优势，
        // 又保留 Yahoo 对 appl→AAPL、tes→TSLA 这类模糊匹配的排序。
        hits.sortedWith(
            compareByDescending<StockHit> { rank(it) }
                .thenBy { it.symbol.length }
                .thenBy { it.symbol }
        ).take(limit)
    }

    private fun rank(h: StockHit): Double {
        val base = kotlin.math.ln(1.0 + h.score.coerceAtLeast(0.0)) * 10.0
        val typeScore = when (h.type) {
            "EQUITY" -> 30.0
            "ETF" -> 10.0
            "INDEX" -> 5.0
            "MUTUALFUND" -> 0.0
            else -> 0.0
        }
        val usScore = if (h.exchange.uppercase() in US_EXCHANGES ||
            h.exchange.contains("NASDAQ", true) || h.exchange.contains("NYSE", true)
        ) 8.0 else 0.0
        return base + typeScore + usScore
    }

    // ------------------------------------------------------------ K 线

    /**
     * @param interval app 里的周期写法：1m / 5m / 15m / 1H / 4H / 1D
     * @return 按时间升序，t 为毫秒（与 Model.kt、OKX/Binance/Bybit 的约定一致）
     */
    suspend fun fetchStockCandles(symbol: String, interval: String, limit: Int = 120): List<Candle> =
        withContext(Dispatchers.IO) {
            val sym = normalizeSymbol(symbol)
            val n = limit.coerceIn(10, 2000)
            val yInterval = yahooInterval(interval)
            val want4h = interval.equals("4H", true) || interval.equals("H4", true)

            // Yahoo 原生 4h 的桶按 08:30/12:30 ET 对齐，和券商常见的 09:30 开盘对齐不一致；
            // 统一用 1h 自己聚合成 4 小时，桶从当日第一根小时线开始。
            val need = if (want4h) n * 4 + 8 else n
            val path = "/v8/finance/chart/" + enc(sym) +
                "?interval=" + yInterval +
                "&range=" + rangeFor(yInterval, need) +
                (if (want4h) "" else "&includePrePost=false")

            val root = getJson(path)
            val rows = parseCandles(root).toMutableList()
            val out = if (want4h) aggregate4h(rows) else rows
            out.takeLast(n)
        }

    private fun parseCandles(root: JSONObject): List<Candle> {
        val chart = root.optJSONObject("chart")
            ?: throw IOException("Yahoo: malformed response")
        chart.optJSONObject("error")?.let {
            throw IOException("Yahoo: " + it.optString("description").ifBlank { it.optString("code") })
        }
        val result = chart.optJSONArray("result")?.optJSONObject(0)
            ?: throw IOException("Yahoo: no data (symbol may be delisted)")
        val meta = result.optJSONObject("meta") ?: JSONObject()
        val ts = result.optJSONArray("timestamp") ?: return emptyList()
        val q = result.optJSONObject("indicators")
            ?.optJSONArray("quote")?.optJSONObject(0)
            ?: return emptyList()
        val open = q.optJSONArray("open")
        val high = q.optJSONArray("high")
        val low = q.optJSONArray("low")
        val close = q.optJSONArray("close")
        val vol = q.optJSONArray("volume")

        val out = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            val o = open.num(i) ?: continue
            val h = high.num(i) ?: continue
            val l = low.num(i) ?: continue
            val c = close.num(i) ?: continue
            val v = vol.num(i) ?: 0.0
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0) continue
            out.add(Candle(t = ts.optLong(i) * 1000L, o = o, h = h, l = l, c = c, v = v))
        }
        // 休市后 Yahoo 会在末尾追加一根「收盘价、成交量 0」的合成 bar（时间等于 regularMarketTime），
        // 它不是真实成交，画图时会多出一根平线，这里去掉。
        val last = out.lastOrNull()
        val marketTimeMs = meta.optLong("regularMarketTime", -1L) * 1000L
        if (last != null && last.v == 0.0 && last.o == last.h && last.h == last.l &&
            last.l == last.c && marketTimeMs > 0 && last.t == marketTimeMs
        ) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    private fun aggregate4h(hourly: List<Candle>): List<Candle> {
        val out = ArrayList<Candle>()
        var started = false
        var start = 0L
        var lastT = 0L
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0
        for (k in hourly) {
            val newBucket = !started ||
                k.t - lastT > 2 * 3_600_000L ||          // 隔夜/午休
                k.t - start >= 4 * 3_600_000L            // 满 4 小时
            if (newBucket) {
                if (started) out.add(Candle(start, o, h, l, c, v))
                started = true
                start = k.t; o = k.o; h = k.h; l = k.l; c = k.c; v = k.v
            } else {
                if (k.h > h) h = k.h
                if (k.l < l) l = k.l
                c = k.c
                v += k.v
            }
            lastT = k.t
        }
        if (started) out.add(Candle(start, o, h, l, c, v))
        return out
    }

    // ------------------------------------------------------------ 报价

    suspend fun fetchStockQuote(symbol: String): StockQuote = withContext(Dispatchers.IO) {
        val sym = normalizeSymbol(symbol)
        // 必须用分钟级 + range=1d 才有 previousClose；interval=1d 的响应里没有昨收，
        // chartPreviousClose 则是「区间前一根」，多日区间会算错涨跌幅。
        val path = "/v8/finance/chart/" + enc(sym) + "?interval=5m&range=1d&includePrePost=true"
        val root = getJson(path)
        val chart = root.optJSONObject("chart")
            ?: throw IOException("Yahoo: malformed response")
        chart.optJSONObject("error")?.let {
            throw IOException("Yahoo: " + it.optString("description").ifBlank { it.optString("code") })
        }
        val result = chart.optJSONArray("result")?.optJSONObject(0)
            ?: throw IOException("Yahoo: no data (symbol may be delisted)")
        val m = result.optJSONObject("meta") ?: throw IOException("Yahoo: no meta")

        val price = m.num("regularMarketPrice") ?: throw IOException("Yahoo: no price")
        val prev = m.num("previousClose") ?: m.num("chartPreviousClose")
        val reg = m.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        val state = marketState(m)

        StockQuote(
            symbol = m.optString("symbol", sym),
            name = m.optString("longName").ifBlank { m.optString("shortName") },
            exchange = m.optString("fullExchangeName").ifBlank { m.optString("exchangeName") },
            currency = m.optString("currency"),
            price = price,
            previousClose = prev,
            dayHigh = m.num("regularMarketDayHigh"),
            dayLow = m.num("regularMarketDayLow"),
            volume = m.num("regularMarketVolume"),
            marketTimeSec = m.optLong("regularMarketTime", 0L).takeIf { it > 0L },
            marketState = state,
            extendedPrice = m.num("fulldayPrice"),
            regularStartSec = reg?.optLong("start")?.takeIf { it > 0L },
            regularEndSec = reg?.optLong("end")?.takeIf { it > 0L }
        )
    }

    /** 用 meta 里的当日交易时段判断，不依赖手机本地时区。 */
    private fun marketState(meta: JSONObject): String {
        val p = meta.optJSONObject("currentTradingPeriod") ?: return "UNKNOWN"
        val now = System.currentTimeMillis() / 1000L
        fun within(key: String): Boolean {
            val o = p.optJSONObject(key) ?: return false
            val s = o.optLong("start", 0L)
            val e = o.optLong("end", 0L)
            return s > 0 && e > 0 && now >= s && now < e
        }
        return when {
            within("regular") -> "REGULAR"
            within("pre") -> "PRE"
            within("post") -> "POST"
            else -> "CLOSED"
        }
    }

    // ------------------------------------------------------------ 工具

    private suspend fun getJson(path: String): JSONObject {
        var last: Exception? = null
        for (host in HOSTS) {
            try {
                val req = Request.Builder()
                    .url("https://" + host + path)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .build()
                val resp = http.newCall(req).execute()
                val code = resp.code
                val body = resp.use { it.body?.string() ?: "" }
                if (code !in 200..299) {
                    val desc = runCatching {
                        JSONObject(body).optJSONObject("chart")
                            ?.optJSONObject("error")?.optString("description")
                    }.getOrNull()
                    last = IOException("HTTP " + code + (desc?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                    // 4xx（429 除外）换镜像也没用
                    if (code in 400..499 && code != 429) break
                    continue
                }
                return JSONObject(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("request failed")
    }

    private fun normalizeSymbol(raw: String): String {
        var s = raw.trim().uppercase()
        if (s.isEmpty()) throw IllegalArgumentException("empty symbol")
        // BRK.B / BRK/B → BRK-B（Yahoo 的类别股写法）；SAP.DE 这类交易所后缀保留
        if (s.length <= 6 && s.contains('.') && s.substringAfterLast('.').length == 1) {
            s = s.replace('.', '-')
        }
        s = s.replace('/', '-')
        return s
    }

    private fun yahooInterval(interval: String): String = when (interval.lowercase()) {
        "1m" -> "1m"
        "5m" -> "5m"
        "15m" -> "15m"
        "1h" -> "1h"
        "4h" -> "1h"          // 先取 1h，再本地聚合
        "1d" -> "1d"
        else -> throw IllegalArgumentException("unsupported interval: $interval")
    }

    private fun rangeFor(yInterval: String, needBars: Int): String = when (yInterval) {
        // 每个交易日大致：1m=390 根、5m=78、15m=26、1h=6.5、1d=1，留 ~1.6 倍余量
        "1m" -> when {
            needBars <= 390 -> "1d"
            needBars <= 1900 -> "5d"
            else -> "7d"                     // Yahoo 1m 上限 8 天
        }
        "5m" -> when {
            needBars <= 78 -> "1d"
            needBars <= 390 -> "5d"
            needBars <= 1500 -> "1mo"
            else -> "60d"                    // 上限 60 天
        }
        "15m" -> when {
            needBars <= 26 -> "1d"
            needBars <= 130 -> "5d"
            needBars <= 500 -> "1mo"
            else -> "60d"
        }
        "1h" -> when {
            needBars <= 7 -> "1d"
            needBars <= 32 -> "5d"
            needBars <= 130 -> "1mo"
            needBars <= 390 -> "3mo"
            needBars <= 780 -> "6mo"
            else -> "2y"                     // 上限 730 天
        }
        "1d" -> when {
            needBars <= 22 -> "1mo"
            needBars <= 65 -> "3mo"
            needBars <= 130 -> "6mo"
            needBars <= 260 -> "1y"
            needBars <= 520 -> "2y"
            needBars <= 1250 -> "5y"
            needBars <= 2500 -> "10y"
            else -> "max"
        }
        else -> "1mo"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }

    private fun JSONArray?.num(i: Int): Double? {
        if (this == null || i < 0 || i >= length() || isNull(i)) return null
        val d = optDouble(i, Double.NaN)
        return if (d.isNaN()) null else d
    }
}

// ---------------------------------------------------------------- 探针主程序

private fun fmt(d: Double?): String = d?.let { String.format("%.2f", it) } ?: "-"

private fun ts(t: Long): String =
    java.time.Instant.ofEpochMilli(t)
        .atZone(java.time.ZoneId.of("America/New_York"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm z"))

fun main() = runBlocking {
    val api = StockApi()

    println("=== 1) 符号搜索 ===")
    for (q in listOf("nvd", "appl", "tes", "nvda")) {
        val hits = runCatching { api.searchStocks(q, 5) }
        println("query '$q' -> " + if (hits.isSuccess) {
            hits.getOrNull()!!.joinToString(" | ") { "${it.symbol}(${it.type},${it.exchange}) ${it.name}" }
        } else "FAIL ${hits.exceptionOrNull()?.message}")
    }

    println()
    println("=== 2) NVDA K 线（各周期取 120 根，和 app 默认一致）===")
    for (iv in listOf("1m", "5m", "15m", "1H", "4H", "1D")) {
        val t0 = System.nanoTime()
        val r = runCatching { api.fetchStockCandles("NVDA", iv, 120) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        r.fold(
            onSuccess = { list ->
                val ok = list.isNotEmpty() &&
                    list.zipWithNext().all { (a, b) -> b.t > a.t } &&
                    list.all { it.h >= maxOf(it.o, it.c) && it.l <= minOf(it.o, it.c) } &&
                    list.all { it.t % 60_000L == 0L } &&
                    list.all { it.t > 1_500_000_000_000L }
                println("$iv -> ${list.size} 根, ${ms}ms, 校验=${if (ok) "OK" else "FAIL"}")
                list.takeLast(2).forEach {
                    println("     ${ts(it.t)}  O=${fmt(it.o)} H=${fmt(it.h)} L=${fmt(it.l)} C=${fmt(it.c)} V=${it.v}")
                }
            },
            onFailure = { println("$iv -> FAIL ${it.message} (${ms}ms)") }
        )
    }
    runCatching { api.fetchStockCandles("ZZZZZZ", "1D", 10) }
        .onSuccess { println("不存在的代码 -> 意外返回 ${it.size} 根") }
        .onFailure { println("不存在的代码 -> ${it.message}") }

    println()
    println("=== 3) 报价 ===")
    for (sym in listOf("NVDA", "AAPL", "TSLA")) {
        val r = runCatching { api.fetchStockQuote(sym) }
        r.fold(
            onSuccess = { q ->
                println(
                    "${q.symbol} ${q.name} [${q.exchange}] ${q.currency} " +
                        "state=${q.marketState} price=${fmt(q.price)} " +
                        "prevClose=${fmt(q.previousClose)} chg=${fmt(q.changePct)}% " +
                        "day=${fmt(q.dayLow)}~${fmt(q.dayHigh)} vol=${q.volume?.toLong()} " +
                        "extPrice=${fmt(q.extendedPrice)} time=${q.marketTimeSec?.let { ts(it * 1000) }}"
                )
                println("     toQuote() = ${q.toQuote()}")
            },
            onFailure = { println("$sym -> FAIL ${it.message}") }
        )
    }

    println()
    println("PROBE DONE")
}
