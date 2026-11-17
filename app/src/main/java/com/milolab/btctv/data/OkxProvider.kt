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

    override fun parseWs(raw: String): Quote? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optString("event").isNotEmpty()) return null
        val d = o.optJSONArray("data") ?: return null
        if (d.length() == 0) return null
        val t = d.optJSONObject(0) ?: return null
        val last = t.num("last") ?: return null
        return Quote(last, t.num("open24h"), t.num("high24h"), t.num("low24h"), t.num("vol24h"), t.num("volCcy24h"))
    }

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
