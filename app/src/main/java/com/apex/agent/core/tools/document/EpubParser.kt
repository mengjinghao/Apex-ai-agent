package com.apex.core.tools.document

import java.io.InputStream
import java.util.zip.ZipInputStream

class EpubParser : DocumentParser {
    override val supportedTypes: List<DocumentType> = listOf(DocumentType.EPUB)

    override fun canParse(type: DocumentType): Boolean = type == DocumentType.EPUB

    override suspend fun parse(inputStream: InputStream, fileName: String): DocumentParseResult {
        return try {
            val textContent = extractText(inputStream, fileName)
            DocumentParseResult(
                type = DocumentType.EPUB,
                title = fileName.removeSuffix(".epub"),
                textContent = textContent,
                success = true
            )
        } catch (e: Exception) {
            DocumentParseResult(
                type = DocumentType.EPUB,
                success = false,
                errorMessage = e.message
            )
        }
    }

    override suspend fun extractText(inputStream: InputStream, fileName: String): String {
        val result = StringBuilder()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml") || entry.name.endsWith(".htm")) {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    result.append(extractTextFromHtml(content))
                    result.append("\n")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result.toString()
    }

    private fun extractTextFromHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}