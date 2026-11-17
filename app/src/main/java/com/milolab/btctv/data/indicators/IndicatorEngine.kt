package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.IndicatorSettings

/**
 * 指标计算入口。整批结果走 [IndicatorCache]（按 spec + 参数 + 数据修订号复用）；
 * 实时刷新只替换“最后一根受影响 K 线”的输出，前 n-1 个点复用旧数组，
 * 避免 UI 层每个 tick 重建整份结果。
 */
class IndicatorEngine(private val cache: IndicatorCache = IndicatorCache()) {

    /** 数据/参数变化后的全量计算；相同 revision 直接命中缓存。 */
    fun compute(
        settings: IndicatorSettings,
        candles: List<Candle>,
        revision: Long
    ): Map<String, IndicatorResult> {
        if (candles.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, IndicatorResult>()
        for (spec in settings.enabledSpecs()) {
            out[spec.id] = cache.get(spec, candles, settings.paramsFor(spec), revision)
        }
        return out
    }

    /**
     * 只更新最后一根：按当前参数算出最新结果，再把结果里最后一个点贴到旧数组上。
     * 前 n-1 个点保持原对象/原值。这里不写缓存，避免每个 tick 的临时 revision
     * 把 LRU 塞满；缓存只服务 [compute] 的稳定 revision。
     */
    fun refreshLast(
        settings: IndicatorSettings,
        candles: List<Candle>,
        revision: Long,
        previous: Map<String, IndicatorResult>
    ): Map<String, IndicatorResult> {
        if (candles.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, IndicatorResult>(previous.size)
        for (spec in settings.enabledSpecs()) {
            val old = previous[spec.id]
            if (old == null) {
                out[spec.id] = cache.get(spec, candles, settings.paramsFor(spec), revision)
                continue
            }
            val fresh = spec.evaluate(candles, settings.paramsFor(spec))
            out[spec.id] = patchLast(old, fresh)
        }
        return out
    }

    fun invalidate(specId: String? = null) = cache.invalidate(specId)

    fun clearCache() = cache.clear()

    val cacheSize: Int get() = cache.size

    fun cachedVersions(specId: String): Set<Long> = cache.versionsOf(specId)

    private fun patchLast(previous: IndicatorResult, fresh: IndicatorResult): IndicatorResult {
        val last = fresh.length - 1
        if (last < 0 || previous.series.isEmpty()) return fresh
        val merged = ArrayList<IndicatorSeries>(fresh.series.size)
        for (s in fresh.series) {
            val old = previous.series(s.key)
            if (old == null || old.size != s.size) {
                merged.add(s)
                continue
            }
            val values = old.values.copyOf()
            values[last] = s.values[last]
            val colors = old.colors?.copyOf()
            val newColors = s.colors
            if (colors != null && newColors != null && colors.size == s.size) {
                colors[last] = newColors[last]
            }
            merged.add(
                IndicatorSeries(
                    key = s.key,
                    label = s.label,
                    values = values,
                    color = s.color,
                    style = s.style,
                    width = s.width,
                    fillAlpha = s.fillAlpha,
                    colors = colors ?: newColors,
                    dash = s.dash
                )
            )
        }
        return IndicatorResult(
            series = merged,
            levels = fresh.levels,
            regions = fresh.regions,
            zones = fresh.zones,
            guides = fresh.guides
        )
    }
}
