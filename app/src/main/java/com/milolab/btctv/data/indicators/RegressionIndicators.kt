package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** 回归类指标：窗口 0 表示全部传入的 K 线（即“全部可见”）。 */
object RegressionIndicators {

    private val windowParam = ParamSpec.number("window", "回归窗口（0=全部）", 0.0, 0.0, 2000.0, 1.0, "根")

    val linear: IndicatorSpec = specOf(
        id = "linreg",
        name = "线性回归线",
        type = IndicatorType.OVERLAY,
        params = listOf(windowParam),
        warmup = { 1 }
    ) { candles, p ->
        val values = linearValues(SeriesMath.close(candles), p.i("window"))
        IndicatorResult(
            series = listOf(line("linreg", "回归线", values, IndicatorColors.LINE_1, width = 1.4f))
        )
    }

    val channel: IndicatorSpec = specOf(
        id = "linreg_channel",
        name = "线性回归通道 ±1/2σ",
        type = IndicatorType.OVERLAY,
        params = listOf(windowParam),
        warmup = { 1 }
    ) { candles, p ->
        val close = SeriesMath.close(candles)
        val start = windowStart(close.size, p.i("window"))
        val model = fitLinear(close, start, close.size - 1)
        val n = close.size
        val mid = na(n)
        val up1 = na(n)
        val up2 = na(n)
        val lo1 = na(n)
        val lo2 = na(n)
        if (model != null) {
            val sd = max(0.0, model.residualStd)
            for (i in start until n) {
                val v = model.at(i)
                if (v.isNaN()) continue
                mid[i] = v
                up1[i] = v + sd
                lo1[i] = v - sd
                up2[i] = v + 2.0 * sd
                lo2[i] = v - 2.0 * sd
            }
        }
        IndicatorResult(
            series = listOf(
                line("upper2", "+2σ", up2, IndicatorColors.LINE_2, width = 0.8f, dash = true),
                line("upper1", "+1σ", up1, IndicatorColors.LINE_2, width = 1f),
                line("mid", "回归线", mid, IndicatorColors.LINE_1, width = 1.5f),
                line("lower1", "-1σ", lo1, IndicatorColors.LINE_2, width = 1f),
                line("lower2", "-2σ", lo2, IndicatorColors.LINE_2, width = 0.8f, dash = true)
            ),
            zones = listOf(
                IndicatorZone("upper1", "lower1", IndicatorColors.LINE_2, alpha = 0.08f),
                IndicatorZone("upper2", "lower2", IndicatorColors.LINE_2, alpha = 0.04f)
            )
        )
    }

    val polynomial: IndicatorSpec = specOf(
        id = "polyreg",
        name = "多项式回归",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.choice(
                "degree", "阶数", 2.0,
                ParamChoice("二次", 2.0),
                ParamChoice("三次", 3.0)
            ),
            windowParam
        ),
        warmup = { it.i("degree") }
    ) { candles, p ->
        val degree = p.i("degree")
        val values = polynomialValues(SeriesMath.close(candles), p.i("window"), degree)
        IndicatorResult(
            series = listOf(
                line("poly", "${if (degree == 2) "二次" else "三次"}回归", values, IndicatorColors.LINE_3, width = 1.6f)
            )
        )
    }

    val logarithmic: IndicatorSpec = specOf(
        id = "logreg",
        name = "对数回归（指数趋势）",
        type = IndicatorType.OVERLAY,
        params = listOf(windowParam),
        warmup = { 1 }
    ) { candles, p ->
        val values = logValues(SeriesMath.close(candles), p.i("window"))
        IndicatorResult(
            series = listOf(line("logreg", "对数回归", values, IndicatorColors.LINE_4, width = 1.5f))
        )
    }

    val all: List<IndicatorSpec> = listOf(linear, channel, polynomial, logarithmic)

    // ---- 拟合工具 ----

    internal fun windowStart(n: Int, lookback: Int): Int =
        if (lookback <= 0) 0 else max(0, n - lookback)

    internal class LinearModel(
        private val origin: Double,
        private val intercept: Double,
        private val slope: Double,
        val residualStd: Double
    ) {
        fun at(index: Int): Double = intercept + slope * (index - origin)
    }

    internal fun fitLinear(values: DoubleArray, from: Int, to: Int): LinearModel? {
        if (from < 0 || to >= values.size || to <= from) return null
        val origin = (from + to) / 2.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        var count = 0
        for (i in from..to) {
            val y = values[i]
            if (y.isNaN()) continue
            val x = i - origin
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
            count++
        }
        if (count < 2) return null
        val denom = count * sxx - sx * sx
        if (denom == 0.0) return null
        val slope = (count * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / count
        val model = LinearModel(origin, intercept, slope, 0.0)
        var sumSq = 0.0
        var used = 0
        for (i in from..to) {
            val y = values[i]
            if (y.isNaN()) continue
            val d = y - model.at(i)
            sumSq += d * d
            used++
        }
        val sd = if (used > 1) sqrt(sumSq / used) else 0.0
        return LinearModel(origin, intercept, slope, sd)
    }

    internal fun linearValues(values: DoubleArray, lookback: Int): DoubleArray {
        val out = na(values.size)
        if (values.size < 2) return out
        val from = windowStart(values.size, lookback)
        val model = fitLinear(values, from, values.size - 1) ?: return out
        for (i in from until values.size) out[i] = model.at(i)
        return out
    }

    internal fun logValues(values: DoubleArray, lookback: Int): DoubleArray {
        val out = na(values.size)
        if (values.size < 2) return out
        val from = windowStart(values.size, lookback)
        val logged = DoubleArray(values.size) { i ->
            val v = values[i]
            if (v > 0.0 && !v.isNaN()) ln(v) else Double.NaN
        }
        val model = fitLinear(logged, from, values.size - 1) ?: return out
        for (i in from until values.size) out[i] = if (values[i] > 0.0) exp(model.at(i)) else Double.NaN
        return out
    }

    internal fun polynomialValues(values: DoubleArray, lookback: Int, degree: Int): DoubleArray {
        val out = na(values.size)
        val n = values.size
        if (n < degree + 1) return out
        val from = windowStart(n, lookback)
        if (from + degree >= n) return out
        val mid = (from + n - 1) / 2.0
        val scale = max(1.0, (n - 1 - from) / 2.0)

        fun tOf(index: Int): Double = (index - mid) / scale

        val size = degree + 1
        val matrix = Array(size) { DoubleArray(size) }
        val rhs = DoubleArray(size)
        var used = 0
        for (i in from until n) {
            val y = values[i]
            if (y.isNaN()) continue
            val t = tOf(i)
            val powers = DoubleArray(2 * degree + 1)
            powers[0] = 1.0
            for (k in 1..2 * degree) powers[k] = powers[k - 1] * t
            for (row in 0..degree) {
                for (col in 0..degree) matrix[row][col] += powers[row + col]
                rhs[row] += y * powers[row]
            }
            used++
        }
        if (used < size) return out
        val coeff = solve(matrix, rhs) ?: return out
        for (i in from until n) {
            val t = tOf(i)
            var value = 0.0
            var power = 1.0
            for (k in 0..degree) {
                value += coeff[k] * power
                power *= t
            }
            out[i] = value
        }
        return out
    }

    /** 部分主元高斯消元；奇异矩阵返回 null。 */
    internal fun solve(matrixIn: Array<DoubleArray>, rhsIn: DoubleArray): DoubleArray? {
        val n = matrixIn.size
        val a = Array(n) { matrixIn[it].copyOf() }
        val b = rhsIn.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
            }
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmpRow = a[col]
                a[col] = a[pivot]
                a[pivot] = tmpRow
                val tmp = b[col]
                b[col] = b[pivot]
                b[pivot] = tmp
            }
            val div = a[col][col]
            for (j in col until n) a[col][j] /= div
            b[col] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor == 0.0) continue
                for (j in col until n) a[row][j] -= factor * a[col][j]
                b[row] -= factor * b[col]
            }
        }
        return b
    }
}
