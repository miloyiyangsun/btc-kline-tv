import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.indicators.IndicatorEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndicatorEngineTest {

    private fun candles(n: Int, lastClose: Double = 100.0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var p = 90.0
        for (i in 0 until n) {
            val o = p
            val c = if (i == n - 1) lastClose else o + if (i % 4 < 2) 0.6 else -0.35
            out.add(
                Candle(
                    t = i * 60_000L,
                    o = o,
                    h = max(o, c) + 0.4,
                    l = min(o, c) - 0.4,
                    c = c,
                    v = 10.0 + i
                )
            )
            p = c
        }
        return out
    }

    private val settings = IndicatorSettings.DEFAULT
        .withToggled("ema")
        .withToggled("rsi")
        .withParam("ema", "period", 9.0)

    @Test
    fun sameRevisionHitsCacheAndReturnsSameObjects() {
        val engine = IndicatorEngine()
        val cs = candles(120)
        val a = engine.compute(settings, cs, revision = 7L)
        val b = engine.compute(settings, cs, revision = 7L)
        assertEquals(setOf("ema", "rsi"), a.keys)
        assertSame(a["ema"], b["ema"])
        assertSame(a["rsi"], b["rsi"])
        assertEquals(2, engine.cacheSize)
        assertEquals(setOf(7L), engine.cachedVersions("ema"))
    }

    @Test
    fun newRevisionRecomputes() {
        val engine = IndicatorEngine()
        val cs = candles(120)
        val a = engine.compute(settings, cs, revision = 1L)
        val b = engine.compute(settings, cs, revision = 2L)
        assertNotSame(a["ema"], b["ema"])
        assertEquals(setOf(1L, 2L), engine.cachedVersions("ema"))
    }

    @Test
    fun refreshLastKeepsOldPrefixAndUpdatesOnlyLastPoint() {
        val engine = IndicatorEngine()
        val first = candles(120)
        val previous = engine.compute(settings, first, revision = 1L)

        val changedLast = first.last().let { it.copy(c = it.c + 3.0, h = it.h + 3.0) }
        val updated = first.dropLast(1) + changedLast

        val patched = engine.refreshLast(settings, updated, revision = 2L, previous = previous)
        val fresh = engine.compute(settings, updated, revision = 3L)

        for (id in listOf("ema", "rsi")) {
            val oldResult = previous.getValue(id)
            val patchResult = patched.getValue(id)
            val freshResult = fresh.getValue(id)
            for (series in patchResult.series) {
                val oldSeries = oldResult.series(series.key) ?: continue
                val freshSeries = freshResult.series(series.key) ?: continue
                val last = series.size - 1
                assertTrue(last > 0)
                for (i in 0 until last) {
                    assertEquals(oldSeries[i], series[i], "$id/${series.key}[$i] 前缀被改动")
                }
                assertEquals(freshSeries[last], series[last], "$id/${series.key} 最后一点不是新值")
            }
        }
    }

    @Test
    fun refreshLastDoesNotFillCacheWithEveryTickRevision() {
        val engine = IndicatorEngine()
        val cs = candles(120)
        val previous = engine.compute(settings, cs, revision = 10L)
        val versionsBefore = engine.cachedVersions("ema")

        engine.refreshLast(settings, cs, revision = 11L, previous = previous)
        engine.refreshLast(settings, cs, revision = 12L, previous = previous)

        assertEquals(versionsBefore, engine.cachedVersions("ema"))
    }

    @Test
    fun refreshLastFallsBackToFullResultForNewIndicators() {
        val engine = IndicatorEngine()
        val cs = candles(120)
        val patched = engine.refreshLast(settings, cs, revision = 1L, previous = emptyMap())
        assertEquals(setOf("ema", "rsi"), patched.keys)
        assertEquals(cs.size, patched.getValue("ema").length)
    }

    @Test
    fun emptyCandlesProduceEmptyResults() {
        val engine = IndicatorEngine()
        assertTrue(engine.compute(settings, emptyList(), 1L).isEmpty())
        assertTrue(engine.refreshLast(settings, emptyList(), 1L, emptyMap()).isEmpty())
    }
}
