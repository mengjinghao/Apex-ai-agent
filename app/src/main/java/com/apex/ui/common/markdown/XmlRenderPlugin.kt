package com.apex.ui.common.markdown

/**
 * XML 渲染插件 — Stub 接口。
 * 原用于在聊天消息中渲染自定义 XML 标签为 Compose 组件，UI 移除后保留接口。
 */
interface XmlRenderPlugin {
    val tagName: String
    fun canHandle(tag: String): Boolean = tag.equals(tagName, ignoreCase = true)
    fun render(attrs: Map<String, String>, children: String): XmlRenderResult?
}
