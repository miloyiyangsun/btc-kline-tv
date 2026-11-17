package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.max
import kotlin.math.min

/** 主图趋势 / 通道类指标。 */
object TrendIndicators {

    private val period20 = ParamSpec.number("period", "周期", 20.0, 2.0, 500.0)

    val ma: IndicatorSpec = specOf(
        id = "ma",
        name = "均线 MA",
        type = IndicatorType.OVERLAY,
        params = listOf(period20),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val values = SeriesMath.sma(SeriesMath.close(candles), period)
        IndicatorResult(series = listOf(line("ma", "MA$period", values, IndicatorColors.MA)))
    }

    val ema: IndicatorSpec = specOf(
        id = "ema",
        name = "指数均线 EMA",
        type = IndicatorType.OVERLAY,
        params = listOf(period20),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val values = SeriesMath.ema(SeriesMath.close(candles), period)
        IndicatorResult(series = listOf(line("ema", "EMA$period", values, IndicatorColors.MA_FAST)))
    }

    val wma: IndicatorSpec = specOf(
        id = "wma",
        name = "加权均线 WMA",
        type = IndicatorType.OVERLAY,
        params = listOf(period20),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val values = SeriesMath.wma(SeriesMath.close(candles), period)
        IndicatorResult(series = listOf(line("wma", "WMA$period", values, IndicatorColors.MA_SLOW)))
    }

    /** 固定均线组，颜色由快到慢渐变。 */
    val maGroup: IndicatorSpec = specOf(
        id = "ma_group",
        name = "均线组 5/10/20/60/120/250",
        type = IndicatorType.OVERLAY,
        warmup = { 249 }
    ) { candles, _ ->
        val close = SeriesMath.close(candles)
        val periods = intArrayOf(5, 10, 20, 60, 120, 250)
        val colors = longArrayOf(
            0xFF4FC3F7, 0xFF81C784, 0xFFFFD54F, 0xFFFF8A65, 0xFFBA68C8, 0xFF90A4AE
        )
        val series = periods.mapIndexed { idx, period ->
            line(
                key = "ma$period",
                label = "MA$period",
                values = SeriesMath.sma(close, period),
                color = colors[idx],
                width = 1f
            )
        }
        IndicatorResult(series = series)
    }

    val boll: IndicatorSpec = specOf(
        id = "boll",
        name = "布林带 BOLL",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("period", "周期", 20.0, 2.0, 500.0),
            ParamSpec.number("mult", "标准差倍数", 2.0, 0.1, 10.0, 0.1)
        ),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val mult = p.d("mult")
        val close = SeriesMath.close(candles)
        val mid = SeriesMath.sma(close, period)
        val sd = SeriesMath.stdev(close, period)
        val upper = DoubleArray(close.size) { mid[it] + mult * sd[it] }
        val lower = DoubleArray(close.size) { mid[it] - mult * sd[it] }
        IndicatorResult(
            series = listOf(
                line("upper", "上轨", upper, IndicatorColors.BAND, width = 1f),
                line("mid", "中轨", mid, IndicatorColors.NEUTRAL, width = 1f, dash = true),
                line("lower", "下轨", lower, IndicatorColors.BAND, width = 1f)
            ),
            zones = listOf(IndicatorZone("upper", "lower", IndicatorColors.BAND, alpha = 0.08f))
        )
    }

    private val vwapAnchor = ParamSpec.choice(
        "anchor", "锚定方式", 0.0,
        ParamChoice("自动", 0.0),
        ParamChoice("全部K线", 1.0),
        ParamChoice("UTC 日", 2.0)
    )

    val vwap: IndicatorSpec = specOf(
        id = "vwap",
        name = "成交量加权均价 VWAP",
        type = IndicatorType.OVERLAY,
        params = listOf(vwapAnchor),
        warmup = { 0 }
    ) { candles, p ->
        val anchor = p.i("anchor")
        val n = candles.size
        val out = na(n)
        if (n == 0) return@specOf IndicatorResult(
            series = listOf(line("vwap", "VWAP", out, IndicatorColors.MA_FAST))
        )
        val resetDaily = when (anchor) {
            1 -> false
            2 -> true
            else -> {
                val step = SeriesMath.medianStep(candles)
                step > 0 && step < DAY_MS
            }
        }
        var cumPv = 0.0
        var cumV = 0.0
        var day = if (resetDaily) Math.floorDiv(candles[0].t, DAY_MS) else Long.MIN_VALUE
        for (i in 0 until n) {
            val c = candles[i]
            if (resetDaily) {
                val d = Math.floorDiv(c.t, DAY_MS)
                if (d != day) {
                    day = d
                    cumPv = 0.0
                    cumV = 0.0
                }
            }
            val tp = (c.h + c.l + c.c) / 3.0
            cumPv += tp * c.v
            cumV += c.v
            out[i] = if (cumV > 0.0) cumPv / cumV else tp
        }
        IndicatorResult(series = listOf(line("vwap", "VWAP", out, IndicatorColors.MA_FAST, width = 1.2f)))
    }

    val sar: IndicatorSpec = specOf(
        id = "sar",
        name = "抛物线 SAR",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("step", "步长", 0.02, 0.001, 0.1, 0.001),
            ParamSpec.number("max", "最大步长", 0.2, 0.05, 1.0, 0.01)
        ),
        warmup = { 1 }
    ) { candles, p ->
        val values = parabolicSar(candles, p.d("step"), p.d("max"))
        IndicatorResult(series = listOf(line("sar", "SAR", values, IndicatorColors.MA_SLOW, SeriesStyle.DOTS, width = 1.5f)))
    }

    val donchian: IndicatorSpec = specOf(
        id = "donchian",
        name = "唐奇安通道",
        type = IndicatorType.OVERLAY,
        params = listOf(ParamSpec.number("period", "周期", 20.0, 2.0, 500.0)),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val upper = SeriesMath.highest(SeriesMath.high(candles), period)
        val lower = SeriesMath.lowest(SeriesMath.low(candles), period)
        val mid = DoubleArray(candles.size) { (upper[it] + lower[it]) / 2.0 }
        IndicatorResult(
            series = listOf(
                line("upper", "上轨", upper, IndicatorColors.LINE_1, width = 1f),
                line("mid", "中轨", mid, IndicatorColors.NEUTRAL, width = 1f, dash = true),
                line("lower", "下轨", lower, IndicatorColors.LINE_1, width = 1f)
            ),
            zones = listOf(IndicatorZone("upper", "lower", IndicatorColors.LINE_1, alpha = 0.07f))
        )
    }

    val keltner: IndicatorSpec = specOf(
        id = "keltner",
        name = "肯特纳通道 Keltner",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("emaPeriod", "EMA 周期", 20.0, 2.0, 500.0),
            ParamSpec.number("atrPeriod", "ATR 周期", 10.0, 1.0, 500.0),
            ParamSpec.number("mult", "ATR 倍数", 2.0, 0.1, 10.0, 0.1)
        ),
        warmup = { maxOf(it.i("emaPeriod"), it.i("atrPeriod")) - 1 }
    ) { candles, p ->
        val mid = SeriesMath.ema(SeriesMath.close(candles), p.i("emaPeriod"))
        val atr = SeriesMath.atr(candles, p.i("atrPeriod"))
        val mult = p.d("mult")
        val upper = DoubleArray(candles.size) { mid[it] + mult * atr[it] }
        val lower = DoubleArray(candles.size) { mid[it] - mult * atr[it] }
        IndicatorResult(
            series = listOf(
                line("upper", "上轨", upper, IndicatorColors.LINE_2, width = 1f),
                line("mid", "中轨", mid, IndicatorColors.NEUTRAL, width = 1f, dash = true),
                line("lower", "下轨", lower, IndicatorColors.LINE_2, width = 1f)
            ),
            zones = listOf(IndicatorZone("upper", "lower", IndicatorColors.LINE_2, alpha = 0.07f))
        )
    }

    val envelope: IndicatorSpec = specOf(
        id = "envelope",
        name = "包络线 Envelope",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("period", "周期", 20.0, 2.0, 500.0),
            ParamSpec.number("percent", "偏离百分比", 2.5, 0.1, 25.0, 0.1, "%")
        ),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val mid = SeriesMath.sma(SeriesMath.close(candles), p.i("period"))
        val k = p.d("percent") / 100.0
        val upper = DoubleArray(candles.size) { mid[it] * (1.0 + k) }
        val lower = DoubleArray(candles.size) { mid[it] * (1.0 - k) }
        IndicatorResult(
            series = listOf(
                line("upper", "上轨", upper, IndicatorColors.LINE_3, width = 1f),
                line("mid", "中轨", mid, IndicatorColors.NEUTRAL, width = 1f, dash = true),
                line("lower", "下轨", lower, IndicatorColors.LINE_3, width = 1f)
            ),
            zones = listOf(IndicatorZone("upper", "lower", IndicatorColors.LINE_3, alpha = 0.06f))
        )
    }

    val superTrend: IndicatorSpec = specOf(
        id = "supertrend",
        name = "超级趋势 SuperTrend",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("period", "ATR 周期", 10.0, 1.0, 200.0),
            ParamSpec.number("mult", "ATR 倍数", 3.0, 0.5, 10.0, 0.1)
        ),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val st = superTrend(candles, p.i("period"), p.d("mult"))
        val colors = LongArray(candles.size) { i ->
            when {
                st.values[i].isNaN() -> 0L
                st.up[i] -> IndicatorColors.BULL
                else -> IndicatorColors.BEAR
            }
        }
        IndicatorResult(
            series = listOf(
                line(
                    "supertrend", "SuperTrend", st.values, IndicatorColors.BULL,
                    width = 1.6f, colors = colors
                )
            )
        )
    }

    val all: List<IndicatorSpec> = listOf(
        ma, ema, wma, maGroup, boll, vwap, sar, donchian, keltner, envelope, superTrend
    )

    // ---- 具体算法 ----

    internal fun parabolicSar(candles: List<Candle>, step: Double, maxStep: Double): DoubleArray {
        val n = candles.size
        val out = na(n)
        if (n < 2) return out
        var up = candles[1].c >= candles[0].c
        var af = step
        var ep = if (up) max(candles[0].h, candles[1].h) else min(candles[0].l, candles[1].l)
        var sar = if (up) min(candles[0].l, candles[1].l) else max(candles[0].h, candles[1].h)
        out[1] = sar
        for (i in 2 until n) {
            sar += af * (ep - sar)
            if (up) {
                sar = min(sar, candles[i - 1].l)
                sar = min(sar, candles[i - 2].l)
                if (candles[i].l < sar) {
                    up = false
                    sar = ep
                    ep = candles[i].l
                    af = step
                } else if (candles[i].h > ep) {
                    ep = candles[i].h
                    af = min(maxStep, af + step)
                }
            } else {
                sar = max(sar, candles[i - 1].h)
                sar = max(sar, candles[i - 2].h)
                if (candles[i].h > sar) {
                    up = true
                    sar = ep
                    ep = candles[i].h
                    af = step
                } else if (candles[i].l < ep) {
                    ep = candles[i].l
                    af = min(maxStep, af + step)
                }
            }
            out[i] = sar
        }
        return out
    }

    internal class SuperTrendResult(val values: DoubleArray, val up: BooleanArray)

    internal fun superTrend(candles: List<Candle>, period: Int, mult: Double): SuperTrendResult {
        val n = candles.size
        val values = na(n)
        val up = BooleanArray(n)
        if (n == 0) return SuperTrendResult(values, up)
        val atr = SeriesMath.atr(candles, period)
        val upper = na(n)
        val lower = na(n)
        val finalUpper = na(n)
        val finalLower = na(n)
        for (i in 0 until n) {
            val hl2 = (candles[i].h + candles[i].l) / 2.0
            upper[i] = hl2 + mult * atr[i]
            lower[i] = hl2 - mult * atr[i]
        }
        val first = atr.indexOfFirst { !it.isNaN() }
        if (first < 0) return SuperTrendResult(values, up)
        var dirUp = candles[first].c >= (candles[first].h + candles[first].l) / 2.0
        for (i in first until n) {
            if (i == first) {
                finalUpper[i] = upper[i]
                finalLower[i] = lower[i]
            } else {
                finalUpper[i] = if (upper[i] < finalUpper[i - 1] || candles[i - 1].c > finalUpper[i - 1]) {
                    upper[i]
                } else {
                    finalUpper[i - 1]
                }
                finalLower[i] = if (lower[i] > finalLower[i - 1] || candles[i - 1].c < finalLower[i - 1]) {
                    lower[i]
                } else {
                    finalLower[i - 1]
                }
                dirUp = when {
                    candles[i].c > finalUpper[i - 1] -> true
                    candles[i].c < finalLower[i - 1] -> false
                    else -> dirUp
                }
            }
            up[i] = dirUp
            values[i] = if (dirUp) finalLower[i] else finalUpper[i]
        }
        return SuperTrendResult(values, up)
    }

    private const val DAY_MS = 86_400_000L
}
