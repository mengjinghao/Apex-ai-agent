package com.apex.core.tools.document

import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xslf.extractor.XSLFPowerPointExtractor
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.extractor.XSSFExcelExtractor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

class OfficeParser : DocumentParser {
    override val supportedTypes: List<DocumentType> = listOf(DocumentType.DOCX, DocumentType.XLSX, DocumentType.PPTX)

    override fun canParse(type: DocumentType): Boolean = type in supportedTypes

    override suspend fun parse(inputStream: InputStream, fileName: String): DocumentParseResult {
        return try {
            val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
                "docx" -> parseDocx(inputStream, fileName)
                "xlsx", "xls" -> parseXlsx(inputStream, fileName)
                "pptx", "ppt" -> parsePptx(inputStream, fileName)
                else -> DocumentParseResult(
                    type = DocumentType.DOCX,
                    success = false,
                    errorMessage = "Unsupported file type"
                )
            }
        } catch (e: Exception) {
            DocumentParseResult(
                type = DocumentType.DOCX,
                success = false,
                errorMessage = e.message
            )
        }
    }

    override suspend fun extractText(inputStream: InputStream, fileName: String): String {
        return try {
            val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
                "docx" -> extractDocxText(inputStream)
                "xlsx", "xls" -> extractXlsxText(inputStream)
                "pptx", "ppt" -> extractPptxText(inputStream)
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }
        private fun parseDocx(inputStream: InputStream, fileName: String): DocumentParseResult {
        val document = XWPFDocument(inputStream)
        val extractor = XWPFWordExtractor(document)
        val textContent = extractor.text
        document.close()
        return DocumentParseResult(
            type = DocumentType.DOCX,
            title = fileName.removeSuffix(".docx"),
            textContent = textContent,
            success = true
        )
    }
        private fun parseXlsx(inputStream: InputStream, fileName: String): DocumentParseResult {
        val workbook = XSSFWorkbook(inputStream)
        val extractor = XSSFExcelExtractor(workbook)
        extractor.formulaTextPreference = true
        val textContent = extractor.text
        workbook.close()
        return DocumentParseResult(
            type = DocumentType.XLSX,
            title = fileName.removeSuffix(".xlsx"),
            textContent = textContent,
            success = true
        )
    }
        private fun parsePptx(inputStream: InputStream, fileName: String): DocumentParseResult {
        val slideShow = XMLSlideShow(inputStream)
        val extractor = XSLFPowerPointExtractor(slideShow)
        val textContent = extractor.text
        slideShow.close()
        return DocumentParseResult(
            type = DocumentType.PPTX,
            title = fileName.removeSuffix(".pptx"),
            textContent = textContent,
            success = true
        )
    }
        private fun extractDocxText(inputStream: InputStream): String {
        val document = XWPFDocument(inputStream)
        val extractor = XWPFWordExtractor(document)
        val text = extractor.text
        document.close()
        return text
    }
        private fun extractXlsxText(inputStream: InputStream): String {
        val workbook = XSSFWorkbook(inputStream)
        val extractor = XSSFExcelExtractor(workbook)
        extractor.formulaTextPreference = true
        val text = extractor.text
        workbook.close()
        return text
    }
        private fun extractPptxText(inputStream: InputStream): String {
        val slideShow = XMLSlideShow(inputStream)
        val extractor = XSLFPowerPointExtractor(slideShow)
        val text = extractor.text
        slideShow.close()
        return text
    }
}