package com.milolab.btctv.data.hub

import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.MarketFilter
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.OkxTickerRow
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.StockUniverse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 榜单里的一行；instrument 可直接交给 UI 换主图/搜索 */
data class Mover(
    val instrument: Instrument,
    val price: Double,
    val changePct: Double,
    val quoteVolume24h: Double
) {
    val symbol: String get() = instrument.baseCurrency
    val marketType: MarketType get() = instrument.marketType
    val instId: String get() = instrument.symbolFor(PROVIDER_OKX).orEmpty()
}

/** 一次批量快照；涨/跌榜只是同一份 rows 的不同排序与过滤 */
data class MoversSnapshot(
    val rows: List<Mover>,
    val updatedAtMs: Long,
    val stale: Boolean
) {
    fun gainers(limit: Int = 5, filter: MarketFilter = MarketFilter.ALL): List<Mover> =
        ranked(limit, filter, ascending = false)

    fun losers(limit: Int = 5, filter: MarketFilter = MarketFilter.ALL): List<Mover> =
        ranked(limit, filter, ascending = true)

    private fun ranked(limit: Int, filter: MarketFilter, ascending: Boolean): List<Mover> {
        val comparator = if (ascending) {
            compareBy<Mover> { it.changePct }.thenByDescending { it.quoteVolume24h }
        } else {
            compareByDescending<Mover> { it.changePct }.thenByDescending { it.quoteVolume24h }
        }
        // 涨榜只放上涨的、跌榜只放下跌的；全市场同向时榜单为空比伪装成"跌幅榜"更诚实
        val direction: (Mover) -> Boolean = if (ascending) {
            { it.changePct < 0.0 }
        } else {
            { it.changePct > 0.0 }
        }
        return rows.asSequence()
            .filter { it.matches(filter) && direction(it) }
            .sortedWith(comparator)
            .take(limit.coerceAtLeast(1))
            .toList()
    }
}

private fun Mover.matches(filter: MarketFilter): Boolean = when (filter) {
    MarketFilter.ALL -> true
    MarketFilter.CRYPTO -> marketType == MarketType.CRYPTO
    MarketFilter.STOCK -> marketType == MarketType.STOCK
}

/** 低成交额噪声阈值；股票永续的成交额天然低于主流币，单独一档 */
data class MoversConfig(
    val minQuoteVolumeUsd: Double = 1_000_000.0,
    val minStockQuoteVolumeUsd: Double = 50_000.0
)

fun interface MoversSource {
    suspend fun fetch(): List<Mover>
}

/** 涨跌榜：30s TTL、单飞、失败保留上次快照并标 stale */
class MoversRepository(
    private val source: MoversSource,
    private val ttlMs: Long = 30_000L,
    private val now: () -> Long = System::currentTimeMillis
) {

    constructor(
        client: MarketClient,
        config: MoversConfig = MoversConfig(),
        ttlMs: Long = 30_000L,
        now: () -> Long = System::currentTimeMillis
    ) : this(OkxMoversSource(client, config, now = now), ttlMs, now)

    private val _snapshot = MutableStateFlow<MoversSnapshot?>(null)
    val snapshot: StateFlow<MoversSnapshot?> = _snapshot.asStateFlow()

    private val lock = Any()
    private var inflight: CompletableDeferred<MoversSnapshot?>? = null

    suspend fun refresh(force: Boolean = false): MoversSnapshot? {
        val current = _snapshot.value
        if (!force && current != null && !current.stale && now() - current.updatedAtMs < ttlMs) {
            return current
        }

        val (owner, deferred) = synchronized(lock) {
            val existing = inflight
            if (existing != null) {
                false to existing
            } else {
                val created = CompletableDeferred<MoversSnapshot?>()
                inflight = created
                true to created
            }
        }
        if (!owner) return deferred.await()

        var outcome: MoversSnapshot? = null
        try {
            outcome = MoversSnapshot(source.fetch(), now(), stale = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = _snapshot.value?.copy(stale = true)
        } finally {
            synchronized(lock) { inflight = null }
            if (outcome != null) _snapshot.value = outcome
            deferred.complete(outcome)
        }
        return outcome
    }

    fun current(): MoversSnapshot? = _snapshot.value

    fun gainers(limit: Int = 5, filter: MarketFilter = MarketFilter.ALL): List<Mover> =
        _snapshot.value?.gainers(limit, filter) ?: emptyList()

    fun losers(limit: Int = 5, filter: MarketFilter = MarketFilter.ALL): List<Mover> =
        _snapshot.value?.losers(limit, filter) ?: emptyList()
}

/**
 * OKX 批量源：SPOT 全市场 + SWAP 中 instCategory=3（美股永续）。
 * SWAP 的 instCategory 不在 /market/tickers 里，需要 /public/instruments 元数据；
 * 元数据 6 小时缓存，tickers 每次刷新都重新拉。
 */
class OkxMoversSource(
    private val api: OkxPublicApi,
    private val config: MoversConfig = MoversConfig(),
    private val stockMetaTtlMs: Long = 6 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis
) : MoversSource {

    constructor(
        client: MarketClient,
        config: MoversConfig = MoversConfig(),
        stockMetaTtlMs: Long = 6 * 60 * 60 * 1000L,
        now: () -> Long = System::currentTimeMillis
    ) : this(OkxPublicApi(client.http), config, stockMetaTtlMs, now)

    private val metaLock = Any()
    private var stockTickers: Map<String, String> = emptyMap()
    private var stockTickersAtMs = 0L
    private var stockTickersLoaded = false

    override suspend fun fetch(): List<Mover> {
        val spot = api.tickers("SPOT")
        val stockSwapTickers = stockSwapTickers()
        val swaps = if (stockSwapTickers.isEmpty()) {
            emptyList()
        } else {
            try {
                api.tickers("SWAP")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        return buildMovers(spot, swaps, stockSwapTickers, config)
    }

    private suspend fun stockSwapTickers(): Map<String, String> {
        synchronized(metaLock) {
            if (stockTickersLoaded && now() - stockTickersAtMs < stockMetaTtlMs) {
                return stockTickers
            }
        }
        val parsed = try {
            parseStockSwapMeta(api.instrumentsJson("SWAP"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return synchronized(metaLock) { stockTickers }
        }
        synchronized(metaLock) {
            if (parsed.isNotEmpty() || !stockTickersLoaded) {
                stockTickers = parsed
                stockTickersAtMs = now()
                stockTickersLoaded = true
            }
            return stockTickers
        }
    }
}

/**
 * 从 /public/instruments?instType=SWAP 里挑出真正已上市的美股永续：
 * instCategory=3、state=live、USDT 结算、非 pre_market，且代码能通过 StockUniverse 过滤。
 * 返回 instId -> 正股代码。
 */
fun parseStockSwapMeta(raw: String): Map<String, String> {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val arr = root.optJSONArray("data") ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("instCategory") != "3") continue
        if (o.optString("state") != "live") continue
        if (o.optString("settleCcy") != "USDT") continue
        if (o.optString("ruleType") == "pre_market") continue
        val instId = o.optString("instId").trim()
        val ticker = o.optString("ctValCcy").trim().uppercase()
        if (instId.isEmpty() || !StockUniverse.isEligible(ticker)) continue
        out[instId] = ticker
    }
    return out
}

/** 纯映射：批量 tickers + 股票元数据 → 榜单行（含成交额过滤）；便于单测 */
fun buildMovers(
    spot: List<OkxTickerRow>,
    swaps: List<OkxTickerRow>,
    stockSwapTickers: Map<String, String>,
    config: MoversConfig = MoversConfig()
): List<Mover> {
    val cryptos = ArrayList<Mover>(spot.size)
    for (row in spot) {
        if (!row.instId.endsWith("-USDT")) continue
        val base = row.instId.substringBefore('-')
        if (base.isEmpty() || row.last <= 0.0) continue
        val change = row.changePct ?: continue
        val volume = row.quoteVol24h ?: 0.0
        if (volume < config.minQuoteVolumeUsd) continue
        val instrument = Instruments.crypto(
            base = base,
            priceDecimals = priceDecimalsOf(row.lastRaw),
            symbols = mapOf(PROVIDER_OKX to row.instId)
        )
        cryptos.add(Mover(instrument, row.last, change, volume))
    }

    val cryptoNames = spot.asSequence()
        .filter { it.instId.endsWith("-USDT") }
        .map { it.instId.substringBefore('-').uppercase() }
        .toHashSet()
    val stocks = ArrayList<Mover>(stockSwapTickers.size)
    for (row in swaps) {
        val ticker = stockSwapTickers[row.instId] ?: continue
        if (row.last <= 0.0) continue
        val change = row.changePct ?: continue
        val volume = row.quoteVol24h ?: 0.0
        if (volume < config.minStockQuoteVolumeUsd) continue
        val instrument = Instruments.stock(
            ticker = ticker,
            priceDecimals = priceDecimalsOf(row.lastRaw),
            symbols = mapOf(PROVIDER_OKX to row.instId),
            kind = StockKind.PERP,
            cryptoBases = cryptoNames
        )
        stocks.add(Mover(instrument, row.last, change, volume))
    }
    return cryptos + stocks
}
