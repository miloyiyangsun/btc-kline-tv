package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.roundToInt

/** 指标挂载位置：主图叠加、副图、水平线类。 */
enum class IndicatorType(val label: String) {
    OVERLAY("主图叠加"),
    OSCILLATOR("副图"),
    LEVELS("水平线")
}

enum class ParamKind { NUMBER, CHOICE, BOOL }

data class ParamChoice(val label: String, val value: Double)

/** 一个可调参数。UI 可以直接按 min/max/step 生成滑块，choice 类型生成下拉。 */
data class ParamSpec(
    val key: String,
    val label: String,
    val default: Double,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val step: Double = 1.0,
    val kind: ParamKind = ParamKind.NUMBER,
    val choices: List<ParamChoice> = emptyList(),
    val unit: String = ""
) {
    init {
        require(key.isNotBlank()) { "param key 不能为空" }
        require(kind != ParamKind.CHOICE || choices.isNotEmpty()) { "$key: CHOICE 参数必须提供 choices" }
    }

    fun normalize(value: Double): Double = when (kind) {
        ParamKind.NUMBER -> value.coerceIn(min, max)
        ParamKind.CHOICE -> if (choices.any { it.value == value }) value else default
        ParamKind.BOOL -> if (value != 0.0) 1.0 else 0.0
    }

    companion object {
        fun number(
            key: String,
            label: String,
            default: Double,
            min: Double,
            max: Double,
            step: Double = 1.0,
            unit: String = ""
        ) = ParamSpec(key, label, default, min, max, step, ParamKind.NUMBER, unit = unit)

        fun choice(key: String, label: String, default: Double, vararg choices: ParamChoice) =
            ParamSpec(key, label, default, kind = ParamKind.CHOICE, choices = choices.toList())

        fun bool(key: String, label: String, default: Boolean) =
            ParamSpec(key, label, if (default) 1.0 else 0.0, 0.0, 1.0, 1.0, ParamKind.BOOL)
    }
}

/** 指标参数值，按 key 取值；每次 evaluate 前会按 ParamSpec 自动夹紧/校正。 */
data class IndicatorParams(val values: Map<String, Double>) {

    fun d(key: String, fallback: Double = Double.NaN): Double = values[key] ?: fallback

    fun i(key: String, fallback: Int = 0): Int {
        val v = values[key] ?: return fallback
        return if (v.isFinite()) v.roundToInt() else fallback
    }

    fun b(key: String, fallback: Boolean = false): Boolean {
        val v = values[key] ?: return fallback
        return v != 0.0
    }

    fun with(key: String, value: Double): IndicatorParams =
        IndicatorParams(values + (key to value))

    companion object {
        val EMPTY = IndicatorParams(emptyMap())

        fun of(vararg pairs: Pair<String, Double>) = IndicatorParams(pairs.toMap())
    }
}

enum class SeriesStyle { LINE, HISTOGRAM, AREA, STEP, DOTS }

/**
 * 一条与 K 线索引对齐的序列。values.size == candles.size，预热期为 NaN。
 * [colors] 非空时按点着色（SuperTrend、MACD 柱等）。
 */
class IndicatorSeries(
    val key: String,
    val label: String,
    val values: DoubleArray,
    val color: Long,
    val style: SeriesStyle = SeriesStyle.LINE,
    val width: Float = 1f,
    val fillAlpha: Float = 0f,
    val colors: LongArray? = null,
    val dash: Boolean = false
) {
    val size: Int get() = values.size

    operator fun get(index: Int): Double = values[index]

    override fun toString(): String = "IndicatorSeries(key=$key, label=$label, size=${values.size})"
}

enum class LevelStyle { SOLID, DASHED, DOTTED }

/** 一条水平价位线；OVERLAY 下 value 是价格，OSCILLATOR 的 guides 里是指标值。 */
data class IndicatorLevel(
    val label: String,
    val value: Double,
    val color: Long,
    val style: LevelStyle = LevelStyle.DASHED,
    val width: Float = 1f
)

/** 横向色带（例如价值区 VAH~VAL），整张图宽。 */
data class IndicatorRegion(
    val label: String,
    val low: Double,
    val high: Double,
    val color: Long,
    val alpha: Float = 0.10f
)

/** 纵向色带：由两条已命名序列围成的区域（布林带、云图）。 */
data class IndicatorZone(
    val upperKey: String,
    val lowerKey: String,
    val color: Long,
    val alpha: Float = 0.12f
)

data class ValueRange(val min: Double, val max: Double)

/** 指标输出。series 与 levels/regions/zones/guides 都只是绘制建议，不影响计算。 */
class IndicatorResult(
    val series: List<IndicatorSeries> = emptyList(),
    val levels: List<IndicatorLevel> = emptyList(),
    val regions: List<IndicatorRegion> = emptyList(),
    val zones: List<IndicatorZone> = emptyList(),
    val guides: List<IndicatorLevel> = emptyList()
) {
    val length: Int get() = series.firstOrNull()?.size ?: 0

    fun series(key: String): IndicatorSeries? = series.firstOrNull { it.key == key }

    override fun toString(): String =
        "IndicatorResult(series=${series.map { it.key }}, levels=${levels.size}, regions=${regions.size})"

    companion object {
        val EMPTY = IndicatorResult()
    }
}

/**
 * 指标定义。UI 设置页只需要读 id/name/type/params/suggestedRange；
 * 计算时调用 [evaluate]，参数可用默认值或用户覆盖值。
 */
class IndicatorSpec(
    val id: String,
    val name: String,
    val type: IndicatorType,
    val params: List<ParamSpec> = emptyList(),
    val suggestedRange: ValueRange? = null,
    private val warmupFn: (IndicatorParams) -> Int = { 0 },
    private val calculator: (List<Candle>, IndicatorParams) -> IndicatorResult
) {
    init {
        require(id.isNotBlank()) { "指标 id 不能为空" }
        require(params.map { it.key }.distinct().size == params.size) { "$id: 参数 key 重复" }
    }

    val defaultParams: IndicatorParams =
        IndicatorParams(params.associate { it.key to it.default })

    fun withParams(overrides: Map<String, Double>): IndicatorParams {
        if (overrides.isEmpty()) return defaultParams
        return IndicatorParams(
            params.associate { p -> p.key to p.normalize(overrides[p.key] ?: p.default) }
        )
    }

    fun withParams(params: IndicatorParams): IndicatorParams = withParams(params.values)

    /** 预热 K 线数；前 warmup 根的结果为 NaN。 */
    fun warmup(params: IndicatorParams = defaultParams): Int =
        warmupFn(withParams(params)).coerceIn(0, Int.MAX_VALUE)

    fun evaluate(
        candles: List<Candle>,
        params: IndicatorParams = defaultParams
    ): IndicatorResult {
        val p = withParams(params)
        return calculator(candles, p)
    }

    fun evaluate(candles: List<Candle>, overrides: Map<String, Double>): IndicatorResult =
        evaluate(candles, withParams(overrides))

    override fun toString(): String = "IndicatorSpec($id, $name, $type)"
}

/** 统一的颜色建议（ARGB）。UI 可以整体替换主题。 */
object IndicatorColors {
    const val MA_FAST = 0xFF4FC3F7
    const val MA = 0xFFFFB74D
    const val MA_SLOW = 0xFFBA68C8

    const val BAND = 0xFFFFD54F
    const val BAND_FILL = 0x1AFFD54F
    const val BULL = 0xFF26A69A
    const val BEAR = 0xFFEF5350
    const val NEUTRAL = 0xFF90A4AE
    const val POC = 0xFFFFB300
    const val VAH = 0xFFEF9A9A
    const val VAL = 0xFF80CBC4
    const val RESISTANCE = 0xFFEF5350
    const val SUPPORT = 0xFF26A69A
    const val PIVOT = 0xFFFFD54F

    const val FAST = 0xFF4FC3F7
    const val SLOW = 0xFFFFB74D
    const val SIGNAL = 0xFFBA68C8
    const val HIST_UP = 0xFF26A69A
    const val HIST_DOWN = 0xFFEF5350
    const val LINE_1 = 0xFF4DD0E1
    const val LINE_2 = 0xFFFF8A65
    const val LINE_3 = 0xFFDCE775
    const val LINE_4 = 0xFFF06292
}

/** 所有预热值统一用 NaN。 */
internal fun na(n: Int): DoubleArray = DoubleArray(n) { Double.NaN }

internal fun constant(n: Int, value: Double): DoubleArray = DoubleArray(n) { value }

internal fun specOf(
    id: String,
    name: String,
    type: IndicatorType,
    params: List<ParamSpec> = emptyList(),
    range: ValueRange? = null,
    warmup: (IndicatorParams) -> Int = { 0 },
    compute: (List<Candle>, IndicatorParams) -> IndicatorResult
): IndicatorSpec = IndicatorSpec(id, name, type, params, range, warmup, compute)

internal fun line(
    key: String,
    label: String,
    values: DoubleArray,
    color: Long,
    style: SeriesStyle = SeriesStyle.LINE,
    width: Float = 1f,
    fillAlpha: Float = 0f,
    colors: LongArray? = null,
    dash: Boolean = false
): IndicatorSeries =
    IndicatorSeries(key, label, values, color, style, width, fillAlpha, colors, dash)
