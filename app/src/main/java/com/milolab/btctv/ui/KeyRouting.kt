package com.milolab.btctv.ui

import androidx.compose.ui.input.key.Key

/** 遥控器按键被解释成的意图 */
sealed interface KeyIntent {
    data class ChangeTf(val delta: Int) : KeyIntent
    data class Zoom(val delta: Int) : KeyIntent
    data object OpenSettings : KeyIntent
    data object OpenSearch : KeyIntent
    data object None : KeyIntent
}

/**
 * 按键路由：唯一的真源。
 * 设置或搜索面板打开时一律返回 None —— 让按键全部交给面板的焦点系统，
 * 绝不能再去改主界面的周期/缩放（之前遥控器"控制下面那层"就是这里没分家）。
 */
fun routeKey(settingsOpen: Boolean, searchOpen: Boolean, key: Key): KeyIntent {
    if (settingsOpen || searchOpen) return KeyIntent.None
    return when (key) {
        Key.DirectionLeft -> KeyIntent.ChangeTf(-1)
        Key.DirectionRight -> KeyIntent.ChangeTf(1)
        Key.DirectionUp -> KeyIntent.Zoom(-1)
        Key.DirectionDown -> KeyIntent.Zoom(1)
        Key.Menu, Key.Search -> KeyIntent.OpenSearch
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> KeyIntent.OpenSettings
        else -> KeyIntent.None
    }
}

/** 设置面板里按 BACK：先收起展开的参数，再关面板。 */
enum class SettingsBackAction { COLLAPSE_PARAMS, CLOSE_SETTINGS }

fun settingsBackAction(expandedIndicator: String?): SettingsBackAction =
    if (expandedIndicator != null) SettingsBackAction.COLLAPSE_PARAMS else SettingsBackAction.CLOSE_SETTINGS
