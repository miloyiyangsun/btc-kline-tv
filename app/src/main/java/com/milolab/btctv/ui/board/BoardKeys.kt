package com.milolab.btctv.ui.board

import com.milolab.btctv.ui.board.layout.FocusDirection

/** 看板所处的交互模式（与设计文档 §7.1 一致）。 */
enum class BoardMode { BOARD, FULLSCREEN, SEARCH, SETTINGS }

/** 与 Compose Key 解耦的按键枚举，方便纯单测。 */
enum class BoardKey { Left, Right, Up, Down, Ok, Back, Menu }

sealed interface BoardIntent {
    data class MoveFocus(val direction: FocusDirection) : BoardIntent
    data class ChangeTf(val delta: Int) : BoardIntent

    /** 非图表格且没有邻居：短暂提示「该格没有周期」 */
    data object HintNoTimeframe : BoardIntent
    data object EnterFullscreen : BoardIntent
    data object ExitFullscreen : BoardIntent

    /** 非主图格：打开改标的搜索，选中后写 Fixed 绑定 */
    data object OpenSlotSearch : BoardIntent
    data object OpenSettings : BoardIntent
    data object CloseSearch : BoardIntent
    data object None : BoardIntent
}

/**
 * 看板按键路由。经典布局完全不经过这里，仍走 [com.milolab.btctv.ui.routeKey]。
 *
 * 与设计文档 §7.2 的差异（按本任务的产品决定）：
 * - OK：主图格进入全屏，其余格打开改标的搜索；
 * - MENU：打开设置（设置里有「布局」区）。
 *
 * @param focusedIsChart 当前格是否图表类（主图/迷你图/价格卡）
 * @param focusedIsMainChart 当前格是否主图模块（OK 全屏）
 * @param hasHorizontalNeighbor 当前格在对应方向是否有几何邻居
 * @param longPress ◀▶ 按住 ≥400ms（强制切周期）
 */
fun routeBoardKey(
    mode: BoardMode,
    key: BoardKey,
    focusedIsChart: Boolean = true,
    focusedIsMainChart: Boolean = false,
    hasHorizontalNeighbor: Boolean = false,
    longPress: Boolean = false
): BoardIntent {
    when (mode) {
        BoardMode.SEARCH -> return if (key == BoardKey.Back) BoardIntent.CloseSearch else BoardIntent.None
        BoardMode.SETTINGS -> return BoardIntent.None
        BoardMode.FULLSCREEN -> return when (key) {
            BoardKey.Left -> BoardIntent.ChangeTf(-1)
            BoardKey.Right -> BoardIntent.ChangeTf(1)
            BoardKey.Menu -> BoardIntent.OpenSettings
            BoardKey.Back, BoardKey.Ok -> BoardIntent.ExitFullscreen
            else -> BoardIntent.None
        }

        BoardMode.BOARD -> Unit
    }

    return when (key) {
        BoardKey.Left, BoardKey.Right -> {
            val delta = if (key == BoardKey.Left) -1 else 1
            when {
                longPress -> BoardIntent.ChangeTf(delta)
                hasHorizontalNeighbor ->
                    BoardIntent.MoveFocus(if (delta < 0) FocusDirection.Left else FocusDirection.Right)

                focusedIsChart -> BoardIntent.ChangeTf(delta)
                else -> BoardIntent.HintNoTimeframe
            }
        }

        BoardKey.Up -> BoardIntent.MoveFocus(FocusDirection.Up)
        BoardKey.Down -> BoardIntent.MoveFocus(FocusDirection.Down)
        BoardKey.Ok ->
            if (focusedIsMainChart) BoardIntent.EnterFullscreen else BoardIntent.OpenSlotSearch

        BoardKey.Menu -> BoardIntent.OpenSettings
        BoardKey.Back -> BoardIntent.None
    }
}

/** 布局编辑是否允许该格切周期：只有图表类模块有“周期”概念。 */
fun moduleSupportsTimeframe(module: com.milolab.btctv.ui.board.layout.ModuleType): Boolean = when (module) {
    is com.milolab.btctv.ui.board.layout.ModuleType.MainChart,
    is com.milolab.btctv.ui.board.layout.ModuleType.MiniChart,
    is com.milolab.btctv.ui.board.layout.ModuleType.PriceCard -> true

    else -> false
}
