package com.milolab.btctv.data.indicators

/**
 * 全部指标的注册表。设置界面可直接遍历 [all] 或按 [IndicatorType] 分组，
 * 用 IndicatorSpec 里的 params/默认值/范围生成开关和参数控件。
 */
object IndicatorRegistry {

    /** 顺序即设置页展示顺序：主图 → 回归 → 水平线 → 副图。 */
    val all: List<IndicatorSpec> = listOf(
        TrendIndicators.ma,
        TrendIndicators.ema,
        TrendIndicators.wma,
        TrendIndicators.maGroup,
        TrendIndicators.boll,
        TrendIndicators.vwap,
        TrendIndicators.sar,
        TrendIndicators.donchian,
        TrendIndicators.keltner,
        IchimokuIndicator.spec,
        TrendIndicators.superTrend,
        TrendIndicators.envelope,

        RegressionIndicators.linear,
        RegressionIndicators.channel,
        RegressionIndicators.polynomial,
        RegressionIndicators.logarithmic,

        LevelIndicators.pivot,
        LevelIndicators.swing,
        LevelIndicators.fibonacci,
        LevelIndicators.volumeProfile,
        LevelIndicators.roundNumbers,

        OscillatorIndicators.rsi,
        OscillatorIndicators.macd,
        OscillatorIndicators.kdj,
        OscillatorIndicators.atr,
        OscillatorIndicators.obv,
        OscillatorIndicators.cci,
        OscillatorIndicators.williamsR,
        OscillatorIndicators.roc,
        OscillatorIndicators.stochRsi,
        OscillatorIndicators.volumeMa
    )

    private val index: Map<String, IndicatorSpec> = all.associateBy { it.id }

    init {
        require(index.size == all.size) { "IndicatorRegistry 存在重复 id" }
    }

    val overlays: List<IndicatorSpec> = all.filter { it.type == IndicatorType.OVERLAY }
    val oscillators: List<IndicatorSpec> = all.filter { it.type == IndicatorType.OSCILLATOR }
    val levels: List<IndicatorSpec> = all.filter { it.type == IndicatorType.LEVELS }

    fun byId(id: String): IndicatorSpec? = index[id]

    fun requireById(id: String): IndicatorSpec =
        index[id] ?: error("未知指标 id: $id")

    fun byType(type: IndicatorType): List<IndicatorSpec> = when (type) {
        IndicatorType.OVERLAY -> overlays
        IndicatorType.OSCILLATOR -> oscillators
        IndicatorType.LEVELS -> levels
    }

    fun contains(id: String): Boolean = index.containsKey(id)

    fun ids(): List<String> = all.map { it.id }

    /** UI 保存/恢复用：某指标的一组默认参数。 */
    fun defaultParams(id: String): Map<String, Double>? =
        index[id]?.defaultParams?.values
}
