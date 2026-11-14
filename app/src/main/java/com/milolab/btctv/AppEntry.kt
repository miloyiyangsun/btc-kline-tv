package com.milolab.btctv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milolab.btctv.ui.TvScreenInner
import com.milolab.btctv.ui.UiActions

/** 把 ViewModel 接到纯 Compose 界面上 */
@Composable
fun TvScreen(vm: MarketViewModel) {
    val st by vm.ui.collectAsStateWithLifecycle()

    // 返回键：设置面板打开时先关面板，再按才退出
    BackHandler(enabled = st.settingsOpen) { vm.closeSettings() }

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
            reconnect = vm::reconnect
        )
    )
}
