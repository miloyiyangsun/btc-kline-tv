package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 序列计算工具。约定：输入输出都与 candles 索引对齐；无效值用 NaN；
 * 带 period 的函数会自动跳过前导 NaN（用于 MACD signal、StochRSI 等二次平滑）。
 */
object SeriesMath {

    fun firstValid(src: DoubleArray): Int {
        for (i in src.indices) if (!src[i].isNaN()) return i
        return -1
    }

    fun lastValid(src: DoubleArray): Double {
        for (i in src.indices.reversed()) if (!src[i].isNaN()) return src[i]
        return Double.NaN
    }

    fun open(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].o }

    fun high(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].h }

    fun low(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].l }

    fun close(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].c }

    fun volume(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].v }

    fun hl2(candles: List<Candle>): DoubleArray =
        DoubleArray(candles.size) { (candles[it].h + candles[it].l) / 2.0 }

    fun hlc3(candles: List<Candle>): DoubleArray =
        DoubleArray(candles.size) { (candles[it].h + candles[it].l + candles[it].c) / 3.0 }

    fun ohlc4(candles: List<Candle>): DoubleArray =
        DoubleArray(candles.size) {
            (candles[it].o + candles[it].h + candles[it].l + candles[it].c) / 4.0
        }

    fun sma(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0) return out
        var sum = 0.0
        for (i in start until src.size) {
            sum += src[i]
            if (i - start >= period) sum -= src[i - period]
            if (i - start >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** 标准 EMA：以首个 period 根 SMA 为种子，之前为 NaN。 */
    fun ema(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0 || start + period > src.size) return out
        var seed = 0.0
        for (i in start until start + period) seed += src[i]
        var prev = seed / period
        out[start + period - 1] = prev
        val k = 2.0 / (period + 1.0)
        for (i in start + period until src.size) {
            prev = src[i] * k + prev * (1.0 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder 平滑（RMA）：alpha = 1/period。 */
    fun rma(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0 || start + period > src.size) return out
        var seed = 0.0
        for (i in start until start + period) seed += src[i]
        var prev = seed / period
        out[start + period - 1] = prev
        for (i in start + period until src.size) {
            prev = (prev * (period - 1) + src[i]) / period
            out[i] = prev
        }
        return out
    }

    fun wma(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0) return out
        val denom = period * (period + 1) / 2.0
        for (i in start + period - 1 until src.size) {
            var acc = 0.0
            for (k in 0 until period) acc += src[i - k] * (period - k)
            out[i] = acc / denom
        }
        return out
    }

    /** 滚动标准差；默认总体标准差（布林带口径）。 */
    fun stdev(src: DoubleArray, period: Int, sample: Boolean = false): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0) return out
        var sum = 0.0
        var sumSq = 0.0
        val div = if (sample) (period - 1).coerceAtLeast(1) else period
        for (i in start until src.size) {
            val v = src[i]
            sum += v
            sumSq += v * v
            if (i - start >= period) {
                val old = src[i - period]
                sum -= old
                sumSq -= old * old
            }
            if (i - start >= period - 1) {
                val variance = sumSq / period - (sum / period) * (sum / period)
                out[i] = sqrt(max(0.0, variance * period / div))
            }
        }
        return out
    }

    fun highest(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0) return out
        for (i in start + period - 1 until src.size) {
            var m = Double.NEGATIVE_INFINITY
            for (j in i - period + 1..i) if (src[j] > m) m = src[j]
            out[i] = m
        }
        return out
    }

    fun lowest(src: DoubleArray, period: Int): DoubleArray {
        val out = na(src.size)
        if (period <= 0) return out
        val start = firstValid(src)
        if (start < 0) return out
        for (i in start + period - 1 until src.size) {
            var m = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) if (src[j] < m) m = src[j]
            out[i] = m
        }
        return out
    }

    fun trueRange(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val out = na(n)
        if (n == 0) return out
        for (i in 0 until n) {
            val c = candles[i]
            out[i] = if (i == 0) {
                c.h - c.l
            } else {
                val pc = candles[i - 1].c
                max(c.h - c.l, max(abs(c.h - pc), abs(c.l - pc)))
            }
        }
        return out
    }

    fun atr(candles: List<Candle>, period: Int): DoubleArray = rma(trueRange(candles), period)

    /** out[i] = values[i - bars]，用于一目均衡表的前移线。 */
    fun shiftForward(values: DoubleArray, bars: Int): DoubleArray {
        val out = na(values.size)
        if (bars < 0) return out
        for (i in bars until values.size) out[i] = values[i - bars]
        return out
    }

    /** out[i] = values[i + bars]，用于迟行线。 */
    fun shiftBackward(values: DoubleArray, bars: Int): DoubleArray {
        val out = na(values.size)
        if (bars < 0) return out
        for (i in 0 until values.size - bars) out[i] = values[i + bars]
        return out
    }

    /** K 线时间戳的中位间隔（毫秒），用于判断周期。 */
    fun medianStep(candles: List<Candle>): Long {
        if (candles.size < 2) return 0L
        val diffs = LongArray(candles.size - 1)
        for (i in 1 until candles.size) diffs[i - 1] = candles[i].t - candles[i - 1].t
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    fun signColors(values: DoubleArray, positive: Long, negative: Long): LongArray =
        LongArray(values.size) {
            val v = values[it]
            when {
                v.isNaN() -> 0L
                v >= 0.0 -> positive
                else -> negative
            }
        }

    fun allNaN(n: Int): DoubleArray = na(n)

    fun clampIndex(index: Int, size: Int): Int = index.coerceIn(0, (size - 1).coerceAtLeast(0))

    /** 取 [from, to] 区间内最小/最大值，忽略 NaN。 */
    fun minMax(values: DoubleArray, from: Int, to: Int): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(values.size - 1)) {
            val v = values[i]
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (lo == Double.POSITIVE_INFINITY) Pair(Double.NaN, Double.NaN) else Pair(lo, hi)
    }

    fun maxAbsDeviation(values: DoubleArray, from: Int, to: Int, center: Double): Double {
        var sum = 0.0
        var count = 0
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(values.size - 1)) {
            val v = values[i]
            if (v.isNaN()) continue
            sum += abs(v - center)
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }

    fun safeDiv(a: Double, b: Double, fallback: Double = Double.NaN): Double =
        if (b == 0.0 || b.isNaN() || a.isNaN()) fallback else a / b

    fun lastClose(candles: List<Candle>): Double = candles.lastOrNull()?.c ?: Double.NaN

    fun finiteOrNull(v: Double): Double? = if (v.isFinite()) v else null

    fun minOf(values: DoubleArray, from: Int = 0): Double {
        var m = Double.POSITIVE_INFINITY
        for (i in from until values.size) if (!values[i].isNaN() && values[i] < m) m = values[i]
        return if (m == Double.POSITIVE_INFINITY) Double.NaN else m
    }

    fun maxOf(values: DoubleArray, from: Int = 0): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in from until values.size) if (!values[i].isNaN() && values[i] > m) m = values[i]
        return if (m == Double.NEGATIVE_INFINITY) Double.NaN else m
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
}
