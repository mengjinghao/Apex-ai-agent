package com.apex.core.tools.document

import android.content.Context
import com.apex.agent.core.security.InputSanitizer
import com.apex.data.model.AITool
import com.apex.data.model.ToolParameter
import com.apex.data.model.ToolResult
import com.apex.data.model.StringResultData
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class DocumentTools(private val context: Context) {

    private val parserFactory = DocumentParserFactory
    private val videoSummarizer = VideoSummarizer(context)
    private val ocrEnhancer = OcrEnhancer()
    private val inputSanitizer = InputSanitizer()

    companion object {
        private const val TAG = "DocumentTools"
    }

    suspend fun parseDocument(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val filePath = tool.parameters.find { it.name == "file_path" }?.value ?: ""
            val fileName = File(filePath).name

            val parser = parserFactory.getParserForFile(fileName)
                ?: return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Unsupported file format: ${fileName}"
                )

            FileInputStream(filePath).use { inputStream ->
                val result = parser.parse(inputStream, fileName)

                if (result.success) {
                    // 对提取的文本内容进行安全消毒
                    var sanitizedTextContent = result.textContent
                    if (result.textContent.isNotBlank()) {
                        try {
                            val sanitizeResult = inputSanitizer.sanitize(result.textContent)
                            sanitizedTextContent = sanitizeResult.sanitizedText
                            if (sanitizeResult.findings.isNotEmpty()) {
                                AppLogger.d(TAG, "文档内容消毒完成: 发现${sanitizeResult.findings.size}个安全问�?)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "文档内容消毒失败，使用原始内�?, e)
                            // 消毒失败时使用原始内容，不阻断流�?
                        }
                    }

                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData(
                            buildString {
                                appendLine("文档类型: ${result.type}")
                                appendLine("标题: ${result.title}")
                                appendLine("页数: ${result.pages.size}")
                                if (sanitizedTextContent.isNotBlank()) {
                                    appendLine("\n文本内容:")
                                    appendLine(sanitizedTextContent.take(2000))
                                }
                            }
                        )
                    )
                } else {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = result.errorMessage
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "解析失败: ${e.message}"
            )
        }
    }

    suspend fun extractText(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val filePath = tool.parameters.find { it.name == "file_path" }?.value ?: ""
            val fileName = File(filePath).name

            val parser = parserFactory.getParserForFile(fileName)
                ?: return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Unsupported file format"
                )

            FileInputStream(filePath).use { inputStream ->
                val text = parser.extractText(inputStream, fileName)

                // 对提取的文本进行安全消毒
                val sanitizedText = try {
                    val sanitizeResult = inputSanitizer.sanitize(text)
                    if (sanitizeResult.findings.isNotEmpty()) {
                        AppLogger.d(TAG, "文档提取内容消毒完成: 发现${sanitizeResult.findings.size}个安全问�?)
                    }
                    sanitizeResult.sanitizedText
                } catch (e: Exception) {
                    AppLogger.e(TAG, "文档提取内容消毒失败，使用原始内�?, e)
                    // 消毒失败时使用原始内容，不阻断流�?
                    text
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(sanitizedText)
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "提取文本失败: ${e.message}"
            )
        }
    }

    suspend fun summarizeVideo(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val videoPath = tool.parameters.find { it.name == "video_path" }?.value ?: ""
            val includeFrames = tool.parameters.find { it.name == "include_frames" }?.value?.toBoolean() ?: false

            val result = videoSummarizer.generateSummary(
                videoPath = videoPath,
                includeFrames = includeFrames,
                includeTranscript = true
            )

            if (result.success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(
                        buildString {
                            appendLine("视频时长: ${result.duration}�?)
                            appendLine("关键帧数�? ${result.keyFrames.size}")
                            appendLine("\n字幕内容:")
                            appendLine(result.transcript.take(3000))
                            if (result.summary.isNotBlank()) {
                                appendLine("\n摘要:")
                                appendLine(result.summary)
                            }
                        }
                    )
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = result.errorMessage
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "视频摘要生成失败: ${e.message}"
            )
        }
    }

    suspend fun recognizeTable(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val imagePath = tool.parameters.find { it.name == "image_path" }?.value ?: ""

            val imageBytes = File(imagePath).readBytes()
            val result = ocrEnhancer.recognizeTable(imageBytes)

            if (result.tables.isNotEmpty()) {
                val markdownTables = result.tables.mapIndexed { index, table ->
                    buildString {
                        appendLine("表格 ${index + 1}:")
                        table.forEachIndexed { rowIndex, row ->
                            appendLine(row.joinToString(" | "))
                            if (rowIndex == 0) {
                                appendLine(row.map { "---" }.joinToString(" | "))
                            }
                        }
                    }
                }.joinToString("\n")

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(markdownTables)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "未检测到表格"
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "表格识别失败: ${e.message}"
            )
        }
    }

    suspend fun recognizeHandwriting(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val imagePath = tool.parameters.find { it.name == "image_path" }?.value ?: ""

            val imageBytes = File(imagePath).readBytes()
            val result = ocrEnhancer.recognizeHandwriting(imageBytes)

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(
                    buildString {
                        appendLine("识别结果:")
                        appendLine(result.text)
                        appendLine("\n置信�? ${(result.confidence * 100).toInt()}%")
                        if (result.words.isNotEmpty()) {
                            appendLine("\n词汇详情:")
                            result.words.take(20).forEach { word ->
                                appendLine("- ${word.text} (${(word.confidence * 100).toInt()}%)")
                            }
                        }
                    }
                )
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "手写识别失败: ${e.message}"
            )
        }
    }
}