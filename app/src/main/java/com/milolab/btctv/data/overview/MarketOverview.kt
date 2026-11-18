package com.milolab.btctv.data.overview

import org.json.JSONObject

/** 走势序列上的一个采样点，t 为 epoch 秒 */
data class HistoryPoint(val t: Long, val v: Double)

/**
 * 大盘总览快照。字段为 null 表示该部分数据当前不可用（源失败且无缓存）。
 *
 * @param btcDominance BTC 市占率，百分数（58.44 表示 58.44%）
 * @param stablecoinDominancePct 稳定币市值 / 加密总市值，百分数
 * @param globalUpdatedAtMs 全局数据的源端更新时间；源不提供时为空
 * @param stablecoinUpdatedAtMs 稳定币数据的源端更新时间；源不提供时为抓取时间
 * @param fetchedAtMs 本地最近一次成功抓取的时间
 * @param source 参与合成的源名，如 "CoinGecko + DefiLlama"
 * @param stale 是否有任一部分超出容忍时效或最近一次抓取失败
 */
data class MarketOverview(
    val btcDominance: Double? = null,
    val ethDominance: Double? = null,
    val totalMarketCapUsd: Double? = null,
    val totalChange24hPct: Double? = null,
    val totalVolume24hUsd: Double? = null,
    val stablecoinMarketCapUsd: Double? = null,
    val stablecoinDominancePct: Double? = null,
    val globalUpdatedAtMs: Long? = null,
    val stablecoinUpdatedAtMs: Long? = null,
    val fetchedAtMs: Long = 0L,
    val source: String = "",
    val stale: Boolean = true,
    val btcDominanceHistory: List<HistoryPoint> = emptyList(),
    val totalMarketCapHistory: List<HistoryPoint> = emptyList(),
    val stablecoinMarketCapHistory: List<HistoryPoint> = emptyList()
)

/** 全局行情源返回的原始值（尚未与稳定币部分合成） */
data class GlobalStats(
    val totalMarketCapUsd: Double?,
    val totalVolume24hUsd: Double?,
    val change24hPct: Double?,
    val btcDominance: Double?,
    val ethDominance: Double?,
    val updatedAtMs: Long,
    val fetchedAtMs: Long,
    val source: String
)

/** 稳定币源返回的原始值 */
data class StablecoinStats(
    val marketCapUsd: Double?,
    val updatedAtMs: Long,
    val fetchedAtMs: Long,
    val source: String
)

internal fun stablecoinDominancePct(stableUsd: Double?, totalUsd: Double?): Double? {
    if (stableUsd == null || totalUsd == null) return null
    if (stableUsd <= 0.0 || totalUsd <= 0.0) return null
    return stableUsd / totalUsd * 100.0
}

internal fun JSONObject.optNum(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key, Double.NaN)
    return if (d.isNaN() || d.isInfinite()) null else d
}

internal fun JSONObject.optNumAt(vararg path: String): Double? {
    var node: JSONObject = this
    for (i in 0 until path.size - 1) {
        node = node.optJSONObject(path[i]) ?: return null
    }
    return node.optNum(path.last())
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optString(key).toLongOrNull()
        ?: optDouble(key, Double.NaN).takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()
}

/** 把 JSON 里可能出现的 "1.2e+12" / 数字 / null 统一转成 Double */
internal fun Any?.asDoubleOrNull(): Double? = when (this) {
    null, JSONObject.NULL -> null
    is Number -> toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
    is String -> toDoubleOrNull()
    else -> null
}
