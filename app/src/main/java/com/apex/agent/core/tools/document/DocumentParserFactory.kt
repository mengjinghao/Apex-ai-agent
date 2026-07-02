package com.apex.core.tools.document

object DocumentParserFactory {
    private val parsers = listOf(
        EpubParser(),
        CbzParser(),
        OfficeParser(),
        PdfParser()
    )

    fun getParser(documentType: DocumentType): DocumentParser? {
        return parsers.find { it.canParse(documentType) }
    }

    fun getParserForFile(fileName: String): DocumentParser? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val type = when (extension) {
            "epub" -> DocumentType.EPUB
            "cbz" -> DocumentType.CBZ
            "cbr" -> DocumentType.CBR
            "docx", "doc" -> DocumentType.DOCX
            "xlsx", "xls" -> DocumentType.XLSX
            "pptx", "ppt" -> DocumentType.PPTX
            "pdf" -> DocumentType.PDF
            else -> null
        }
        return type?.let { getParser(it) }
    }

    fun detectDocumentType(fileName: String): DocumentType? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "epub" -> DocumentType.EPUB
            "cbz" -> DocumentType.CBZ
            "cbr" -> DocumentType.CBR
            "docx", "doc" -> DocumentType.DOCX
            "xlsx", "xls" -> DocumentType.XLSX
            "pptx", "ppt" -> DocumentType.PPTX
            "pdf" -> DocumentType.PDF
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> DocumentType.IMAGE
            "mp4", "mkv", "avi", "mov", "webm" -> DocumentType.VIDEO
            else -> null
        }
    }
}