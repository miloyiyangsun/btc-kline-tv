package com.milolab.btctv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milolab.btctv.ui.SettingsBackAction
import com.milolab.btctv.ui.TvScreenInner
import com.milolab.btctv.ui.UiActions
import com.milolab.btctv.ui.board.BoardActions
import com.milolab.btctv.ui.settingsBackAction

/** 把 ViewModel 接到纯 Compose 界面上 */
@Composable
fun TvScreen(vm: MarketViewModel) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()

    // 返回键：搜索 → 展开的参数 → 设置面板 → 看板全屏 → 退出 App。
    // 看板根层不启用 BackHandler，交给系统默认的退出行为。
    BackHandler(enabled = st.searchOpen || st.settingsOpen || board.fullscreenSlotId != null) {
        when {
            st.searchOpen -> vm.closeSearch()
            st.settingsOpen &&
                settingsBackAction(st.expandedIndicator) == SettingsBackAction.COLLAPSE_PARAMS ->
                vm.expandIndicator(null)

            st.settingsOpen -> vm.closeSettings()
            board.fullscreenSlotId != null -> vm.boardExitFullscreen()
        }
    }

    TvScreenInner(
        st = st,
        act = UiActions(
            setTf = vm::setTf,
            setStyle = vm::setStyle,
            zoom = vm::zoom,
            openSettings = vm::openSettings,
            closeSettings = vm::closeSettings,
            toggleFlash = vm::toggleFlash,
            toggleAntiBurn = vm::toggleAntiBurn,
            toggleClock = vm::toggleClock,
            toggleVolume = vm::toggleVolume,
            toggleAutoStart = vm::toggleAutoStart,
            reconnect = vm::reconnect,
            toggleIndicator = vm::toggleIndicator,
            stepIndicatorParam = vm::stepIndicatorParam,
            expandIndicator = vm::expandIndicator,
            toggleIndicatorGroup = vm::toggleIndicatorGroup,
            allIndicatorsOff = vm::allIndicatorsOff,
            resetIndicators = vm::resetIndicators,
            openSearch = vm::openSearch,
            closeSearch = vm::closeSearch,
            setQuery = vm::setQuery,
            setFilter = vm::setFilter,
            selectInstrument = vm::selectInstrument,
            toggleFavorite = vm::toggleFavorite
        ),
        board = board,
        b = BoardActions(
            applyPreset = vm::boardApplyPreset,
            applyCustomSpec = vm::boardApplySpec,
            restoreClassic = vm::boardRestoreClassic,
            focusSlot = vm::boardFocusSlot,
            enterFullscreen = vm::boardEnterFullscreen,
            openSlotSearch = vm::boardOpenSlotSearch,
            assignSlotInstrument = vm::assignSlotInstrument,
            setSlotBinding = vm::boardSetSlotBinding,
            setSlotTimeframe = vm::boardSetSlotTimeframe,
            setSlotModule = vm::boardSetSlotModule,
            setSlotParam = vm::boardSetSlotParam,
            splitSlot = vm::boardSplitSlot,
            mergeSlot = vm::boardMergeSlot,
            deleteSlot = vm::boardDeleteSlot,
            startCustom = vm::boardStartCustom,
            updateCustom = vm::boardApplySpec,
            saveCustom = vm::boardSaveCustom,
            clearCustom = vm::boardClearCustom,
            showHint = vm::showBoardHint,
            dismissHint = vm::dismissBoardHint
        )
    )
}
