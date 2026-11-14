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

fun fmtAxis(p: Double): String = when {
    p >= 1000 -> String.format(Locale.US, "%,.0f", p)
    p >= 1 -> String.format(Locale.US, "%.2f", p)
    else -> String.format(Locale.US, "%.4f", p)
}

fun fmtSigned(p: Double): String {
    val s = fmtPrice(kotlin.math.abs(p))
    return if (p >= 0) "+$s" else "-$s"
}

fun fmtPct(p: Double): String = String.format(Locale.US, "%+.2f%%", p)

fun fmtVol(v: Double): String = when {
    v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.2f", v)
}

fun timeHms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun timeHm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun dateTime(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
