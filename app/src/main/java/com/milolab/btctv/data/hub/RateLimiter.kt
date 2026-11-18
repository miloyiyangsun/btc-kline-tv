package com.milolab.btctv.data.hub

import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.min

/**
 * 全局限流令牌桶。默认 10 次/s、突发 20：OKX /market/tickers 限额 20 次/2s、
 * /market/candles 40 次/2s，这里统一按更紧的一档再留一半余量。
 */
class RateLimiter(
    private val ratePerSecond: Double = DEFAULT_RATE_PER_SECOND,
    private val burst: Int = DEFAULT_BURST,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init {
        require(ratePerSecond > 0.0) { "ratePerSecond must be > 0" }
        require(burst > 0) { "burst must be > 0" }
    }

    private var tokens = burst.toDouble()
    private var lastRefillMs = nowMs()

    /** 尝试取 [count] 个令牌；成功返回 0，否则返回需要等待的毫秒数（不消耗令牌） */
    @Synchronized
    fun tryAcquire(count: Int = 1): Long {
        require(count in 1..burst) { "count must be in 1..$burst" }
        val now = nowMs()
        refill(now)
        if (tokens + EPSILON >= count) {
            tokens = (tokens - count).coerceAtLeast(0.0)
            return 0L
        }
        val missing = count - tokens
        // 减去极小量，避免 0.99 个令牌被浮点误差算成需要 2ms
        return ceil(missing / ratePerSecond * 1000.0 - EPSILON).toLong().coerceAtLeast(1L)
    }

    suspend fun acquire(count: Int = 1) {
        while (true) {
            val wait = tryAcquire(count)
            if (wait == 0L) return
            delay(wait)
        }
    }

    /** 当前可用令牌数（观察用，不消耗） */
    @Synchronized
    fun available(): Double {
        refill(nowMs())
        return tokens
    }

    private fun refill(now: Long) {
        if (now <= lastRefillMs) return
        val elapsed = now - lastRefillMs
        tokens = min(burst.toDouble(), tokens + elapsed / 1000.0 * ratePerSecond)
        lastRefillMs = now
    }

    companion object {
        const val DEFAULT_RATE_PER_SECOND = 10.0
        const val DEFAULT_BURST = 20
        private const val EPSILON = 1e-9

        /** 进程内共享的 OKX 公共 REST 预算 */
        val okxGlobal: RateLimiter by lazy { RateLimiter() }
    }
}
