package com.apex.ui.features.chat.components.style.input.common

/**
 * 输入菜单开关钩子参数 — Stub 数据类。
 */
data class InputMenuToggleHookParams(
    val toggleId: String,
    val currentState: Boolean,
    val inputText: String = "",
    val extras: Map<String, Any?> = emptyMap()
)
