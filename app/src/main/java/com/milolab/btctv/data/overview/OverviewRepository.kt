package com.milolab.btctv.data.overview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File

/**
 * 市场总览数据层：内存 + 文件缓存、主备源、限流退避、本地采样序列。
 *
 * - 全程不依赖 Android Context：落盘位置由 [cacheFile] 注入（app 侧用 filesDir/overview_cache.json）
 * - 网络与文件 IO 均在 Dispatchers.IO 上执行；对外只暴露 suspend / Flow
 * - CoinGecko 无 key 时极易 429（Retry-After: 60），失败时保留旧数据并标记 stale
 *
 * @param backupFallbackAfterMs 缓存仍新鲜时不切备源，避免 CoinGecko/CoinPaprika 口径来回跳变
 */
class OverviewRepository(
    private val cacheFile: File? = null,
    private val globalSources: List<OverviewGlobalSource> = defaultGlobalSources(),
    private val stablecoinSources: List<OverviewStablecoinSource> = defaultStablecoinSources(),
    private val historySources: List<OverviewHistorySource> = defaultHistorySources(),
    private val now: () -> Long = System::currentTimeMillis,
    private val globalTtlMs: Long = 120_000L,
    private val stablecoinTtlMs: Long = 120_000L,
    private val historyTtlMs: Long = 30 * 60_000L,
    private val sampleIntervalMs: Long = 5 * 60_000L,
    private val maxSamples: Int = 500,
    private val backupFallbackAfterMs: Long = 15 * 60_000L,
    private val writeThrottleMs: Long = 30_000L
) {

    private val mutex = Mutex()
    private val state = MutableStateFlow<MarketOverview?>(null)
    private var cache = OverviewCache()
    private var loaded = false
    private var lastWriteMs = 0L

    /** 最近一次构建的快照（UI 可直接读取，无需挂起） */
    val overview: StateFlow<MarketOverview?> = state.asStateFlow()

    fun current(): MarketOverview? = state.value

    /** 只读缓存（首次会从文件加载，不发起网络请求） */
    suspend fun cached(): MarketOverview? = mutex.withLock {
        ensureLoaded()
        buildOverview(now(), globalFailed = false, stableFailed = false).also { state.value = it }
    }

    /**
     * 按 TTL 抓取；[force] 仅绕过 TTL，仍遵守失败退避。
     * 任何源失败都不会抛给调用方，而是保留旧值并把结果标记 stale。
     */
    suspend fun refresh(force: Boolean = false): MarketOverview? {
        val result = mutex.withLock { doRefresh(force, now()) }
        state.value = result
        return result
    }

    /** 订阅式刷新：立即发一次，然后按 [intervalMs] 轮询（TTL 未到时会命中缓存） */
    fun overviewFlow(intervalMs: Long = 60_000L): Flow<MarketOverview?> = flow {
        emit(refresh())
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            emit(refresh())
        }
    }.distinctUntilChanged()

    // ------------------------------------------------------------ 抓取

    private suspend fun doRefresh(force: Boolean, t: Long): MarketOverview {
        ensureLoaded()
        var changed = false
        var globalFailed = false
        var stableFailed = false

        val g = cache.global
        val globalAge = g?.let { t - it.fetchedAtMs }
        val globalDue = force || globalAge == null || globalAge >= globalTtlMs
        if (globalDue) {
            for (src in pickSources(globalSources, globalAge, cache.global?.source, t) { it.name }) {
                try {
                    val stats = src.fetch(t)
                    val sampleT = (stats.updatedAtMs / 1000L).takeIf { it > 0 } ?: (t / 1000L)
                    cache = cache.copy(
                        global = stats,
                        btcHistory = appendSample(
                            cache.btcHistory, sampleT, stats.btcDominance, sampleIntervalMs / 1000L, maxSamples
                        ),
                        totalHistory = appendSample(
                            cache.totalHistory, sampleT, stats.totalMarketCapUsd, sampleIntervalMs / 1000L, maxSamples
                        ),
                        backoff = cache.backoff - src.name
                    )
                    changed = true
                    globalFailed = false
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cache = cache.copy(backoff = cache.backoff + (src.name to nextBackoff(src.name, t, e)))
                    changed = true
                    globalFailed = true
                }
            }
        }

        val s = cache.stable
        val stableAge = s?.let { t - it.fetchedAtMs }
        val stableDue = force || stableAge == null || stableAge >= stablecoinTtlMs
        if (stableDue) {
            for (src in pickSources(stablecoinSources, stableAge, cache.stable?.source, t) { it.name }) {
                try {
                    val stats = src.fetch(t)
                    cache = cache.copy(stable = stats, backoff = cache.backoff - src.name)
                    changed = true
                    stableFailed = false
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cache = cache.copy(backoff = cache.backoff + (src.name to nextBackoff(src.name, t, e)))
                    changed = true
                    stableFailed = true
                }
            }
        }

        // 走势小图非核心数据：不因失败标记 stale，30 分钟内不重试
        val historyDue = cache.stableHistory.isEmpty() || t - cache.historyFetchedAtMs >= historyTtlMs
        if (historyDue) {
            for (src in historySources) {
                try {
                    val points = src.fetchStablecoinHistory(t, HISTORY_DAYS)
                    if (points.isNotEmpty()) {
                        cache = cache.copy(stableHistory = points, historyFetchedAtMs = t)
                        changed = true
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cache = cache.copy(historyFetchedAtMs = t)
                    changed = true
                    break
                }
            }
        }

        persist(t, changed)
        return buildOverview(t, globalFailed, stableFailed)
    }

    /**
     * 主源失败时是否切备源：
     * - 缓存为空、已过期（超过 [backupFallbackAfterMs]）、或当前缓存本来就来自备源 → 允许
     * - 否则只用主源，宁可短暂沿用主源旧值，也不在两种市占率口径之间来回跳
     */
    private fun <T> pickSources(
        sources: List<T>,
        cacheAgeMs: Long?,
        cacheSource: String?,
        t: Long,
        name: (T) -> String
    ): List<T> {
        val primary = sources.firstOrNull()?.let { name(it) }
        val allowBackup = cacheAgeMs == null ||
            cacheAgeMs >= backupFallbackAfterMs ||
            (cacheSource != null && cacheSource != primary)
        val limit = if (allowBackup) sources.size else 1
        return sources.take(limit).filter { (cache.backoff[name(it)]?.untilMs ?: 0L) <= t }
    }

    private fun nextBackoff(name: String, t: Long, error: Exception): Backoff {
        val prev = cache.backoff[name] ?: Backoff()
        val failures = (prev.failures + 1).coerceAtMost(10)
        val base = 60_000L shl (failures - 1).coerceAtMost(5)
        val retryAfter = (error as? OverviewFetchException)?.retryAfterMs ?: 0L
        val waitMs = maxOf(base, retryAfter).coerceAtMost(MAX_BACKOFF_MS)
        return Backoff(failures, t + waitMs)
    }

    // ------------------------------------------------------------ 合成

    private fun buildOverview(t: Long, globalFailed: Boolean, stableFailed: Boolean): MarketOverview {
        val g = cache.global
        val s = cache.stable
        val globalAge = g?.let { t - it.fetchedAtMs }
        val stableAge = s?.let { t - it.fetchedAtMs }
        val stale = globalFailed || stableFailed || g == null || s == null ||
            (globalAge ?: Long.MAX_VALUE) > globalTtlMs * 2 ||
            (stableAge ?: Long.MAX_VALUE) > stablecoinTtlMs * 2

        val source = listOfNotNull(g?.source, s?.source)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")

        return MarketOverview(
            btcDominance = g?.btcDominance,
            ethDominance = g?.ethDominance,
            totalMarketCapUsd = g?.totalMarketCapUsd,
            totalChange24hPct = g?.change24hPct,
            totalVolume24hUsd = g?.totalVolume24hUsd,
            stablecoinMarketCapUsd = s?.marketCapUsd,
            stablecoinDominancePct = stablecoinDominancePct(s?.marketCapUsd, g?.totalMarketCapUsd),
            globalUpdatedAtMs = g?.updatedAtMs,
            stablecoinUpdatedAtMs = s?.updatedAtMs,
            fetchedAtMs = maxOf(g?.fetchedAtMs ?: 0L, s?.fetchedAtMs ?: 0L),
            source = source,
            stale = stale,
            btcDominanceHistory = cache.btcHistory,
            totalMarketCapHistory = cache.totalHistory,
            stablecoinMarketCapHistory = cache.stableHistory
        )
    }

    // ------------------------------------------------------------ 文件缓存

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = cacheFile ?: return
        val text = withContext(Dispatchers.IO) {
            runCatching { if (f.exists()) f.readText() else null }.getOrNull()
        } ?: return
        cache = runCatching { OverviewCache.fromJson(JSONObject(text).toString()) }
            .getOrDefault(OverviewCache())
    }

    private suspend fun persist(t: Long, changed: Boolean) {
        if (!changed) return
        val f = cacheFile ?: return
        if (t - lastWriteMs < writeThrottleMs && f.exists()) return
        val text = cache.toJson().toString()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val abs = f.absoluteFile
                abs.parentFile?.mkdirs()
                val tmp = File(abs.parentFile, abs.name + ".tmp")
                tmp.writeText(text)
                tmp.copyTo(abs, overwrite = true)
                tmp.delete()
                true
            }.getOrDefault(false)
        }
        if (ok) lastWriteMs = t
    }

    companion object {
        private const val HISTORY_DAYS = 30
        private const val MAX_BACKOFF_MS = 30 * 60_000L

        private val sharedHttp: OkHttpClient by lazy { defaultOverviewHttpClient() }

        private fun defaultGlobalSources(): List<OverviewGlobalSource> =
            listOf(CoinGeckoGlobalSource(sharedHttp), CoinPaprikaGlobalSource(sharedHttp))

        private fun defaultStablecoinSources(): List<OverviewStablecoinSource> =
            listOf(DefiLlamaStablecoinSource(sharedHttp), CoinGeckoStablecoinSource(sharedHttp))

        private fun defaultHistorySources(): List<OverviewHistorySource> =
            listOf(DefiLlamaStablecoinSource(sharedHttp))

        /** app 侧便捷入口：缓存写到 filesDir/overview_cache.json */
        fun withDefaults(cacheFile: File?): OverviewRepository = OverviewRepository(cacheFile = cacheFile)
    }
}
