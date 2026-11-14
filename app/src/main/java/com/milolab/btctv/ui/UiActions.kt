package com.milolab.btctv.ui

import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.TF

/** 界面的全部交互回调，离屏预览时全部留空 */
data class UiActions(
    val setTf: (TF) -> Unit = {},
    val setStyle: (ChartStyle) -> Unit = {},
    val zoom: (Int) -> Unit = {},
    val openSettings: () -> Unit = {},
    val closeSettings: () -> Unit = {},
    val toggleFlash: () -> Unit = {},
    val toggleAntiBurn: () -> Unit = {},
    val toggleClock: () -> Unit = {},
    val toggleVolume: () -> Unit = {},
    val toggleAutoStart: () -> Unit = {},
    val reconnect: () -> Unit = {}
)
