package com.milolab.btctv.data.overview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 通用浏览器 UA，不带任何个人信息 */
internal const val OVERVIEW_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

const val SOURCE_COINGECKO = "CoinGecko"
const val SOURCE_COINPAPRIKA = "CoinPaprika"
const val SOURCE_DEFILLAMA = "DefiLlama"

/** 源不可用时抛出；code/retryAfterMs 用于退避（CoinGecko 429 会带 Retry-After: 60） */
class OverviewFetchException(
    message: String,
    val code: Int? = null,
    val retryAfterMs: Long? = null
) : IOException(message)

/** 所有大盘总览数据源的公共接口。全局/稳定币/走势各自独立，失败时可按域单独回退。 */
interface OverviewProvider {
    val name: String
}

interface OverviewGlobalSource : OverviewProvider {
    suspend fun fetch(fetchedAtMs: Long): GlobalStats
}

interface OverviewStablecoinSource : OverviewProvider {
    suspend fun fetch(fetchedAtMs: Long): StablecoinStats
}

interface OverviewHistorySource : OverviewProvider {
    suspend fun fetchStablecoinHistory(fetchedAtMs: Long, days: Int): List<HistoryPoint>
}

internal suspend fun httpGet(http: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url(url)
        .header("User-Agent", OVERVIEW_USER_AGENT)
        .header("Accept", "application/json")
        .build()
    http.newCall(req).execute().use { r ->
        val body = r.body?.string()
        if (!r.isSuccessful) {
            val retry = r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)
            throw OverviewFetchException("HTTP ${r.code} for $url", r.code, retry)
        }
        body ?: throw OverviewFetchException("empty body for $url")
    }
}

// ------------------------------------------------------------------ 解析

/** CoinGecko /api/v3/global */
fun parseCoinGeckoGlobal(raw: String, fetchedAtMs: Long): GlobalStats {
    val data = JSONObject(raw).optJSONObject("data")
        ?: throw OverviewFetchException("CoinGecko: missing data object")
    val caps = data.optJSONObject("market_cap_percentage")
    val total = data.optNumAt("total_market_cap", "usd")
        ?: throw OverviewFetchException("CoinGecko: missing total_market_cap.usd")
    return GlobalStats(
        totalMarketCapUsd = total,
        totalVolume24hUsd = data.optNumAt("total_volume", "usd"),
        change24hPct = data.optNum("market_cap_change_percentage_24h_usd"),
        btcDominance = caps?.optNum("btc"),
        ethDominance = caps?.optNum("eth"),
        updatedAtMs = data.optLongOrNull("updated_at")?.times(1000L) ?: fetchedAtMs,
        fetchedAtMs = fetchedAtMs,
        source = SOURCE_COINGECKO
    )
}

/** CoinPaprika /v1/global（备源：无 ETH 占比，市占率口径与 CoinGecko 略有差异） */
fun parseCoinPaprikaGlobal(raw: String, fetchedAtMs: Long): GlobalStats {
    val root = JSONObject(raw)
    val total = root.optNum("market_cap_usd")
        ?: throw OverviewFetchException("CoinPaprika: missing market_cap_usd")
    return GlobalStats(
        totalMarketCapUsd = total,
        totalVolume24hUsd = root.optNum("volume_24h_usd"),
        change24hPct = root.optNum("market_cap_change_24h"),
        btcDominance = root.optNum("bitcoin_dominance_percentage"),
        ethDominance = null,
        updatedAtMs = root.optLongOrNull("last_updated")?.times(1000L) ?: fetchedAtMs,
        fetchedAtMs = fetchedAtMs,
        source = SOURCE_COINPAPRIKA
    )
}

/** DefiLlama /stablecoins：把每个资产的 circulating（所有 peg 币种，DefiLlama 已折算 USD）加总 */
fun parseDefiLlamaStablecoins(raw: String, fetchedAtMs: Long): StablecoinStats {
    val assets = JSONObject(raw).optJSONArray("peggedAssets")
        ?: throw OverviewFetchException("DefiLlama: missing peggedAssets")
    var total = 0.0
    var counted = 0
    for (i in 0 until assets.length()) {
        val circ = assets.optJSONObject(i)?.optJSONObject("circulating") ?: continue
        val keys = circ.keys()
        while (keys.hasNext()) {
            val v = circ.opt(keys.next()).asDoubleOrNull() ?: continue
            if (v > 0) {
                total += v
                counted++
            }
        }
    }
    if (counted == 0) throw OverviewFetchException("DefiLlama: no circulating values")
    return StablecoinStats(
        marketCapUsd = total,
        updatedAtMs = fetchedAtMs,
        fetchedAtMs = fetchedAtMs,
        source = SOURCE_DEFILLAMA
    )
}

/** DefiLlama /stablecoincharts/all：取最近 days+1 个日点，每天各币种 USD 市值求和 */
fun parseDefiLlamaStablecoinHistory(raw: String, days: Int = 30): List<HistoryPoint> {
    val arr = JSONArray(raw)
    val from = (arr.length() - days - 1).coerceAtLeast(0)
    val out = ArrayList<HistoryPoint>(days + 1)
    for (i in from until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val t = o.optLongOrNull("date") ?: continue
        val totals = o.optJSONObject("totalCirculatingUSD") ?: continue
        var sum = 0.0
        var any = false
        val keys = totals.keys()
        while (keys.hasNext()) {
            val v = totals.opt(keys.next()).asDoubleOrNull() ?: continue
            if (v > 0) {
                sum += v
                any = true
            }
        }
        if (any) out.add(HistoryPoint(t, sum))
    }
    return out
}

/** CoinGecko /coins/categories：取 id == "stablecoins" 的 market_cap */
fun parseCoinGeckoStablecoinCategory(raw: String, fetchedAtMs: Long): StablecoinStats {
    val arr = JSONArray(raw)
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("id") == "stablecoins") {
            val cap = o.optNum("market_cap")
                ?: throw OverviewFetchException("CoinGecko categories: stablecoins has no market_cap")
            return StablecoinStats(
                marketCapUsd = cap,
                updatedAtMs = o.optString("updated_at").let { s ->
                    parseIsoInstantMs(s) ?: fetchedAtMs
                },
                fetchedAtMs = fetchedAtMs,
                source = SOURCE_COINGECKO
            )
        }
    }
    throw OverviewFetchException("CoinGecko categories: stablecoins not found")
}

/** 解析 "2026-11-17T23:44:50.000Z"；失败返回 null（不引入 java.time，兼容 minSdk 23） */
internal fun parseIsoInstantMs(s: String): Long? {
    if (s.length < 20 || s[4] != '-' || s[10] != 'T') return null
    return runCatching {
        val y = s.substring(0, 4).toInt()
        val mo = s.substring(5, 7).toInt()
        val d = s.substring(8, 10).toInt()
        val h = s.substring(11, 13).toInt()
        val mi = s.substring(14, 16).toInt()
        val se = s.substring(17, 19).toInt()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, se)
        cal.timeInMillis
    }.getOrNull()
}

// ------------------------------------------------------------------ 源实现

class CoinGeckoGlobalSource(http: OkHttpClient) : OverviewGlobalSource {
    private val http = http
    override val name = SOURCE_COINGECKO
    override suspend fun fetch(fetchedAtMs: Long): GlobalStats =
        parseCoinGeckoGlobal(httpGet(http, "https://api.coingecko.com/api/v3/global"), fetchedAtMs)
}

class CoinPaprikaGlobalSource(http: OkHttpClient) : OverviewGlobalSource {
    private val http = http
    override val name = SOURCE_COINPAPRIKA
    override suspend fun fetch(fetchedAtMs: Long): GlobalStats =
        parseCoinPaprikaGlobal(httpGet(http, "https://api.coinpaprika.com/v1/global"), fetchedAtMs)
}

class DefiLlamaStablecoinSource(http: OkHttpClient) : OverviewStablecoinSource, OverviewHistorySource {
    private val http = http
    override val name = SOURCE_DEFILLAMA
    override suspend fun fetch(fetchedAtMs: Long): StablecoinStats =
        parseDefiLlamaStablecoins(
            httpGet(http, "https://stablecoins.llama.fi/stablecoins?includePrices=false"),
            fetchedAtMs
        )

    override suspend fun fetchStablecoinHistory(fetchedAtMs: Long, days: Int): List<HistoryPoint> =
        parseDefiLlamaStablecoinHistory(
            httpGet(http, "https://stablecoins.llama.fi/stablecoincharts/all"),
            days
        )
}

class CoinGeckoStablecoinSource(http: OkHttpClient) : OverviewStablecoinSource {
    private val http = http
    override val name = SOURCE_COINGECKO
    override suspend fun fetch(fetchedAtMs: Long): StablecoinStats =
        parseCoinGeckoStablecoinCategory(
            httpGet(http, "https://api.coingecko.com/api/v3/coins/categories"),
            fetchedAtMs
        )
}

fun defaultOverviewHttpClient(): OkHttpClient = OkHttpClient.Builder()
    // 单源上限 15s：超时后立即切备源，避免在不可达域名上长时间阻塞
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
