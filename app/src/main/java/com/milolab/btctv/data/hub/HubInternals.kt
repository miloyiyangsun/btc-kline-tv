package com.milolab.btctv.data.hub

import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.PROVIDER_BINANCE
import com.milolab.btctv.data.PROVIDER_BYBIT
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.Quote
import kotlin.math.min

/** 订阅集差量：只 subscribe 新增、unsubscribe 移除，绝不整条重连 */
data class SubscriptionDelta(
    val subscribe: List<String>,
    val unsubscribe: List<String>
) {
    val isEmpty: Boolean get() = subscribe.isEmpty() && unsubscribe.isEmpty()
}

fun subscriptionDelta(current: Set<String>, desired: Set<String>): SubscriptionDelta =
    SubscriptionDelta(
        subscribe = desired.filterNot(current::contains),
        unsubscribe = current.filterNot(desired::contains)
    )

/**
 * 按标的合并推送：WS 以约 100ms 推送，界面只关心最新价。
 * drain() 只返回价格相比上次发射真的变了的标的。
 */
class QuoteDebouncer {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Quote>()
    private val emittedPrice = HashMap<String, Double>()

    fun submit(instrumentId: String, quote: Quote) {
        synchronized(lock) { pending[instrumentId] = quote }
    }

    fun drain(): Map<String, Quote> {
        synchronized(lock) {
            if (pending.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Quote>(pending.size)
            for ((id, quote) in pending) {
                if (emittedPrice[id] != quote.price) {
                    emittedPrice[id] = quote.price
                    out[id] = quote
                }
            }
            pending.clear()
            return out
        }
    }

    fun reset() {
        synchronized(lock) {
            pending.clear()
            emittedPrice.clear()
        }
    }

    /** 标的被移出订阅集：忘掉它的价格，重新加回时同价也要再发一次 */
    fun forget(instrumentId: String) {
        synchronized(lock) {
            pending.remove(instrumentId)
            emittedPrice.remove(instrumentId)
        }
    }
}

/**
 * WS 链路健康：15s 没有任何消息就开始 REST 批量兜底，30s 仍无消息则强制重连。
 * 任何文本消息（含 pong / 订阅确认）都算链路存活。
 */
class LinkHealth(
    private val staleMs: Long = 15_000L,
    private val reconnectMs: Long = 30_000L
) {
    var open: Boolean = false
        private set
    private var failed = false
    private var lastMessageMs = 0L

    fun onOpen(nowMs: Long) {
        open = true
        failed = false
        lastMessageMs = nowMs
    }

    fun onMessage(nowMs: Long) {
        lastMessageMs = nowMs
        failed = false
    }

    fun onFailure() {
        open = false
        failed = true
    }

    fun isFresh(nowMs: Long): Boolean =
        open && !failed && nowMs - lastMessageMs < staleMs

    fun shouldPollRest(nowMs: Long): Boolean = !isFresh(nowMs)

    fun shouldReconnect(nowMs: Long): Boolean =
        !open || failed || nowMs - lastMessageMs >= reconnectMs
}

/** 1s→2s→4s→8s→16s→30s，带 0–25% 抖动 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val jitterRatio: Double = 0.25,
    private val random: () -> Double = { Math.random() }
) {
    private var attempt = 0

    fun reset() {
        attempt = 0
    }

    fun nextDelayMs(): Long {
        val exponent = min(attempt, 5)
        val raw = min(baseMs.toDouble() * (1L shl exponent), maxMs.toDouble())
        attempt++
        val jitter = raw * jitterRatio * random().coerceIn(0.0, 1.0)
        return (raw + jitter).toLong().coerceAtMost(maxMs)
    }
}

/** 该标的该走哪个行情源：OKX 优先（股票只有 OKX），其余回退 Binance/Bybit */
fun preferredProvider(inst: Instrument): String? = when {
    inst.symbolFor(PROVIDER_OKX) != null -> PROVIDER_OKX
    inst.symbolFor(PROVIDER_BINANCE) != null -> PROVIDER_BINANCE
    inst.symbolFor(PROVIDER_BYBIT) != null -> PROVIDER_BYBIT
    else -> null
}

/** 从原始价格字符串推断显示小数位（OKX tickers 不带 tickSz） */
fun priceDecimalsOf(raw: String?): Int {
    if (raw.isNullOrBlank()) return 2
    val dot = raw.indexOf('.')
    if (dot < 0) return 0
    return (raw.length - dot - 1).coerceIn(0, 8)
}
