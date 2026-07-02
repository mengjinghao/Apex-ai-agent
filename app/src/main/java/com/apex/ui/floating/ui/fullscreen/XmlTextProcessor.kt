package com.apex.ui.floating.ui.fullscreen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * XML 文本处理器 — Stub。
 * 原用于将流式 AI 响应中的 XML 标签处理为可显示文本，UI 移除后保留 processStreamToText API。
 */
object XmlTextProcessor {

    @JvmStatic
    fun processStreamToText(stream: Flow<String>): Flow<String> {
        // Stub: 原 UI 实现会过滤 XML 标签、提取纯文本
        return stream
    }

    @JvmStatic
    fun processText(text: String): String {
        // Stub: 简单移除 XML 标签
        return text.replace(Regex("<[^>]+>"), "")
    }
}
