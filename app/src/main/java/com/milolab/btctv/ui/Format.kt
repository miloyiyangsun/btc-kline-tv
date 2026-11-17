package com.milolab.btctv.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtPrice(p: Double): String = when {
    p >= 1000 -> String.format(Locale.US, "%,.2f", p)
    p >= 1 -> String.format(Locale.US, "%.2f", p)
    p >= 0.01 -> String.format(Locale.US, "%.4f", p)
    else -> String.format(Locale.US, "%.6f", p)
}

/** 小数位完全由 Instrument.priceDecimals 决定 */
fun fmtPrice(p: Double, decimals: Int): String {
    val d = decimals.coerceIn(0, 8)
    val pattern = if (d == 0) "%,.0f" else "%,.${d}f"
    return String.format(Locale.US, pattern, p)
}

fun fmtAxis(p: Double): String = when {
    p >= 1000 -> String.format(Locale.US, "%,.0f", p)
    p >= 1 -> String.format(Locale.US, "%.2f", p)
    else -> String.format(Locale.US, "%.4f", p)
}

/** 坐标轴按标的精度显示；大数保留整数刻度避免标签过长 */
fun fmtAxis(p: Double, decimals: Int): String = when {
    p >= 1000 -> String.format(Locale.US, "%,.0f", p)
    else -> fmtPrice(p, decimals)
}

fun fmtSigned(p: Double): String {
    val s = fmtPrice(kotlin.math.abs(p))
    return if (p >= 0) "+$s" else "-$s"
}

fun fmtSigned(p: Double, decimals: Int): String {
    val s = fmtPrice(kotlin.math.abs(p), decimals)
    return if (p >= 0) "+$s" else "-$s"
}

fun fmtPct(p: Double): String = String.format(Locale.US, "%+.2f%%", p)

/** 指标数值：价格类不要科学计数，副图小数值保留足够精度 */
fun fmtIndicator(v: Double): String {
    if (!v.isFinite()) return "—"
    val a = kotlin.math.abs(v)
    return when {
        a >= 1_000_000 -> String.format(Locale.US, "%,.0f", v)
        a >= 1000 -> String.format(Locale.US, "%,.1f", v)
        a >= 100 -> String.format(Locale.US, "%.1f", v)
        a >= 1 -> String.format(Locale.US, "%.2f", v)
        a == 0.0 -> "0"
        a >= 0.01 -> String.format(Locale.US, "%.4f", v)
        else -> String.format(Locale.US, "%.6f", v)
    }
}

fun fmtVol(v: Double): String = when {
    v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.2f", v)
}

fun timeHms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun timeHm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun dateTime(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
