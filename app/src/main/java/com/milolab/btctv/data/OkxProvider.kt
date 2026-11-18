package com.milolab.btctv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class OkxProvider(
    http: OkHttpClient,
    wsHttp: OkHttpClient
) : WebSocketMarketProvider(PROVIDER_OKX, http, wsHttp) {

    override suspend fun fetchCandles(inst: Instrument, tf: TF, limit: Int): List<Candle> {
        val symbol = inst.requireSymbol(id)
        val want = limit.coerceIn(20, 500)
        // /market/candles 单次最多 300 根；超过就按 after（更早）翻页。
        val pageLimit = want.coerceAtMost(300)
        val all = ArrayList<Candle>(want)
        var after: Long? = null
        while (all.size < want) {
            val url = buildString {
                append("https://www.okx.com/api/v5/market/candles?instId=$symbol&bar=${tf.okx}&limit=$pageLimit")
                if (after != null) append("&after=$after")
            }
            val raw = json(url)
            val data = raw.optJSONArray("data") ?: throw IOException("okx no data")
            val page = ArrayList<Candle>(data.length())
            for (i in data.length() - 1 downTo 0) {
                val a = data.optJSONArray(i) ?: continue
                runCatching {
                    Candle(
                        t = a.getString(0).toLong(),
                        o = a.getString(1).toDouble(),
                        h = a.getString(2).toDouble(),
                        l = a.getString(3).toDouble(),
                        c = a.getString(4).toDouble(),
                        v = a.getString(5).toDouble()
                    )
                }.getOrNull()?.let { page.add(it) }
            }
            if (page.isEmpty()) break
            all.addAll(0, page)
            if (data.length() < pageLimit) break
            val oldest = page.first().t
            if (after != null && oldest >= after) break
            after = oldest
        }
        return all.distinctBy { it.t }.sortedBy { it.t }.takeLast(want)
    }

    override suspend fun fetchQuote(inst: Instrument): Quote {
        val symbol = inst.requireSymbol(id)
        val raw = json("https://www.okx.com/api/v5/market/ticker?instId=$symbol")
        val d = raw.optJSONArray("data")?.optJSONObject(0) ?: throw IOException("okx no ticker")
        return Quote(
            price = d.num("last") ?: throw IOException("okx no last"),
            open24h = d.num("open24h"),
            high24h = d.num("high24h"),
            low24h = d.num("low24h"),
            vol24h = d.num("vol24h"),
            quoteVol24h = d.num("volCcy24h")
        )
    }

    override fun spec(inst: Instrument): WsSpec {
        val symbol = inst.requireSymbol(id)
        return WsSpec(
            url = "wss://ws.okx.com:443/ws/v5/public",
            subscribe = """{"op":"subscribe","args":[{"channel":"tickers","instId":"$symbol"}]}""",
            ping = "ping"
        )
    }

    override fun parseWs(raw: String): Quote? = parseOkxTickerWsMessage(raw)?.toQuote()

    override suspend fun search(): List<Instrument> = withContext(Dispatchers.IO) {
        val spotArr = json("https://www.okx.com/api/v5/public/instruments?instType=SPOT")
            .optJSONArray("data") ?: JSONArray()
        val swapArr = json("https://www.okx.com/api/v5/public/instruments?instType=SWAP")
            .optJSONArray("data") ?: JSONArray()

        val cryptos = ArrayList<Instrument>()
        val tokenRows = ArrayList<JSONObject>()
        for (i in 0 until spotArr.length()) {
            val o = spotArr.optJSONObject(i) ?: continue
            if (o.optString("state") != "live") continue
            if (o.optString("quoteCcy") != "USDT") continue
            // instCategory=3 是 OKX 的代币化股票/ETF，不能混进加密币目录
            if (o.optString("instCategory") == "3") {
                tokenRows.add(o)
                continue
            }
            val base = o.optString("baseCcy").trim()
            val symbol = o.optString("instId").trim()
            if (base.isEmpty() || symbol.isEmpty()) continue
            cryptos.add(
                Instruments.crypto(
                    base = base,
                    priceDecimals = decimalsOf(o.optString("tickSz")),
                    symbols = mapOf(PROVIDER_OKX to symbol)
                )
            )
        }

        val cryptoBases = cryptos.mapTo(HashSet()) { it.baseCurrency.uppercase() }
        val out = ArrayList<Instrument>(cryptos.size + 500)
        out.addAll(cryptos)
        out.addAll(stockSwaps(swapArr, cryptoBases))
        out.addAll(tokenizedStocks(tokenRows, cryptoBases))
        out
    }

    /**
     * 股票永续：instType=SWAP 且 instCategory=3，正股代码取交易所返回的 ctValCcy。
     * ruleType=pre_market 是未上市/预发行合约，直接排除。
     */
    private fun stockSwaps(arr: JSONArray, cryptoBases: Set<String>): List<Instrument> {
        val out = ArrayList<Instrument>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("instCategory") != "3") continue
            if (o.optString("state") != "live") continue
            if (o.optString("settleCcy") != "USDT") continue
            if (o.optString("ruleType") == "pre_market") continue
            val symbol = o.optString("instId").trim()
            val ticker = o.optString("ctValCcy").trim()
            if (symbol.isEmpty() || !StockUniverse.isEligible(ticker)) continue
            out.add(
                Instruments.stock(
                    ticker = ticker,
                    priceDecimals = decimalsOf(o.optString("tickSz")),
                    symbols = mapOf(PROVIDER_OKX to symbol),
                    kind = StockKind.PERP,
                    cryptoBases = cryptoBases
                )
            )
        }
        return out
    }

    /**
     * 代币化股票现货：同样是 instCategory=3，baseCcy 是 X+正股代码（交易所元数据），
     * instId 一律用返回值，不做任何拼接。
     */
    private fun tokenizedStocks(rows: List<JSONObject>, cryptoBases: Set<String>): List<Instrument> {
        val out = ArrayList<Instrument>(rows.size)
        for (o in rows) {
            val base = o.optString("baseCcy").trim().uppercase()
            val symbol = o.optString("instId").trim()
            if (symbol.isEmpty() || base.length < 2 || !base.startsWith("X")) continue
            val ticker = base.substring(1)
            if (!StockUniverse.isEligible(ticker)) continue
            out.add(
                Instruments.stock(
                    ticker = ticker,
                    priceDecimals = decimalsOf(o.optString("tickSz")),
                    symbols = mapOf(PROVIDER_OKX to symbol),
                    kind = StockKind.TOKENIZED,
                    cryptoBases = cryptoBases
                )
            )
        }
        return out
    }
}

// ------------------------------------------------------------ 多标的公共行情辅助

/** OKX tickers 频道/REST 的单行数据；24h 字段可能缺失，此时只更新价格 */
data class OkxTickerRow(
    val instId: String,
    val last: Double,
    val open24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val vol24h: Double? = null,
    val quoteVol24h: Double? = null,
    val tsMs: Long? = null,
    /** 原始价格字符串，用于推断显示小数位 */
    val lastRaw: String? = null
) {
    val changePct: Double?
        get() = open24h?.takeIf { it > 0.0 }?.let { (last - it) / it * 100.0 }

    fun toQuote(): Quote = Quote(last, open24h, high24h, low24h, vol24h, quoteVol24h)
}

fun parseOkxTicker(o: JSONObject): OkxTickerRow? {
    val instId = o.optString("instId").trim()
    if (instId.isEmpty()) return null
    val last = o.num("last") ?: return null
    return OkxTickerRow(
        instId = instId,
        last = last,
        open24h = o.num("open24h"),
        high24h = o.num("high24h"),
        low24h = o.num("low24h"),
        vol24h = o.num("vol24h"),
        quoteVol24h = o.num("volCcy24h"),
        tsMs = o.num("ts")?.toLong(),
        lastRaw = o.optString("last").takeIf { it.isNotBlank() }
    )
}

fun parseOkxTickerArray(arr: JSONArray?): List<OkxTickerRow> {
    if (arr == null) return emptyList()
    val out = ArrayList<OkxTickerRow>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { parseOkxTicker(it)?.let(out::add) }
    }
    return out
}

/** 解析 GET /api/v5/market/tickers 的完整响应 */
fun parseOkxTickers(raw: String): List<OkxTickerRow> {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    return parseOkxTickerArray(root.optJSONArray("data"))
}

/** 解析 WS tickers 推送；订阅确认等 event 消息返回 null */
fun parseOkxTickerWsMessage(raw: String): OkxTickerRow? {
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (o.optString("event").isNotEmpty()) return null
    val d = o.optJSONArray("data") ?: return null
    if (d.length() == 0) return null
    return d.optJSONObject(0)?.let { parseOkxTicker(it) }
}

/** 一条 subscribe/unsubscribe 消息可带 N 个 instId；QuoteHub 复用同一连接做增量变更 */
object OkxFeed {
    const val WS_URL = "wss://ws.okx.com:443/ws/v5/public"
    const val REST_BASE = "https://www.okx.com"

    fun subscribe(instIds: Collection<String>): String = control("subscribe", instIds)

    fun unsubscribe(instIds: Collection<String>): String = control("unsubscribe", instIds)

    private fun control(op: String, instIds: Collection<String>): String {
        val args = JSONArray()
        for (id in instIds) {
            args.put(JSONObject().put("channel", "tickers").put("instId", id))
        }
        return JSONObject().put("op", op).put("args", args).toString()
    }
}
