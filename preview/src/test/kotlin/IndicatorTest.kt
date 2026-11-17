package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndicatorTest {

    // ------------------------------------------------------------ helpers

    private fun mk(
        closes: List<Double>,
        highs: List<Double> = closes.map { it + 1.0 },
        lows: List<Double> = closes.map { it - 1.0 },
        volumes: List<Double> = List(closes.size) { 1.0 },
        startT: Long = 1_700_000_000_000L,
        stepMs: Long = 60_000L
    ): List<Candle> {
        require(highs.size == closes.size && lows.size == closes.size && volumes.size == closes.size)
        return closes.indices.map { i ->
            Candle(
                t = startT + i * stepMs,
                o = closes[i],
                h = highs[i],
                l = lows[i],
                c = closes[i],
                v = volumes[i]
            )
        }
    }

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-9) {
        if (expected.isNaN()) {
            assertTrue(actual.isNaN(), "expected NaN, got $actual")
        } else {
            assertEquals(expected, actual, eps, "expected $expected, got $actual")
        }
    }

    private fun seriesValues(spec: IndicatorSpec, candles: List<Candle>, key: String): DoubleArray =
        spec.evaluate(candles).series(key)?.values ?: error("missing series $key")

    // ------------------------------------------------------------ 数学底座

    @Test
    fun smaKnownValues() {
        val v = SeriesMath.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(v[0].isNaN())
        assertTrue(v[1].isNaN())
        assertClose(2.0, v[2])
        assertClose(3.0, v[3])
        assertClose(4.0, v[4])
    }

    @Test
    fun emaSeedsWithSma() {
        val v = SeriesMath.ema(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(v[1].isNaN())
        assertClose(2.0, v[2])
        assertClose(3.0, v[3])
        assertClose(4.0, v[4])
    }

    @Test
    fun emaHandlesLeadingNaN() {
        val v = SeriesMath.ema(doubleArrayOf(Double.NaN, 1.0, 2.0, 3.0), 2)
        assertTrue(v[0].isNaN())
        assertTrue(v[1].isNaN())
        assertClose(1.5, v[2])
        assertClose(2.5, v[3])
    }

    @Test
    fun wmaKnownValues() {
        val v = SeriesMath.wma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertClose(14.0 / 6.0, v[2])
        assertClose(20.0 / 6.0, v[3])
        assertClose(26.0 / 6.0, v[4])
    }

    @Test
    fun populationStdev() {
        val v = SeriesMath.stdev(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 3)
        assertClose(sqrt(2.0 / 3.0), v[2])
        assertClose(sqrt(2.0 / 3.0), v[3])
    }

    // ------------------------------------------------------------ 副图核心

    @Test
    fun rsiHandCalculation() {
        val up = OscillatorIndicators.rsiValues(doubleArrayOf(1.0, 2.0, 3.0), 2)
        assertClose(100.0, up[2])

        val down = OscillatorIndicators.rsiValues(doubleArrayOf(3.0, 2.0, 1.0), 2)
        assertClose(0.0, down[2])

        val mixed = OscillatorIndicators.rsiValues(doubleArrayOf(1.0, 2.0, 1.0, 2.0), 2)
        assertClose(50.0, mixed[2])
        assertClose(75.0, mixed[3])
    }

    @Test
    fun rsiFlatIsFifty() {
        val v = OscillatorIndicators.rsiValues(doubleArrayOf(5.0, 5.0, 5.0, 5.0), 3)
        assertClose(50.0, v[3])
    }

    @Test
    fun macdHandCalculation() {
        val close = doubleArrayOf(1.0, 2.0, 4.0, 7.0, 11.0)
        val (macd, signal, hist) = OscillatorIndicators.macdValues(close, 1, 3, 2)
        assertTrue(macd[1].isNaN())
        assertClose(1.6666666666666667, macd[2])
        assertClose(2.3333333333333335, macd[3])
        assertClose(3.1666666666666665, macd[4])
        assertTrue(signal[2].isNaN())
        assertClose(2.0, signal[3])
        assertClose(2.7777777777777777, signal[4])
        assertClose(0.3888888888888889, hist[4])
    }

    @Test
    fun kdjHandCalculation() {
        val candles = mk(
            closes = listOf(7.0, 8.0, 10.0, 11.0),
            highs = listOf(10.0, 11.0, 12.0, 13.0),
            lows = listOf(5.0, 6.0, 7.0, 8.0)
        )
        val r = OscillatorIndicators.kdjValues(candles, 3, 3, 3)
        assertTrue(r.k[1].isNaN())
        assertClose(57.142857142857146, r.k[2], 1e-9)
        assertClose(52.38095238095239, r.d[2], 1e-9)
        assertClose(66.66666666666666, r.j[2], 1e-9)
        assertClose(61.904761904761905, r.k[3], 1e-9)
        assertClose(55.55555555555556, r.d[3], 1e-9)
        assertClose(74.60317460317461, r.j[3], 1e-9)
    }

    @Test
    fun atrHandCalculation() {
        val candles = mk(
            closes = listOf(9.0, 10.0, 11.0),
            highs = listOf(10.0, 11.0, 12.0),
            lows = listOf(8.0, 9.0, 10.0)
        )
        val v = SeriesMath.atr(candles, 2)
        assertTrue(v[0].isNaN())
        assertClose(2.0, v[1])
        assertClose(2.0, v[2])
    }

    @Test
    fun williamsPercentRHandCalculation() {
        val candles = mk(
            closes = listOf(7.0, 8.0, 11.0),
            highs = listOf(10.0, 11.0, 12.0),
            lows = listOf(8.0, 9.0, 10.0)
        )
        val v = OscillatorIndicators.williamsR
            .evaluate(candles, mapOf("period" to 3.0))
            .series("williams")!!.values
        assertTrue(v[1].isNaN())
        assertClose(-25.0, v[2])
    }

    @Test
    fun cciHandCalculation() {
        val candles = mk(closes = listOf(1.0, 2.0, 3.0), highs = listOf(1.0, 2.0, 3.0), lows = listOf(1.0, 2.0, 3.0))
        val v = OscillatorIndicators.cci
            .evaluate(candles, mapOf("period" to 3.0))
            .series("cci")!!.values
        assertTrue(v[1].isNaN())
        assertClose(100.0, v[2])
    }

    @Test
    fun rocHandCalculation() {
        val candles = mk(closes = listOf(100.0, 110.0, 121.0))
        val v = OscillatorIndicators.roc.evaluate(candles, mapOf("period" to 2.0)).series("roc")!!.values
        assertTrue(v[1].isNaN())
        assertClose(21.0, v[2])
    }

    @Test
    fun obvHandCalculation() {
        val candles = mk(
            closes = listOf(1.0, 2.0, 1.0, 1.0, 3.0),
            volumes = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        )
        val v = seriesValues(OscillatorIndicators.obv, candles, "obv")
        assertEquals(0.0, v[0])
        assertEquals(20.0, v[1])
        assertEquals(-10.0, v[2])
        assertEquals(-10.0, v[3])
        assertEquals(40.0, v[4])
    }

    @Test
    fun volumeMaUsesVolumeSource() {
        val candles = mk(closes = listOf(1.0, 2.0, 3.0, 4.0), volumes = listOf(1.0, 2.0, 3.0, 4.0))
        val result = OscillatorIndicators.volumeMa.evaluate(candles, mapOf("period" to 2.0))
        val volume = result.series("volume")!!.values
        val ma = result.series("volume_ma")!!.values
        assertEquals(4.0, volume[3])
        assertTrue(ma[0].isNaN())
        assertClose(1.5, ma[1])
        assertClose(2.5, ma[2])
        assertClose(3.5, ma[3])
    }

    // ------------------------------------------------------------ 主图叠加

    @Test
    fun bollingerHandCalculation() {
        val candles = mk(closes = listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        val result = TrendIndicators.boll.evaluate(candles, mapOf("period" to 5.0, "mult" to 2.0))
        val mid = result.series("mid")!!.values
        val upper = result.series("upper")!!.values
        val lower = result.series("lower")!!.values
        assertTrue(mid[3].isNaN())
        assertClose(3.0, mid[4])
        assertClose(3.0 + 2.0 * sqrt(2.0), upper[4], 1e-9)
        assertClose(3.0 - 2.0 * sqrt(2.0), lower[4], 1e-9)
        assertEquals(1, result.zones.size)
        assertEquals("upper", result.zones[0].upperKey)
    }

    @Test
    fun envelopeHandCalculation() {
        val candles = mk(closes = List(20) { 3.0 })
        val result = TrendIndicators.envelope.evaluate(candles, mapOf("percent" to 10.0))
        assertClose(3.3, result.series("upper")!!.values[19])
        assertClose(2.7, result.series("lower")!!.values[19])
    }

    @Test
    fun donchianHandCalculation() {
        val candles = mk(
            closes = listOf(1.0, 2.0, 3.0),
            highs = listOf(1.0, 2.0, 3.0),
            lows = listOf(0.0, 1.0, 2.0)
        )
        val result = TrendIndicators.donchian.evaluate(candles, mapOf("period" to 2.0))
        val upper = result.series("upper")!!.values
        val lower = result.series("lower")!!.values
        val mid = result.series("mid")!!.values
        assertTrue(upper[0].isNaN())
        assertClose(2.0, upper[1])
        assertClose(3.0, upper[2])
        assertClose(0.0, lower[1])
        assertClose(1.0, lower[2])
        assertClose(1.0, mid[1])
        assertClose(2.0, mid[2])
    }

    @Test
    fun keltnerHandCalculation() {
        val candles = mk(
            closes = List(5) { 100.0 },
            highs = List(5) { 101.0 },
            lows = List(5) { 99.0 }
        )
        val result = TrendIndicators.keltner.evaluate(
            candles,
            mapOf("emaPeriod" to 3.0, "atrPeriod" to 3.0, "mult" to 2.0)
        )
        assertClose(104.0, result.series("upper")!!.values[4])
        assertClose(96.0, result.series("lower")!!.values[4])
        assertClose(100.0, result.series("mid")!!.values[4])
    }

    @Test
    fun vwapHandCalculation() {
        val candles = mk(
            closes = listOf(10.0, 20.0),
            highs = listOf(12.0, 22.0),
            lows = listOf(8.0, 18.0),
            volumes = listOf(1.0, 3.0)
        )
        val result = TrendIndicators.vwap.evaluate(candles, mapOf("anchor" to 1.0))
        val vwap = result.series("vwap")!!.values
        assertClose(10.0, vwap[0])
        assertClose(17.5, vwap[1])
    }

    @Test
    fun vwapResetsPerUtcDay() {
        val candles = mk(
            closes = listOf(10.0, 20.0),
            highs = listOf(10.0, 20.0),
            lows = listOf(10.0, 20.0),
            volumes = listOf(1.0, 1.0),
            startT = 1_700_006_400_000L - 60_000L,
            stepMs = 60_000L
        )
        val result = TrendIndicators.vwap.evaluate(candles, mapOf("anchor" to 2.0))
        val vwap = result.series("vwap")!!.values
        assertClose(10.0, vwap[0])
        assertClose(20.0, vwap[1])
    }

    @Test
    fun parabolicSarHandCalculation() {
        val candles = mk(
            closes = listOf(9.0, 10.0, 11.0, 11.0),
            highs = listOf(10.0, 11.0, 12.0, 11.5),
            lows = listOf(8.0, 9.0, 10.0, 9.5)
        )
        val v = TrendIndicators.parabolicSar(candles, 0.02, 0.2)
        assertTrue(v[0].isNaN())
        assertClose(8.0, v[1])
        assertClose(8.0, v[2])
        assertClose(8.16, v[3])
    }

    @Test
    fun superTrendStaysOnTrendSide() {
        val up = mk(closes = List(30) { 100.0 + it }, highs = List(30) { 100.0 + it + 1 }, lows = List(30) { 100.0 + it - 3 })
        val upResult = TrendIndicators.superTrend.evaluate(up, mapOf("period" to 3.0, "mult" to 3.0))
        val upSeries = upResult.series("supertrend")!!
        assertTrue(upSeries.values[10] < up[10].c)
        assertEquals(IndicatorColors.BULL, upSeries.colors!![10])

        val down = mk(closes = List(30) { 200.0 - it }, highs = List(30) { 200.0 - it + 3 }, lows = List(30) { 200.0 - it - 1 })
        val downResult = TrendIndicators.superTrend.evaluate(down, mapOf("period" to 3.0, "mult" to 3.0))
        val downSeries = downResult.series("supertrend")!!
        assertTrue(downSeries.values[10] > down[10].c)
        assertEquals(IndicatorColors.BEAR, downSeries.colors!![10])
    }

    @Test
    fun ichimokuShiftAndWarmup() {
        val candles = (0 until 100).map { i ->
            Candle(
                t = 1_700_000_000_000L + i * 60_000L,
                o = i + 5.0,
                h = i + 10.0,
                l = i.toDouble(),
                c = i + 5.0,
                v = 1.0
            )
        }
        val result = IchimokuIndicator.spec.evaluate(candles)
        val tenkan = result.series("tenkan")!!.values
        val kijun = result.series("kijun")!!.values
        val senkouA = result.series("senkouA")!!.values
        val senkouB = result.series("senkouB")!!.values
        val chikou = result.series("chikou")!!.values

        assertTrue(tenkan[7].isNaN())
        assertClose(9.0, tenkan[8])
        assertClose(26.0, tenkan[25])
        assertClose(17.5, kijun[25])
        assertTrue(senkouA[50].isNaN())
        assertClose(21.75, senkouA[51])
        assertTrue(senkouB[76].isNaN())
        assertClose(30.5, senkouB[77])
        assertClose(31.0, chikou[0])
        assertTrue(chikou[74].isNaN())
        assertEquals(77, IchimokuIndicator.spec.warmup())
    }

    // ------------------------------------------------------------ 回归

    @Test
    fun linearRegressionFitsPerfectLine() {
        val closes = (0 until 20).map { 3.0 + 2.0 * it }
        val values = seriesValues(RegressionIndicators.linear, mk(closes), "linreg")
        for (i in closes.indices) assertClose(closes[i], values[i], 1e-8)
    }

    @Test
    fun linearRegressionChannelHasZeroWidthOnPerfectLine() {
        val closes = (0 until 20).map { 3.0 + 2.0 * it }
        val result = RegressionIndicators.channel.evaluate(mk(closes))
        val mid = result.series("mid")!!.values
        for (key in listOf("upper1", "lower1", "upper2", "lower2")) {
            val v = result.series(key)!!.values
            for (i in closes.indices) {
                assertClose(mid[i], v[i], 1e-8)
                assertClose(closes[i], v[i], 1e-8)
            }
        }
        assertEquals(2, result.zones.size)
    }

    @Test
    fun regressionWindowLeavesNaNOutside() {
        val closes = (0 until 20).map { 100.0 + sin(it / 3.0) * 10.0 + it * 0.5 }
        val values = RegressionIndicators.linear
            .evaluate(mk(closes), mapOf("window" to 5.0))
            .series("linreg")!!.values
        for (i in 0 until 15) assertTrue(values[i].isNaN(), "index $i should be warmup")
        for (i in 15 until 20) assertTrue(values[i].isFinite(), "index $i should be fitted")
    }

    @Test
    fun polynomialRegressionFitsQuadratic() {
        val closes = (0 until 25).map { (it * it).toDouble() }
        val values = seriesValues(RegressionIndicators.polynomial, mk(closes), "poly")
        for (i in closes.indices) assertClose(closes[i], values[i], 1e-6)
    }

    @Test
    fun polynomialRegressionFitsCubic() {
        val closes = (0 until 25).map { (it * it * it).toDouble() }
        val values = RegressionIndicators.polynomial
            .evaluate(mk(closes), mapOf("degree" to 3.0))
            .series("poly")!!.values
        for (i in closes.indices) assertClose(closes[i], values[i], 1e-5)
    }

    @Test
    fun logRegressionFitsExponential() {
        val closes = (0 until 30).map { exp(2.0 + 0.1 * it) }
        val values = seriesValues(RegressionIndicators.logarithmic, mk(closes), "logreg")
        for (i in closes.indices) assertClose(closes[i], values[i], 1e-6)
    }

    // ------------------------------------------------------------ 水平线

    @Test
    fun classicPivotHandCalculation() {
        val levels = LevelIndicators.classicPivots(LevelIndicators.Hlc(110.0, 90.0, 100.0))
            .associate { it.label to it.value }
        assertClose(100.0, levels.getValue("P"))
        assertClose(110.0, levels.getValue("R1"))
        assertClose(90.0, levels.getValue("S1"))
        assertClose(120.0, levels.getValue("R2"))
        assertClose(80.0, levels.getValue("S2"))
        assertClose(130.0, levels.getValue("R3"))
        assertClose(70.0, levels.getValue("S3"))
    }

    @Test
    fun fibonacciPivotHandCalculation() {
        val levels = LevelIndicators.fibonacciPivots(LevelIndicators.Hlc(110.0, 90.0, 100.0))
            .associate { it.label to it.value }
        assertClose(100.0, levels.getValue("P"))
        assertClose(107.64, levels.getValue("R1"))
        assertClose(112.36, levels.getValue("R2"))
        assertClose(120.0, levels.getValue("R3"))
        assertClose(92.36, levels.getValue("S1"))
        assertClose(87.64, levels.getValue("S2"))
        assertClose(80.0, levels.getValue("S3"))
    }

    @Test
    fun camarillaPivotHandCalculation() {
        val levels = LevelIndicators.camarillaPivots(LevelIndicators.Hlc(110.0, 90.0, 100.0))
            .associate { it.label to it.value }
        assertClose(111.0, levels.getValue("R4"))
        assertClose(105.5, levels.getValue("R3"))
        assertClose(103.66666666666667, levels.getValue("R2"))
        assertClose(101.83333333333333, levels.getValue("R1"))
        assertClose(98.16666666666667, levels.getValue("S1"))
        assertClose(96.33333333333333, levels.getValue("S2"))
        assertClose(94.5, levels.getValue("S3"))
        assertClose(89.0, levels.getValue("S4"))
    }

    @Test
    fun pivotSpecUsesPreviousCandleBasis() {
        val candles = mk(
            closes = listOf(100.0, 100.0, 100.0),
            highs = listOf(110.0, 110.0, 110.0),
            lows = listOf(90.0, 90.0, 90.0)
        )
        val result = LevelIndicators.pivot.evaluate(candles, mapOf("basis" to 0.0, "type" to 0.0))
        val p = result.levels.first { it.label == "P" }
        assertClose(100.0, p.value)
    }

    @Test
    fun swingLevelsFindClusteredHighLow() {
        val closes = MutableList(15) { 88.0 }
        val highs = MutableList(15) { 90.0 }
        val lows = MutableList(15) { 85.0 }
        closes[5] = 95.0
        highs[5] = 100.0
        closes[10] = 85.0
        lows[10] = 80.0
        val result = LevelIndicators.swing.evaluate(
            mk(closes, highs, lows),
            mapOf("left" to 2.0, "right" to 2.0, "maxLevels" to 5.0, "mergePct" to 0.5)
        )
        val resistance = result.levels.first { it.label.startsWith("压力") }
        val support = result.levels.first { it.label.startsWith("支撑") }
        assertClose(100.0, resistance.value)
        assertClose(80.0, support.value)
        assertTrue(resistance.value > support.value)
    }

    @Test
    fun fibonacciRetracementHandCalculation() {
        val closes = MutableList(12) { 155.0 }
        val highs = MutableList(12) { 160.0 }
        val lows = MutableList(12) { 150.0 }
        closes[2] = 150.0
        lows[2] = 100.0
        closes[8] = 190.0
        highs[8] = 200.0
        val result = LevelIndicators.fibonacci.evaluate(mk(closes, highs, lows), mapOf("lookback" to 0.0))
        val levels = result.levels.associate { it.label to it.value }
        assertClose(138.2, levels.getValue("回撤 61.8%"))
        assertClose(161.8, levels.getValue("回撤 38.2%"))
        assertClose(261.8, levels.getValue("扩展 161.8%"))
        assertEquals(1, result.regions.size)
        assertClose(138.2, result.regions[0].low)
        assertClose(161.8, result.regions[0].high)
    }

    @Test
    fun volumeProfilePocAndValueArea() {
        val prices = (100..109).map { it.toDouble() }
        val volumes = prices.map { if (it == 105.0) 100.0 else 1.0 }
        val result = LevelIndicators.volumeProfile.evaluate(
            mk(prices, prices, prices, volumes),
            mapOf("bins" to 9.0, "valueArea" to 70.0, "lookback" to 0.0)
        )
        val levels = result.levels.associate { it.label to it.value }
        val poc = levels.getValue("POC")
        val vah = levels.getValue("VAH")
        val valLevel = levels.getValue("VAL")
        assertTrue(poc in 104.5..106.5, "POC=$poc")
        assertTrue(vah > valLevel)
        assertEquals(1, result.regions.size)
    }

    @Test
    fun roundLevelsExactMultiples() {
        val levels = LevelIndicators.roundLevels(10_300.0, 1_000.0, 2)
        val values = levels.map { it.value }.toSet()
        assertEquals(setOf(11_000.0, 12_000.0, 10_000.0, 9_000.0, 8_000.0), values)

        val onLevel = LevelIndicators.roundLevels(10_000.0, 1_000.0, 1)
        assertEquals(setOf(11_000.0, 9_000.0), onLevel.map { it.value }.toSet())

        assertClose(100.0, LevelIndicators.autoRoundStep(71_180.0))
        assertClose(1.0, LevelIndicators.autoRoundStep(100.0))
    }

    // ------------------------------------------------------------ 注册表 / 参数

    @Test
    fun registryEnumeratesAllIndicators() {
        assertEquals(31, IndicatorRegistry.all.size)
        assertEquals(31, IndicatorRegistry.all.map { it.id }.distinct().size)
        assertEquals(16, IndicatorRegistry.overlays.size)
        assertEquals(5, IndicatorRegistry.levels.size)
        assertEquals(10, IndicatorRegistry.oscillators.size)
        assertEquals(31, IndicatorRegistry.overlays.size + IndicatorRegistry.levels.size + IndicatorRegistry.oscillators.size)
        assertEquals(IndicatorType.OSCILLATOR, IndicatorRegistry.requireById("rsi").type)
        assertEquals(IndicatorType.LEVELS, IndicatorRegistry.requireById("pivot").type)
        assertNotNull(IndicatorRegistry.byId("macd"))
        assertNull(IndicatorRegistry.byId("not_an_indicator"))
        assertEquals(20.0, IndicatorRegistry.defaultParams("ma")!!.getValue("period"))
    }

    @Test
    fun paramsAreClampedAndChoicesValidated() {
        val ma = IndicatorRegistry.requireById("ma")
        assertEquals(20.0, ma.defaultParams.d("period"))
        assertEquals(500.0, ma.withParams(mapOf("period" to 10_000.0)).d("period"))
        assertEquals(2.0, ma.withParams(mapOf("period" to -10.0)).d("period"))

        val pivot = IndicatorRegistry.requireById("pivot")
        assertEquals(0.0, pivot.withParams(mapOf("type" to 99.0)).d("type"))
        assertEquals(2.0, pivot.withParams(mapOf("type" to 2.0)).d("type"))

        val fib = IndicatorRegistry.requireById("fib")
        assertTrue(fib.defaultParams.b("extensions"))
        assertFalse(fib.withParams(mapOf("extensions" to 0.0)).b("extensions"))
    }

    @Test
    fun warmupPrefixesAreNaN() {
        val candles = mk(List(320) { 100.0 + it * 0.3 + sin(it / 5.0) * 4.0 })

        fun check(spec: IndicatorSpec, key: String, warmup: Int) {
            val values = spec.evaluate(candles).series(key)!!.values
            for (i in 0 until warmup) assertTrue(values[i].isNaN(), "${spec.id}/$key index $i should be NaN")
            assertTrue(values[warmup].isFinite(), "${spec.id}/$key index $warmup should be valid")
        }

        check(TrendIndicators.ma, "ma", 19)
        check(TrendIndicators.maGroup, "ma250", 249)
        check(OscillatorIndicators.rsi, "rsi", 14)
        check(OscillatorIndicators.atr, "atr", 13)
        check(OscillatorIndicators.macd, "hist", 33)
        check(OscillatorIndicators.stochRsi, "d", 31)
        check(OscillatorIndicators.kdj, "k", 8)
    }

    @Test
    fun allRegistrySpecsProduceAlignedResults() {
        val candles = (0 until 320).map { i ->
            val base = 100.0 + i * 0.4 + sin(i / 6.0) * 8.0
            val close = base + sin(i / 2.3) * 2.0
            Candle(
                t = 1_700_000_000_000L + i * 60_000L,
                o = base,
                h = maxOf(base, close) + 6.0,
                l = minOf(base, close) - 6.0,
                c = close,
                v = 50.0 + (i % 23)
            )
        }
        for (spec in IndicatorRegistry.all) {
            val result = spec.evaluate(candles)
            assertEquals(result.series.size, result.series.map { it.key }.distinct().size, "${spec.id}: duplicate series key")
            val keys = result.series.map { it.key }.toSet()
            for (s in result.series) {
                assertEquals(candles.size, s.values.size, "${spec.id}/${s.key}: length")
                for (v in s.values) assertTrue(v.isNaN() || v.isFinite(), "${spec.id}/${s.key}: bad value $v")
                s.colors?.let { assertEquals(candles.size, it.size, "${spec.id}/${s.key}: colors length") }
            }
            for (level in result.levels) assertTrue(level.value.isFinite(), "${spec.id}: level ${level.label}")
            for (region in result.regions) {
                assertTrue(region.low.isFinite() && region.high.isFinite(), "${spec.id}: region")
                assertTrue(region.low <= region.high, "${spec.id}: region low>high")
            }
            for (zone in result.zones) {
                assertTrue(zone.upperKey in keys, "${spec.id}: zone upper ${zone.upperKey}")
                assertTrue(zone.lowerKey in keys, "${spec.id}: zone lower ${zone.lowerKey}")
            }
            for (guide in result.guides) assertTrue(guide.value.isFinite(), "${spec.id}: guide ${guide.label}")
        }
    }

    @Test
    fun macdHistogramColorsMatchSign() {
        val candles = mk((0 until 80).map { 100.0 + it * 0.5 + sin(it / 3.0) * 4.0 })
        val hist = OscillatorIndicators.macd.evaluate(candles).series("hist")!!
        assertNotNull(hist.colors)
        for (i in hist.values.indices) {
            val v = hist.values[i]
            if (v.isNaN()) continue
            val expected = if (v >= 0.0) IndicatorColors.HIST_UP else IndicatorColors.HIST_DOWN
            assertEquals(expected, hist.colors!![i])
        }
    }

    // ------------------------------------------------------------ 缓存

    @Test
    fun cacheReusesByVersionAndParams() {
        val candles = mk(List(60) { 100.0 + sin(it / 4.0) * 5.0 })
        val cache = IndicatorCache(capacity = 4)
        val spec = IndicatorRegistry.requireById("rsi")
        val p = spec.defaultParams

        val a = cache.get(spec, candles, p, 1L)
        val b = cache.get(spec, candles, p, 1L)
        assertSame(a, b)
        assertEquals(1, cache.size)

        val c = cache.get(spec, candles, p, 2L)
        assertNotSame(a, c)
        assertEquals(2, cache.size)

        val p7 = spec.withParams(mapOf("period" to 7.0))
        val d = cache.get(spec, candles, p7, 2L)
        assertNotSame(c, d)
        assertEquals(3, cache.size)
        assertEquals(setOf(1L, 2L), cache.versionsOf("rsi"))

        cache.invalidate("rsi")
        assertEquals(0, cache.size)
    }

    @Test
    fun cacheFingerprintDetectsLastCandleChange() {
        val candles = mk(List(20) { 100.0 + it })
        val f1 = IndicatorCache.fingerprint(candles)
        val f2 = IndicatorCache.fingerprint(candles)
        assertEquals(f1, f2)

        val changed = candles.toMutableList()
        changed[changed.lastIndex] = changed.last().copy(c = changed.last().c + 1.0)
        assertNotEquals(f1, IndicatorCache.fingerprint(changed))

        val cache = IndicatorCache()
        val spec = IndicatorRegistry.requireById("ema")
        val p = spec.defaultParams
        assertSame(cache.get(spec, candles, p), cache.get(spec, candles, p))
        assertNotSame(cache.get(spec, candles, p), cache.get(spec, changed, p))
    }

    @Test
    fun lruEvictsOldEntries() {
        val candles = mk(List(30) { 100.0 + it })
        val cache = IndicatorCache(capacity = 2)
        val spec = IndicatorRegistry.requireById("ema")
        val p = spec.defaultParams
        cache.get(spec, candles, p, 1L)
        cache.get(spec, candles, p, 2L)
        cache.get(spec, candles, p, 3L)
        assertEquals(2, cache.size)
    }
}
