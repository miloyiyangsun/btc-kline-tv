package com.milolab.btctv.ui.board.layout

import kotlin.math.min

/** 纯浮点矩形，与 dp/px 无关；x/y 为左上角。 */
data class LayoutRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
    val area: Float get() = width * height

    fun overlaps(other: LayoutRect): Boolean =
        width > 0f && height > 0f && other.width > 0f && other.height > 0f &&
            x < other.right && other.x < right &&
            y < other.bottom && other.y < bottom
}

/**
 * 几何参数都是 1920×1080 设计画布单位。求解时按
 * scale = min(bounds.width/designWidth, bounds.height/designHeight) 等比缩放，
 * 因此 1280×720 / 1920×1080 / 3840×2160 得到完全一致的比例。
 * minCell 是分屏方向上的最小格尺寸（设计单位），空间不足时自动降到可用均分值。
 */
data class LayoutGeometryConfig(
    val margin: Float = 24f,
    val gap: Float = 12f,
    val designWidth: Float = 1920f,
    val designHeight: Float = 1080f,
    val minCell: Float = 48f
)

object LayoutGeometry {

    fun solve(
        spec: LayoutSpec,
        bounds: LayoutRect,
        config: LayoutGeometryConfig = LayoutGeometryConfig()
    ): Map<String, LayoutRect> {
        val width = bounds.width.coerceAtLeast(0f)
        val height = bounds.height.coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) {
            return spec.slots.keys.associateWith { LayoutRect(bounds.x, bounds.y, 0f, 0f) }
        }

        val scale = scaleOf(width, height, config)
        val margin = config.margin * scale
        val gap = config.gap * scale
        val minCell = config.minCell * scale

        val content = LayoutRect(
            x = bounds.x + margin,
            y = bounds.y + margin,
            width = (width - margin * 2f).coerceAtLeast(0f),
            height = (height - margin * 2f).coerceAtLeast(0f)
        )
        val chrome = (spec.topChrome.coerceAtLeast(0f) * scale)
        val chromeGap = if (chrome > 0f) gap else 0f
        val rootRect = LayoutRect(
            x = content.x,
            y = content.y + chrome + chromeGap,
            width = content.width,
            height = (content.height - chrome - chromeGap).coerceAtLeast(0f)
        )

        val out = LinkedHashMap<String, LayoutRect>()
        solveNode(spec.root, rootRect, gap, minCell, out)
        return out
    }

    fun solve(
        spec: LayoutSpec,
        width: Float,
        height: Float,
        config: LayoutGeometryConfig = LayoutGeometryConfig()
    ): Map<String, LayoutRect> = solve(spec, LayoutRect(0f, 0f, width, height), config)

    fun slotRect(
        spec: LayoutSpec,
        slotId: String,
        bounds: LayoutRect,
        config: LayoutGeometryConfig = LayoutGeometryConfig()
    ): LayoutRect? = solve(spec, bounds, config)[slotId]

    private fun scaleOf(width: Float, height: Float, config: LayoutGeometryConfig): Float {
        if (config.designWidth <= 0f || config.designHeight <= 0f) return 1f
        return min(width / config.designWidth, height / config.designHeight)
    }

    private fun solveNode(
        node: LayoutNode,
        rect: LayoutRect,
        gap: Float,
        minCell: Float,
        out: MutableMap<String, LayoutRect>
    ) {
        when (node) {
            is LayoutNode.Leaf -> out[node.slotId] = rect
            is LayoutNode.Split -> {
                val n = node.children.size
                if (n == 0) return
                val horizontal = node.direction == SplitDirection.Horizontal
                val length = if (horizontal) rect.width else rect.height
                val start = if (horizontal) rect.x else rect.y
                val end = if (horizontal) rect.right else rect.bottom
                val available = (length - gap * (n - 1)).coerceAtLeast(0f)
                val lengths = distribute(node.weights, n, available, minCell)

                var position = start
                for (i in 0 until n) {
                    val childLength = if (i == n - 1) {
                        (end - position).coerceAtLeast(0f)
                    } else {
                        lengths[i]
                    }
                    val child = if (horizontal) {
                        LayoutRect(position, rect.y, childLength, rect.height)
                    } else {
                        LayoutRect(rect.x, position, rect.width, childLength)
                    }
                    solveNode(node.children[i], child, gap, minCell, out)
                    position += childLength + gap
                }
            }
        }
    }

    /** 权重先净化（非有限/≤0 视为 1），再按最小格保护重新分配，保证总和 = available。 */
    private fun distribute(weights: List<Float>, n: Int, available: Float, minCell: Float): FloatArray {
        if (n <= 0) return FloatArray(0)
        if (available <= 0f) return FloatArray(n)

        val clean = FloatArray(n) { i ->
            val w = weights.getOrNull(i)
            if (w != null && w.isFinite() && w > 0f) w else 1f
        }
        val sum = clean.sum()
        val out = FloatArray(n) { i -> available * clean[i] / sum }

        val effectiveMin = minCell.coerceIn(0f, available / n)
        if (effectiveMin <= 0f) return out

        var deficit = 0f
        for (i in 0 until n) {
            if (out[i] < effectiveMin) {
                deficit += effectiveMin - out[i]
                out[i] = effectiveMin
            }
        }
        if (deficit <= 0f) return out

        val excess = (0 until n).sumOf { i -> (out[i] - effectiveMin).toDouble() }.toFloat()
        if (excess <= 0f) return out
        val ratio = (deficit / excess).coerceIn(0f, 1f)
        for (i in 0 until n) {
            val extra = out[i] - effectiveMin
            if (extra > 0f) out[i] -= extra * ratio
        }
        return out
    }
}
