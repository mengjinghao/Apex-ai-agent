package com.apex.agent.core.normal.multimodal

import java.util.concurrent.ConcurrentHashMap

/**
 * F17: 多模态输入解析器
 *
 * 支持图片、文件、语音等多种输入形式的智能解析：
 * - 图片：OCR 识别 + 场景描述 + 图表数据提取
 * - 文件：PDF/Word/Excel/代码 文件内容提取
 * - 语音：语音转文本 + 指令识别
 * - 截图：UI 元素识别 + 文字提取
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 独立处理多模态
 * - 狂暴不关心输入模态
 * - 本功能是**单 Agent 统一多模态入口**，提升用户输入体验
 */

/**
 * 多模态输入类型
 */
enum class MultimodalType {
    TEXT,
    IMAGE,
    FILE,
    VOICE,
    SCREENSHOT,
    VIDEO_FRAME,
    AUDIO_CLIP
}

/**
 * 多模态输入
 */
data class MultimodalInput(
    val id: String,
    val type: MultimodalType,
    val mimeType: String,
    val source: InputSource,
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class InputSource {
    USER_UPLOAD,        // 用户上传
    SCREENSHOT,         // 屏幕截图
    VOICE_RECORDING,    // 语音录制
    CLIPBOARD,          // 剪贴板
    CAMERA,             // 相机
    DRAG_DROP,          // 拖拽
    URI_REFERENCE       // URI 引用
}

/**
 * 解析结果
 */
data class MultimodalParseResult(
    val inputId: String,
    val type: MultimodalType,
    val success: Boolean,
    val extractedText: String = "",
    val extractedData: Map<String, Any> = emptyMap(),
    val description: String = "",
    val suggestedPrompt: String = "",
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val processingTimeMs: Long = 0
)

/**
 * 图片解析结果
 */
data class ImageParseResult(
    val ocrText: String,
    val objects: List<DetectedObject>,
    val sceneDescription: String,
    val chartData: ChartData?,
    val faces: List<FaceInfo>,
    val colors: List<String>,
    val dimensions: Pair<Int, Int>
)

data class DetectedObject(val label: String, val confidence: Float, val boundingBox: BoundingBox)
data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float)
data class ChartData(val chartType: String, val title: String, val dataPoints: List<DataPoint>, val axes: Pair<String, String>)
data class DataPoint(val label: String, val value: Double)
data class FaceInfo(val age: Int?, val gender: String?, val emotion: String?)

/**
 * 文件解析结果
 */
data class FileParseResult(
    val fileName: String,
    val fileType: FileType,
    val content: String,
    val structure: FileStructure?,
    val metadata: FileMetadata,
    val summary: String
)

enum class FileType {
    TEXT, CODE, PDF, WORD, EXCEL, POWERPOINT,
    MARKDOWN, JSON, XML, CSV, YAML, HTML,
    IMAGE, ARCHIVE, BINARY, UNKNOWN
}

data class FileStructure(
    val sections: List<SectionInfo>,
    val toc: List<String>,
    val codeBlocks: List<CodeBlockInfo>
)

data class SectionInfo(val title: String, val level: Int, val content: String, val page: Int?)
data class CodeBlockInfo(val language: String, val startLine: Int, val endLine: Int, val content: String)
data class FileMetadata(val author: String?, val createdAt: Long?, val modifiedAt: Long?, val size: Long, val encoding: String?)

/**
 * 语音解析结果
 */
data class VoiceParseResult(
    val transcript: String,
    val language: String,
    val confidence: Float,
    val segments: List<VoiceSegment>,
    val detectedIntent: String?,
    val entities: List<String>
)

data class VoiceSegment(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)

/**
 * 多模态输入解析器
 */
class MultimodalInputParser {

    private val parsers = ConcurrentHashMap<MultimodalType, MultimodalParser>()

    init {
        registerBuiltinParsers()
    }

    /**
     * 注册解析器
     */
    fun registerParser(type: MultimodalType, parser: MultimodalParser) {
        parsers[type] = parser
    }

    /**
     * 解析输入
     */
    suspend fun parse(input: MultimodalInput): MultimodalParseResult {
        val start = System.currentTimeMillis()
        val parser = parsers[input.type] ?: return MultimodalParseResult(
            inputId = input.id,
            type = input.type,
            success = false,
            errors = listOf("不支持的输入类型: ${input.type}"),
            processingTimeMs = System.currentTimeMillis() - start
        )

        return try {
            val result = parser.parse(input)
            result.copy(processingTimeMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            MultimodalParseResult(
                inputId = input.id,
                type = input.type,
                success = false,
                errors = listOf(e.message ?: "解析失败"),
                processingTimeMs = System.currentTimeMillis() - start
            )
        }
    }

    /**
     * 批量解析
     */
    suspend fun parseBatch(inputs: List<MultimodalInput>): List<MultimodalParseResult> {
        return inputs.map { parse(it) }
    }

    /**
     * 生成建议 prompt
     */
    fun generateSuggestedPrompt(results: List<MultimodalParseResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[多模态输入已解析]")
        results.forEach { result ->
            if (result.success) {
                sb.appendLine("- 类型: ${result.type}")
                if (result.description.isNotBlank()) {
                    sb.appendLine("  描述: ${result.description}")
                }
                if (result.extractedText.isNotBlank()) {
                    sb.appendLine("  内容: ${result.extractedText.take(500)}")
                }
            } else {
                sb.appendLine("- 类型: ${result.type} (解析失败: ${result.errors.joinToString()})")
            }
        }
        return sb.toString()
    }

    // ============ 内置解析器 ============

    private fun registerBuiltinParsers() {
        // 文本解析器
        registerParser(MultimodalType.TEXT, TextParser())
        // 图片解析器（占位，需注入 OCR/VLM）
        registerParser(MultimodalType.IMAGE, ImageParser())
        registerParser(MultimodalType.SCREENSHOT, ImageParser())
        // 文件解析器
        registerParser(MultimodalType.FILE, FileParser())
        // 语音解析器（占位，需注入 ASR）
        registerParser(MultimodalType.VOICE, VoiceParser())
        registerParser(MultimodalType.AUDIO_CLIP, VoiceParser())
    }

    // ============ 文本解析器 ============

    class TextParser : MultimodalParser {
        override suspend fun parse(input: MultimodalInput): MultimodalParseResult {
            val text = input.metadata["text"]?.toString() ?: ""
            return MultimodalParseResult(
                inputId = input.id,
                type = MultimodalType.TEXT,
                success = true,
                extractedText = text,
                description = "文本输入",
                suggestedPrompt = "用户输入了 ${text.length} 字符的文本"
            )
        }
    }

    // ============ 图片解析器 ============

    class ImageParser : MultimodalParser {
        override suspend fun parse(input: MultimodalInput): MultimodalParseResult {
            // 占位实现：实际应调用 OCR/VLM 服务
            val imagePath = input.metadata["path"]?.toString()
            val result = ImageParseResult(
                ocrText = input.metadata["ocr_text"]?.toString() ?: "[图片内容需要 OCR 识别]",
                objects = emptyList(),
                sceneDescription = input.metadata["description"]?.toString() ?: "[图片场景描述]",
                chartData = null,
                faces = emptyList(),
                colors = emptyList(),
                dimensions = (input.metadata["width"] as? Int ?: 0) to (input.metadata["height"] as? Int ?: 0)
            )

            return MultimodalParseResult(
                inputId = input.id,
                type = input.type,
                success = true,
                extractedText = result.ocrText,
                extractedData = mapOf(
                    "imageParseResult" to result,
                    "path" to (imagePath ?: "")
                ),
                description = result.sceneDescription,
                suggestedPrompt = "用户上传了一张图片，识别内容: ${result.ocrText.take(200)}"
            )
        }
    }

    // ============ 文件解析器 ============

    class FileParser : MultimodalParser {
        override suspend fun parse(input: MultimodalInput): MultimodalParseResult {
            val fileName = input.metadata["fileName"]?.toString() ?: "unknown"
            val filePath = input.metadata["path"]?.toString() ?: ""
            val content = input.metadata["content"]?.toString() ?: ""

            val fileType = detectFileType(fileName)
            val structure = analyzeStructure(content, fileType)
            val metadata = FileMetadata(
                author = input.metadata["author"]?.toString(),
                createdAt = input.metadata["createdAt"] as? Long,
                modifiedAt = input.metadata["modifiedAt"] as? Long,
                size = (input.metadata["size"] as? Number)?.toLong() ?: content.length.toLong(),
                encoding = "UTF-8"
            )
            val summary = generateSummary(content, fileType)

            val result = FileParseResult(
                fileName = fileName,
                fileType = fileType,
                content = content,
                structure = structure,
                metadata = metadata,
                summary = summary
            )

            return MultimodalParseResult(
                inputId = input.id,
                type = MultimodalType.FILE,
                success = true,
                extractedText = content,
                extractedData = mapOf("fileParseResult" to result),
                description = "$fileName (${fileType.name}, ${metadata.size} bytes)",
                suggestedPrompt = "用户上传了文件 $fileName，内容摘要: $summary"
            )
        }

        private fun detectFileType(fileName: String): FileType {
            val ext = fileName.substringAfterLast(".", "").lowercase()
            return when (ext) {
                "txt", "log" -> FileType.TEXT
                "kt", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "h", "swift" -> FileType.CODE
                "pdf" -> FileType.PDF
                "doc", "docx" -> FileType.WORD
                "xls", "xlsx" -> FileType.EXCEL
                "ppt", "pptx" -> FileType.POWERPOINT
                "md", "markdown" -> FileType.MARKDOWN
                "json" -> FileType.JSON
                "xml" -> FileType.XML
                "csv", "tsv" -> FileType.CSV
                "yaml", "yml" -> FileType.YAML
                "html", "htm" -> FileType.HTML
                "png", "jpg", "jpeg", "gif", "bmp", "webp" -> FileType.IMAGE
                "zip", "tar", "gz", "rar", "7z" -> FileType.ARCHIVE
                else -> FileType.UNKNOWN
            }
        }

        private fun analyzeStructure(content: String, fileType: FileType): FileStructure? {
            val sections = mutableListOf<SectionInfo>()
            val toc = mutableListOf<String>()
            val codeBlocks = mutableListOf<CodeBlockInfo>()

            when (fileType) {
                FileType.MARKDOWN, FileType.TEXT -> {
                    val lines = content.lines()
                    var currentSection: SectionInfo? = null
                    var sectionContent = StringBuilder()
                    var inCodeBlock = false
                    var codeStart = 0
                    var codeLang = ""

                    lines.forEachIndexed { i, line ->
                        if (line.startsWith("```")) {
                            if (inCodeBlock) {
                                codeBlocks.add(CodeBlockInfo(codeLang, codeStart, i, lines.subList(codeStart, i + 1).joinToString("\n")))
                                inCodeBlock = false
                            } else {
                                inCodeBlock = true
                                codeStart = i
                                codeLang = line.removePrefix("```").trim()
                            }
                        }
                        if (line.matches(Regex("^#{1,6}\\s+.+"))) {
                            if (currentSection != null) {
                                sections.add(currentSection!!.copy(content = sectionContent.toString()))
                            }
                            val level = line.takeWhile { it == '#' }.length
                            val title = line.dropWhile { it == '#' }.trim()
                            toc.add(title)
                            currentSection = SectionInfo(title, level, "", i + 1)
                            sectionContent = StringBuilder()
                        } else if (currentSection != null) {
                            sectionContent.appendLine(line)
                        }
                    }
                    if (currentSection != null) {
                        sections.add(currentSection!!.copy(content = sectionContent.toString()))
                    }
                }
                FileType.CODE -> {
                    // 代码文件：提取函数/类定义
                    val patterns = mapOf(
                        "function" to Regex("(?:fun|function|def|func)\\s+(\\w+)"),
                        "class" to Regex("(?:class|interface|object|struct|enum)\\s+(\\w+)")
                    )
                    patterns.forEach { (type, regex) ->
                        regex.findAll(content).forEach { match ->
                            sections.add(SectionInfo("$type: ${match.groupValues[1]}", 1, "", null))
                        }
                    }
                }
                else -> {}
            }

            return FileStructure(sections, toc, codeBlocks)
        }

        private fun generateSummary(content: String, fileType: FileType): String {
            return when (fileType) {
                FileType.CODE -> "代码文件，${content.lines().size} 行"
                FileType.MARKDOWN -> "Markdown 文档，${content.lines().size} 行"
                FileType.JSON -> "JSON 数据，${content.length} 字符"
                else -> "文件内容，${content.length} 字符"
            }
        }
    }

    // ============ 语音解析器 ============

    class VoiceParser : MultimodalParser {
        override suspend fun parse(input: MultimodalInput): MultimodalParseResult {
            // 占位：实际应调用 ASR 服务
            val transcript = input.metadata["transcript"]?.toString() ?: "[语音转文本结果]"
            val language = input.metadata["language"]?.toString() ?: "zh-CN"
            val confidence = (input.metadata["confidence"] as? Number)?.toFloat() ?: 0.9f

            val result = VoiceParseResult(
                transcript = transcript,
                language = language,
                confidence = confidence,
                segments = listOf(VoiceSegment(transcript, 0, 0, confidence)),
                detectedIntent = detectIntent(transcript),
                entities = extractEntities(transcript)
            )

            return MultimodalParseResult(
                inputId = input.id,
                type = input.type,
                success = true,
                extractedText = transcript,
                extractedData = mapOf("voiceParseResult" to result),
                description = "语音输入 ($language, 置信度 ${(confidence * 100).toInt()}%)",
                suggestedPrompt = "用户语音输入: $transcript"
            )
        }

        private fun detectIntent(text: String): String? {
            val intentPatterns = mapOf(
                "查询" to listOf("查", "找", "搜索", "search", "find", "query"),
                "操作" to listOf("打开", "关闭", "删除", "创建", "open", "close", "delete", "create"),
                "翻译" to listOf("翻译", "translate"),
                "提醒" to listOf("提醒", "闹钟", "remind", "alarm"),
                "闲聊" to listOf("你好", "聊天", "hello", "hi", "hey")
            )
            for ((intent, keywords) in intentPatterns) {
                if (keywords.any { text.contains(it, ignoreCase = true) }) return intent
            }
            return null
        }

        private fun extractEntities(text: String): List<String> {
            val entities = mutableListOf<String>()
            // 时间实体
            Regex("\\d{1,2}[:：]\\d{2}|\\d{1,2}点|明天|后天|今天|now|today|tomorrow").findAll(text)
                .map { it.value }.toList().let { entities.addAll(it) }
            // 数字实体
            Regex("\\b\\d+\\b").findAll(text).map { it.value }.take(3).toList().let { entities.addAll(it) }
            return entities
        }
    }
}

/**
 * 解析器接口
 */
fun interface MultimodalParser {
    suspend fun parse(input: MultimodalInput): MultimodalParseResult
}
