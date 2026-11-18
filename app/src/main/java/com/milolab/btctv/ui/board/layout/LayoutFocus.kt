package com.milolab.btctv.ui.board.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class FocusDirection { Up, Down, Left, Right }

/**
 * D-pad 几何焦点导航（设计文档 §7.3）。候选先按中心点方向过滤，
 * 再按「主轴距离 + 垂直偏移×0.5 + 无交叉重叠惩罚」选最近者；
 * 没有候选时返回 null（Board 据此把 ◀▶ 变成切周期）。
 */
object LayoutFocus {

    fun next(
        currentSlotId: String,
        rects: Map<String, LayoutRect>,
        direction: FocusDirection,
        eps: Float = 0.5f
    ): String? {
        val current = rects[currentSlotId] ?: return null
        return next(
            current = current,
            candidates = rects.filterKeys { it != currentSlotId }.map { it.key to it.value },
            direction = direction,
            eps = eps
        )
    }

    fun next(
        current: LayoutRect,
        candidates: List<Pair<String, LayoutRect>>,
        direction: FocusDirection,
        eps: Float = 0.5f
    ): String? {
        val pool = candidates.filter { (_, r) ->
            when (direction) {
                FocusDirection.Right -> r.centerX > current.centerX + eps
                FocusDirection.Left -> r.centerX < current.centerX - eps
                FocusDirection.Down -> r.centerY > current.centerY + eps
                FocusDirection.Up -> r.centerY < current.centerY - eps
            }
        }
        if (pool.isEmpty()) return null

        return pool.minByOrNull { (_, r) -> score(current, r, direction) }?.first
    }

    private fun score(current: LayoutRect, candidate: LayoutRect, direction: FocusDirection): Float {
        val primary = when (direction) {
            FocusDirection.Right -> candidate.x - current.right
            FocusDirection.Left -> current.x - candidate.right
            FocusDirection.Down -> candidate.y - current.bottom
            FocusDirection.Up -> current.y - candidate.bottom
        }.coerceAtLeast(0f)

        val perpendicular = when (direction) {
            FocusDirection.Right, FocusDirection.Left -> abs(candidate.centerY - current.centerY)
            FocusDirection.Up, FocusDirection.Down -> abs(candidate.centerX - current.centerX)
        }

        val crossOverlap = when (direction) {
            FocusDirection.Right, FocusDirection.Left ->
                overlap(current.y, current.bottom, candidate.y, candidate.bottom)
            FocusDirection.Up, FocusDirection.Down ->
                overlap(current.x, current.right, candidate.x, candidate.right)
        }

        val penalty = if (crossOverlap > 0f) 0f else 1_000f
        return primary + perpendicular * 0.5f + penalty
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float =
        max(0f, min(a1, b1) - max(a0, b0))
}
