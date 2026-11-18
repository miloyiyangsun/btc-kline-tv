package com.milolab.btctv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

internal fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optString(key).toDoubleOrNull()
}

/** tickSize 字符串 → 价格小数位，例如 "0.00001000" → 5 */
internal fun decimalsOf(tickSize: String?): Int {
    if (tickSize.isNullOrBlank()) return 2
    val scale = runCatching {
        BigDecimal(tickSize.trim()).stripTrailingZeros().scale()
    }.getOrDefault(2)
    return scale.coerceIn(0, 8)
}

/** 元素可能是数字（Binance 时间戳）或字符串（Binance OHLC、OKX/Bybit 全部字段），两种都要收 */
private fun JSONArray.numberAt(index: Int): Double? = when (val v = opt(index)) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun JSONArray.longAt(index: Int): Long? = when (val v = opt(index)) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

internal fun JSONArray.toCandles(): List<Candle> {
    val out = ArrayList<Candle>(length())
    for (i in 0 until length()) {
        val a = optJSONArray(i) ?: continue
        val t = a.longAt(0) ?: continue
        val o = a.numberAt(1) ?: continue
        val h = a.numberAt(2) ?: continue
        val l = a.numberAt(3) ?: continue
        val c = a.numberAt(4) ?: continue
        val v = a.numberAt(5) ?: continue
        out.add(Candle(t, o, h, l, c, v))
    }
    return out
}

/** 交易所通用的 REST + WebSocket 骨架，子类只负责 URL/订阅/解析 */
internal abstract class WebSocketMarketProvider(
    final override val id: String,
    private val http: OkHttpClient,
    private val wsHttp: OkHttpClient
) : MarketProvider {

    protected data class WsSpec(val url: String, val subscribe: String? = null, val ping: String? = null)

    protected suspend fun json(url: String): JSONObject = JSONObject(text(url))

    protected suspend fun jsonArray(url: String): JSONArray = JSONArray(text(url))

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

    protected abstract fun spec(inst: Instrument): WsSpec

    protected abstract fun parseWs(raw: String): Quote?

    /** 多久收不到消息就认为 WS 不可用；股票永续源可缩短以便尽快降级到 REST 轮询 */
    protected open val staleTimeoutMs: Long = 30_000L

    /** 实时价格流。断开时抛出异常，交给上层重连（重连期间就是 REST 轮询）。 */
    override fun liveStream(inst: Instrument): Flow<Quote> = callbackFlow {
        val s = spec(inst)
        val lastMessage = AtomicLong(System.currentTimeMillis())

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastMessage.set(System.currentTimeMillis())
                s.subscribe?.let { runCatching { webSocket.send(it) } }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessage.set(System.currentTimeMillis())
                parseWs(text)?.let { trySend(it) }
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
            val checkMs = (staleTimeoutMs / 3).coerceIn(1_000L, 20_000L)
            while (isActive) {
                delay(checkMs)
                if (System.currentTimeMillis() - lastMessage.get() > staleTimeoutMs) {
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
}
