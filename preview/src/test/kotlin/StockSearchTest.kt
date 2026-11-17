package com.milolab.btctv

import com.milolab.btctv.data.InstrumentSearch
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.StockUniverse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StockSearchTest {

    private fun nvda(kind: StockKind) = Instruments.stock(
        ticker = "NVDA",
        priceDecimals = 2,
        symbols = mapOf(
            PROVIDER_OKX to if (kind == StockKind.PERP) "NVDA-USDT-SWAP" else "XNVDA-USDT"
        ),
        kind = kind
    )

    private val catalog = listOf(
        Instruments.BTC,
        Instruments.SOL,
        Instruments.crypto("APP", 4, mapOf(PROVIDER_OKX to "APP-USDT")),
        Instruments.stock(
            "APP", symbols = mapOf(PROVIDER_OKX to "APP-USDT-SWAP"),
            kind = StockKind.PERP, cryptoBases = setOf("APP")
        ),
        Instruments.stock(
            "APP", symbols = mapOf(PROVIDER_OKX to "XAPP-USDT"),
            kind = StockKind.TOKENIZED, cryptoBases = setOf("APP")
        ),
        nvda(StockKind.PERP),
        nvda(StockKind.TOKENIZED)
    )

    @Test
    fun nvdRanksPerpetualBeforeTokenized() {
        val hits = InstrumentSearch.search("nvd", catalog)
        assertEquals(listOf("NVDA", "NVDA.x"), hits.take(2).map { it.id })
        assertEquals(StockKind.PERP, hits[0].stockKind)
        assertEquals(StockKind.TOKENIZED, hits[1].stockKind)
    }

    @Test
    fun chineseAliasFindsBothStockKinds() {
        val hits = InstrumentSearch.search("英伟达", catalog)
        assertEquals(listOf("NVDA", "NVDA.x"), hits.take(2).map { it.id })
    }

    @Test
    fun stockIdUsesPrefixWhenCryptoBaseCollides() {
        val perp = catalog.first { it.baseCurrency == "APP" && it.stockKind == StockKind.PERP }
        val token = catalog.first { it.baseCurrency == "APP" && it.stockKind == StockKind.TOKENIZED }
        assertEquals("stock:APP", perp.id)
        assertEquals("stock:APP.x", token.id)
        assertEquals("APP", perp.baseCurrency)
        assertEquals(MarketType.STOCK, perp.marketType)
        assertEquals("USD", perp.quoteCurrency)
    }

    @Test
    fun preIpoAndSyntheticTickersAreRejected() {
        assertFalse(StockUniverse.isEligible("OPENAI"))
        assertFalse(StockUniverse.isEligible("ANTHROPIC"))
        assertFalse(StockUniverse.isEligible("US500"))
        assertFalse(StockUniverse.isEligible("CSOPSAMSUNG2L"))
        assertTrue(StockUniverse.isEligible("NVDA"))
        assertTrue(StockUniverse.isEligible("BRKB"))
    }

    @Test
    fun mergeKeepsPerpetualAndTokenizedAsSeparateEntries() {
        val merged = Instruments.merge(catalog)
        val stocks = merged.filter { it.baseCurrency == "NVDA" }
        assertEquals(listOf("NVDA", "NVDA.x"), stocks.map { it.id })
        assertTrue(stocks[0].hot)
        assertFalse(stocks[1].hot)
        assertEquals("英伟达 NVIDIA", stocks[0].displayName)
    }
}
