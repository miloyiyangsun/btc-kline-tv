package com.milolab.btctv.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.C
import com.milolab.btctv.ui.board.layout.LayoutGeometry
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutRect
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.Slot
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SplitDirection
import com.milolab.btctv.ui.board.layout.SplitRatio
import com.milolab.btctv.ui.board.layout.StatKind
import com.milolab.btctv.ui.board.layout.leafSlotIds
import kotlin.math.min

private const val CUSTOM_ID = "custom"
private val FocusBorder = Color(0xFFFFD79A)

/**
 * 设置页的「布局」区：15 个预置 + 自定义编辑器。
 * 选中即应用并持久化（Prefs.layout）；编辑操作自动另存为 custom。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutSettingsSection(
    board: BoardUiState,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("布局", color = C.Text, fontSize = (18f * s).sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width((10f * s).dp))
        Text(
            if (board.active) board.spec.name.ifBlank { "自定义布局" } else "经典单图",
            color = accent.copy(alpha = 0.9f),
            fontSize = (12f * s).sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (board.active) "OK 改标的/全屏 · MENU 设置" else "选一个预置进入多格看板",
            color = C.Dimmer,
            fontSize = (11f * s).sp
        )
    }
    Spacer(Modifier.height((4f * s).dp))
    Text(
        "点击立即生效并保存。非经典布局才启用分屏看板；经典布局保持原来的单图界面。",
        color = C.Dimmer,
        fontSize = (11.5f * s).sp
    )
    board.hint?.let {
        Spacer(Modifier.height((5f * s).dp))
        Text(it, color = Color(0xFFFFC46B), fontSize = (12f * s).sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height((10f * s).dp))

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
    var focusTaken by remember { mutableStateOf(false) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((10f * s).dp),
        verticalArrangement = Arrangement.spacedBy((10f * s).dp),
        maxItemsInEachRow = 3
    ) {
        LayoutPresets.all.forEach { preset ->
            val selected = board.active && board.spec.id == preset.id
            val takeFocus = !focusTaken && (selected || board.spec.id !in LayoutPresets.all.map { it.id })
            if (takeFocus) focusTaken = true
            LayoutCard(
                name = preset.name,
                spec = preset,
                selected = selected,
                accent = accent,
                s = s,
                focusRequester = if (takeFocus) initialFocus else null
            ) { b.applyPreset(preset.id) }
        }
        val custom = board.customSpec
        if (custom != null) {
            val selected = board.spec.id == CUSTOM_ID
            val takeFocus = !focusTaken && selected
            if (takeFocus) focusTaken = true
            LayoutCard(
                name = "自定义布局",
                spec = custom,
                selected = selected,
                accent = accent,
                s = s,
                focusRequester = if (takeFocus) initialFocus else null
            ) { b.applyCustomSpec(custom) }
        }
    }

    Spacer(Modifier.height((12f * s).dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallChip("进入自定义编辑", board.spec.id == CUSTOM_ID, accent, s) { b.startCustom() }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("保存自定义布局", false, accent, s) { b.saveCustom() }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("清除自定义布局", false, accent, s) { b.clearCustom() }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("恢复经典布局", !board.active, accent, s) { b.restoreClassic() }
    }

    Spacer(Modifier.height((14f * s).dp))
    SlotEditor(board, b, accent, s)
}

// ------------------------------------------------------------------ 预置卡片

@Composable
private fun LayoutCard(
    name: String,
    spec: LayoutSpec,
    selected: Boolean,
    accent: Color,
    s: Float,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((9f * s).dp)
    val borderColor = when {
        focused -> FocusBorder
        selected -> accent
        else -> C.Grid
    }
    Column(
        Modifier
            .width((236f * s).dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else C.PanelHigh)
            .border((if (focused || selected) 2f else 1f).dp, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding((7f * s).dp)
    ) {
        LayoutThumb(
            spec = spec,
            accent = accent,
            selected = selected,
            modifier = Modifier
                .fillMaxWidth()
                .height((86f * s).dp)
        )
        Spacer(Modifier.height((5f * s).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                color = if (selected || focused) C.Text else C.Text.copy(alpha = 0.82f),
                fontSize = (12f * s).sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Text("✓", color = accent, fontSize = (12f * s).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 按 LayoutGeometry 画布局缩略图；模块只画占位矩形，不画真实数据。 */
@Composable
private fun LayoutThumb(
    spec: LayoutSpec,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF0A0D14))
    ) {
        val rects = LayoutGeometry.solve(spec, LayoutRect(0f, 0f, 1920f, 1080f))
        val scale = min(size.width / 1920f, size.height / 1080f)
        val ox = (size.width - 1920f * scale) / 2f
        val oy = (size.height - 1080f * scale) / 2f
        for (rect in rects.values) {
            if (rect.width <= 0f || rect.height <= 0f) continue
            drawRoundRect(
                color = accent.copy(alpha = if (selected) 0.34f else 0.18f),
                topLeft = Offset(ox + rect.x * scale, oy + rect.y * scale),
                size = Size(rect.width * scale, rect.height * scale),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

// ------------------------------------------------------------------ 自定义编辑

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(
    board: BoardUiState,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    val ids = board.spec.root.leafSlotIds()
    val focusId = board.focusedSlotId?.takeIf { it in ids } ?: ids.firstOrNull()
    val slot = focusId?.let { board.spec.slots[it] }

    Text(
        "自定义编辑",
        color = C.Text,
        fontSize = (15f * s).sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height((3f * s).dp))
    Text(
        "先选一格，再拆分/合并/换模块。编辑会自动保存为「自定义布局」，最多 9 格。",
        color = C.Dimmer,
        fontSize = (11f * s).sp
    )
    Spacer(Modifier.height((8f * s).dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        ids.forEachIndexed { index, id ->
            SmallChip("第 ${index + 1} 格", id == focusId, accent, s) { b.focusSlot(id) }
        }
    }

    if (slot == null || focusId == null) {
        Spacer(Modifier.height((6f * s).dp))
        Text("当前布局没有可编辑的格子", color = C.Dimmer, fontSize = (11.5f * s).sp)
        return
    }

    Spacer(Modifier.height((6f * s).dp))
    Text(
        "当前：${moduleLabel(slot.module)} · ${bindingLabel(slot.binding)}" +
            if (slot.timeframe != null) " · 周期 ${slot.timeframe.label}" else "",
        color = C.Text.copy(alpha = 0.85f),
        fontSize = (12f * s).sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height((8f * s).dp))

    EditorSplitRows(focusId, b, accent, s)
    Spacer(Modifier.height((8f * s).dp))

    Row {
        SmallChip("合并该格", false, accent, s) { b.mergeSlot(focusId) }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("删除该格", false, accent, s) { b.deleteSlot(focusId) }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("改标的…", false, accent, s) { b.openSlotSearch(focusId) }
    }

    Spacer(Modifier.height((10f * s).dp))
    EditorModuleRow(focusId, slot, b, accent, s)
    Spacer(Modifier.height((10f * s).dp))
    EditorBindingRow(focusId, slot, b, accent, s)
    Spacer(Modifier.height((10f * s).dp))
    EditorTimeframeRow(focusId, slot, b, accent, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorSplitRows(
    slotId: String,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        EditorLabel("左右")
        SmallChip("1:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Horizontal, SplitRatio.OneToOne)
        }
        SmallChip("1:2", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Horizontal, SplitRatio.OneToTwo)
        }
        SmallChip("2:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Horizontal, SplitRatio.TwoToOne)
        }
        SmallChip("1:1:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Horizontal, SplitRatio.OneToOneOne)
        }
        Spacer(Modifier.width((10f * s).dp))
        EditorLabel("上下")
        SmallChip("1:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Vertical, SplitRatio.OneToOne)
        }
        SmallChip("1:2", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Vertical, SplitRatio.OneToTwo)
        }
        SmallChip("2:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Vertical, SplitRatio.TwoToOne)
        }
        SmallChip("1:1:1", false, accent, s) {
            b.splitSlot(slotId, SplitDirection.Vertical, SplitRatio.OneToOneOne)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorModuleRow(
    slotId: String,
    slot: Slot,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    EditorLabel("模块")
    Spacer(Modifier.height((4f * s).dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        moduleChip(slot, ModuleType.MainChart, "主图", b, accent, s)
        moduleChip(slot, ModuleType.MiniChart, "迷你图", b, accent, s)
        moduleChip(slot, ModuleType.PriceCard, "价格卡", b, accent, s)
        moduleChip(slot, ModuleType.StatTile(StatKind.BTCD), "BTC 占比", b, accent, s)
        moduleChip(slot, ModuleType.StatTile(StatKind.TOTAL), "总市值", b, accent, s)
        moduleChip(slot, ModuleType.StatTile(StatKind.STABLE_CAP), "稳定币市值", b, accent, s)
        moduleChip(slot, ModuleType.StatTile(StatKind.STABLE_DOM), "稳定币占比", b, accent, s)
        moduleChip(slot, ModuleType.Watchlist, "自选列表", b, accent, s)
        moduleChip(slot, ModuleType.Movers, "涨跌榜", b, accent, s)
        moduleChip(slot, ModuleType.TickerStrip, "跑马灯", b, accent, s)
    }
}

@Composable
private fun moduleChip(
    slot: Slot,
    module: ModuleType,
    label: String,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    SmallChip(label, slot.module == module, accent, s) { b.setSlotModule(slot.id, module) }
}

@Composable
private fun EditorBindingRow(
    slotId: String,
    slot: Slot,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    EditorLabel("标的")
    Spacer(Modifier.height((4f * s).dp))
    Row {
        SmallChip(
            "跟随主标的",
            slot.binding is SlotBinding.FollowMain,
            accent,
            s
        ) { b.setSlotBinding(slotId, SlotBinding.FollowMain) }
        Spacer(Modifier.width((8f * s).dp))
        SmallChip("固定标的…", slot.binding is SlotBinding.Fixed, accent, s) {
            b.openSlotSearch(slotId)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorTimeframeRow(
    slotId: String,
    slot: Slot,
    b: BoardActions,
    accent: Color,
    s: Float
) {
    EditorLabel("周期")
    Spacer(Modifier.height((4f * s).dp))
    if (!moduleSupportsTimeframe(slot.module)) {
        Text("该格没有周期（列表/统计类模块）", color = C.Dimmer, fontSize = (11.5f * s).sp)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        SmallChip("跟随主周期", slot.timeframe == null, accent, s) {
            b.setSlotTimeframe(slotId, null)
        }
        TF.entries.forEach { tf ->
            SmallChip(tf.label, slot.timeframe == tf, accent, s) {
                b.setSlotTimeframe(slotId, tf)
            }
        }
    }
}

// ------------------------------------------------------------------ 小部件

@Composable
private fun EditorLabel(text: String) {
    Text(
        text,
        color = C.Dim,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun SmallChip(
    label: String,
    selected: Boolean,
    accent: Color,
    s: Float,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((7f * s).dp)
    Text(
        label,
        color = when {
            selected -> Color(0xFF0A0C12)
            focused -> C.Text
            else -> C.Dim
        },
        fontSize = (11.5f * s).sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent else C.PanelHigh)
            .border(
                (if (focused) 2f else 1f).dp,
                if (focused) FocusBorder else if (selected) accent else C.Grid,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = (9f * s).dp, vertical = (5f * s).dp)
    )
}

// ------------------------------------------------------------------ 文案

private fun moduleLabel(module: ModuleType): String = when (module) {
    ModuleType.MainChart -> "主图"
    ModuleType.MiniChart -> "迷你图"
    ModuleType.PriceCard -> "价格卡"
    is ModuleType.StatTile -> when (module.stat) {
        StatKind.BTCD -> "BTC 占比"
        StatKind.TOTAL -> "总市值"
        StatKind.STABLE_CAP -> "稳定币市值"
        StatKind.STABLE_DOM -> "稳定币占比"
    }

    ModuleType.Watchlist -> "自选列表"
    ModuleType.Movers -> "涨跌榜"
    ModuleType.TickerStrip -> "跑马灯"
}

private fun bindingLabel(binding: SlotBinding): String = when (binding) {
    is SlotBinding.FollowMain -> "跟随主标的"
    is SlotBinding.Fixed -> {
        val inst = Instruments.byId(binding.instrumentId)
        "固定 " + (inst?.baseCurrency ?: binding.instrumentId)
    }
}
