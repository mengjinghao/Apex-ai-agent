package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

/**
 * 无限上下文技能
 * 支持超长文本处理和滑动窗口
 */
class InfiniteContextSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val windowSize = 4096
    private val overlap = 512
    
    init {
        manifest = BurstSkillManifest(
            skillId = "infinite_context",
            skillName = "无限上下文",
            version = "1.0.0",
            description = "支持超长文本处理，使用滑动窗口技术",
            author = "Apex Agent",
            tags = listOf("context", "long-text", "sliding-window"),
            priority = 90,
            capabilities = listOf(
                "sliding_window",
                "context_merging"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val text = task.input.text ?: ""
            val windows = createSlidingWindows(text)
            
            val results = windows.mapIndexed { index, window ->
                processWindow(index, window)
            }
            
            val merged = mergeResults(results)
            
            val executionTime = System.currentTimeMillis() - startTime
            BurstSkillResult(
                success = true,
                output = merged,
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    tokensProcessed = text.length,
                    stepsCompleted = windows.size
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun createSlidingWindows(text: String): List<String> {
        if (text.length <= windowSize) {
            return listOf(text)
        }
        
        val windows = mutableListOf<String>()
        var position = 0
        
        while (position < text.length) {
            val end = minOf(position + windowSize, text.length)
            windows.add(text.substring(position, end))
            position += windowSize - overlap
        }
        
        return windows
    }
    
    private suspend fun processWindow(index: Int, window: String): WindowResult {
        delay(50)
        return WindowResult(
            index = index,
            result = "Processed window $index: ${window.take(50)}...",
            continuation = if (index > 0) null else window.takeLast(overlap)
        )
    }
    
    private fun mergeResults(results: List<WindowResult>): String {
        return results.sortedBy { it.index }.joinToString("\n") { it.result }
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
    
    override fun evaluate(): Float = 0.9f
    
    data class WindowResult(
        val index: Int,
        val result: String,
        val continuation: String?
    )
}
