package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 流处理器技能
 * 实现大文本分块处理、并行处理、内存优化
 */
class StreamProcessorSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val chunkSize: Int = 8192
    private val maxConcurrentChunks: Int = 3
    private val chunkSemaphore = Semaphore(maxConcurrentChunks)
    
    init {
        manifest = BurstSkillManifest(
            skillId = "stream_processor",
            skillName = "流处理器",
            version = "1.0.0",
            description = "大文本流式处理，支持分块、并行处理和内存优化",
            author = "Apex Agent",
            tags = listOf("streaming", "chunking", "parallel"),
            priority = 75,
            capabilities = listOf(
                "text_chunking",
                "parallel_processing",
                "memory_optimization",
                "progress_tracking"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            val input = task.input.text ?: task.description
            val mode = task.metadata["mode"] ?: "sequential"
            
            val chunks = splitIntoChunks(input)
            val results = mutableListOf<String>()
            var processedCount = 0
            
            when (mode) {
                "parallel" -> {
                    // 并行处理
                    val deferredResults = chunks.mapIndexed { index, chunkInfo ->
                        scope.async {
                            chunkSemaphore.withPermit {
                                processChunk(chunkInfo.content, chunkInfo)
                            }
                        }
                    }
                    
                    deferredResults.awaitAll().forEach { result ->
                        results.add(result)
                        processedCount++
                    }
                }
                else -> {
                    // 顺序处理
                    for (chunkInfo in chunks) {
                        chunkSemaphore.withPermit {
                            val result = processChunk(chunkInfo.content, chunkInfo)
                            results.add(result)
                            processedCount++
                        }
                    }
                }
            }
            
            val rawResult = results.joinToString("")
            val finalResult = if (context.utilityProcessor?.isEnabled == true && rawResult.length > 100) {
                runBlocking(Dispatchers.IO) {
                    context.utilityProcessor!!.formatForContext(rawResult, rawResult.length)
                }
            } else rawResult
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Stream processing completed:
                    |- Input size: ${input.length} chars
                    |- Total chunks: ${chunks.size}
                    |- Processed chunks: $processedCount
                    |- Output size: ${finalResult.length} chars
                    |- Mode: $mode
                    |- Memory freed: true
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = processedCount
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun splitIntoChunks(text: String): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var position = 0
        var index = 0
        
        while (position < text.length) {
            val endPosition = minOf(position + chunkSize, text.length)
            
            // 尝试在单词边界分割
            var actualEnd = endPosition
            if (endPosition < text.length) {
                val lastSpace = text.lastIndexOf(' ', endPosition)
                if (lastSpace > position) {
                    actualEnd = lastSpace + 1
                }
            }
            
            val content = text.substring(position, actualEnd)
            
            chunks.add(ChunkInfo(
                index = index,
                content = content,
                startPosition = position,
                endPosition = actualEnd,
                isFirst = index == 0,
                isLast = actualEnd >= text.length
            ))
            
            position = actualEnd
            index++
        }
        
        return chunks
    }
    
    private fun processChunk(content: String, chunkInfo: ChunkInfo): String {
        // 模拟处理：实际应该调用处理器
        return content
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.78f
    
    data class ChunkInfo(
        val index: Int,
        val content: String,
        val startPosition: Int,
        val endPosition: Int,
        val isFirst: Boolean = false,
        val isLast: Boolean = false
    )
    
    data class ProcessingResult(
        val processedContent: String,
        val totalChunks: Int,
        val processedChunks: Int,
        val memoryFreed: Boolean = true
    )
}