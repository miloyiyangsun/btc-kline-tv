package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle

/** 一目均衡表。先行带前移 displacement 根，迟行线后移 displacement 根。 */
object IchimokuIndicator {

    val spec: IndicatorSpec = specOf(
        id = "ichimoku",
        name = "一目均衡表",
        type = IndicatorType.OVERLAY,
        params = listOf(
            ParamSpec.number("tenkan", "转换线周期", 9.0, 2.0, 100.0),
            ParamSpec.number("kijun", "基准线周期", 26.0, 2.0, 200.0),
            ParamSpec.number("senkouB", "先行带B周期", 52.0, 2.0, 300.0),
            ParamSpec.number("displacement", "前移/后移", 26.0, 1.0, 100.0)
        ),
        warmup = { p ->
            val kijun = p.i("kijun") - 1 + p.i("displacement")
            val senkouB = p.i("senkouB") - 1 + p.i("displacement")
            maxOf(kijun, senkouB)
        }
    ) { candles, p ->
        val tenkanP = p.i("tenkan")
        val kijunP = p.i("kijun")
        val senkouBP = p.i("senkouB")
        val disp = p.i("displacement")

        val high = SeriesMath.high(candles)
        val low = SeriesMath.low(candles)
        val close = SeriesMath.close(candles)

        fun midpoint(period: Int): DoubleArray {
            val hh = SeriesMath.highest(high, period)
            val ll = SeriesMath.lowest(low, period)
            return DoubleArray(candles.size) { (hh[it] + ll[it]) / 2.0 }
        }

        val tenkan = midpoint(tenkanP)
        val kijun = midpoint(kijunP)
        val senkouBBase = midpoint(senkouBP)
        val senkouABase = DoubleArray(candles.size) { (tenkan[it] + kijun[it]) / 2.0 }

        val senkouA = SeriesMath.shiftForward(senkouABase, disp)
        val senkouB = SeriesMath.shiftForward(senkouBBase, disp)
        val chikou = SeriesMath.shiftBackward(close, disp)

        IndicatorResult(
            series = listOf(
                line("tenkan", "转换线", tenkan, 0xFFEF5350, width = 1f),
                line("kijun", "基准线", kijun, 0xFF42A5F5, width = 1f),
                line("senkouA", "先行带A", senkouA, 0xFF66BB6A, width = 1f),
                line("senkouB", "先行带B", senkouB, 0xFFFFA726, width = 1f),
                line("chikou", "迟行线", chikou, 0xFF90A4AE, width = 1f, dash = true)
            ),
            zones = listOf(IndicatorZone("senkouA", "senkouB", 0xFF4FC3F7, alpha = 0.10f))
        )
    }

    val all: List<IndicatorSpec> = listOf(spec)
}
