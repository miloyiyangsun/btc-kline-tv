package com.milolab.btctv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milolab.btctv.ui.SettingsBackAction
import com.milolab.btctv.ui.TvScreenInner
import com.milolab.btctv.ui.UiActions
import com.milolab.btctv.ui.settingsBackAction

/** 把 ViewModel 接到纯 Compose 界面上 */
@Composable
fun TvScreen(vm: MarketViewModel) {
    val st by vm.ui.collectAsStateWithLifecycle()

    // 返回键：搜索 → 展开的参数 → 设置面板，最后才退出
    BackHandler(enabled = st.searchOpen || st.settingsOpen) {
        when {
            st.searchOpen -> vm.closeSearch()
            settingsBackAction(st.expandedIndicator) == SettingsBackAction.COLLAPSE_PARAMS ->
                vm.expandIndicator(null)
            else -> vm.closeSettings()
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
            selectInstrument = vm::setInstrument,
            toggleFavorite = vm::toggleFavorite
        )
    )
}
