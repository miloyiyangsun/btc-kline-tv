package com.milolab.btctv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class Snapshot(val provider: String, val candles: List<Candle>, val quote: Quote)

/**
 * 行情接入层：把交易所当成统一 MarketProvider，REST 拉历史K线 + WebSocket 推实时成交价。
 * 加密币在多家交易所间回退；股票（OKX 永续/代币化现货）只走 OKX。
 */
class MarketClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wsHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val providers: Map<String, MarketProvider> = listOf(
        OkxProvider(http, wsHttp),
        BinanceProvider(http, wsHttp),
        BybitProvider(http, wsHttp)
    ).associateBy { it.id }

    fun source(id: String?): MarketProvider = providers[id] ?: providers.getValue(PROVIDER_OKX)

    // ---------------------------------------------------------------- REST

    suspend fun fetchCandles(provider: String, inst: Instrument, tf: TF, limit: Int = 120): List<Candle> =
        source(provider).fetchCandles(inst, tf, limit)

    suspend fun fetchQuote(provider: String, inst: Instrument): Quote =
        source(provider).fetchQuote(inst)

    /** 股票只走 OKX；加密币按 preferred → OKX → Binance → Bybit 依次回退 */
    suspend fun bootstrap(preferred: String?, inst: Instrument, tf: TF, limit: Int = 120): Snapshot {
        if (inst.marketType == MarketType.STOCK) {
            val symbol = inst.symbolFor(PROVIDER_OKX)
                ?: throw IOException("${inst.displayName} 没有 OKX 交易对")
            val candles = fetchCandles(PROVIDER_OKX, inst, tf, limit)
            if (candles.isEmpty()) throw IOException("$symbol 暂无 K 线")
            val quote = try {
                fetchQuote(PROVIDER_OKX, inst)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Quote(candles.last().c)
            }
            return Snapshot(PROVIDER_OKX, candles, quote)
        }

        val order = (listOfNotNull(preferred) + PROVIDERS).distinct()
        var lastError: Exception? = null
        for (p in order) {
            if (inst.symbolFor(p) == null) continue
            try {
                val candles = fetchCandles(p, inst, tf, limit)
                if (candles.isEmpty()) continue
                val quote = try {
                    fetchQuote(p, inst)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Quote(candles.last().c)
                }
                return Snapshot(p, candles, quote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("${inst.displayName} 暂无可用行情源")
    }

    // ----------------------------------------------------------- WebSocket

    fun liveStream(provider: String, inst: Instrument): Flow<Quote> =
        source(provider).liveStream(inst)

    // ---------------------------------------------------------------- 搜索

    /** 并行拉各交易所目录，任一失败不影响其它；结果按 id 合并 */
    suspend fun searchAll(): List<Instrument> = coroutineScope {
        val jobs = PROVIDERS.map { id ->
            async {
                try {
                    providers.getValue(id).search()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        Instruments.merge(jobs.awaitAll().flatten())
    }
}

/** 用最新价修补最后一根K线（时间跨过周期就补一根新K线） */
fun List<Candle>.applyTick(price: Double, tfSeconds: Long, nowMs: Long): List<Candle> {
    if (isEmpty()) return this
    val last = last()
    val periodMs = tfSeconds * 1000L
    val bucket = nowMs / periodMs * periodMs
    return if (bucket > last.t) {
        this + Candle(bucket, price, price, price, price, 0.0)
    } else {
        val fixed = last.copy(
            h = max(last.h, price),
            l = kotlin.math.min(last.l, price),
            c = price
        )
        dropLast(1) + fixed
    }
}
