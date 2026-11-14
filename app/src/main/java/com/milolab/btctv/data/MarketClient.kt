package com.milolab.btctv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

const val PROVIDER_BINANCE = "Binance"
const val PROVIDER_OKX = "OKX"
const val PROVIDER_BYBIT = "Bybit"

val PROVIDERS = listOf(PROVIDER_BINANCE, PROVIDER_OKX, PROVIDER_BYBIT)

data class Snapshot(val provider: String, val candles: List<Candle>, val quote: Quote)

private fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key)
    return s.toDoubleOrNull()
}

/**
 * 行情接入层：REST 拉历史K线 + WebSocket 推实时成交价。
 * 三个交易所互为备份，任何一个可用都能跑。
 */
class MarketClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wsHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) BtcTv/1.0")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP " + r.code)
            r.body?.string() ?: throw IOException("empty body")
        }
    }

    // ---------------------------------------------------------------- REST

    suspend fun fetchCandles(provider: String, coin: Coin, tf: TF, limit: Int = 120): List<Candle> {
        val n = limit.coerceIn(20, 500)
        return when (provider) {
            PROVIDER_OKX -> {
                val raw = text("https://www.okx.com/api/v5/market/candles?instId=${coin.okxPair}&bar=${tf.okx}&limit=$n")
                val data = JSONObject(raw).optJSONArray("data") ?: throw IOException("okx no data")
                val out = ArrayList<Candle>(data.length())
                for (i in data.length() - 1 downTo 0) {
                    val a = data.getJSONArray(i)
                    out.add(
                        Candle(
                            t = a.getString(0).toLong(),
                            o = a.getString(1).toDouble(),
                            h = a.getString(2).toDouble(),
                            l = a.getString(3).toDouble(),
                            c = a.getString(4).toDouble(),
                            v = a.getString(5).toDouble()
                        )
                    )
                }
                out
            }

            PROVIDER_BYBIT -> {
                val raw = text("https://api.bybit.com/v5/market/kline?category=spot&symbol=${coin.pair}&interval=${tf.bybit}&limit=$n")
                val list = JSONObject(raw).optJSONObject("result")?.optJSONArray("list") ?: throw IOException("bybit no data")
                val out = ArrayList<Candle>(list.length())
                for (i in list.length() - 1 downTo 0) {
                    val a = list.getJSONArray(i)
                    out.add(
                        Candle(
                            t = a.getString(0).toLong(),
                            o = a.getString(1).toDouble(),
                            h = a.getString(2).toDouble(),
                            l = a.getString(3).toDouble(),
                            c = a.getString(4).toDouble(),
                            v = a.getString(5).toDouble()
                        )
                    )
                }
                out
            }

            else -> {
                val raw = text("https://api.binance.com/api/v3/klines?symbol=${coin.pair}&interval=${tf.binance}&limit=$n")
                val arr = JSONArray(raw)
                val out = ArrayList<Candle>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    out.add(
                        Candle(
                            t = a.getLong(0),
                            o = a.getString(1).toDouble(),
                            h = a.getString(2).toDouble(),
                            l = a.getString(3).toDouble(),
                            c = a.getString(4).toDouble(),
                            v = a.getString(5).toDouble()
                        )
                    )
                }
                out
            }
        }
    }

    suspend fun fetchQuote(provider: String, coin: Coin): Quote = when (provider) {
        PROVIDER_OKX -> {
            val raw = text("https://www.okx.com/api/v5/market/ticker?instId=${coin.okxPair}")
            val d = JSONObject(raw).optJSONArray("data")?.optJSONObject(0) ?: throw IOException("okx no ticker")
            Quote(
                price = d.num("last") ?: throw IOException("okx no last"),
                open24h = d.num("open24h"),
                high24h = d.num("high24h"),
                low24h = d.num("low24h"),
                vol24h = d.num("vol24h"),
                quoteVol24h = d.num("volCcy24h")
            )
        }

        PROVIDER_BYBIT -> {
            val raw = text("https://api.bybit.com/v5/market/tickers?category=spot&symbol=${coin.pair}")
            val d = JSONObject(raw).optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
                ?: throw IOException("bybit no ticker")
            Quote(
                price = d.num("lastPrice") ?: throw IOException("bybit no last"),
                open24h = d.num("prevPrice24h"),
                high24h = d.num("highPrice24h"),
                low24h = d.num("lowPrice24h"),
                vol24h = d.num("volume24h"),
                quoteVol24h = d.num("turnover24h")
            )
        }

        else -> {
            val raw = text("https://api.binance.com/api/v3/ticker/24hr?symbol=${coin.pair}")
            val d = JSONObject(raw)
            Quote(
                price = d.num("lastPrice") ?: throw IOException("binance no last"),
                open24h = d.num("openPrice"),
                high24h = d.num("highPrice"),
                low24h = d.num("lowPrice"),
                vol24h = d.num("volume"),
                quoteVol24h = d.num("quoteVolume")
            )
        }
    }

    /** 依次尝试交易所，返回第一个能出数据的 */
    suspend fun bootstrap(preferred: String?, coin: Coin, tf: TF, limit: Int = 120): Snapshot {
        val order = (listOfNotNull(preferred) + PROVIDERS).distinct()
        var lastError: Exception? = null
        for (p in order) {
            try {
                val candles = fetchCandles(p, coin, tf, limit)
                if (candles.isEmpty()) continue
                val quote = try {
                    fetchQuote(p, coin)
                } catch (e: Exception) {
                    Quote(candles.last().c)
                }
                return Snapshot(p, candles, quote)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("所有行情源都不可用")
    }

    // ----------------------------------------------------------- WebSocket

    private data class WsSpec(val url: String, val subscribe: String?, val ping: String?)

    private fun spec(provider: String, coin: Coin): WsSpec = when (provider) {
        PROVIDER_OKX -> WsSpec(
            "wss://ws.okx.com:443/ws/v5/public",
            """{"op":"subscribe","args":[{"channel":"tickers","instId":"${coin.okxPair}"}]}""",
            "ping"
        )

        PROVIDER_BYBIT -> WsSpec(
            "wss://stream.bybit.com/v5/public/spot",
            """{"op":"subscribe","args":["tickers.${coin.pair}"]}""",
            """{"op":"ping"}"""
        )

        else -> WsSpec(
            "wss://stream.binance.com:9443/stream?streams=${coin.pair.lowercase()}@aggTrade/${coin.pair.lowercase()}@ticker",
            null,
            null
        )
    }

    /** 实时价格流。断开时抛出异常，交给上层重连。 */
    fun liveStream(provider: String, coin: Coin): Flow<Quote> = callbackFlow {
        val s = spec(provider, coin)
        val lastMessage = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastMessage.set(System.currentTimeMillis())
                s.subscribe?.let { runCatching { webSocket.send(it) } }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessage.set(System.currentTimeMillis())
                parseWs(provider, text)?.let { trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IOException(t.message ?: "ws failure"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("ws closed " + code))
            }
        }

        val ws = wsHttp.newWebSocket(Request.Builder().url(s.url).build(), listener)

        val heartbeat = launch {
            while (isActive) {
                kotlinx.coroutines.delay(20_000)
                if (System.currentTimeMillis() - lastMessage.get() > 30_000) {
                    runCatching { ws.close(1001, "stale") }
                    close(IOException("ws heartbeat timeout"))
                    break
                }
                s.ping?.let { runCatching { ws.send(it) } }
            }
        }

        awaitClose {
            heartbeat.cancel()
            runCatching { ws.cancel() }
        }
    }

    private fun parseWs(provider: String, raw: String): Quote? {
        if (raw.length < 2) return null
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        return when (provider) {
            PROVIDER_OKX -> {
                if (o.optString("event").isNotEmpty()) return null
                val d = o.optJSONArray("data") ?: return null
                if (d.length() == 0) return null
                val t = d.optJSONObject(0) ?: return null
                val last = t.num("last") ?: return null
                Quote(last, t.num("open24h"), t.num("high24h"), t.num("low24h"), t.num("vol24h"), t.num("volCcy24h"))
            }

            PROVIDER_BYBIT -> {
                if (o.optBoolean("success") || !o.has("data")) return null
                val d = o.optJSONObject("data") ?: return null
                val last = d.num("lastPrice") ?: return null
                Quote(last, d.num("prevPrice24h"), d.num("highPrice24h"), d.num("lowPrice24h"), d.num("volume24h"), d.num("turnover24h"))
            }

            else -> {
                val body = o.optJSONObject("data") ?: o
                when (body.optString("e")) {
                    "aggTrade", "trade" -> {
                        val p = body.num("p") ?: return null
                        Quote(p)
                    }

                    "24hrTicker" -> {
                        val c = body.num("c") ?: return null
                        Quote(c, body.num("o"), body.num("h"), body.num("l"), body.num("v"), body.num("q"))
                    }

                    else -> null
                }
            }
        }
    }
}

/** 用最新价修补最后一根K线（时间跨过周期就补一根新K线） */
fun List<Candle>.applyTick(price: Double, tfSeconds: Long, nowMs: Long): List<Candle> {
    if (isEmpty()) return this
    val last = last()
    val periodMs = tfSeconds * 1000L
    val bucket = nowMs / periodMs * periodMs
    return if (bucket > last.t) {
        val closed = if (bucket - last.t <= periodMs) this else this
        closed + Candle(bucket, price, price, price, price, 0.0)
    } else {
        val fixed = last.copy(
            h = max(last.h, price),
            l = kotlin.math.min(last.l, price),
            c = price
        )
        dropLast(1) + fixed
    }
}
