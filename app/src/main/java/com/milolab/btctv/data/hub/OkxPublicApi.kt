package com.milolab.btctv.data.hub

import com.milolab.btctv.data.OkxFeed
import com.milolab.btctv.data.OkxTickerRow
import com.milolab.btctv.data.parseOkxTickerArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * OKX 公共 REST 的批量读取：/market/tickers 与 /public/instruments。
 * 所有请求共享 [RateLimiter.okxGlobal] 预算，不携带任何用户信息。
 */
class OkxPublicApi(
    private val http: OkHttpClient,
    private val limiter: RateLimiter = RateLimiter.okxGlobal
) {

    suspend fun tickers(instType: String): List<OkxTickerRow> {
        val root = getJson("/api/v5/market/tickers?instType=$instType")
        return withContext(Dispatchers.IO) { parseOkxTickerArray(root.optJSONArray("data")) }
    }

    /** 一次拉多个 instType（SPOT + SWAP），按 instId 合并；用于 WS 降级时的批量兜底 */
    suspend fun tickerMap(instTypes: Collection<String>): Map<String, OkxTickerRow> {
        val out = LinkedHashMap<String, OkxTickerRow>()
        for (type in instTypes.distinct()) {
            for (row in tickers(type)) out[row.instId] = row
        }
        return out
    }

    suspend fun instrumentsJson(instType: String): String {
        val text = requestText("/api/v5/public/instruments?instType=$instType")
        val code = runCatching { JSONObject(text).optString("code", "0") }.getOrDefault("0")
        if (code != "0") throw IOException("OKX code $code")
        return text
    }

    private suspend fun getJson(path: String): JSONObject {
        val root = JSONObject(requestText(path))
        val code = root.optString("code", "0")
        if (code != "0") throw IOException("OKX code $code ${root.optString("msg")}")
        return root
    }

    private suspend fun requestText(path: String): String {
        limiter.acquire()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(OkxFeed.REST_BASE + path)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("OKX HTTP ${r.code}")
                r.body?.string() ?: throw IOException("OKX empty body")
            }
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11) BtcTv/1.0"
    }
}
