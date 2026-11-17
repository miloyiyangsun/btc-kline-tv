package com.milolab.btctv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------ 序列化

fun instrumentToJson(inst: Instrument): JSONObject = JSONObject().apply {
    put("id", inst.id)
    put("name", inst.displayName)
    put("fullName", inst.name)
    put("type", inst.marketType.name)
    put("base", inst.baseCurrency)
    put("quote", inst.quoteCurrency)
    put("decimals", inst.priceDecimals)
    put("prefix", inst.pricePrefix)
    put("accent", inst.accent)
    put("glyph", inst.glyph)
    put("hot", inst.hot)
    put("stockKind", inst.stockKind?.name.orEmpty())
    put("symbols", JSONObject(inst.symbols as Map<*, *>))
}

fun instrumentFromJson(raw: String?): Instrument? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(raw)
        val id = o.optString("id").trim()
        val name = o.optString("name").trim()
        val base = o.optString("base").trim()
        if (id.isEmpty() || name.isEmpty() || base.isEmpty()) return null

        val symbols = LinkedHashMap<String, String>()
        o.optJSONObject("symbols")?.let { sym ->
            for (key in sym.keys()) {
                val v = sym.optString(key)
                if (v.isNotEmpty()) symbols[key] = v
            }
        }
        Instrument(
            id = id,
            displayName = name,
            marketType = runCatching { MarketType.valueOf(o.optString("type")) }
                .getOrDefault(MarketType.CRYPTO),
            baseCurrency = base,
            quoteCurrency = o.optString("quote", "USDT"),
            priceDecimals = o.optInt("decimals", 2).coerceIn(0, 8),
            symbols = symbols,
            name = o.optString("fullName"),
            pricePrefix = o.optString("prefix", "$"),
            accent = o.optLong("accent", 0xFF7FA8FF),
            glyph = o.optString("glyph", base.take(1)),
            hot = o.optBoolean("hot", false),
            stockKind = runCatching { StockKind.valueOf(o.optString("stockKind")) }.getOrNull()
        )
    }.getOrNull()
}

fun instrumentListToJson(items: List<Instrument>): String {
    val arr = JSONArray()
    for (inst in items) arr.put(instrumentToJson(inst))
    return arr.toString()
}

fun instrumentListFromJson(raw: String?): List<Instrument> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        val out = ArrayList<Instrument>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { o ->
                instrumentFromJson(o.toString())?.let { out.add(it) }
            }
        }
        out.distinctBy { it.id }
    }.getOrDefault(emptyList())
}

/**
 * 标的目录：交易所返回的加密现货 + OKX 股票永续/代币化股票。
 * 几百只标的的 JSON 直接存文件，SharedPreferences 只留时间戳，避免主存储被大字符串拖慢。
 */
class InstrumentCatalog(context: Context, private val client: MarketClient) {

    private val sp = context.getSharedPreferences("btc_tv", Context.MODE_PRIVATE)
    private val cacheFile = java.io.File(context.filesDir, CACHE_NAME)

    suspend fun cached(): List<Instrument> = withContext(Dispatchers.IO) {
        val text = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
        if (!text.isNullOrBlank()) return@withContext instrumentListFromJson(text)

        // 旧版本把目录放在 SharedPreferences，读到后迁移到文件并清掉
        val legacy = sp.getString(KEY_CACHE, null) ?: return@withContext emptyList()
        val items = instrumentListFromJson(legacy)
        if (items.isNotEmpty()) {
            if (runCatching { writeCache(instrumentListToJson(items)) }.isSuccess) {
                sp.edit().remove(KEY_CACHE).apply()
            }
        }
        items
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        sp.getInt(KEY_VERSION, 0) != CACHE_VERSION ||
            now - sp.getLong(KEY_AT, 0L) > TTL_MS

    /** 缓存新鲜就直接用；过期或为空才联网。联网失败时退回旧缓存。 */
    suspend fun refreshIfStale(force: Boolean = false): List<Instrument> {
        val cached = cached()
        if (!force && cached.isNotEmpty() && !isStale()) return cached
        val fresh = try {
            client.searchAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (fresh.isEmpty()) return cached
        val json = instrumentListToJson(fresh)
        val saved = withContext(Dispatchers.IO) {
            runCatching { writeCache(json) }.isSuccess
        }
        if (saved) {
            sp.edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putInt(KEY_VERSION, CACHE_VERSION)
                .remove(KEY_CACHE)
                .apply()
        }
        return if (saved) fresh else cached.ifEmpty { fresh }
    }

    private fun writeCache(json: String) {
        val tmp = java.io.File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(json)
        cacheFile.delete()
        if (!tmp.renameTo(cacheFile)) {
            cacheFile.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val CACHE_NAME = "instrument_catalog.json"
        private const val KEY_CACHE = "instrument_catalog"
        private const val KEY_AT = "instrument_catalog_at"
        private const val KEY_VERSION = "instrument_catalog_version"
        private const val CACHE_VERSION = 2
        private const val TTL_MS = 6L * 60 * 60 * 1000

        /** 本地模糊搜索：精确 > 中文别名 > 前缀 > 包含 > 子序列 */
        fun search(query: String, source: List<Instrument>, limit: Int = 80): List<Instrument> =
            InstrumentSearch.search(query, source, limit)
    }
}
