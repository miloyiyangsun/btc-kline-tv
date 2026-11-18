package com.milolab.btctv.data.overview

import org.json.JSONArray
import org.json.JSONObject

/** 单个源的失败退避状态：连续失败次数 + 允许再次尝试的时间点 */
internal data class Backoff(val failures: Int = 0, val untilMs: Long = 0L)

/**
 * 落盘缓存。全部为纯 Kotlin/JSON，不依赖 Android，可直接在 preview 单元测试里读写。
 */
internal data class OverviewCache(
    val global: GlobalStats? = null,
    val stable: StablecoinStats? = null,
    val btcHistory: List<HistoryPoint> = emptyList(),
    val totalHistory: List<HistoryPoint> = emptyList(),
    val stableHistory: List<HistoryPoint> = emptyList(),
    val historyFetchedAtMs: Long = 0L,
    val backoff: Map<String, Backoff> = emptyMap()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        global?.let { put("global", it.toJson()) }
        stable?.let { put("stable", it.toJson()) }
        put("historyFetchedAtMs", historyFetchedAtMs)
        put("btcHistory", historyToJson(btcHistory))
        put("totalHistory", historyToJson(totalHistory))
        put("stableHistory", historyToJson(stableHistory))
        put("backoff", JSONObject().apply {
            backoff.forEach { (name, b) ->
                put(name, JSONObject().put("failures", b.failures).put("untilMs", b.untilMs))
            }
        })
    }

    companion object {
        const val VERSION = 1

        fun fromJson(raw: String): OverviewCache {
            val o = JSONObject(raw)
            return OverviewCache(
                global = o.optJSONObject("global")?.let { globalFromJson(it) },
                stable = o.optJSONObject("stable")?.let { stableFromJson(it) },
                btcHistory = historyFromJson(o.optJSONArray("btcHistory")),
                totalHistory = historyFromJson(o.optJSONArray("totalHistory")),
                stableHistory = historyFromJson(o.optJSONArray("stableHistory")),
                historyFetchedAtMs = o.optLongOrNull("historyFetchedAtMs") ?: 0L,
                backoff = o.optJSONObject("backoff")?.let { bo ->
                    val map = HashMap<String, Backoff>()
                    val keys = bo.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val b = bo.optJSONObject(k) ?: continue
                        map[k] = Backoff(b.optInt("failures", 0), b.optLongOrNull("untilMs") ?: 0L)
                    }
                    map
                } ?: emptyMap()
            )
        }
    }
}

internal fun GlobalStats.toJson(): JSONObject = JSONObject().apply {
    put("totalMarketCapUsd", totalMarketCapUsd ?: JSONObject.NULL)
    put("totalVolume24hUsd", totalVolume24hUsd ?: JSONObject.NULL)
    put("change24hPct", change24hPct ?: JSONObject.NULL)
    put("btcDominance", btcDominance ?: JSONObject.NULL)
    put("ethDominance", ethDominance ?: JSONObject.NULL)
    put("updatedAtMs", updatedAtMs)
    put("fetchedAtMs", fetchedAtMs)
    put("source", source)
}

internal fun globalFromJson(o: JSONObject): GlobalStats = GlobalStats(
    totalMarketCapUsd = o.optNum("totalMarketCapUsd"),
    totalVolume24hUsd = o.optNum("totalVolume24hUsd"),
    change24hPct = o.optNum("change24hPct"),
    btcDominance = o.optNum("btcDominance"),
    ethDominance = o.optNum("ethDominance"),
    updatedAtMs = o.optLongOrNull("updatedAtMs") ?: 0L,
    fetchedAtMs = o.optLongOrNull("fetchedAtMs") ?: 0L,
    source = o.optString("source", "")
)

internal fun StablecoinStats.toJson(): JSONObject = JSONObject().apply {
    put("marketCapUsd", marketCapUsd ?: JSONObject.NULL)
    put("updatedAtMs", updatedAtMs)
    put("fetchedAtMs", fetchedAtMs)
    put("source", source)
}

internal fun stableFromJson(o: JSONObject): StablecoinStats = StablecoinStats(
    marketCapUsd = o.optNum("marketCapUsd"),
    updatedAtMs = o.optLongOrNull("updatedAtMs") ?: 0L,
    fetchedAtMs = o.optLongOrNull("fetchedAtMs") ?: 0L,
    source = o.optString("source", "")
)

private fun historyToJson(points: List<HistoryPoint>): JSONArray = JSONArray().apply {
    points.forEach { put(JSONArray().put(it.t).put(it.v)) }
}

private fun historyFromJson(arr: JSONArray?): List<HistoryPoint> {
    if (arr == null || arr.length() == 0) return emptyList()
    val out = ArrayList<HistoryPoint>(arr.length())
    for (i in 0 until arr.length()) {
        val p = arr.optJSONArray(i) ?: continue
        val t = p.optLong(0, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: continue
        val v = p.optDouble(1, Double.NaN)
        if (!v.isNaN() && !v.isInfinite()) out.add(HistoryPoint(t, v))
    }
    return out
}

/**
 * 追加本地采样点：
 * - 同一秒的点直接覆盖（源端更新时间返回同值）
 * - 距最后一个点不足 minIntervalSec 时丢弃
 * - 时间倒退的点丢弃（防止源切换导致序列乱序）
 * - 超出 maxPoints 时保留最新 maxPoints 个
 */
internal fun appendSample(
    points: List<HistoryPoint>,
    tSec: Long,
    value: Double?,
    minIntervalSec: Long,
    maxPoints: Int
): List<HistoryPoint> {
    if (value == null || value <= 0.0) return points
    val last = points.lastOrNull()
    if (last != null) {
        if (last.t == tSec) return points.dropLast(1) + HistoryPoint(tSec, value)
        if (tSec < last.t) return points
        if (tSec - last.t < minIntervalSec) return points
    }
    val next = points + HistoryPoint(tSec, value)
    return if (next.size > maxPoints) next.subList(next.size - maxPoints, next.size).toList() else next
}
