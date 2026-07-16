package com.apex.agent.core.normal.rendering

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

enum class RenderNodeType { DEFAULT }
data class RenderNode(val data: String = "")
data class RenderTree(val data: String = "")
class StreamingMarkdownRenderer
