import com.milolab.btctv.data.overview.GlobalStats
import com.milolab.btctv.data.overview.HistoryPoint
import com.milolab.btctv.data.overview.OverviewFetchException
import com.milolab.btctv.data.overview.OverviewGlobalSource
import com.milolab.btctv.data.overview.OverviewHistorySource
import com.milolab.btctv.data.overview.OverviewRepository
import com.milolab.btctv.data.overview.OverviewStablecoinSource
import com.milolab.btctv.data.overview.StablecoinStats
import com.milolab.btctv.data.overview.appendSample
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewRepositoryTest {

    private class Clock(var t: Long = 1_700_000_000_000L)

    private class FakeGlobal(
        override val name: String,
        var mcap: Double = 1_000.0,
        var btc: Double = 60.0,
        var fail: Exception? = null
    ) : OverviewGlobalSource {
        var calls = 0
        override suspend fun fetch(fetchedAtMs: Long): GlobalStats {
            calls++
            fail?.let { throw it }
            return GlobalStats(
                totalMarketCapUsd = mcap,
                totalVolume24hUsd = 100.0,
                change24hPct = 1.5,
                btcDominance = btc,
                ethDominance = 10.0,
                updatedAtMs = fetchedAtMs,
                fetchedAtMs = fetchedAtMs,
                source = name
            )
        }
    }

    private class FakeStable(
        override val name: String,
        var mcap: Double = 250.0,
        var fail: Exception? = null
    ) : OverviewStablecoinSource {
        var calls = 0
        override suspend fun fetch(fetchedAtMs: Long): StablecoinStats {
            calls++
            fail?.let { throw it }
            return StablecoinStats(mcap, fetchedAtMs, fetchedAtMs, name)
        }
    }

    private class FakeHistory(
        override val name: String = "Hist",
        var points: List<HistoryPoint> = listOf(HistoryPoint(1_699_000_000, 2.5e11))
    ) : OverviewHistorySource {
        var calls = 0
        override suspend fun fetchStablecoinHistory(fetchedAtMs: Long, days: Int): List<HistoryPoint> {
            calls++
            return points
        }
    }

    private fun repo(
        clock: Clock,
        g: List<OverviewGlobalSource>,
        s: List<OverviewStablecoinSource>,
        h: List<OverviewHistorySource> = emptyList(),
        file: File? = null
    ) = OverviewRepository(
        cacheFile = file,
        globalSources = g,
        stablecoinSources = s,
        historySources = h,
        now = { clock.t }
    )

    @Test
    fun ttlBlocksRefetchUntilExpired() = runBlocking {
        val clock = Clock()
        val g = FakeGlobal("G")
        val s = FakeStable("S")
        val r = repo(clock, listOf(g), listOf(s))

        val first = r.refresh()!!
        assertEquals(1, g.calls)
        assertEquals(1, s.calls)
        assertEquals(60.0, first.btcDominance)
        assertEquals(25.0, first.stablecoinDominancePct!!, 1e-9)
        assertFalse(first.stale)
        assertEquals("G + S", first.source)

        clock.t += 30_000
        r.refresh()
        assertEquals(1, g.calls, "TTL 内不应重新请求")

        clock.t += 120_000
        r.refresh()
        assertEquals(2, g.calls, "超过 120s 应刷新全局数据")
        assertEquals(2, s.calls, "稳定币 TTL 同为 120s，应一并刷新")
    }

    @Test
    fun failureKeepsOldValuesAndMarksStale() = runBlocking {
        val clock = Clock()
        val g = FakeGlobal("G")
        val s = FakeStable("S")
        val r = repo(clock, listOf(g), listOf(s))
        r.refresh()!!

        g.mcap = 2_000.0
        g.fail = IOException("network down")
        clock.t += 121_000
        val second = r.refresh()!!

        assertEquals(1_000.0, second.totalMarketCapUsd, "应保留旧值")
        assertTrue(second.stale, "抓取失败应标记 stale")
        assertEquals(2, g.calls)
    }

    @Test
    fun retryAfterHeaderIsHonored() = runBlocking {
        val clock = Clock()
        val g = FakeGlobal("G", fail = OverviewFetchException("HTTP 429", 429, 60_000))
        val r = repo(clock, listOf(g), listOf(FakeStable("S")))

        val first = r.refresh()!!
        assertNull(first.btcDominance)
        assertTrue(first.stale)
        assertEquals(1, g.calls)

        clock.t += 10_000
        r.refresh(force = true)
        assertEquals(1, g.calls, "Retry-After 窗口内不应重试")

        clock.t += 55_000
        g.fail = null
        val recovered = r.refresh(force = true)!!
        assertEquals(2, g.calls)
        assertFalse(recovered.stale)
        assertEquals(60.0, recovered.btcDominance)
    }

    @Test
    fun backoffGrowsExponentially() = runBlocking {
        val clock = Clock()
        val g = FakeGlobal("G", fail = IOException("boom"))
        val r = repo(clock, listOf(g), listOf(FakeStable("S")))
        r.refresh()
        assertEquals(1, g.calls)

        clock.t += 60_001
        r.refresh(force = true)
        assertEquals(2, g.calls, "第一次退避 60s 后可重试")

        clock.t += 60_000
        r.refresh(force = true)
        assertEquals(2, g.calls, "第二次退避 120s 内不应重试")

        clock.t += 61_000
        r.refresh(force = true)
        assertEquals(3, g.calls)
    }

    @Test
    fun backupUsedOnFirstFillAndKeptWhilePrimaryFails() = runBlocking {
        val clock = Clock()
        val primary = FakeGlobal("CoinGecko", fail = IOException("429"))
        val backup = FakeGlobal("CoinPaprika", mcap = 2_300.0)
        val r = repo(clock, listOf(primary, backup), listOf(FakeStable("S")))

        val first = r.refresh()!!
        assertEquals("CoinPaprika + S", first.source)
        assertEquals(1, backup.calls)
        assertFalse(first.stale, "备源成功就不算 stale")

        clock.t += 121_000
        r.refresh()
        assertEquals(2, backup.calls, "缓存来自备源时应继续允许备源")
        assertEquals(2_300.0, r.current()!!.totalMarketCapUsd)
    }

    @Test
    fun backupNotUsedWhilePrimaryCacheIsFresh() = runBlocking {
        val clock = Clock()
        val primary = FakeGlobal("CoinGecko")
        val backup = FakeGlobal("CoinPaprika", mcap = 2_300.0)
        val r = repo(clock, listOf(primary, backup), listOf(FakeStable("S")))
        r.refresh()!!
        assertEquals(0, backup.calls)

        primary.fail = IOException("429")
        clock.t += 121_000
        val second = r.refresh()!!

        assertEquals(0, backup.calls, "主源缓存仍新鲜时不应切到备源")
        assertEquals(1_000.0, second.totalMarketCapUsd)
        assertTrue(second.stale)
    }

    @Test
    fun cacheFileSurvivesRestartWithoutNetwork() = runBlocking {
        val clock = Clock()
        val dir = Files.createTempDirectory("overview-cache-test").toFile()
        try {
            val file = File(dir, "overview_cache.json")
            val r1 = repo(clock, listOf(FakeGlobal("G")), listOf(FakeStable("S")), file = file)
            r1.refresh()
            assertTrue(file.exists())
            assertTrue(file.length() > 0)

            val r2 = repo(clock, emptyList(), emptyList(), file = file)
            val loaded = r2.refresh()!!
            assertEquals(1_000.0, loaded.totalMarketCapUsd)
            assertEquals(250.0, loaded.stablecoinMarketCapUsd)
            assertEquals("G + S", loaded.source)
            assertFalse(loaded.stale, "缓存仍在新旧容忍窗口内")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedCacheFileIsIgnored() = runBlocking {
        val clock = Clock()
        val dir = Files.createTempDirectory("overview-cache-bad").toFile()
        try {
            val file = File(dir, "overview_cache.json")
            file.writeText("{not json")
            val r = repo(clock, listOf(FakeGlobal("G")), listOf(FakeStable("S")), file = file)
            val o = r.refresh()!!
            assertEquals(1_000.0, o.totalMarketCapUsd)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun historyIsCachedAndDoesNotAffectStale() = runBlocking {
        val clock = Clock()
        val hist = FakeHistory()
        val r = repo(clock, listOf(FakeGlobal("G")), listOf(FakeStable("S")), listOf(hist))
        val first = r.refresh()!!
        assertEquals(1, hist.calls)
        assertEquals(1, first.stablecoinMarketCapHistory.size)

        clock.t += 121_000
        r.refresh()
        assertEquals(1, hist.calls, "历史 30 分钟内不重复拉取")

        clock.t += 31 * 60_000
        r.refresh()
        assertEquals(2, hist.calls)
    }

    @Test
    fun historyFailureDoesNotMarkStale() = runBlocking {
        val clock = Clock()
        val hist = object : OverviewHistorySource {
            override val name = "H"
            override suspend fun fetchStablecoinHistory(fetchedAtMs: Long, days: Int): List<HistoryPoint> =
                throw IOException("chart down")
        }
        val r = repo(clock, listOf(FakeGlobal("G")), listOf(FakeStable("S")), listOf(hist))
        val o = r.refresh()!!
        assertFalse(o.stale)
        assertTrue(o.stablecoinMarketCapHistory.isEmpty())
    }

    @Test
    fun appendSampleThrottlesReplacesAndCaps() {
        val base = listOf(HistoryPoint(1_000, 5.0))
        assertEquals(base, appendSample(base, 1_060, 6.0, 300, 500), "间隔不足应丢弃")
        assertEquals(base, appendSample(base, 999, 6.0, 300, 500), "时间倒退应丢弃")

        val added = appendSample(base, 1_300, 6.0, 300, 500)
        assertEquals(2, added.size)
        assertEquals(6.0, added.last().v)

        val replaced = appendSample(added, 1_300, 7.0, 300, 500)
        assertEquals(2, replaced.size)
        assertEquals(7.0, replaced.last().v)

        val many = (0 until 600).map { HistoryPoint(it * 300L, 1.0) }
        val capped = appendSample(many, 600 * 300L, 2.0, 300, 500)
        assertEquals(500, capped.size)
        assertEquals(600 * 300L, capped.last().t)
        assertEquals(101 * 300L, capped.first().t)
    }

    @Test
    fun localSamplesAccumulateOnRefresh() = runBlocking {
        val clock = Clock()
        val g = FakeGlobal("G", btc = 58.0)
        val r = repo(clock, listOf(g), listOf(FakeStable("S")))
        r.refresh()
        assertEquals(1, r.current()!!.btcDominanceHistory.size)

        g.btc = 59.0
        clock.t += 10 * 60_000
        r.refresh(force = true)
        val o = r.current()!!
        assertEquals(2, o.btcDominanceHistory.size)
        assertEquals(59.0, o.btcDominanceHistory.last().v)
        assertEquals(2, o.totalMarketCapHistory.size)
    }

    @Test
    fun nullStablecoinLeavesDominanceNull() = runBlocking {
        val clock = Clock()
        val s = FakeStable("S", fail = IOException("down"))
        val r = repo(clock, listOf(FakeGlobal("G")), listOf(s))
        val o = r.refresh()!!
        assertNull(o.stablecoinMarketCapUsd)
        assertNull(o.stablecoinDominancePct)
        assertTrue(o.stale)
    }
}
