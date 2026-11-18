import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.PROVIDER_BINANCE
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.hub.CandleStore
import com.milolab.btctv.data.hub.candleTtlMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandleStoreTest {

    private var clock = 1_700_000_000_000L

    private fun inst(base: String) = Instruments.crypto(
        base = base,
        priceDecimals = 2,
        symbols = mapOf(PROVIDER_BINANCE to "${base}USDT")
    )

    private fun series(count: Int, price: Double = 100.0): List<Candle> =
        (0 until count).map { i ->
            val t = TF.M1.bucketStart(clock) - (count - 1 - i) * 60_000L
            Candle(t, price, price + 1, price - 1, price, 1.0)
        }

    @Test
    fun candleTtlsAreOrderedByTimeframe() {
        assertEquals(30_000L, candleTtlMs(TF.M1))
        assertEquals(30_000L, candleTtlMs(TF.M1))
        assertEquals(300_000L, candleTtlMs(TF.H1))
        assertEquals(1_800_000L, candleTtlMs(TF.D1))
        assertEquals(3_600_000L, candleTtlMs(TF.W1))
        assertTrue(candleTtlMs(TF.M1) < candleTtlMs(TF.H1))
        assertTrue(candleTtlMs(TF.H1) < candleTtlMs(TF.D1))
    }

    @Test
    fun cacheHitInsideTtlDoesNotRefetch() = runBlocking {
        var calls = 0
        val store = CandleStore(
            fetcher = { _, _, limit -> calls++; series(limit) },
            now = { clock }
        )
        val a = store.candles(inst("AAA"), TF.M1, 20)
        clock += 29_999L
        val b = store.candles(inst("AAA"), TF.M1, 20)
        assertEquals(1, calls)
        assertEquals(a, b)
        assertEquals(20, a.size)
    }

    @Test
    fun expiredCacheRefetches() = runBlocking {
        var calls = 0
        val store = CandleStore(
            fetcher = { _, _, limit -> calls++; series(limit) },
            now = { clock }
        )
        store.candles(inst("AAA"), TF.M1, 20)
        clock += 30_000L
        store.candles(inst("AAA"), TF.M1, 20)
        assertEquals(2, calls)
    }

    @Test
    fun largerRequestAfterSmallOneRefetches() = runBlocking {
        var calls = 0
        val store = CandleStore(
            fetcher = { _, _, limit -> calls++; series(limit) },
            now = { clock }
        )
        store.candles(inst("AAA"), TF.M1, 20)
        store.candles(inst("AAA"), TF.M1, 60)
        assertEquals(2, calls)
    }

    @Test
    fun concurrentRequestsForSameKeyShareOneFetch() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val store = CandleStore(
            fetcher = { _, _, limit ->
                calls++
                gate.await()
                series(limit)
            },
            now = { clock }
        )
        val first = async { store.candles(inst("AAA"), TF.M1, 20) }
        val second = async { store.candles(inst("AAA"), TF.M1, 20) }
        withTimeout(2_000) { while (calls == 0) delay(5) }
        gate.complete(Unit)
        val a = first.await()
        val b = second.await()
        assertEquals(1, calls)
        assertEquals(a, b)
    }

    @Test
    fun differentKeysAreFetchedSeparately() = runBlocking {
        var calls = 0
        val store = CandleStore(
            fetcher = { _, _, limit -> calls++; series(limit) },
            now = { clock }
        )
        store.candles(inst("AAA"), TF.M1, 20)
        store.candles(inst("AAA"), TF.H1, 20)
        store.candles(inst("BBB"), TF.M1, 20)
        assertEquals(3, calls)
    }

    @Test
    fun failureKeepsServingTheLastGoodSeries() = runBlocking {
        var calls = 0
        var fail = false
        val store = CandleStore(
            fetcher = { _, _, limit ->
                calls++
                if (fail) throw IOException("boom")
                series(limit)
            },
            now = { clock }
        )
        val good = store.candles(inst("AAA"), TF.M1, 20)
        clock += 31_000L
        fail = true
        val kept = store.candles(inst("AAA"), TF.M1, 20)
        assertEquals(good, kept)
        assertEquals(2, calls)
        // 过期后每次仍会尝试重拉，但依旧返回旧值
        clock += 31_000L
        assertEquals(good, store.candles(inst("AAA"), TF.M1, 20))
        assertEquals(3, calls)
    }

    @Test
    fun failureWithoutCachePropagates() = runBlocking {
        val store = CandleStore(
            fetcher = { _, _, _ -> throw IOException("no network") },
            now = { clock }
        )
        assertFailsWith<IOException> { store.candles(inst("AAA"), TF.M1, 20) }
        assertNull(store.peek(inst("AAA"), TF.M1))
    }

    @Test
    fun emptyResponseIsTreatedAsFailure() = runBlocking {
        val store = CandleStore(
            fetcher = { _, _, _ -> emptyList() },
            now = { clock }
        )
        assertFailsWith<IOException> { store.candles(inst("AAA"), TF.M1, 20) }
        Unit
    }

    @Test
    fun lruEvictsLeastRecentlyUsedEntry() = runBlocking {
        val store = CandleStore(
            fetcher = { _, _, limit -> series(limit) },
            maxEntries = 2,
            now = { clock }
        )
        store.candles(inst("AAA"), TF.M1, 20)
        store.candles(inst("BBB"), TF.M1, 20)
        store.candles(inst("CCC"), TF.M1, 20)
        assertNull(store.peek(inst("AAA"), TF.M1))
        assertNotNull(store.peek(inst("BBB"), TF.M1))
        assertNotNull(store.peek(inst("CCC"), TF.M1))
        Unit
    }

    @Test
    fun sparkReturnsRequestedNumberOfCloses() = runBlocking {
        val store = CandleStore(
            fetcher = { _, _, limit -> series(limit, price = 50.0) },
            now = { clock }
        )
        val spark = store.spark(inst("AAA"), TF.M1, 48)
        assertEquals(48, spark.size)
        assertTrue(spark.all { it == 50.0 })
    }

    @Test
    fun applyTickPatchesCachedLastCandle() = runBlocking {
        val store = CandleStore(
            fetcher = { _, _, limit -> series(limit) },
            now = { clock }
        )
        val before = store.candles(inst("AAA"), TF.M1, 20)
        val patched = store.applyTick(inst("AAA"), 105.0, TF.M1, clock)
        assertNotNull(patched)
        assertEquals(20, patched.size)
        assertEquals(105.0, patched.last().c)
        assertEquals(105.0, patched.last().h)
        assertEquals(100.0, patched.last().o)
        assertEquals(105.0, store.peek(inst("AAA"), TF.M1)?.last()?.c)
        assertNull(store.applyTick(inst("AAA"), 105.0, TF.H1, clock))
        // 原缓存对象不被就地修改，旧引用保持原值
        assertEquals(before.last().c, 100.0)
    }

    @Test
    fun invalidateDropsOneOrAllTimeframes() = runBlocking {
        var calls = 0
        val store = CandleStore(
            fetcher = { _, _, limit -> calls++; series(limit) },
            now = { clock }
        )
        store.candles(inst("AAA"), TF.M1, 20)
        store.candles(inst("AAA"), TF.H1, 20)
        store.invalidate(inst("AAA"), TF.M1)
        assertNull(store.peek(inst("AAA"), TF.M1))
        assertNotNull(store.peek(inst("AAA"), TF.H1))
        store.candles(inst("AAA"), TF.M1, 20)
        assertEquals(3, calls)
        store.invalidate(inst("AAA"))
        assertNull(store.peek(inst("AAA"), TF.H1))
    }

    @Test
    fun separateInstrumentIdsHaveSeparateCaches() = runBlocking {
        val store = CandleStore(
            fetcher = { inst, _, limit -> series(limit, price = if (inst.id == "AAAUSDT") 10.0 else 20.0) },
            now = { clock }
        )
        assertEquals(10.0, store.candles(inst("AAA"), TF.M1, 20).last().c)
        assertEquals(20.0, store.candles(inst("BBB"), TF.M1, 20).last().c)
    }
}
