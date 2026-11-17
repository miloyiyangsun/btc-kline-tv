package com.milolab.btctv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException

internal class BybitProvider(
    http: OkHttpClient,
    wsHttp: OkHttpClient
) : WebSocketMarketProvider(PROVIDER_BYBIT, http, wsHttp) {

    override suspend fun fetchCandles(inst: Instrument, tf: TF, limit: Int): List<Candle> {
        val symbol = inst.requireSymbol(id)
        val n = limit.coerceIn(20, 500)
        val raw = json(
            "https://api.bybit.com/v5/market/kline?category=spot&symbol=$symbol&interval=${tf.bybit}&limit=$n"
        )
        val list = raw.optJSONObject("result")?.optJSONArray("list") ?: throw IOException("bybit no data")
        return list.toCandles().asReversed()
    }

    override suspend fun fetchQuote(inst: Instrument): Quote {
        val symbol = inst.requireSymbol(id)
        val raw = json("https://api.bybit.com/v5/market/tickers?category=spot&symbol=$symbol")
        val d = raw.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
            ?: throw IOException("bybit no ticker")
        return Quote(
            price = d.num("lastPrice") ?: throw IOException("bybit no last"),
            open24h = d.num("prevPrice24h"),
            high24h = d.num("highPrice24h"),
            low24h = d.num("lowPrice24h"),
            vol24h = d.num("volume24h"),
            quoteVol24h = d.num("turnover24h")
        )
    }

    override fun spec(inst: Instrument): WsSpec {
        val symbol = inst.requireSymbol(id)
        return WsSpec(
            url = "wss://stream.bybit.com/v5/public/spot",
            subscribe = """{"op":"subscribe","args":["tickers.$symbol"]}""",
            ping = """{"op":"ping"}"""
        )
    }

    override fun parseWs(raw: String): Quote? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optBoolean("success") || !o.has("data")) return null
        val d = o.optJSONObject("data") ?: return null
        val last = d.num("lastPrice") ?: return null
        return Quote(last, d.num("prevPrice24h"), d.num("highPrice24h"), d.num("lowPrice24h"), d.num("volume24h"), d.num("turnover24h"))
    }

    /** 只收录加密现货；symbolType=xstocks 的代币化股票由 OKX 提供，这里不接 */
    override suspend fun search(): List<Instrument> = withContext(Dispatchers.IO) {
        val root = json("https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000")
        val arr = root.optJSONObject("result")?.optJSONArray("list") ?: return@withContext emptyList()
        val out = ArrayList<Instrument>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status") != "Trading") continue
            if (o.optString("quoteCoin") != "USDT") continue
            if (o.optString("symbolType") == "xstocks") continue
            val base = o.optString("baseCoin").trim()
            val symbol = o.optString("symbol").trim()
            if (base.isEmpty() || symbol.isEmpty()) continue
            out.add(
                Instruments.crypto(
                    base = base,
                    priceDecimals = decimalsOf(o.optJSONObject("priceFilter")?.optString("tickSize")),
                    symbols = mapOf(PROVIDER_BYBIT to symbol)
                )
            )
        }
        out
    }
}
