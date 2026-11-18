package com.milolab.btctv.ui.board

import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SplitDirection
import com.milolab.btctv.ui.board.layout.SplitRatio
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.modules.ChartDensity
import com.milolab.btctv.ui.board.modules.MainChartData
import com.milolab.btctv.ui.board.modules.MiniChartData
import com.milolab.btctv.ui.board.modules.MoversData
import com.milolab.btctv.ui.board.modules.PriceCardData
import com.milolab.btctv.ui.board.modules.StatTileData
import com.milolab.btctv.ui.board.modules.TickerStripData
import com.milolab.btctv.ui.board.modules.WatchlistData

/** 一个槽位当前要画的模块数据（B2 类型，集成层负责填充）。 */
sealed interface SlotContent {
    data class Main(
        val data: MainChartData,
        val density: ChartDensity
    ) : SlotContent

    data class Mini(val data: MiniChartData) : SlotContent
    data class Card(val data: PriceCardData) : SlotContent
    data class Stat(val data: StatTileData) : SlotContent
    data class Watch(val data: WatchlistData) : SlotContent
    data class Movers(val data: MoversData) : SlotContent
    data class Ticker(val data: TickerStripData) : SlotContent
}

/**
 * 看板（非经典布局）的全部界面状态。只放纯数据，便于离屏预览与单测。
 * [fullscreenSlotId] 非空时进入单格全屏；BACK 只退出全屏、不离开看板。
 */
data class BoardUiState(
    val spec: LayoutSpec = LayoutPresets.classic(),
    val customSpec: LayoutSpec? = null,
    val focusedSlotId: String? = null,
    val fullscreenSlotId: String? = null,
    /** 非空表示搜索弹层是为该槽位改标的，而不是切全局主标的 */
    val searchTargetSlotId: String? = null,
    val contents: Map<String, SlotContent> = emptyMap(),
    /** 非阻塞提示（非法编辑、该格没有周期、已恢复经典布局…） */
    val hint: String? = null
) {
    val active: Boolean get() = !spec.legacy

    val focusId: String?
        get() = fullscreenSlotId ?: focusedSlotId ?: spec.slots.keys.firstOrNull()

    fun slotLabel(slotId: String?): String {
        if (slotId == null) return "格"
        val index = spec.root.leafSlotIds().indexOf(slotId)
        return if (index >= 0) "第 ${index + 1} 格" else "该格"
    }
}

/**
 * 看板专用的界面动作；与经典界面的 [com.milolab.btctv.ui.UiActions] 并存。
 * 参考设计文档 §6.4.4，用一个聚合对象避免每个 Composable 都长一串参数。
 */
data class BoardActions(
    val applyPreset: (String) -> Unit = {},
    val applyCustomSpec: (LayoutSpec) -> Unit = {},
    val restoreClassic: () -> Unit = {},
    val focusSlot: (String) -> Unit = {},
    val enterFullscreen: (String?) -> Unit = {},
    val openSlotSearch: (String) -> Unit = {},
    val assignSlotInstrument: (String, Instrument) -> Unit = { _, _ -> },
    val setSlotBinding: (String, SlotBinding) -> Unit = { _, _ -> },
    val setSlotTimeframe: (String, TF?) -> Unit = { _, _ -> },
    val setSlotModule: (String, ModuleType) -> Unit = { _, _ -> },
    val setSlotParam: (String, String, String) -> Unit = { _, _, _ -> },
    val splitSlot: (String, SplitDirection, SplitRatio) -> Unit = { _, _, _ -> },
    val mergeSlot: (String) -> Unit = {},
    val deleteSlot: (String) -> Unit = {},
    val startCustom: () -> Unit = {},
    val updateCustom: (LayoutSpec) -> Unit = {},
    val saveCustom: () -> Unit = {},
    val clearCustom: () -> Unit = {},
    val showHint: (String) -> Unit = {},
    val dismissHint: () -> Unit = {}
)
