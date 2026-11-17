package com.milolab.btctv.data.indicators

import com.milolab.btctv.data.Candle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 压力位 / 支撑位类指标（type = LEVELS）。 */
object LevelIndicators {

    val pivot: IndicatorSpec = specOf(
        id = "pivot",
        name = "枢轴点 Pivot",
        type = IndicatorType.LEVELS,
        params = listOf(
            ParamSpec.choice(
                "type", "类型", 0.0,
                ParamChoice("经典", 0.0),
                ParamChoice("斐波那契", 1.0),
                ParamChoice("Camarilla", 2.0)
            ),
            ParamSpec.choice(
                "basis", "参考周期", 1.0,
                ParamChoice("上一根K线", 0.0),
                ParamChoice("前一 UTC 日", 1.0),
                ParamChoice("前一 UTC 周", 2.0)
            )
        )
    ) { candles, p ->
        val hlc = referenceHlc(candles, p.i("basis"))
        if (hlc == null) return@specOf IndicatorResult.EMPTY
        val levels = when (p.i("type")) {
            1 -> fibonacciPivots(hlc)
            2 -> camarillaPivots(hlc)
            else -> classicPivots(hlc)
        }
        IndicatorResult(
            levels = levels,
            regions = listOf(
                IndicatorRegion("枢轴区间", hlc.l, hlc.h, IndicatorColors.NEUTRAL, alpha = 0.04f)
            )
        )
    }

    val swing: IndicatorSpec = specOf(
        id = "swing_levels",
        name = "摆动高低点支撑压力",
        type = IndicatorType.LEVELS,
        params = listOf(
            ParamSpec.number("left", "左侧K线", 5.0, 1.0, 100.0),
            ParamSpec.number("right", "右侧K线", 5.0, 1.0, 100.0),
            ParamSpec.number("maxLevels", "每侧最多条数", 5.0, 1.0, 20.0),
            ParamSpec.number("mergePct", "相近价位合并阈值", 0.5, 0.0, 10.0, 0.1, "%")
        ),
        warmup = { it.i("left") + it.i("right") }
    ) { candles, p ->
        val levels = swingLevels(
            candles,
            p.i("left"),
            p.i("right"),
            p.i("maxLevels"),
            p.d("mergePct")
        )
        IndicatorResult(levels = levels)
    }

    val fibonacci: IndicatorSpec = specOf(
        id = "fib",
        name = "斐波那契回撤/扩展",
        type = IndicatorType.LEVELS,
        params = listOf(
            ParamSpec.number("lookback", "回看K线（0=全部）", 200.0, 0.0, 2000.0, 1.0, "根"),
            ParamSpec.bool("extensions", "显示扩展位", true)
        )
    ) { candles, p ->
        fibLevels(candles, p.i("lookback"), p.b("extensions"))
    }

    val volumeProfile: IndicatorSpec = specOf(
        id = "volume_profile",
        name = "成交量密集区 POC/VAH/VAL",
        type = IndicatorType.LEVELS,
        params = listOf(
            ParamSpec.number("bins", "价格分箱", 24.0, 4.0, 200.0),
            ParamSpec.number("valueArea", "价值区占比", 70.0, 50.0, 95.0, 1.0, "%"),
            ParamSpec.number("lookback", "回看K线（0=全部）", 0.0, 0.0, 2000.0, 1.0, "根")
        )
    ) { candles, p ->
        volumeProfileLevels(candles, p.i("lookback"), p.i("bins"), p.d("valueArea"))
    }

    val roundNumbers: IndicatorSpec = specOf(
        id = "round_levels",
        name = "整数关口",
        type = IndicatorType.LEVELS,
        params = listOf(
            ParamSpec.number("step", "步长（0=自动）", 0.0, 0.0, 1_000_000_000.0, 1.0),
            ParamSpec.number("count", "上下各几条", 3.0, 1.0, 10.0)
        )
    ) { candles, p ->
        val last = candles.lastOrNull()?.c ?: return@specOf IndicatorResult.EMPTY
        IndicatorResult(levels = roundLevels(last, p.d("step"), p.i("count")))
    }

    val all: List<IndicatorSpec> = listOf(pivot, swing, fibonacci, volumeProfile, roundNumbers)

    // ---- 枢轴点 ----

    internal data class Hlc(val h: Double, val l: Double, val c: Double)

    internal fun referenceHlc(candles: List<Candle>, basis: Int): Hlc? {
        if (candles.isEmpty()) return null
        if (basis == 0) {
            val c = candles.last()
            return Hlc(c.h, c.l, c.c)
        }
        val byWeek = basis == 2
        val buckets = LinkedHashMap<Long, Hlc>()
        for (c in candles) {
            val key = bucketKey(c.t, byWeek)
            val old = buckets[key]
            buckets[key] = if (old == null) {
                Hlc(c.h, c.l, c.c)
            } else {
                Hlc(max(old.h, c.h), min(old.l, c.l), c.c)
            }
        }
        val keys = buckets.keys.sorted()
        return when {
            keys.size >= 2 -> buckets[keys[keys.size - 2]]
            keys.isNotEmpty() -> buckets[keys.last()]
            else -> null
        }
    }

    private fun bucketKey(t: Long, byWeek: Boolean): Long {
        val day = Math.floorDiv(t, DAY_MS)
        return if (byWeek) Math.floorDiv(day + 3, 7) else day
    }

    internal fun classicPivots(hlc: Hlc): List<IndicatorLevel> {
        val p = (hlc.h + hlc.l + hlc.c) / 3.0
        val range = hlc.h - hlc.l
        return listOf(
            IndicatorLevel("R3", hlc.h + 2.0 * (p - hlc.l), IndicatorColors.RESISTANCE, width = 1f),
            IndicatorLevel("R2", p + range, IndicatorColors.RESISTANCE, width = 1.1f),
            IndicatorLevel("R1", 2.0 * p - hlc.l, IndicatorColors.RESISTANCE, width = 1.2f),
            IndicatorLevel("P", p, IndicatorColors.PIVOT, LevelStyle.SOLID, 1.5f),
            IndicatorLevel("S1", 2.0 * p - hlc.h, IndicatorColors.SUPPORT, width = 1.2f),
            IndicatorLevel("S2", p - range, IndicatorColors.SUPPORT, width = 1.1f),
            IndicatorLevel("S3", hlc.l - 2.0 * (hlc.h - p), IndicatorColors.SUPPORT, width = 1f)
        )
    }

    internal fun fibonacciPivots(hlc: Hlc): List<IndicatorLevel> {
        val p = (hlc.h + hlc.l + hlc.c) / 3.0
        val range = hlc.h - hlc.l
        return listOf(
            IndicatorLevel("R3", p + range, IndicatorColors.RESISTANCE),
            IndicatorLevel("R2", p + range * 0.618, IndicatorColors.RESISTANCE),
            IndicatorLevel("R1", p + range * 0.382, IndicatorColors.RESISTANCE, width = 1.2f),
            IndicatorLevel("P", p, IndicatorColors.PIVOT, LevelStyle.SOLID, 1.5f),
            IndicatorLevel("S1", p - range * 0.382, IndicatorColors.SUPPORT, width = 1.2f),
            IndicatorLevel("S2", p - range * 0.618, IndicatorColors.SUPPORT),
            IndicatorLevel("S3", p - range, IndicatorColors.SUPPORT)
        )
    }

    internal fun camarillaPivots(hlc: Hlc): List<IndicatorLevel> {
        val range = (hlc.h - hlc.l) * 1.1
        val c = hlc.c
        return listOf(
            IndicatorLevel("R4", c + range / 2.0, IndicatorColors.RESISTANCE, width = 1.3f),
            IndicatorLevel("R3", c + range / 4.0, IndicatorColors.RESISTANCE),
            IndicatorLevel("R2", c + range / 6.0, IndicatorColors.RESISTANCE),
            IndicatorLevel("R1", c + range / 12.0, IndicatorColors.RESISTANCE),
            IndicatorLevel("S1", c - range / 12.0, IndicatorColors.SUPPORT),
            IndicatorLevel("S2", c - range / 6.0, IndicatorColors.SUPPORT),
            IndicatorLevel("S3", c - range / 4.0, IndicatorColors.SUPPORT),
            IndicatorLevel("S4", c - range / 2.0, IndicatorColors.SUPPORT, width = 1.3f)
        )
    }

    // ---- 摆动高低点聚类 ----

    internal fun swingLevels(
        candles: List<Candle>,
        left: Int,
        right: Int,
        maxLevels: Int,
        mergePct: Double
    ): List<IndicatorLevel> {
        val n = candles.size
        if (n < left + right + 1) return emptyList()
        val pivots = ArrayList<Double>()
        for (i in left until n - right) {
            var isHigh = true
            var isLow = true
            for (j in i - left..i + right) {
                if (j == i) continue
                if (candles[j].h >= candles[i].h) isHigh = false
                if (candles[j].l <= candles[i].l) isLow = false
            }
            if (isHigh) pivots.add(candles[i].h)
            if (isLow) pivots.add(candles[i].l)
        }
        if (pivots.isEmpty()) return emptyList()
        pivots.sort()

        class Cluster(var sum: Double, var count: Int, var mean: Double)
        val clusters = ArrayList<Cluster>()
        val tolerance = mergePct / 100.0
        for (price in pivots) {
            val last = clusters.lastOrNull()
            if (last != null && abs(price - last.mean) <= abs(last.mean) * tolerance) {
                last.sum += price
                last.count++
                last.mean = last.sum / last.count
            } else {
                clusters.add(Cluster(price, 1, price))
            }
        }

        val lastClose = candles.last().c
        val supports = clusters
            .filter { it.mean < lastClose }
            .sortedByDescending { it.mean }
            .take(maxLevels)
        val resistances = clusters
            .filter { it.mean > lastClose }
            .sortedBy { it.mean }
            .take(maxLevels)

        val out = ArrayList<IndicatorLevel>(supports.size + resistances.size)
        supports.forEachIndexed { i, c ->
            out.add(
                IndicatorLevel(
                    "支撑${i + 1}",
                    c.mean,
                    IndicatorColors.SUPPORT,
                    width = if (i == 0) 1.3f else 1f
                )
            )
        }
        resistances.forEachIndexed { i, c ->
            out.add(
                IndicatorLevel(
                    "压力${i + 1}",
                    c.mean,
                    IndicatorColors.RESISTANCE,
                    width = if (i == 0) 1.3f else 1f
                )
            )
        }
        return out
    }

    // ---- 斐波那契 ----

    internal fun fibLevels(candles: List<Candle>, lookback: Int, extensions: Boolean): IndicatorResult {
        val n = candles.size
        if (n == 0) return IndicatorResult.EMPTY
        val from = RegressionIndicators.windowStart(n, lookback)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var hiIdx = from
        var loIdx = from
        for (i in from until n) {
            if (candles[i].h > hi) {
                hi = candles[i].h
                hiIdx = i
            }
            if (candles[i].l < lo) {
                lo = candles[i].l
                loIdx = i
            }
        }
        if (!hi.isFinite() || !lo.isFinite()) return IndicatorResult.EMPTY
        val range = hi - lo
        if (range <= 0.0) {
            return IndicatorResult(levels = listOf(IndicatorLevel("斐波 100%", hi, IndicatorColors.PIVOT)))
        }
        val upSwing = hiIdx > loIdx
        val retracements = doubleArrayOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
        val levels = ArrayList<IndicatorLevel>()
        for (r in retracements) {
            val price = if (upSwing) hi - r * range else lo + r * range
            val color = when (r) {
                0.0, 1.0 -> IndicatorColors.NEUTRAL
                0.618 -> IndicatorColors.PIVOT
                else -> 0xFFB0BEC5
            }
            levels.add(
                IndicatorLevel(
                    "回撤 ${ratioLabel(r)}",
                    price,
                    color,
                    LevelStyle.DASHED,
                    if (r == 0.618) 1.3f else 1f
                )
            )
        }
        if (extensions) {
            for (r in doubleArrayOf(1.272, 1.618)) {
                val price = if (upSwing) hi + (r - 1.0) * range else lo - (r - 1.0) * range
                levels.add(
                    IndicatorLevel(
                        "扩展 ${ratioLabel(r)}",
                        price,
                        IndicatorColors.LINE_4,
                        LevelStyle.DOTTED,
                        1f
                    )
                )
            }
        }
        val zoneLow: Double
        val zoneHigh: Double
        if (upSwing) {
            zoneLow = hi - 0.618 * range
            zoneHigh = hi - 0.382 * range
        } else {
            zoneLow = lo + 0.382 * range
            zoneHigh = lo + 0.618 * range
        }
        return IndicatorResult(
            levels = levels,
            regions = listOf(
                IndicatorRegion("黄金回撤区", min(zoneLow, zoneHigh), max(zoneLow, zoneHigh), IndicatorColors.PIVOT, 0.08f)
            )
        )
    }

    private fun ratioLabel(r: Double): String {
        val pct = r * 100.0
        val rounded = (pct * 10.0).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            "${rounded.toLong()}%"
        } else {
            "${rounded}%"
        }
    }

    // ---- 成交量分布 ----

    internal fun volumeProfileLevels(
        candles: List<Candle>,
        lookback: Int,
        bins: Int,
        valueAreaPct: Double
    ): IndicatorResult {
        val n = candles.size
        if (n == 0) return IndicatorResult.EMPTY
        val from = RegressionIndicators.windowStart(n, lookback)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (i in from until n) {
            hi = max(hi, candles[i].h)
            lo = min(lo, candles[i].l)
        }
        if (!hi.isFinite() || !lo.isFinite()) return IndicatorResult.EMPTY
        if (hi - lo <= 1e-12) {
            return IndicatorResult(
                levels = listOf(
                    IndicatorLevel("POC", hi, IndicatorColors.POC, LevelStyle.SOLID, 1.5f),
                    IndicatorLevel("VAH", hi, IndicatorColors.VAH),
                    IndicatorLevel("VAL", hi, IndicatorColors.VAL)
                )
            )
        }
        val binCount = bins.coerceIn(1, 1000)
        val width = (hi - lo) / binCount
        val hist = DoubleArray(binCount)
        for (i in from until n) {
            val c = candles[i]
            if (c.v <= 0.0) continue
            val low = max(lo, min(hi, c.l))
            val high = max(lo, min(hi, c.h))
            if (high - low <= 1e-12) {
                val b = (((c.c - lo) / width).toInt()).coerceIn(0, binCount - 1)
                hist[b] += c.v
                continue
            }
            val first = (((low - lo) / width).toInt()).coerceIn(0, binCount - 1)
            val last = (((high - lo) / width).toInt()).coerceIn(0, binCount - 1)
            for (b in first..last) {
                val binLow = lo + b * width
                val binHigh = binLow + width
                val overlap = min(high, binHigh) - max(low, binLow)
                if (overlap > 0.0) hist[b] += c.v * overlap / (high - low)
            }
        }
        var poc = 0
        for (b in 1 until binCount) if (hist[b] > hist[poc]) poc = b
        var total = 0.0
        for (v in hist) total += v
        val target = total * valueAreaPct / 100.0
        var lower = poc
        var upper = poc
        var accumulated = hist[poc]
        while (accumulated < target && (lower > 0 || upper < binCount - 1)) {
            val upVol = if (upper + 1 < binCount) hist[upper + 1] else -1.0
            val downVol = if (lower - 1 >= 0) hist[lower - 1] else -1.0
            if (upVol >= downVol) {
                upper++
                accumulated += upVol
            } else {
                lower--
                accumulated += downVol
            }
        }
        val pocPrice = lo + (poc + 0.5) * width
        val valPrice = lo + lower * width
        val vahPrice = lo + (upper + 1) * width
        return IndicatorResult(
            levels = listOf(
                IndicatorLevel("VAH", vahPrice, IndicatorColors.VAH, LevelStyle.DASHED, 1f),
                IndicatorLevel("POC", pocPrice, IndicatorColors.POC, LevelStyle.SOLID, 1.6f),
                IndicatorLevel("VAL", valPrice, IndicatorColors.VAL, LevelStyle.DASHED, 1f)
            ),
            regions = listOf(
                IndicatorRegion("价值区", valPrice, vahPrice, IndicatorColors.POC, 0.07f)
            )
        )
    }

    // ---- 整数关口 ----

    internal fun roundLevels(last: Double, stepParam: Double, count: Int): List<IndicatorLevel> {
        if (!last.isFinite() || last <= 0.0) return emptyList()
        val step = if (stepParam > 0.0) stepParam else autoRoundStep(last)
        if (step <= 0.0) return emptyList()
        val base = floor(last / step) * step
        val out = ArrayList<IndicatorLevel>(count * 2 + 1)
        for (k in 1..count) {
            out.add(
                IndicatorLevel(
                    "压力 ${plainPrice(base + k * step)}",
                    base + k * step,
                    IndicatorColors.RESISTANCE,
                    width = 1f
                )
            )
        }
        if (base < last - step * 1e-9) {
            out.add(IndicatorLevel("支撑 ${plainPrice(base)}", base, IndicatorColors.SUPPORT, width = 1f))
        }
        for (k in 1..count) {
            out.add(
                IndicatorLevel(
                    "支撑 ${plainPrice(base - k * step)}",
                    base - k * step,
                    IndicatorColors.SUPPORT,
                    width = 1f
                )
            )
        }
        return out
    }

    internal fun autoRoundStep(price: Double): Double {
        if (price <= 0.0) return 1.0
        val magnitude = floor(log10(price))
        return 10.0.pow(magnitude - 2.0)
    }

    private fun plainPrice(value: Double): String {
        val rounded = kotlin.math.round(value)
        return if (abs(value - rounded) < 1e-9) {
            rounded.toLong().toString()
        } else {
            val scaled = kotlin.math.round(value * 1e8) / 1e8
            scaled.toString()
        }
    }

    private const val DAY_MS = 86_400_000L
}
