package com.apex.core.tools.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.resume

class OcrEnhancer {

    private val defaultRecognizer: TextRecognizer = TextRecognition.getClient()
        private val chineseRecognizer: TextRecognizer = ChineseTextRecognizer.getClient()
        private val japaneseRecognizer: TextRecognizer = JapaneseTextRecognizer.getClient()
        private val koreanRecognizer: TextRecognizer = KoreanTextRecognizer.getClient()
        private val devanagariRecognizer: TextRecognizer = DevanagariTextRecognizer.getClient()

    suspend fun recognizeText(imageData: ByteArray): OcrResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = ByteArrayInputStream(imageData).use { BitmapFactory.decodeStream(it) }
                ?: return@withContext OcrResult("", 0f, emptyList(), "auto")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = processTextRecognition(inputImage)

            bitmap.recycle()

            OcrResult(
                text = result.text,
                confidence = result.textBlocks.sumOf { it.questions.forEach { q -> } ; 1f } / (result.textBlocks.size.coerceAtLeast(1)),
                blocks = result.textBlocks.map { block ->
                    val boundingBox = block.boundingBox
                    TextBlock(
                        text = block.text,
                        boundingBox = BoundingBox(
                            x = boundingBox?.left ?: 0,
                            y = boundingBox?.top ?: 0,
                            width = boundingBox?.width() ?: 0,
                            height = boundingBox?.height() ?: 0
                        ),
                        confidence = block.questions.map { 1f }.average().toFloat()
                    )
                },
                language = detectLanguage(result.text)
            )
        } catch (e: Exception) {
            OcrResult("", 0f, emptyList(), "auto")
        }
    }

    suspend fun recognizeTable(imageData: ByteArray): TableOcrResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = ByteArrayInputStream(imageData).use { BitmapFactory.decodeStream(it) }
                ?: return@withContext TableOcrResult(emptyList(), 0f, "")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = processTextRecognition(inputImage)
        val tables = detectTables(result)
        val fullText = result.text

            bitmap.recycle()

            TableOcrResult(
                tables = tables,
                confidence = if (result.textBlocks.isNotEmpty()) 0.85f else 0f,
                fullText = fullText
            )
        } catch (e: Exception) {
            TableOcrResult(emptyList(), 0f, "")
        }
    }

    suspend fun recognizeHandwriting(imageData: ByteArray): HandwritingResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = ByteArrayInputStream(imageData).use { BitmapFactory.decodeStream(it) }
                ?: return@withContext HandwritingResult("", 0f, emptyList())
        val enhancedImage = enhanceImage(imageData)
        val enhancedBitmap = ByteArrayInputStream(enhancedImage).use { BitmapFactory.decodeStream(it) }
                ?: bitmap

            val inputImage = InputImage.fromBitmap(enhancedBitmap, 0)
        val recognitionResult = processTextRecognition(inputImage)
        val words = mutableListOf<HandwritingWord>()
            recognitionResult.textBlocks.forEach { block ->
                block.boundingBox?.let { box ->
                    words.add(
                        HandwritingWord(
                            text = block.text,
                            boundingBox = BoundingBox(
                                x = box.left,
                                y = box.top,
                                width = box.width(),
                                height = box.height()
                            ),
                            confidence = 0.7f
                        )
                    )
                }
            }
        if (enhancedBitmap != bitmap) {
                enhancedBitmap.recycle()
            }
            bitmap.recycle()

            HandwritingResult(
                text = recognitionResult.text,
                confidence = 0.75f,
                words = words
            )
        } catch (e: Exception) {
            HandwritingResult("", 0f, emptyList())
        }
    }

    suspend fun enhanceImage(imageData: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        try {
            val bitmap = ByteArrayInputStream(imageData).use { BitmapFactory.decodeStream(it) }
                ?: return@withContext imageData

            val contrast = 1.5f
            val brightness = 20f
            val colorMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        val outputStream = ByteArrayOutputStream()
            outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            bitmap.recycle()
            outputBitmap.recycle()

            outputStream.toByteArray()
        } catch (e: Exception) {
            imageData
        }
    }
        private suspend fun processTextRecognition(inputImage: InputImage): com.google.mlkit.vision.text.Text {
        return suspendCancellableCoroutine { continuation ->
            defaultRecognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener {
                    continuation.resume(com.google.mlkit.vision.text.Text.builder().setText("").build())
                }
        }
    }
        private fun detectLanguage(text: String): String {
        return when {
            text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
            text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' } -> "ja"
            text.any { it in '\uAC00'..'\uD7AF' } -> "ko"
            text.any { it in '\u0900'..'\u097F' || it in '\uA800'..'\uA83F' } -> "hi"
            else -> "auto"
        }
    }
        private fun detectTables(result: com.google.mlkit.vision.text.Text): List<List<List<String>>> {
        val tables = mutableListOf<List<List<String>>>()
        val blocks = result.textBlocks

        if (blocks.isEmpty()) return tables

        val sortedBlocks = blocks.sortedBy { it.boundingBox?.top ?: 0 }
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<TextBlock>()

        sortedBlocks.forEachIndexed { index, block ->
            val currentBox = block.boundingBox
            val nextBox = sortedBlocks.getOrNull(index + 1)?.boundingBox

            currentRow.add(block)
        if (nextBox == null || (currentBox != null && nextBox != null && kotlin.math.abs(currentBox.top - nextBox.top) > currentBox.height / 2)) {
                val sortedCells = currentRow.sortedBy { it.boundingBox?.left ?: 0 }
                rows.add(sortedCells.map { it.text })
                currentRow = mutableListOf()
            }
        }
        if (currentRow.isNotEmpty()) {
            val sortedCells = currentRow.sortedBy { it.boundingBox?.left ?: 0 }
            rows.add(sortedCells.map { it.text })
        }
        if (rows.isNotEmpty()) {
            tables.add(rows)
        }
        return tables
    }
        fun close() {
        defaultRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
        devanagariRecognizer.close()
    }
}

data class OcrResult(
    val text: String,
    val confidence: Float,
    val blocks: List<TextBlock> = emptyList(),
    val language: String = "auto"
)

data class TextBlock(
    val text: String,
    val boundingBox: BoundingBox,
    val confidence: Float
)

data class BoundingBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class TableOcrResult(
    val tables: List<List<List<String>>>,
    val confidence: Float,
    val fullText: String
)

data class HandwritingResult(
    val text: String,
    val confidence: Float,
    val words: List<HandwritingWord> = emptyList()
)

data class HandwritingWord(
    val text: String,
    val boundingBox: BoundingBox,
    val confidence: Float
)