package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle

/**
 * 指标结果 LRU 缓存。UI 每 120ms 刷新一次时，只要 candles 版本没变就直接复用结果；
 * 版本号由调用方提供（例如 UiState 里的 tickCount / 数据修订号），也可以不传，
 * 由 [fingerprint] 用首尾 K 线和最后一根 OHLCV 生成轻量指纹。
 *
 * 结果对象里的 DoubleArray 是共享的，调用方只读，不要原地修改。
 */
class IndicatorCache(private val capacity: Int = 64) {

    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private data class Key(
        val specId: String,
        val params: Map<String, Double>,
        val version: Long
    )

    private val lock = Any()

    private val entries = object : LinkedHashMap<Key, IndicatorResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, IndicatorResult>): Boolean =
            size > capacity
    }

    /** candles 版本由调用方维护；版本变化即视为数据变化。 */
    fun get(
        spec: IndicatorSpec,
        candles: List<Candle>,
        params: IndicatorParams = spec.defaultParams,
        version: Long
    ): IndicatorResult {
        val normalized = spec.withParams(params)
        val key = Key(spec.id, normalized.values, version)
        synchronized(lock) {
            entries[key]?.let { return it }
        }
        val result = spec.evaluate(candles, normalized)
        synchronized(lock) {
            entries[key] = result
        }
        return result
    }

    /** 没有显式版本时使用 O(1) 的轻量指纹（含首尾时间与最后一根 OHLCV）。 */
    fun get(
        spec: IndicatorSpec,
        candles: List<Candle>,
        params: IndicatorParams = spec.defaultParams
    ): IndicatorResult = get(spec, candles, params, fingerprint(candles))

    fun get(
        spec: IndicatorSpec,
        candles: List<Candle>,
        overrides: Map<String, Double>,
        version: Long
    ): IndicatorResult = get(spec, candles, spec.withParams(overrides), version)

    fun invalidate(specId: String? = null) {
        synchronized(lock) {
            if (specId == null) entries.clear() else entries.keys.removeAll { it.specId == specId }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    val size: Int
        get() = synchronized(lock) { entries.size }

    /** 仅用于测试/诊断：当前缓存里的 key 数量（按版本区分）。 */
    fun versionsOf(specId: String): Set<Long> = synchronized(lock) {
        entries.keys.filter { it.specId == specId }.map { it.version }.toSet()
    }

    companion object {
        /**
         * 轻量指纹：大小、首尾时间、最后 3 根的 OHLCV。
         * 足以覆盖“追加新 K 线 / 最后一根实时更新 / 窗口整体平移”；
         * 若数据源会修改中间历史，请改用显式 version。
         */
        fun fingerprint(candles: List<Candle>): Long {
            var h = 1125899906842597L
            fun mix(v: Long) {
                h = 31L * h + v
            }

            mix(candles.size.toLong())
            if (candles.isNotEmpty()) {
                mix(candles.first().t)
                val start = (candles.size - 3).coerceAtLeast(0)
                for (i in start until candles.size) {
                    val c = candles[i]
                    mix(c.t)
                    mix(c.o.toRawBits())
                    mix(c.h.toRawBits())
                    mix(c.l.toRawBits())
                    mix(c.c.toRawBits())
                    mix(c.v.toRawBits())
                }
            }
            return h
        }
    }
}
