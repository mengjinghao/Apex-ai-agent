package com.apex.core.tools.document

import java.io.InputStream

enum class DocumentType {
    EPUB, CBZ, CBR, DOCX, XLSX, PPTX, PDF, IMAGE, VIDEO
}

interface DocumentParser {
    val supportedTypes: List<DocumentType>
    fun canParse(type: DocumentType): Boolean
    suspend fun parse(inputStream: InputStream, fileName: String): DocumentParseResult
    suspend fun extractText(inputStream: InputStream, fileName: String): String
}

data class DocumentParseResult(
    val type: DocumentType,
    val title: String = "",
    val pages: List<DocumentPage> = emptyList(),
    val textContent: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val images: List<ByteArray> = emptyList(),
    val tables: List<List<List<String>>> = emptyList(),
    val success: Boolean = true,
    val errorMessage: String? = null
)

data class DocumentPage(
    val pageNumber: Int,
    val content: String = "",
    val image: ByteArray? = null,
    val tables: List<List<List<String>>> = emptyList()
)