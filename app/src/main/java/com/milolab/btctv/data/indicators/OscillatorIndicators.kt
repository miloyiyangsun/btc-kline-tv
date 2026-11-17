package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle

/** 副图振荡指标。 */
object OscillatorIndicators {

    private fun guide(label: String, value: Double, color: Long = 0xFF546E7A) =
        IndicatorLevel(label, value, color, LevelStyle.DASHED, 0.8f)

    val rsi: IndicatorSpec = specOf(
        id = "rsi",
        name = "RSI 相对强弱",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "周期", 14.0, 2.0, 200.0)),
        range = ValueRange(0.0, 100.0),
        warmup = { it.i("period") }
    ) { candles, p ->
        val values = rsiValues(SeriesMath.close(candles), p.i("period"))
        IndicatorResult(
            series = listOf(line("rsi", "RSI${p.i("period")}", values, IndicatorColors.LINE_1, width = 1.3f)),
            guides = listOf(
                guide("超买 70", 70.0, IndicatorColors.BEAR),
                guide("中轴 50", 50.0),
                guide("超卖 30", 30.0, IndicatorColors.BULL)
            )
        )
    }

    val macd: IndicatorSpec = specOf(
        id = "macd",
        name = "MACD",
        type = IndicatorType.OSCILLATOR,
        params = listOf(
            ParamSpec.number("fast", "快线", 12.0, 2.0, 100.0),
            ParamSpec.number("slow", "慢线", 26.0, 3.0, 200.0),
            ParamSpec.number("signal", "信号线", 9.0, 2.0, 100.0)
        ),
        warmup = { p -> p.i("slow") + p.i("signal") - 2 }
    ) { candles, p ->
        val fastP = p.i("fast")
        val slowP = p.i("slow")
        val sigP = p.i("signal")
        val (macd, signal, hist) = macdValues(SeriesMath.close(candles), fastP, slowP, sigP)
        val histColors = SeriesMath.signColors(hist, IndicatorColors.HIST_UP, IndicatorColors.HIST_DOWN)
        IndicatorResult(
            series = listOf(
                line("hist", "柱", hist, IndicatorColors.HIST_UP, SeriesStyle.HISTOGRAM, colors = histColors),
                line("macd", "DIF", macd, IndicatorColors.FAST, width = 1.3f),
                line("signal", "DEA", signal, IndicatorColors.SIGNAL, width = 1.3f)
            ),
            guides = listOf(guide("零轴", 0.0))
        )
    }

    val kdj: IndicatorSpec = specOf(
        id = "kdj",
        name = "KDJ 随机指标",
        type = IndicatorType.OSCILLATOR,
        params = listOf(
            ParamSpec.number("period", "RSV 周期", 9.0, 2.0, 200.0),
            ParamSpec.number("kSmooth", "K 平滑", 3.0, 1.0, 50.0),
            ParamSpec.number("dSmooth", "D 平滑", 3.0, 1.0, 50.0)
        ),
        warmup = { p -> p.i("period") - 1 }
    ) { candles, p ->
        val result = kdjValues(candles, p.i("period"), p.i("kSmooth"), p.i("dSmooth"))
        IndicatorResult(
            series = listOf(
                line("k", "K", result.k, IndicatorColors.FAST, width = 1.2f),
                line("d", "D", result.d, IndicatorColors.SLOW, width = 1.2f),
                line("j", "J", result.j, IndicatorColors.SIGNAL, width = 1f, dash = true)
            ),
            guides = listOf(
                guide("超买 80", 80.0, IndicatorColors.BEAR),
                guide("超卖 20", 20.0, IndicatorColors.BULL)
            )
        )
    }

    val atr: IndicatorSpec = specOf(
        id = "atr",
        name = "ATR 平均真实波幅",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "周期", 14.0, 1.0, 200.0)),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val values = SeriesMath.atr(candles, p.i("period"))
        IndicatorResult(
            series = listOf(line("atr", "ATR${p.i("period")}", values, IndicatorColors.LINE_2, width = 1.3f, fillAlpha = 0.08f, style = SeriesStyle.LINE))
        )
    }

    val obv: IndicatorSpec = specOf(
        id = "obv",
        name = "OBV 能量潮",
        type = IndicatorType.OSCILLATOR,
        warmup = { 0 }
    ) { candles, _ ->
        val n = candles.size
        val out = na(n)
        if (n > 0) {
            out[0] = 0.0
            for (i in 1 until n) {
                out[i] = when {
                    candles[i].c > candles[i - 1].c -> out[i - 1] + candles[i].v
                    candles[i].c < candles[i - 1].c -> out[i - 1] - candles[i].v
                    else -> out[i - 1]
                }
            }
        }
        IndicatorResult(series = listOf(line("obv", "OBV", out, IndicatorColors.LINE_3, width = 1.3f)))
    }

    val cci: IndicatorSpec = specOf(
        id = "cci",
        name = "CCI 顺势指标",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "周期", 20.0, 2.0, 200.0)),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val tp = SeriesMath.hlc3(candles)
        val avg = SeriesMath.sma(tp, period)
        val n = candles.size
        val out = na(n)
        for (i in period - 1 until n) {
            val mean = avg[i]
            if (mean.isNaN()) continue
            val dev = SeriesMath.maxAbsDeviation(tp, i - period + 1, i, mean)
            out[i] = if (dev.isNaN() || dev == 0.0) 0.0 else (tp[i] - mean) / (0.015 * dev)
        }
        IndicatorResult(
            series = listOf(line("cci", "CCI$period", out, IndicatorColors.LINE_2, width = 1.3f)),
            guides = listOf(
                guide("+100", 100.0, IndicatorColors.BEAR),
                guide("0", 0.0),
                guide("-100", -100.0, IndicatorColors.BULL)
            )
        )
    }

    val williamsR: IndicatorSpec = specOf(
        id = "williams_r",
        name = "Williams %R",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "周期", 14.0, 2.0, 200.0)),
        range = ValueRange(-100.0, 0.0),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val period = p.i("period")
        val hh = SeriesMath.highest(SeriesMath.high(candles), period)
        val ll = SeriesMath.lowest(SeriesMath.low(candles), period)
        val close = SeriesMath.close(candles)
        val out = na(candles.size)
        for (i in candles.indices) {
            val range = hh[i] - ll[i]
            if (range.isNaN()) continue
            out[i] = if (range == 0.0) -50.0 else (hh[i] - close[i]) / range * -100.0
        }
        IndicatorResult(
            series = listOf(line("williams", "W%R$period", out, IndicatorColors.LINE_1, width = 1.3f)),
            guides = listOf(
                guide("超买 -20", -20.0, IndicatorColors.BEAR),
                guide("超卖 -80", -80.0, IndicatorColors.BULL)
            )
        )
    }

    val roc: IndicatorSpec = specOf(
        id = "roc",
        name = "ROC 变动率",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "周期", 12.0, 1.0, 200.0)),
        warmup = { it.i("period") }
    ) { candles, p ->
        val period = p.i("period")
        val close = SeriesMath.close(candles)
        val out = na(close.size)
        for (i in period until close.size) {
            val base = close[i - period]
            if (base != 0.0 && !base.isNaN()) out[i] = (close[i] - base) / base * 100.0
        }
        IndicatorResult(
            series = listOf(line("roc", "ROC$period", out, IndicatorColors.LINE_4, width = 1.3f)),
            guides = listOf(guide("零轴", 0.0))
        )
    }

    val stochRsi: IndicatorSpec = specOf(
        id = "stochrsi",
        name = "StochRSI",
        type = IndicatorType.OSCILLATOR,
        params = listOf(
            ParamSpec.number("rsiPeriod", "RSI 周期", 14.0, 2.0, 200.0),
            ParamSpec.number("stochPeriod", "Stoch 周期", 14.0, 2.0, 200.0),
            ParamSpec.number("kSmooth", "K 平滑", 3.0, 1.0, 50.0),
            ParamSpec.number("dSmooth", "D 平滑", 3.0, 1.0, 50.0)
        ),
        range = ValueRange(0.0, 100.0),
        warmup = { p ->
            p.i("rsiPeriod") + p.i("stochPeriod") + p.i("kSmooth") + p.i("dSmooth") - 3
        }
    ) { candles, p ->
        val rsiValues = rsiValues(SeriesMath.close(candles), p.i("rsiPeriod"))
        val stoch = SeriesMath.highest(rsiValues, p.i("stochPeriod"))
        val low = SeriesMath.lowest(rsiValues, p.i("stochPeriod"))
        val raw = na(rsiValues.size)
        for (i in rsiValues.indices) {
            if (stoch[i].isNaN() || low[i].isNaN()) continue
            val range = stoch[i] - low[i]
            raw[i] = if (range == 0.0) 50.0 else (rsiValues[i] - low[i]) / range * 100.0
        }
        val k = SeriesMath.sma(raw, p.i("kSmooth"))
        val d = SeriesMath.sma(k, p.i("dSmooth"))
        IndicatorResult(
            series = listOf(
                line("k", "K", k, IndicatorColors.FAST, width = 1.2f),
                line("d", "D", d, IndicatorColors.SLOW, width = 1.2f)
            ),
            guides = listOf(
                guide("超买 80", 80.0, IndicatorColors.BEAR),
                guide("超卖 20", 20.0, IndicatorColors.BULL)
            )
        )
    }

    val volumeMa: IndicatorSpec = specOf(
        id = "volume_ma",
        name = "成交量均线",
        type = IndicatorType.OSCILLATOR,
        params = listOf(ParamSpec.number("period", "均线周期", 20.0, 1.0, 200.0)),
        warmup = { it.i("period") - 1 }
    ) { candles, p ->
        val volume = SeriesMath.volume(candles)
        val volumeColors = LongArray(candles.size) {
            if (candles[it].c >= candles[it].o) 0x9926A69A else 0x99EF5350
        }
        val ma = SeriesMath.sma(volume, p.i("period"))
        IndicatorResult(
            series = listOf(
                line("volume", "成交量", volume, IndicatorColors.NEUTRAL, SeriesStyle.HISTOGRAM, colors = volumeColors),
                line("volume_ma", "量MA${p.i("period")}", ma, IndicatorColors.MA, width = 1.3f)
            )
        )
    }

    val all: List<IndicatorSpec> = listOf(
        rsi, macd, kdj, atr, obv, cci, williamsR, roc, stochRsi, volumeMa
    )

    // ---- 算法 ----

    internal fun rsiValues(close: DoubleArray, period: Int): DoubleArray {
        val n = close.size
        val out = na(n)
        if (period <= 0 || n <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = close[i] - close[i - 1]
            if (diff >= 0.0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = rsiFrom(avgGain, avgLoss)
        for (i in period + 1 until n) {
            val diff = close[i] - close[i - 1]
            val g = if (diff > 0.0) diff else 0.0
            val l = if (diff < 0.0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = rsiFrom(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiFrom(avgGain: Double, avgLoss: Double): Double = when {
        avgGain == 0.0 && avgLoss == 0.0 -> 50.0
        avgLoss == 0.0 -> 100.0
        avgGain == 0.0 -> 0.0
        else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    internal class KdjResult(val k: DoubleArray, val d: DoubleArray, val j: DoubleArray)

    internal fun kdjValues(candles: List<Candle>, period: Int, kSmooth: Int, dSmooth: Int): KdjResult {
        val n = candles.size
        val k = na(n)
        val d = na(n)
        val j = na(n)
        if (n == 0 || period <= 0) return KdjResult(k, d, j)
        val hh = SeriesMath.highest(SeriesMath.high(candles), period)
        val ll = SeriesMath.lowest(SeriesMath.low(candles), period)
        val close = SeriesMath.close(candles)
        var prevK = 50.0
        var prevD = 50.0
        for (i in period - 1 until n) {
            val range = hh[i] - ll[i]
            if (range.isNaN()) continue
            val rsv = if (range == 0.0) 50.0 else (close[i] - ll[i]) / range * 100.0
            val curK = (prevK * (kSmooth - 1) + rsv) / kSmooth
            val curD = (prevD * (dSmooth - 1) + curK) / dSmooth
            k[i] = curK
            d[i] = curD
            j[i] = 3.0 * curK - 2.0 * curD
            prevK = curK
            prevD = curD
        }
        return KdjResult(k, d, j)
    }

    /** MACD 方便测试/复用的纯函数入口。 */
    internal fun macdValues(
        close: DoubleArray,
        fastPeriod: Int,
        slowPeriod: Int,
        signalPeriod: Int
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val fast = SeriesMath.ema(close, fastPeriod)
        val slow = SeriesMath.ema(close, slowPeriod)
        val macd = DoubleArray(close.size) { fast[it] - slow[it] }
        val signal = SeriesMath.ema(macd, signalPeriod)
        val hist = DoubleArray(close.size) { macd[it] - signal[it] }
        return Triple(macd, signal, hist)
    }
}
