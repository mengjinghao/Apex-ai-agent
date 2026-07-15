package com.apex.api.chat.enhance

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.regex.Pattern

/**
 * 流式输出优化器。
 * 实现顺滑的打字效果，智能断句，匀速渲染等优化
 */
object StreamingOutputOptimizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val TAG = "StreamingOptimizer"

    // 打字间隔（毫秒）
    private const val TYPING_INTERVAL_MS = 40L

    // 每次渲染的字符数
    private const val CHARS_PER_TICK = 2

    // 断句识别：中文标点符
    private val CHINESE_PUNCTUATION = setOf(' ' ' ' ' ' ' ' '。
    
    // 断句识别：英文标点符
    private val ENGLISH_PUNCTUATION = setOf('.', '!', '?', ';', ':', ',')

    /**
     * 优化流式输出，实现顺滑打字效    * @param rawFlow 原始流式输出
     * @return 优化后的流式输出
     */
    suspend fun optimizeStream(rawFlow: suspend ((String) -> Unit) -> Unit): Flow<String> = 
        channelFlow {
            // 缓冲区：累积接收到但尚未渲染的内
    val buffer = StringBuilder()
            
            // 完整输出文本：用于异常恢
    val fullText = StringBuilder()

            // 匀速渲染协
    val renderJob = scope.launch {
                try {
                    while (isActive) {
                        delay(TYPING_INTERVAL_MS)
                        
                        // 从缓冲区取内容渲
    if (buffer.isNotEmpty()) {
                            val chunkSize = minOf(CHARS_PER_TICK, buffer.length)
        val chunk = buffer.substring(0, chunkSize)
                            buffer.delete(0, chunkSize)
                            
                            // 发送到输出                           fullText.append(chunk)
                            send(chunk)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "渲染协程异常", e)
                }
            }

            try {
                // 收集原始流式输出
                rawFlow { chunk ->
                    // 处理断句，避免拆分汉字或词语
    val processedChunk = handleWordBreak(chunk)
                    buffer.append(processedChunk)
                }

                // 等待缓冲区排               while (buffer.isNotEmpty() && renderJob.isActive) {
                    delay(TYPING_INTERVAL_MS / 2)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "流式输出异常", e)
                
                // 异常兜底：输出已收集的完整文
    val remainingBuffer = buffer.toString()
        if (remainingBuffer.isNotEmpty()) {
                    send(remainingBuffer)
                }
                
                // 发送错误提               send("\n\n[输出中断，请重试]")
            } finally {
                renderJob.cancel()
            }
        }

    /**
     * 处理词语断句，避免拆分汉字或完整词语
     * @param input 输入文本
     * @return 处理后的文本
     */
    private fun handleWordBreak(input: String): String {
        if (input.isEmpty()) return input

        // 处理中文：避免在汉字中间截断
        // （简化实现：完整保留输入块，在渲染层面做更细粒度控制
    return input
    }

    /**
     * 智能缓冲：在适当位置（标点后）进行输    * @param buffer 当前缓冲    * @return 可以安全输出的文    */
    fun smartBuffer(buffer: String): Pair<String, String> {
        // 查找最后一个标点符号的位置
    var lastPunctuationIndex = -1
        for (i in buffer.length - 1 downTo 0) {
            val char = buffer[i]
            if (char in CHINESE_PUNCTUATION || char in ENGLISH_PUNCTUATION) {
                lastPunctuationIndex = i
                break
            }
        }

        // 如果找到标点，在标点后分
    return if (lastPunctuationIndex > 0) {
            val output = buffer.substring(0, lastPunctuationIndex + 1)
        val remaining = buffer.substring(lastPunctuationIndex + 1)
            output to remaining
        } else {
            // 如果没有找到标点，返回空输出，继续缓           "" to buffer
        }
    }

    /**
     * 计算渲染进度百分    * @param receivedChars 已接收字符数
     * @param estimatedTotalChars 估计总字符数
     * @return 进度百分    */
    fun calculateProgress(receivedChars: Int, estimatedTotalChars: Int): Int {
        if (estimatedTotalChars == null || estimatedTotalChars <= 0) return 0
        
        val progress = (receivedChars * 100) / estimatedTotalChars
        return progress.coerceIn(0, 100)
    }

    /**
     * 配置选项
     */
    data class OptimizationConfig(
        val typingSpeed: Long = TYPING_INTERVAL_MS,
        val charsPerTick: Int = CHARS_PER_TICK,
        val enableSmartBuffering: Boolean = true,
        val enableErrorRecovery: Boolean = true
    )

    /**
     * 默认配置
     */
    val DEFAULT_CONFIG = OptimizationConfig()
}
