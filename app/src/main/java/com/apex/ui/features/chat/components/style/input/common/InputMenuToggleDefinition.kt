package com.apex.ui.features.chat.components.style.input.common

/**
 * 输入菜单开关定义 — Stub 数据类。
 * 业务代码（ToolPkgCommonBridgePlugin）构造此对象用于注册输入菜单项。
 */
data class InputMenuToggleDefinition(
    val id: String,
    val label: String,
    val initialState: Boolean = false,
    val onToggle: ((Boolean) -> Unit)? = null,
    val icon: String? = null,
    val tooltip: String? = null
)
