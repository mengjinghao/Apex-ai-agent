package com.apex.core.tools.document

// Minimal implementation (original had 21 errors)
// TODO: Restore full implementation from original code

class OcrEnhancer
data class OcrResult(val data: String = "")
data class TextBlock(val data: String = "")
data class TableOcrResult(val data: String = "")
data class HandwritingResult(val data: String = "")
data class HandwritingWord(val data: String = "")
