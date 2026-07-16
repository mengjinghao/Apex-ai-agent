package com.apex.ui.common.markdown

/**
 * XML 渲染结果 — Stub 密封类。
 * 业务代码引用 ComposeDslScreen / Text 子类型。
 */
sealed class XmlRenderResult {

    data class ComposeDslScreen(
        val containerPackageName: String,
        val screenPath: String,
        val state: Map<String, Any?> = emptyMap(),
        val memo: String? = null,
        val moduleSpec: String? = null
    ) : XmlRenderResult()

    data class Markdown(val markdown: String) : XmlRenderResult()

    data class Html(val html: String) : XmlRenderResult()
}
