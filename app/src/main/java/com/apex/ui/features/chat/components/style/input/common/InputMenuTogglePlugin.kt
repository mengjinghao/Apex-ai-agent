package com.apex.ui.features.chat.components.style.input.common

/**
 * 输入菜单开关插件 — Stub 接口。
 * 业务代码通过实现此接口注册自定义输入菜单开关。
 */
interface InputMenuTogglePlugin {
    val pluginId: String

    fun getDefinitions(): List<InputMenuToggleDefinition>

    fun onToggle(params: InputMenuToggleHookParams) {}

    fun onInputChanged(params: InputMenuToggleHookParams) {}
}
