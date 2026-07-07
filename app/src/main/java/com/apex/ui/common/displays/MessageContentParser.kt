package com.apex.ui.common.displays

/**
 * 消息内容解析器 — Stub。
 * 原用于解析 AI 响应中的工具调用标记，业务代码引用其 toolParamPattern。
 */
object MessageContentParser {
    val toolParamPattern: Regex = Regex("""\{\{tool:([^}]+)\}\}""")
}
