package com.milolab.btctv.data

import kotlinx.coroutines.flow.Flow
import java.io.IOException

const val PROVIDER_BINANCE = "Binance"
const val PROVIDER_OKX = "OKX"
const val PROVIDER_BYBIT = "Bybit"

/** 加密现货多源回退；股票标的只走 OKX，不参与这个列表 */
val PROVIDERS = listOf(PROVIDER_OKX, PROVIDER_BINANCE, PROVIDER_BYBIT)

/**
 * 单个行情源。每个实现只认自己交易所的 symbol（来自 Instrument.symbols），
 * 不在任何地方用 base+quote 拼交易对。
 */
interface MarketProvider {
    val id: String

    suspend fun fetchCandles(inst: Instrument, tf: TF, limit: Int = 120): List<Candle>

    suspend fun fetchQuote(inst: Instrument): Quote

    fun liveStream(inst: Instrument): Flow<Quote>

    /** 该交易所的全部标的（加密现货/股票永续/代币化股票），失败时抛异常 */
    suspend fun search(): List<Instrument>
}

internal fun Instrument.requireSymbol(provider: String): String =
    symbolFor(provider) ?: throw IOException("$displayName 未在 $provider 上线")
