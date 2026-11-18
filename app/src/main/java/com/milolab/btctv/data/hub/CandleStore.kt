package com.milolab.btctv.data.hub

import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.applyTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/**
 * 迷你图/多周期格共用的 K 线缓存：
 * - key = (instrument.id, TF)，同一 key 并发请求只发一次（单飞）；
 * - TTL 按周期（1m 30s、1H 5min、1D 30min…），过期后才重拉；
 * - LRU 上限 [maxEntries]，布局切换不会无限增长；
 * - 失败时保留旧值继续返回（角标由 UI 标“延迟”）；
 * - OKX 标的先过全局令牌桶，Binance/Bybit 标的只做缓存与单飞。
 */
class CandleStore(
    private val fetcher: Fetcher,
    private val limiter: RateLimiter = RateLimiter.okxGlobal,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlFor: (TF) -> Long = ::candleTtlMs,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun interface Fetcher {
        suspend fun fetch(inst: Instrument, tf: TF, limit: Int): List<Candle>
    }

    constructor(client: MarketClient) : this(
        Fetcher { inst, tf, limit ->
            val provider = preferredProvider(inst)
                ?: throw IOException("${inst.displayName} 没有可用行情源")
            client.fetchCandles(provider, inst, tf, limit)
        }
    )

    private class Entry(
        var candles: List<Candle>,
        var fetchedAtMs: Long,
        var lastAccessMs: Long
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}
    private val inflight = HashMap<String, CompletableDeferred<List<Candle>>>()

    suspend fun candles(inst: Instrument, tf: TF, limit: Int = 120): List<Candle> {
        val want = limit.coerceIn(20, 500)
        val key = keyOf(inst, tf)
        val nowMs = now()

        synchronized(lock) {
            val entry = cache[key]
            if (entry != null) {
                entry.lastAccessMs = nowMs
                if (nowMs - entry.fetchedAtMs < ttlFor(tf) && entry.candles.size >= want) {
                    return entry.candles.takeLast(want)
                }
            }
        }

        val (owner, deferred) = synchronized(lock) {
            val existing = inflight[key]
            if (existing != null) {
                false to existing
            } else {
                val created = CompletableDeferred<List<Candle>>()
                inflight[key] = created
                true to created
            }
        }
        if (!owner) return deferred.await().takeLast(want)

        var outcome: List<Candle>? = null
        var failure: Throwable? = null
        var fallback: List<Candle>? = null
        try {
            if (inst.symbolFor(PROVIDER_OKX) != null) limiter.acquire()
            val fetched = fetcher.fetch(inst, tf, want)
            if (fetched.isEmpty()) throw IOException("${inst.displayName} ${tf.label} K线为空")
            val fetchedAt = now()
            synchronized(lock) {
                cache[key] = Entry(fetched, fetchedAt, fetchedAt)
                trimLocked()
            }
            outcome = fetched
        } catch (e: CancellationException) {
            failure = IOException("${inst.displayName} ${tf.label} K线请求已取消")
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            fallback = synchronized(lock) {
                val old = cache[key]?.candles
                inflight.remove(key)
                old
            }
            when {
                failure == null -> deferred.complete(outcome!!)
                fallback != null && fallback!!.isNotEmpty() -> deferred.complete(fallback!!)
                else -> deferred.completeExceptionally(failure)
            }
        }

        val result = outcome
        if (result != null) return result.takeLast(want)
        val kept = fallback
        if (kept != null && kept.isNotEmpty()) return kept.takeLast(want)
        val error = failure
        if (error != null) throw error
        throw IOException("${inst.displayName} ${tf.label} K线请求失败")
    }

    /** 迷你图只取收盘价；点数是取回后再截断，不额外放大请求量 */
    suspend fun spark(inst: Instrument, tf: TF, points: Int = 48): List<Double> {
        val n = points.coerceIn(6, 120)
        return candles(inst, tf, n).takeLast(n).map { it.c }
    }

    fun peek(inst: Instrument, tf: TF): List<Candle>? = synchronized(lock) {
        cache[keyOf(inst, tf)]?.candles
    }

    /** WS 报价到达时修补最后一根；返回修补后的序列（未缓存时返回 null） */
    fun applyTick(
        inst: Instrument,
        price: Double,
        tf: TF,
        nowMs: Long = System.currentTimeMillis()
    ): List<Candle>? = synchronized(lock) {
        val entry = cache[keyOf(inst, tf)] ?: return null
        val patched = entry.candles.applyTick(price, tf, nowMs)
        entry.candles = patched
        entry.lastAccessMs = nowMs
        patched
    }

    fun invalidate(inst: Instrument, tf: TF? = null) {
        synchronized(lock) {
            if (tf == null) {
                cache.keys.removeAll { it.startsWith(inst.id + KEY_SEPARATOR) }
            } else {
                cache.remove(keyOf(inst, tf))
            }
        }
    }

    private fun trimLocked() {
        while (cache.size > maxEntries) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun keyOf(inst: Instrument, tf: TF): String = inst.id + KEY_SEPARATOR + tf.name

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        private const val KEY_SEPARATOR = "@"
    }
}

/** 缓存 TTL：短周期变化快，日线及以上可以放久一点 */
fun candleTtlMs(tf: TF): Long = when (tf) {
    TF.M1 -> 30_000L
    TF.M5 -> 60_000L
    TF.M15 -> 120_000L
    TF.M30 -> 180_000L
    TF.H1 -> 300_000L
    TF.H4 -> 600_000L
    TF.H12 -> 900_000L
    TF.D1 -> 1_800_000L
    TF.W1, TF.MO1, TF.Y1 -> 3_600_000L
}
