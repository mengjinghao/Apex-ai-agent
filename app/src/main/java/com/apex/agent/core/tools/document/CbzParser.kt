package com.apex.core.tools.document

import java.io.InputStream
import java.util.zip.ZipInputStream

class CbzParser : DocumentParser {
    override val supportedTypes: List<DocumentType> = listOf(DocumentType.CBZ, DocumentType.CBR)

    override fun canParse(type: DocumentType): Boolean = type == DocumentType.CBZ || type == DocumentType.CBR

    override suspend fun parse(inputStream: InputStream, fileName: String): DocumentParseResult {
        return try {
            val images = extractImages(inputStream)
            DocumentParseResult(
                type = DocumentType.CBZ,
                title = fileName.removeSuffix(".cbz"),
                images = images,
                success = true
            )
        } catch (e: Exception) {
            DocumentParseResult(
                type = DocumentType.CBZ,
                success = false,
                errorMessage = e.message
            )
        }
    }

    override suspend fun extractText(inputStream: InputStream, fileName: String): String {
        return ""
    }

    private fun extractImages(inputStream: InputStream): List<ByteArray> {
        val images = mutableListOf<ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)$"))) {
                    images.add(zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return images
    }
}