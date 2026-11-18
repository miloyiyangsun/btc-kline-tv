package com.milolab.btctv.ui.board.modules

import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.StockKind
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// 只放 B2 自用的格式化/运动函数；不改 ui/Format.kt。

/** 市值缩写：$2.23T / $292.3B / $986.4B / $12.3M。 */
fun fmtUsdCompact(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    val a = abs(v)
    val scaled: Double
    val unit: String
    when {
        a >= 1e12 -> {
            scaled = v / 1e12
            unit = "T"
        }

        a >= 1e9 -> {
            scaled = v / 1e9
            unit = "B"
        }

        a >= 1e6 -> {
            scaled = v / 1e6
            unit = "M"
        }

        a >= 1e3 -> {
            scaled = v / 1e3
            unit = "K"
        }

        else -> {
            scaled = v
            unit = ""
        }
    }
    val sa = abs(scaled)
    val decimals = when {
        unit.isEmpty() -> if (sa < 100.0) 2 else 0
        sa >= 100.0 -> 1
        else -> 2
    }
    return "$" + String.format(Locale.US, "%,.${decimals}f", scaled) + unit
}

/** 大盘指标大字：占比类 58.45%，市值类 $2.23T；null 显示 —。 */
fun fmtStatValue(kind: StatKind, value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    return when (kind) {
        StatKind.BTCD, StatKind.STABLE_DOM ->
            String.format(Locale.US, "%.2f%%", value)

        StatKind.TOTAL, StatKind.STABLE_MCAP -> fmtUsdCompact(value)
    }
}

/** 带符号百分比（涨跌）：+2.34% / -0.80%；null 显示 —。 */
fun fmtPctSigned(v: Double?, decimals: Int = 2): String {
    if (v == null || !v.isFinite()) return "—"
    val d = decimals.coerceIn(0, 4)
    return String.format(Locale.US, "%+.${d}f%%", v)
}

/** 无符号百分比：58.45%；null 显示 —。 */
fun fmtPctPlain(v: Double?, decimals: Int = 2): String {
    if (v == null || !v.isFinite()) return "—"
    val d = decimals.coerceIn(0, 4)
    return String.format(Locale.US, "%.${d}f%%", v)
}

/** 过期文案：更新于 3 分钟前 / 更新于 2 小时前 / 已过期。 */
fun staleLabel(agoMinutes: Long?): String {
    if (agoMinutes == null || agoMinutes < 0) return "已过期"
    return when {
        agoMinutes <= 0L -> "刚刚更新"
        agoMinutes < 60L -> "更新于 $agoMinutes 分钟前"
        agoMinutes < 24L * 60L -> "更新于 ${agoMinutes / 60L} 小时前"
        else -> "更新于 ${agoMinutes / (24L * 60L)} 天前"
    }
}

/** 市场类型徽标：永续 / 代币现货 / 美股 / 加密。 */
fun marketBadge(instrument: Instrument): String = when {
    instrument.marketType == MarketType.CRYPTO -> "加密"
    instrument.stockKind == StockKind.PERP -> "永续"
    instrument.stockKind == StockKind.TOKENIZED -> "代币现货"
    else -> "美股"
}

/** 跑马灯位移（px）：匀速，按内容总宽循环。纯函数便于单测与确定性预览。 */
fun tickerOffsetPx(
    elapsedMs: Long,
    speed: TickerSpeed,
    density: Float,
    loopWidthPx: Float
): Float {
    if (loopWidthPx <= 0f || density <= 0f || elapsedMs <= 0L) return 0f
    val traveled = elapsedMs / 1000f * speed.dpPerSecond * density
    val wrapped = traveled % loopWidthPx
    return if (wrapped < 0f) wrapped + loopWidthPx else wrapped
}

/** anti-burn 微位移（dp）：随滚动进度做一次极慢的正弦纵向漂移，幅度 ±1.2dp。 */
fun antiBurnShiftDp(offsetPx: Float, loopWidthPx: Float): Float {
    if (loopWidthPx <= 0f) return 0f
    val phase = (offsetPx / loopWidthPx) * 2.0 * PI
    return (sin(phase) * 1.2).toFloat()
}
