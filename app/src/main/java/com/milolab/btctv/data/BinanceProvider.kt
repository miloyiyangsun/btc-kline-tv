package com.milolab.btctv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class BinanceProvider(
    http: OkHttpClient,
    wsHttp: OkHttpClient
) : WebSocketMarketProvider(PROVIDER_BINANCE, http, wsHttp) {

    override suspend fun fetchCandles(inst: Instrument, tf: TF, limit: Int): List<Candle> {
        val symbol = inst.requireSymbol(id)
        val n = limit.coerceIn(20, 500)
        return jsonArray(
            "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=${tf.binance}&limit=$n"
        ).toCandles()
    }

    override suspend fun fetchQuote(inst: Instrument): Quote {
        val symbol = inst.requireSymbol(id)
        val d = json("https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol")
        return Quote(
            price = d.num("lastPrice") ?: throw IOException("binance no last"),
            open24h = d.num("openPrice"),
            high24h = d.num("highPrice"),
            low24h = d.num("lowPrice"),
            vol24h = d.num("volume"),
            quoteVol24h = d.num("quoteVolume")
        )
    }

    override fun spec(inst: Instrument): WsSpec {
        val stream = inst.requireSymbol(id).lowercase()
        return WsSpec("wss://stream.binance.com:9443/stream?streams=$stream@aggTrade/$stream@ticker")
    }

    override fun parseWs(raw: String): Quote? {
        if (raw.length < 2) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val body = o.optJSONObject("data") ?: o
        return when (body.optString("e")) {
            "aggTrade", "trade" -> body.num("p")?.let { Quote(it) }
            "24hrTicker" -> body.num("c")?.let {
                Quote(it, body.num("o"), body.num("h"), body.num("l"), body.num("v"), body.num("q"))
            }
            else -> null
        }
    }

    override suspend fun search(): List<Instrument> = withContext(Dispatchers.IO) {
        val root = json("https://api.binance.com/api/v3/exchangeInfo")
        val arr = root.optJSONArray("symbols") ?: return@withContext emptyList()
        val out = ArrayList<Instrument>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status") != "TRADING") continue
            if (o.optString("quoteAsset") != "USDT") continue
            if (o.has("isSpotTradingAllowed") && !o.optBoolean("isSpotTradingAllowed")) continue
            val base = o.optString("baseAsset").trim()
            val symbol = o.optString("symbol").trim()
            if (base.isEmpty() || symbol.isEmpty()) continue
            out.add(
                Instruments.crypto(
                    base = base,
                    priceDecimals = decimalsOf(priceTick(o.optJSONArray("filters"))),
                    symbols = mapOf(PROVIDER_BINANCE to symbol)
                )
            )
        }
        out
    }

    private fun priceTick(filters: JSONArray?): String? {
        if (filters == null) return null
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: continue
            if (f.optString("filterType") == "PRICE_FILTER") return f.optString("tickSize")
        }
        return null
    }
}
