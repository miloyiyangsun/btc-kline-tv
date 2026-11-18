package com.milolab.btctv.ui.board.layout

import com.milolab.btctv.data.TF

/** 分屏方向：Horizontal 沿 x 轴左右切，Vertical 沿 y 轴上下切。 */
enum class SplitDirection { Horizontal, Vertical }

enum class StatKind { BTCD, TOTAL, STABLE_CAP, STABLE_DOM }

/** 看板可放置的模块类型；StatTile 自带指标种类。 */
sealed interface ModuleType {
    data object MainChart : ModuleType
    data object MiniChart : ModuleType
    data object PriceCard : ModuleType
    data class StatTile(val stat: StatKind) : ModuleType
    data object Watchlist : ModuleType
    data object Movers : ModuleType
    data object TickerStrip : ModuleType
}

/** 槽位数据来源：固定标的（存 Instrument.id）或跟随全局主标的。 */
sealed interface SlotBinding {
    data object FollowMain : SlotBinding
    data class Fixed(val instrumentId: String) : SlotBinding
}

/**
 * 模块参数。只放展示相关配置，不放标的（那是 binding）和周期（Slot.timeframe）。
 * 具体键见 [SlotParamKeys]。
 */
data class Slot(
    val id: String,
    val module: ModuleType,
    val binding: SlotBinding = SlotBinding.FollowMain,
    val params: Map<String, String> = emptyMap(),
    val timeframe: TF? = null
)

sealed interface LayoutNode {
    data class Split(
        val direction: SplitDirection,
        val weights: List<Float>,
        val children: List<LayoutNode>
    ) : LayoutNode

    data class Leaf(val slotId: String) : LayoutNode
}

/**
 * 一个完整的看板布局。slots 以 id 索引，Leaf 只保存 id；
 * [topChrome] 是内容区顶部保留给共享头部的设计高度（1920×1080 单位），0 表示没有。
 */
data class LayoutSpec(
    val id: String,
    val name: String,
    val root: LayoutNode,
    val slots: Map<String, Slot>,
    val topChrome: Float = 0f
) {
    val legacy: Boolean get() = id == LayoutPresets.CLASSIC

    fun slot(id: String): Slot? = slots[id]
}

/** 预置/UI 共用的参数键与取值。 */
object SlotParamKeys {
    const val DETAIL = "detail"
    const val SPARK_WINDOW = "sparkWindow"
    const val SHOW_SPARK = "showSpark"
    const val SPEED = "speed"
    const val SCOPE = "scope"
    const val METRIC = "metric"
    const val LIMIT = "limit"
    const val SOURCE = "source"
    const val INCLUDE_OVERVIEW = "includeOverview"

    const val DETAIL_FULL = "FULL"
    const val DETAIL_COMPACT = "COMPACT"

    const val WINDOW_H1 = "H1"
    const val WINDOW_H24 = "H24"
    const val WINDOW_D7 = "D7"

    const val SPEED_SLOW = "SLOW"
    const val SPEED_NORMAL = "NORMAL"
    const val SPEED_FAST = "FAST"

    const val SCOPE_SPOT_USDT = "SPOT_USDT"
    const val SCOPE_SWAP_USDT = "SWAP_USDT"
    const val SCOPE_ALL_OKX = "ALL_OKX"

    const val METRIC_GAINERS = "GAINERS"
    const val METRIC_LOSERS = "LOSERS"

    const val SOURCE_FAVORITES = "FAVORITES"
    const val SOURCE_HOT = "HOT"
    const val SOURCE_CUSTOM = "CUSTOM"
}

fun LayoutNode.leaves(): List<LayoutNode.Leaf> = when (this) {
    is LayoutNode.Leaf -> listOf(this)
    is LayoutNode.Split -> children.flatMap { it.leaves() }
}

fun LayoutNode.leafSlotIds(): List<String> = leaves().map { it.slotId }

fun LayoutNode.depth(): Int = when (this) {
    is LayoutNode.Leaf -> 1
    is LayoutNode.Split -> 1 + (children.maxOfOrNull { it.depth() } ?: 0)
}

fun LayoutSpec.leafSlots(): List<Slot> = root.leafSlotIds().mapNotNull { slots[it] }
