package com.apex.core.tools.document

data class ParseResult(
    val success: Boolean,
    val result: DocumentParseResult? = null,
    val error: String? = null
)