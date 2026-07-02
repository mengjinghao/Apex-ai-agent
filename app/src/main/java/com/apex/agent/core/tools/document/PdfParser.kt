package com.apex.core.tools.document

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream

class PdfParser : DocumentParser {
    override val supportedTypes: List<DocumentType> = listOf(DocumentType.PDF)

    override fun canParse(type: DocumentType): Boolean = type == DocumentType.PDF

    override suspend fun parse(inputStream: InputStream, fileName: String): DocumentParseResult {
        return try {
            val document = PDDocument.load(inputStream)
            val textStripper = PDFTextStripper()
            textStripper.sortByPosition = true
            val textContent = textStripper.getText(document)

            val renderer = PDFRenderer(document)
            val images = mutableListOf<ByteArray>()

            for (i in 1..document.numberOfPages) {
                val image = renderer.renderImageWithDPI(i - 1, 150.0f, ImageType.RGB)
                images.add(imageToByteArray(image))
            }

            val pages = (1..document.numberOfPages).map { pageNum ->
                textStripper.startPage = pageNum
                textStripper.endPage = pageNum
                DocumentPage(
                    pageNumber = pageNum,
                    content = textStripper.getText(document),
                    image = if (pageNum - 1 < images.size) images[pageNum - 1] else null
                )
            }

            val metadata = mutableMapOf<String, String>()
            document.documentInformation?.let { info ->
                info.title?.let { metadata["title"] = it }
                info.author?.let { metadata["author"] = it }
                info.subject?.let { metadata["subject"] = it }
                info.creator?.let { metadata["creator"] = it }
            }

            document.close()

            DocumentParseResult(
                type = DocumentType.PDF,
                title = fileName.removeSuffix(".pdf"),
                pages = pages,
                textContent = textContent,
                images = images,
                metadata = metadata,
                success = true
            )
        } catch (e: Exception) {
            DocumentParseResult(
                type = DocumentType.PDF,
                success = false,
                errorMessage = e.message
            )
        }
    }

    override suspend fun extractText(inputStream: InputStream, fileName: String): String {
        return try {
            val document = PDDocument.load(inputStream)
            val textStripper = PDFTextStripper()
            textStripper.sortByPosition = true
            val text = textStripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            ""
        }
    }

    private fun imageToByteArray(image: BufferedImage): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }
}