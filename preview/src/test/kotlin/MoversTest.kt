import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketFilter
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.OkxTickerRow
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.hub.Mover
import com.milolab.btctv.data.hub.MoversConfig
import com.milolab.btctv.data.hub.MoversRepository
import com.milolab.btctv.data.hub.MoversSnapshot
import com.milolab.btctv.data.hub.MoversSource
import com.milolab.btctv.data.hub.buildMovers
import com.milolab.btctv.data.hub.parseStockSwapMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoversTest {

    private var clock = 1_700_000_000_000L

    private fun cryptoRow(
        instId: String,
        last: Double,
        open: Double?,
        volume: Double?,
        raw: String = last.toString()
    ) = OkxTickerRow(
        instId = instId,
        last = last,
        open24h = open,
        quoteVol24h = volume,
        lastRaw = raw
    )

    private fun mover(base: String, change: Double, volume: Double, stock: Boolean = false): Mover =
        if (stock) {
            Mover(
                Instruments.stock(ticker = base, priceDecimals = 2, symbols = mapOf(PROVIDER_OKX to "$base-USDT-SWAP")),
                100.0,
                change,
                volume
            )
        } else {
            Mover(Instruments.crypto(base, 2, mapOf(PROVIDER_OKX to "$base-USDT")), 100.0, change, volume)
        }

    // ------------------------------------------------------ 美股元数据识别

    @Test
    fun stockMetaKeepsOnlyLiveUsdtPerpetuals() {
        val raw = """
            {"code":"0","data":[
              {"instId":"NVDA-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDT","ruleType":"normal","ctValCcy":"NVDA"},
              {"instId":"TSLA-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDT","ruleType":"normal","ctValCcy":"tsla"},
              {"instId":"AMD-USDT-SWAP","instCategory":"1","state":"live","settleCcy":"USDT","ruleType":"normal","ctValCcy":"AMD"},
              {"instId":"META-USDT-SWAP","instCategory":"3","state":"suspend","settleCcy":"USDT","ruleType":"normal","ctValCcy":"META"},
              {"instId":"AAPL-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDC","ruleType":"normal","ctValCcy":"AAPL"},
              {"instId":"MSFT-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDT","ruleType":"pre_market","ctValCcy":"MSFT"},
              {"instId":"SP500-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDT","ruleType":"normal","ctValCcy":"SP500"},
              {"instId":"CSOPX-USDT-SWAP","instCategory":"3","state":"live","settleCcy":"USDT","ruleType":"normal","ctValCcy":"CSOPX"}
            ]}
        """.trimIndent()
        val meta = parseStockSwapMeta(raw)
        assertEquals(mapOf("NVDA-USDT-SWAP" to "NVDA", "TSLA-USDT-SWAP" to "TSLA"), meta)
    }

    @Test
    fun stockMetaToleratesBadJsonAndMissingData() {
        assertTrue(parseStockSwapMeta("not json").isEmpty())
        assertTrue(parseStockSwapMeta("""{"code":"0"}""").isEmpty())
        assertTrue(parseStockSwapMeta("""{"code":"0","data":[]}""").isEmpty())
    }

    // --------------------------------------------------------------- 榜单映射

    @Test
    fun buildMoversFiltersVolumeStablesAndMissingChanges() {
        val spot = listOf(
            cryptoRow("BTC-USDT", 65_000.5, 64_000.0, 2_000_000_000.0, raw = "65000.5"),
            cryptoRow("ETH-USDT", 3_000.0, 3_100.0, 500_000.0),
            cryptoRow("DOGE-USDT", 0.12345, 0.1, 2_000_000.0, raw = "0.12345"),
            cryptoRow("BTC-USDC", 65_000.0, 64_000.0, 9_000_000.0),
            cryptoRow("SOL-USDT", 0.0, 150.0, 9_000_000.0),
            cryptoRow("XRP-USDT", 1.0, null, 9_000_000.0),
            cryptoRow("TON-USDT", 2.5, 2.0, 3_000_000.0)
        )
        val swaps = listOf(
            cryptoRow("NVDA-USDT-SWAP", 120.5, 118.0, 300_000.0, raw = "120.50"),
            cryptoRow("TSLA-USDT-SWAP", 250.0, 260.0, 10_000.0),
            cryptoRow("AMD-USDT-SWAP", 100.0, 90.0, 900_000.0),
            cryptoRow("TON-USDT-SWAP", 2.4, 2.0, 100_000.0)
        )
        val meta = mapOf(
            "NVDA-USDT-SWAP" to "NVDA",
            "TSLA-USDT-SWAP" to "TSLA",
            "TON-USDT-SWAP" to "TON"
        )
        val rows = buildMovers(spot, swaps, meta)
        assertEquals(listOf("BTCUSDT", "DOGEUSDT", "TONUSDT", "NVDA", "stock:TON"), rows.map { it.instrument.id })

        val btc = rows.first { it.instrument.id == "BTCUSDT" }
        assertEquals(MarketType.CRYPTO, btc.marketType)
        assertEquals(1, btc.instrument.priceDecimals)
        assertEquals("BTC-USDT", btc.instId)
        assertEquals(1.56328125, btc.changePct, 1e-9)
        assertEquals(2_000_000_000.0, btc.quoteVolume24h)

        val doge = rows.first { it.instrument.id == "DOGEUSDT" }
        assertEquals(5, doge.instrument.priceDecimals)

        val nvda = rows.first { it.instrument.id == "NVDA" }
        assertEquals(MarketType.STOCK, nvda.marketType)
        assertEquals(StockKind.PERP, nvda.instrument.stockKind)
        assertEquals("NVDA-USDT-SWAP", nvda.instId)
        assertEquals(2, nvda.instrument.priceDecimals)

        val tonStock = rows.first { it.instrument.id == "stock:TON" }
        assertEquals(MarketType.STOCK, tonStock.marketType)
        assertEquals("TON", tonStock.symbol)
    }

    @Test
    fun configurableNoiseFilterDropsSmallCaps() {
        val spot = listOf(
            cryptoRow("BTC-USDT", 65_000.0, 64_000.0, 2_000_000.0),
            cryptoRow("DUST-USDT", 1.0, 0.9, 10_000.0)
        )
        val loose = buildMovers(spot, emptyList(), emptyMap(), MoversConfig(minQuoteVolumeUsd = 0.0))
        assertEquals(2, loose.size)
        val strict = buildMovers(spot, emptyList(), emptyMap(), MoversConfig(minQuoteVolumeUsd = 1_000_000.0))
        assertEquals(listOf("BTCUSDT"), strict.map { it.instrument.id })
    }

    // ------------------------------------------------------------ 排序与过滤

    @Test
    fun gainersSortDescendingAndBreakTiesByVolume() {
        val snapshot = MoversSnapshot(
            rows = listOf(
                mover("AAA", 8.0, 5.0),
                mover("BBB", -5.0, 10.0),
                mover("CCC", 8.0, 20.0),
                mover("NVDA", 3.0, 1.0, stock = true)
            ),
            updatedAtMs = clock,
            stale = false
        )
        assertEquals(listOf("CCC", "AAA"), snapshot.gainers(2).map { it.symbol })
        assertEquals(listOf("CCC", "AAA", "NVDA"), snapshot.gainers(10).map { it.symbol })
        assertEquals(listOf("NVDA"), snapshot.gainers(10, MarketFilter.STOCK).map { it.symbol })
        assertEquals(listOf("CCC", "AAA"), snapshot.gainers(10, MarketFilter.CRYPTO).take(2).map { it.symbol })
    }

    @Test
    fun losersSortAscendingAndRespectLimits() {
        val snapshot = MoversSnapshot(
            rows = listOf(
                mover("AAA", 8.0, 5.0),
                mover("BBB", -5.0, 10.0),
                mover("CCC", -9.0, 2.0),
                mover("NVDA", -1.0, 1.0, stock = true)
            ),
            updatedAtMs = clock,
            stale = false
        )
        assertEquals(listOf("CCC", "BBB", "NVDA"), snapshot.losers(10).map { it.symbol })
        assertEquals(listOf("CCC"), snapshot.losers(1).map { it.symbol })
        assertEquals(listOf("NVDA"), snapshot.losers(10, MarketFilter.STOCK).map { it.symbol })
        assertTrue(snapshot.losers(0).size == 1)
        assertTrue(snapshot.losers(10, MarketFilter.CRYPTO).none { it.symbol == "AAA" })
    }

    // ------------------------------------------------------------ 仓库 TTL

    @Test
    fun repositoryCachesForThirtySeconds() = runBlocking {
        var calls = 0
        val source = MoversSource {
            calls++
            listOf(mover("AAA", 1.0, 1_000_000.0))
        }
        val repo = MoversRepository(source, ttlMs = 30_000L, now = { clock })
        val first = repo.refresh()
        assertEquals(1, calls)
        assertFalse(first!!.stale)
        clock += 29_999L
        assertEquals(first, repo.refresh())
        assertEquals(1, calls)
        clock += 1L
        val second = repo.refresh()
        assertEquals(2, calls)
        assertFalse(second!!.stale)
    }

    @Test
    fun forceRefreshBypassesTtl() = runBlocking {
        var calls = 0
        val repo = MoversRepository(
            source = { calls++; listOf(mover("AAA", 1.0, 1_000_000.0)) },
            now = { clock }
        )
        repo.refresh()
        repo.refresh(force = true)
        assertEquals(2, calls)
    }

    @Test
    fun failureKeepsPreviousSnapshotAndMarksStale() = runBlocking {
        var calls = 0
        var fail = false
        val repo = MoversRepository(
            source = {
                calls++
                if (fail) throw IOException("network down")
                listOf(mover("AAA", 1.0, 1_000_000.0))
            },
            now = { clock }
        )
        val first = repo.refresh()!!
        clock += 31_000L
        fail = true
        val stale = repo.refresh()!!
        assertTrue(stale.stale)
        assertEquals(first.rows, stale.rows)
        assertEquals(first.updatedAtMs, stale.updatedAtMs)
        assertEquals(2, calls)
        // stale 不会无限沿用 TTL：下次仍尝试拉取
        val staleAgain = repo.refresh()!!
        assertTrue(staleAgain.stale)
        assertEquals(3, calls)
    }

    @Test
    fun failureWithoutPreviousSnapshotReturnsNull() = runBlocking {
        val repo = MoversRepository(
            source = { throw IOException("down") },
            now = { clock }
        )
        assertNull(repo.refresh())
        assertNull(repo.snapshot.value)
        assertTrue(repo.gainers().isEmpty())
    }

    @Test
    fun concurrentRefreshesShareOneFetch() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val repo = MoversRepository(
            source = {
                calls++
                gate.await()
                listOf(mover("AAA", 1.0, 1_000_000.0))
            },
            now = { clock }
        )
        val a = async { repo.refresh() }
        val b = async { repo.refresh() }
        withTimeout(2_000) { while (calls == 0) delay(5) }
        gate.complete(Unit)
        val first = a.await()
        val second = b.await()
        assertEquals(1, calls)
        assertEquals(first, second)
        assertEquals(1, repo.current()!!.rows.size)
    }

    @Test
    fun repositoryExposesRankingsFromLatestSnapshot() = runBlocking {
        val repo = MoversRepository(
            source = {
                listOf(
                    mover("AAA", 8.0, 5.0),
                    mover("BBB", -5.0, 10.0),
                    mover("NVDA", 3.0, 1.0, stock = true)
                )
            },
            now = { clock }
        )
        repo.refresh()
        assertEquals(listOf("AAA", "NVDA"), repo.gainers(2).map { it.symbol })
        assertEquals(listOf("BBB"), repo.losers(1).map { it.symbol })
        assertEquals(listOf("NVDA"), repo.gainers(5, MarketFilter.STOCK).map { it.symbol })
        assertTrue(repo.losers(5, MarketFilter.STOCK).isEmpty())
    }
}
