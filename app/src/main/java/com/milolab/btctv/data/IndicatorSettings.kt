package com.milolab.btctv.data

import com.milolab.btctv.data.indicators.IndicatorParams
import com.milolab.btctv.data.indicators.IndicatorRegistry
import com.milolab.btctv.data.indicators.IndicatorSpec
import com.milolab.btctv.data.indicators.IndicatorType
import com.milolab.btctv.data.indicators.ParamKind
import com.milolab.btctv.data.indicators.ParamSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** 设置页的四个分组；回归线从 OVERLAY 里单独拆出来。 */
enum class IndicatorGroup(val label: String) {
    OVERLAY("主图叠加"),
    REGRESSION("回归线"),
    LEVELS("压力位·支撑位"),
    OSCILLATOR("副图");

    fun specs(): List<IndicatorSpec> = when (this) {
        OVERLAY -> IndicatorRegistry.overlays.filter { it.id !in REGRESSION_IDS }
        REGRESSION -> IndicatorRegistry.overlays.filter { it.id in REGRESSION_IDS }
        LEVELS -> IndicatorRegistry.levels
        OSCILLATOR -> IndicatorRegistry.oscillators
    }

    private companion object {
        val REGRESSION_IDS = setOf("linreg", "linreg_channel", "polyreg", "logreg")
    }
}

/**
 * 用户选择开启的指标与每个指标的参数覆盖值。纯 Kotlin，Prefs 只负责把
 * [encode] 的字符串塞进 SharedPreferences；默认值就是“全部关闭”。
 */
data class IndicatorSettings(
    /** 已开启的指标 id；显示顺序始终按 IndicatorRegistry 顺序重排。 */
    val enabled: List<String> = emptyList(),
    /** 只保存用户改过的参数；缺省项由 [paramsFor] 用 spec 默认值补齐。 */
    val params: Map<String, Map<String, Double>> = emptyMap()
) {

    val enabledCount: Int get() = enabled.size

    fun isEnabled(id: String): Boolean = id in enabled

    fun enabledOscillators(): List<IndicatorSpec> =
        IndicatorRegistry.oscillators.filter { isEnabled(it.id) }.take(MAX_OSCILLATORS)

    fun oscillatorCount(): Int = enabledOscillators().size

    /** 按注册表顺序返回已开启指标；副图最多返回前 3 个。 */
    fun enabledSpecs(): List<IndicatorSpec> {
        val osc = enabledOscillators().mapTo(HashSet()) { it.id }
        return IndicatorRegistry.all.filter {
            isEnabled(it.id) && (it.type != IndicatorType.OSCILLATOR || it.id in osc)
        }
    }

    fun enabledSpecs(type: IndicatorType): List<IndicatorSpec> {
        val all = enabledSpecs()
        return if (type == IndicatorType.OSCILLATOR) all.filter { it.type == type }.take(MAX_OSCILLATORS)
        else all.filter { it.type == type }
    }

    fun enabledIn(group: IndicatorGroup): Int = group.specs().count { isEnabled(it.id) }

    /** 用 spec.withParams 恢复参数：缺省补默认、越界自动夹紧。 */
    fun paramsFor(spec: IndicatorSpec): IndicatorParams =
        spec.withParams(params[spec.id].orEmpty())

    fun paramsFor(id: String): IndicatorParams? = IndicatorRegistry.byId(id)?.let(::paramsFor)

    fun summaryFor(spec: IndicatorSpec): String {
        if (spec.params.isEmpty()) return "默认"
        val p = paramsFor(spec)
        return spec.params.joinToString("  ") { ps ->
            ps.label + " " + paramValueLabel(ps, p.d(ps.key, ps.default))
        }
    }

    fun canEnable(id: String): Boolean {
        val spec = IndicatorRegistry.byId(id) ?: return false
        if (isEnabled(id)) return true
        return spec.type != IndicatorType.OSCILLATOR || oscillatorCount() < MAX_OSCILLATORS
    }

    /** 已开启则关闭；否则在副图未满 3 个时开启。拒绝时原样返回。 */
    fun withToggled(id: String): IndicatorSettings {
        if (!IndicatorRegistry.contains(id)) return this
        if (isEnabled(id)) return copy(enabled = enabled.filterNot { it == id })
        if (!canEnable(id)) return this
        return copy(enabled = enabled + id)
    }

    fun withParam(id: String, key: String, value: Double): IndicatorSettings {
        val spec = IndicatorRegistry.byId(id) ?: return this
        val ps = spec.params.firstOrNull { it.key == key } ?: return this
        val next = (params[id].orEmpty()) + (key to ps.normalize(value))
        return copy(params = params + (id to next))
    }

    /** ◀ = -1，▶ = +1。NUMBER 按步长并夹紧，CHOICE 循环，BOOL 切换。 */
    fun withParamStep(id: String, key: String, delta: Int): IndicatorSettings {
        val spec = IndicatorRegistry.byId(id) ?: return this
        val ps = spec.params.firstOrNull { it.key == key } ?: return this
        if (delta == 0) return this
        val current = paramsFor(spec).d(ps.key, ps.default)
        val next = when (ps.kind) {
            ParamKind.NUMBER -> {
                val steps = round((current - ps.min) / ps.step)
                ps.normalize(ps.min + (steps + delta) * ps.step)
            }

            ParamKind.CHOICE -> {
                val found = ps.choices.indexOfFirst { it.value == current }
                val index = if (found >= 0) found else ps.choices.indexOfFirst { it.value == ps.default }
                    .coerceAtLeast(0)
                ps.choices[Math.floorMod(index + delta, ps.choices.size)].value
            }

            ParamKind.BOOL -> if (current != 0.0) 0.0 else 1.0
        }
        return withParam(id, key, next)
    }

    /** 保留每个指标的参数，只把开关全部关掉。 */
    fun allOff(): IndicatorSettings = copy(enabled = emptyList())

    /** 恢复出厂：开关全关、参数覆盖全部清空。 */
    fun restoreDefaults(): IndicatorSettings = DEFAULT

    fun encode(): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("enabled", JSONArray(enabled))
        val storedParams = JSONObject()
        for ((id, values) in params) {
            val spec = IndicatorRegistry.byId(id) ?: continue
            val obj = JSONObject()
            for (ps in spec.params) {
                val v = values[ps.key] ?: continue
                if (v.isFinite()) obj.put(ps.key, ps.normalize(v))
            }
            if (obj.length() > 0) storedParams.put(id, obj)
        }
        root.put("params", storedParams)
        return root.toString()
    }

    companion object {
        const val MAX_OSCILLATORS = 3
        const val BASE_BARS = 120
        const val MAX_BARS = 500
        /** 主图最大缩放根数；预热按这个值预留，缩放时不用重新拉历史。 */
        const val MAX_DISPLAY_BARS = 240

        val DEFAULT = IndicatorSettings()

        /**
         * 预热越长拉越多 K 线：warmup + 最大缩放根数，上限 500。
         * 没有开启指标时保持原来的 120 根。
         */
        fun requiredBars(settings: IndicatorSettings, displayBars: Int = MAX_DISPLAY_BARS): Int {
            if (settings.enabled.isEmpty()) return BASE_BARS
            val warmup = settings.enabledSpecs().maxOf { spec ->
                spec.warmup(settings.paramsFor(spec))
            }
            if (warmup <= 0) return BASE_BARS
            return (warmup + displayBars).coerceIn(BASE_BARS, MAX_BARS)
        }

        /** 旧版本没有这个 key、内容损坏、id 不认识时都回退到“全部关闭”。 */
        fun decode(raw: String?): IndicatorSettings {
            if (raw.isNullOrBlank()) return DEFAULT
            return runCatching {
                val root = JSONObject(raw)
                val arr = root.optJSONArray("enabled") ?: JSONArray()
                val ids = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotEmpty() && IndicatorRegistry.contains(id) && id !in ids) ids.add(id)
                }

                val parsed = LinkedHashMap<String, Map<String, Double>>()
                val stored = root.optJSONObject("params")
                if (stored != null) {
                    val keys = stored.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val spec = IndicatorRegistry.byId(id) ?: continue
                        val obj = stored.optJSONObject(id) ?: continue
                        val values = LinkedHashMap<String, Double>()
                        for (ps in spec.params) {
                            if (!obj.has(ps.key)) continue
                            val v = obj.optDouble(ps.key, Double.NaN)
                            if (!v.isNaN()) values[ps.key] = ps.normalize(v)
                        }
                        if (values.isNotEmpty()) parsed[id] = values
                    }
                }
                IndicatorSettings(capOscillators(ids), parsed)
            }.getOrDefault(DEFAULT)
        }

        private fun capOscillators(ids: List<String>): List<String> {
            val osc = IndicatorRegistry.oscillators.map { it.id }.toSet()
            val allowed = ids.filter { it in osc }.take(MAX_OSCILLATORS).toSet()
            return ids.filter { it !in osc || it in allowed }
        }
    }
}

/** 参数值的展示文本：数字去掉多余小数，CHOICE 用选项名，BOOL 用开/关。 */
fun paramValueLabel(spec: ParamSpec, value: Double): String = when (spec.kind) {
    ParamKind.NUMBER -> {
        val base = formatParamNumber(value)
        when {
            spec.unit == "%" -> "$base%"
            spec.unit.isNotBlank() -> "$base ${spec.unit}"
            else -> base
        }
    }

    ParamKind.CHOICE -> spec.choices.firstOrNull { it.value == value }?.label
        ?: spec.choices.firstOrNull { it.value == spec.default }?.label
        ?: formatParamNumber(value)

    ParamKind.BOOL -> if (value != 0.0) "开" else "关"
}

fun formatParamNumber(value: Double): String {
    if (!value.isFinite()) return "—"
    if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
    val text = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}
