import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.aggregateCandles
import com.milolab.btctv.data.applyTick
import com.milolab.btctv.data.indicators.IndicatorEngine
import com.milolab.btctv.data.indicators.IndicatorRegistry
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeframeTest {

    private fun ts(text: String): Long = java.time.Instant.parse(text).toEpochMilli()

    private fun monthStart(year: Int, month: Int): Long =
        LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun candle(
        t: Long,
        o: Double = 100.0,
        h: Double = 110.0,
        l: Double = 90.0,
        c: Double = 100.0,
        v: Double = 1.0
    ) = Candle(t, o, h, l, c, v)

    // ------------------------------------------------------------ 周期映射

    @Test
    fun enumOrderIsTheButtonOrder() {
        assertEquals(
            listOf("1m", "5m", "15m", "30m", "1H", "4H", "12H", "1D", "1W", "1M", "1Y"),
            TF.entries.map { it.label }
        )
    }

    @Test
    fun exchangeIntervalsMatchNativePeriods() {
        assertEquals(Triple("30m", "30m", "30"), TF.M30.intervals())
        assertEquals(Triple("12h", "12Hutc", "720"), TF.H12.intervals())
        assertEquals(Triple("1d", "1Dutc", "D"), TF.D1.intervals())
        assertEquals(Triple("1w", "1Wutc", "W"), TF.W1.intervals())
        assertEquals(Triple("1M", "1Mutc", "M"), TF.MO1.intervals())
        // 除了年线，每家都有原生 interval
        assertTrue(TF.entries.filterNot { it.aggregated }.all {
            it.binance.isNotBlank() && it.okx.isNotBlank() && it.bybit.isNotBlank() && it.seconds > 0
        })
        assertEquals(TF.MO1, TF.Y1.aggregateFrom)
        assertTrue(TF.entries.single { it.aggregated } == TF.Y1)
    }

    private fun TF.intervals() = Triple(binance, okx, bybit)

    @Test
    fun stepCyclesInButtonOrder() {
        assertEquals(TF.M30, TF.M15.step(1))
        assertEquals(TF.M15, TF.M30.step(-1))
        assertEquals(TF.H12, TF.H4.step(1))
        assertEquals(TF.Y1, TF.M1.step(-1))
        assertEquals(TF.M1, TF.Y1.step(1))
        assertEquals(TF.entries.toList(), generateSequence(TF.M1) { it.step(1) }.take(TF.entries.size).toList())
    }

    @Test
    fun axisLabelsFollowPeriodScale() {
        assertEquals("HH:mm", TF.M30.axisPattern)
        assertEquals("MM-dd HH:mm", TF.H12.tooltipPattern)
        assertEquals("MM-dd", TF.D1.axisPattern)
        assertEquals("MM-dd", TF.D1.tooltipPattern)
        assertEquals("yyyy-MM", TF.W1.axisPattern)
        assertEquals("yyyy-MM", TF.MO1.tooltipPattern)
        assertEquals("yyyy", TF.Y1.axisPattern)
        assertEquals("yyyy", TF.Y1.tooltipPattern)
    }

    // ------------------------------------------------------- 日历桶边界

    @Test
    fun weeklyBucketStartsMondayUtc() {
        assertEquals(ts("2026-11-16T00:00:00Z"), TF.W1.bucketStart(ts("2026-11-18T10:30:00Z")))
        assertEquals(ts("2026-11-16T00:00:00Z"), TF.W1.bucketStart(ts("2026-11-16T00:00:00Z")))
        // 周日晚上仍属于上一周（周一为界）
        assertEquals(ts("2026-11-09T00:00:00Z"), TF.W1.bucketStart(ts("2026-11-15T23:59:59Z")))
        assertEquals(ts("2026-11-23T00:00:00Z"), TF.W1.nextBucketStart(ts("2026-11-16T00:00:00Z")))
        // 跨年周也按周一切
        assertEquals(ts("2025-12-29T00:00:00Z"), TF.W1.bucketStart(ts("2026-01-01T12:00:00Z")))
    }

    @Test
    fun monthlyBucketStartsFirstDayUtc() {
        assertEquals(ts("2026-11-01T00:00:00Z"), TF.MO1.bucketStart(ts("2026-11-18T10:30:00Z")))
        assertEquals(ts("2026-11-01T00:00:00Z"), TF.MO1.bucketStart(ts("2026-11-30T23:59:59Z")))
        assertEquals(ts("2026-12-01T00:00:00Z"), TF.MO1.bucketStart(ts("2026-12-01T00:00:00Z")))
        assertEquals(ts("2026-12-01T00:00:00Z"), TF.MO1.nextBucketStart(ts("2026-11-01T00:00:00Z")))
        assertEquals(ts("2027-01-01T00:00:00Z"), TF.MO1.nextBucketStart(ts("2026-12-01T00:00:00Z")))
    }

    @Test
    fun yearlyBucketStartsJanuaryFirstUtc() {
        assertEquals(ts("2026-01-01T00:00:00Z"), TF.Y1.bucketStart(ts("2026-11-18T10:30:00Z")))
        assertEquals(ts("2026-01-01T00:00:00Z"), TF.Y1.bucketStart(ts("2026-01-01T00:00:00Z")))
        assertEquals(ts("2027-01-01T00:00:00Z"), TF.Y1.nextBucketStart(ts("2026-01-01T00:00:00Z")))
    }

    @Test
    fun dayAndIntradayBucketsKeepUtcAlignment() {
        assertEquals(ts("2026-11-18T00:00:00Z"), TF.D1.bucketStart(ts("2026-11-18T05:00:00Z")))
        assertEquals(ts("2026-11-19T00:00:00Z"), TF.D1.nextBucketStart(ts("2026-11-18T00:00:00Z")))
        assertEquals(ts("2026-11-18T12:00:00Z"), TF.H12.bucketStart(ts("2026-11-18T13:00:00Z")))
        assertEquals(ts("2026-11-18T10:01:00Z"), TF.M1.nextBucketStart(ts("2026-11-18T10:00:00Z")))
    }

    // ------------------------------------------------------- 月线聚合年线

    private fun months(count: Int): List<Candle> {
        var d = LocalDate.of(2024, 12, 1)
        val out = ArrayList<Candle>(count)
        for (i in 0 until count) {
            val t = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            out.add(candle(t, o = 100.0 + i, h = 120.0 + i * 10, l = 80.0 - i, c = 110.0 + i, v = 1.0))
            d = d.plusMonths(1)
        }
        return out
    }

    @Test
    fun monthlyCandlesAggregateIntoUtcYears() {
        val yearly = aggregateCandles(months(15), TF.Y1)
        assertEquals(3, yearly.size)
        assertEquals(ts("2024-01-01T00:00:00Z"), yearly[0].t)
        assertEquals(ts("2025-01-01T00:00:00Z"), yearly[1].t)
        assertEquals(ts("2026-01-01T00:00:00Z"), yearly[2].t)

        // 2025 覆盖 i = 1..12
        val y2025 = yearly[1]
        assertEquals(101.0, y2025.o)
        assertEquals(240.0, y2025.h) // 120 + 12*10
        assertEquals(68.0, y2025.l)  // 80 - 12
        assertEquals(122.0, y2025.c) // 110 + 12
        assertEquals(12.0, y2025.v)

        // 2026 只有 1、2 月（i = 13、14），历史短也照常显示
        val y2026 = yearly[2]
        assertEquals(113.0, y2026.o)
        assertEquals(260.0, y2026.h)
        assertEquals(66.0, y2026.l)
        assertEquals(124.0, y2026.c)
        assertEquals(2.0, y2026.v)
    }

    @Test
    fun aggregationBoundsToAvailableHistory() {
        val yearly = aggregateCandles(months(1), TF.Y1).takeLast(20)
        assertEquals(1, yearly.size)
        assertEquals(ts("2024-01-01T00:00:00Z"), yearly.single().t)
        assertEquals(110.0, yearly.single().c)
    }

    // ------------------------------------------------- applyTick 长周期桶

    @Test
    fun yearlyTickPatchesCurrentBarUntilNextJanuary() {
        val yearly = aggregateCandles(months(15), TF.Y1)
        val patched = yearly.applyTick(300.0, TF.Y1, ts("2026-11-18T10:30:00Z"))
        assertEquals(3, patched.size)
        assertEquals(ts("2026-01-01T00:00:00Z"), patched.last().t)
        assertEquals(300.0, patched.last().h)
        assertEquals(300.0, patched.last().c)
        assertEquals(66.0, patched.last().l)

        val low = yearly.applyTick(50.0, TF.Y1, ts("2026-06-01T00:00:00Z"))
        assertEquals(50.0, low.last().l)
        assertEquals(50.0, low.last().c)
        assertEquals(260.0, low.last().h)
    }

    @Test
    fun yearlyTickOpensNewBarOnJanuaryFirst() {
        val yearly = aggregateCandles(months(15), TF.Y1)
        val next = yearly.applyTick(300.0, TF.Y1, ts("2027-01-01T00:00:00Z"))
        assertEquals(4, next.size)
        val bar = next.last()
        assertEquals(ts("2027-01-01T00:00:00Z"), bar.t)
        assertEquals(listOf(300.0, 300.0, 300.0, 300.0, 0.0), listOf(bar.o, bar.h, bar.l, bar.c, bar.v))
    }

    @Test
    fun weeklyTickUsesCalendarWeekNotFixedSeconds() {
        val monday = ts("2026-11-16T00:00:00Z")
        val bars = listOf(candle(monday - 7 * 86_400_000L, c = 90.0), candle(monday, c = 100.0))

        val midWeek = bars.applyTick(105.0, TF.W1, ts("2026-11-18T10:30:00Z"))
        assertEquals(2, midWeek.size)
        assertEquals(monday, midWeek.last().t)
        assertEquals(105.0, midWeek.last().c)

        val nextWeek = bars.applyTick(105.0, TF.W1, ts("2026-11-23T00:00:00Z"))
        assertEquals(3, nextWeek.size)
        assertEquals(ts("2026-11-23T00:00:00Z"), nextWeek.last().t)
    }

    @Test
    fun monthlyTickOpensNewBarOnFirstDay() {
        val nov = ts("2026-11-01T00:00:00Z")
        val bars = listOf(candle(nov, c = 100.0))
        assertEquals(1, bars.applyTick(102.0, TF.MO1, ts("2026-11-30T23:59:59Z")).size)
        val dec = bars.applyTick(102.0, TF.MO1, ts("2026-12-01T00:00:00Z"))
        assertEquals(2, dec.size)
        assertEquals(ts("2026-12-01T00:00:00Z"), dec.last().t)
    }

    @Test
    fun intradayTickStillRollsOnPeriodBoundary() {
        val t = ts("2026-11-18T10:00:00Z")
        val bars = listOf(candle(t, c = 100.0))
        assertEquals(1, bars.applyTick(101.0, TF.M1, ts("2026-11-18T10:00:59Z")).size)
        val rolled = bars.applyTick(101.0, TF.M1, ts("2026-11-18T10:01:00Z"))
        assertEquals(2, rolled.size)
        assertEquals(ts("2026-11-18T10:01:00Z"), rolled.last().t)
    }

    // ------------------------------------------------- 指标在极少K线下安全

    @Test
    fun shortYearlyHistoryKeepsIndicatorsSafe() {
        var settings = IndicatorSettings.DEFAULT
        for (spec in IndicatorRegistry.all) settings = settings.withToggled(spec.id)
        val nineBars = (0 until 9).map { i ->
            candle(
                monthStart(2018 + i, 1),
                o = 100.0 + i * 10,
                h = 130.0 + i * 10,
                l = 90.0 + i * 10,
                c = 120.0 + i * 10,
                v = 10.0
            )
        }
        val results = IndicatorEngine().compute(settings, nineBars, revision = 1L)
        assertTrue(results.isNotEmpty())
        assertTrue(results.values.all { result -> result.series.all { it.values.size == nineBars.size } })

        // 预热远大于 9 根时输出 NaN，而不是拿不足的数据硬算
        val ema = IndicatorSettings.DEFAULT
            .withToggled("ema")
            .withParam("ema", "period", 50.0)
        val emaResult = IndicatorEngine().compute(ema, nineBars, revision = 2L).getValue("ema")
        assertTrue(emaResult.series.all { series -> series.values.all { it.isNaN() } })
    }
}
