import com.milolab.btctv.data.overview.GlobalStats
import com.milolab.btctv.data.overview.OverviewFetchException
import com.milolab.btctv.data.overview.SOURCE_COINGECKO
import com.milolab.btctv.data.overview.SOURCE_COINPAPRIKA
import com.milolab.btctv.data.overview.SOURCE_DEFILLAMA
import com.milolab.btctv.data.overview.StablecoinStats
import com.milolab.btctv.data.overview.globalFromJson
import com.milolab.btctv.data.overview.parseCoinGeckoGlobal
import com.milolab.btctv.data.overview.parseCoinGeckoStablecoinCategory
import com.milolab.btctv.data.overview.parseCoinPaprikaGlobal
import com.milolab.btctv.data.overview.parseDefiLlamaStablecoinHistory
import com.milolab.btctv.data.overview.parseDefiLlamaStablecoins
import com.milolab.btctv.data.overview.parseIsoInstantMs
import com.milolab.btctv.data.overview.stableFromJson
import com.milolab.btctv.data.overview.stablecoinDominancePct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewParseTest {

    private val fetched = 1_794_959_300_000L

    // 取自 2026-11-17 实测响应（截取所需字段）
    private val coinGeckoGlobal = """
        {"data":{"total_market_cap":{"btc":34354927.98,"usd":2224501669659.8125},
        "total_volume":{"usd":80525619793.3481},
        "market_cap_percentage":{"btc":58.44198947933484,"eth":9.977873038622684,"usdt":7.853577653529069},
        "market_cap_change_percentage_24h_usd":-2.827012294854636,
        "updated_at":1794959274}}
    """.trimIndent()

    private val coinPaprikaGlobal = """
        {"market_cap_usd":2314925630602,"volume_24h_usd":118301016383,
        "bitcoin_dominance_percentage":56.52,"cryptocurrencies_number":10748,
        "market_cap_change_24h":0.66,"last_updated":1794959124}
    """.trimIndent()

    private val defiLlamaStablecoins = """
        {"peggedAssets":[
          {"id":"1","name":"Tether","circulating":{"peggedUSD":175220021700.9385}},
          {"id":"2","name":"EURC","circulating":{"peggedUSD":100.0,"peggedEUR":50.0}},
          {"id":"3","name":"Empty","circulating":{}}
        ],"chains":[]}
    """.trimIndent()

    private val categories = """
        [{"id":"smart-contract-platform","name":"Smart Contract Platform","market_cap":1874007057880.39},
         {"id":"stablecoins","name":"Stablecoins","market_cap":283200632623.2003,
          "market_cap_change_24h":-0.0496,"updated_at":"2026-11-17T23:44:50.000Z"}]
    """.trimIndent()

    @Test
    fun parseCoinGeckoGlobalFields() {
        val g = parseCoinGeckoGlobal(coinGeckoGlobal, fetched)
        assertEquals(2_224_501_669_659.8125, g.totalMarketCapUsd)
        assertEquals(80_525_619_793.3481, g.totalVolume24hUsd)
        assertEquals(-2.827, g.change24hPct!!, 0.001)
        assertEquals(58.44198947933484, g.btcDominance!!, 1e-9)
        assertEquals(9.977873038622684, g.ethDominance!!, 1e-9)
        assertEquals(1_794_959_274_000L, g.updatedAtMs)
        assertEquals(SOURCE_COINGECKO, g.source)
        assertEquals(fetched, g.fetchedAtMs)
    }

    @Test
    fun parseCoinGeckoGlobalFallsBackToFetchTimeWhenTimestampMissing() {
        val raw = """{"data":{"total_market_cap":{"usd":100.0},"market_cap_percentage":{"btc":50.0}}}"""
        val g = parseCoinGeckoGlobal(raw, fetched)
        assertEquals(fetched, g.updatedAtMs)
    }

    @Test
    fun parseCoinGeckoGlobalRejectsMissingData() {
        assertFailsWith<OverviewFetchException> { parseCoinGeckoGlobal("""{"status":"error"}""", fetched) }
    }

    @Test
    fun parseCoinPaprikaGlobalFields() {
        val g = parseCoinPaprikaGlobal(coinPaprikaGlobal, fetched)
        assertEquals(2_314_925_630_602.0, g.totalMarketCapUsd)
        assertEquals(118_301_016_383.0, g.totalVolume24hUsd)
        assertEquals(56.52, g.btcDominance!!, 1e-9)
        assertNull(g.ethDominance)
        assertEquals(0.66, g.change24hPct!!, 1e-9)
        assertEquals(1_794_959_124_000L, g.updatedAtMs)
        assertEquals(SOURCE_COINPAPRIKA, g.source)
    }

    @Test
    fun parseDefiLlamaStablecoinsSumsAllPegTypes() {
        val s = parseDefiLlamaStablecoins(defiLlamaStablecoins, fetched)
        assertEquals(175_220_021_850.9385, s.marketCapUsd!!, 1e-6)
        assertEquals(SOURCE_DEFILLAMA, s.source)
        assertEquals(fetched, s.updatedAtMs)
    }

    @Test
    fun parseDefiLlamaStablecoinsRejectsEmpty() {
        assertFailsWith<OverviewFetchException> {
            parseDefiLlamaStablecoins("""{"peggedAssets":[]}""", fetched)
        }
    }

    @Test
    fun parseDefiLlamaHistoryKeepsRequestedWindow() {
        val points = (1..40).joinToString(",") { i ->
            """{"date":"${1_700_000_000 + i * 86400}","totalCirculatingUSD":{"peggedUSD":${i * 1e9},"peggedEUR":${1e9}}}"""
        }
        val raw = "[$points]"
        val all = parseDefiLlamaStablecoinHistory(raw, 30)
        assertEquals(31, all.size)
        assertEquals(1_700_000_000L + 10 * 86400, all.first().t)
        assertEquals(40 * 1e9 + 1e9, all.last().v, 1.0)
        assertTrue(all.zipWithNext().all { (a, b) -> a.t < b.t })
    }

    @Test
    fun parseDefiLlamaHistoryHandlesShortSeries() {
        val raw = """[{"date":"1700000000","totalCirculatingUSD":{"peggedUSD":5000000000}}]"""
        val points = parseDefiLlamaStablecoinHistory(raw, 30)
        assertEquals(1, points.size)
        assertEquals(5e9, points.first().v, 1.0)
    }

    @Test
    fun parseCoinGeckoStablecoinCategory() {
        val s = parseCoinGeckoStablecoinCategory(categories, fetched)
        assertEquals(283_200_632_623.2003, s.marketCapUsd!!, 1e-6)
        assertEquals(SOURCE_COINGECKO, s.source)
        assertEquals(parseIsoInstantMs("2026-11-17T23:44:50.000Z"), s.updatedAtMs)
    }

    @Test
    fun parseCoinGeckoStablecoinCategoryRejectsMissing() {
        assertFailsWith<OverviewFetchException> {
            parseCoinGeckoStablecoinCategory("""[{"id":"defi","market_cap":1.0}]""", fetched)
        }
    }

    @Test
    fun parseIsoInstant() {
        assertEquals(0L, parseIsoInstantMs("1970-01-01T00:00:00Z"))
        assertEquals(1_700_000_000_000L, parseIsoInstantMs("2023-11-14T22:13:20Z"))
        assertNull(parseIsoInstantMs("not-a-date"))
    }

    @Test
    fun dominanceCalculation() {
        assertEquals(25.0, stablecoinDominancePct(250.0, 1000.0)!!, 1e-9)
        assertEquals(12.73, stablecoinDominancePct(283.2, 2224.5)!!, 0.01)
        assertNull(stablecoinDominancePct(null, 1000.0))
        assertNull(stablecoinDominancePct(100.0, null))
        assertNull(stablecoinDominancePct(0.0, 1000.0))
        assertNull(stablecoinDominancePct(100.0, 0.0))
        assertNull(stablecoinDominancePct(-1.0, 1000.0))
    }

    @Test
    fun cacheSerdeRoundTripPreservesNumbersAndTimestamps() {
        val g = GlobalStats(2.2e12, 8.0e10, -2.8, 58.44, 9.97, fetched, fetched, SOURCE_COINGECKO)
        val s = StablecoinStats(2.8e11, fetched, fetched, SOURCE_DEFILLAMA)
        val g2 = globalFromJson(g.let {
            org.json.JSONObject().apply {
                put("totalMarketCapUsd", it.totalMarketCapUsd)
                put("totalVolume24hUsd", it.totalVolume24hUsd)
                put("change24hPct", it.change24hPct)
                put("btcDominance", it.btcDominance)
                put("ethDominance", it.ethDominance)
                put("updatedAtMs", it.updatedAtMs)
                put("fetchedAtMs", it.fetchedAtMs)
                put("source", it.source)
            }
        })
        assertEquals(g, g2)
        val s2 = stableFromJson(
            org.json.JSONObject()
                .put("marketCapUsd", s.marketCapUsd)
                .put("updatedAtMs", s.updatedAtMs)
                .put("fetchedAtMs", s.fetchedAtMs)
                .put("source", s.source)
        )
        assertEquals(s, s2)
    }
}
