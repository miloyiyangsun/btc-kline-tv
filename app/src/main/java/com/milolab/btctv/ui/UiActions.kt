package com.milolab.btctv.ui

import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.IndicatorGroup
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.MarketFilter
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
    val reconnect: () -> Unit = {},
    // ---- 指标 ----
    val toggleIndicator: (String) -> Unit = {},
    val stepIndicatorParam: (String, String, Int) -> Unit = { _, _, _ -> },
    val expandIndicator: (String?) -> Unit = {},
    val toggleIndicatorGroup: (IndicatorGroup) -> Unit = {},
    val allIndicatorsOff: () -> Unit = {},
    val resetIndicators: () -> Unit = {},
    // ---- 标的搜索 ----
    val openSearch: () -> Unit = {},
    val closeSearch: () -> Unit = {},
    val setQuery: (String) -> Unit = {},
    val setFilter: (MarketFilter) -> Unit = {},
    val selectInstrument: (Instrument) -> Unit = {},
    val toggleFavorite: (Instrument) -> Unit = {}
)
