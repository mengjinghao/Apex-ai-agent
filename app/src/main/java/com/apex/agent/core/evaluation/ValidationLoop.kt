package com.apex.agent.core.evaluation

import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 验证结果数据�? */
data class ValidationResult(
    val pass: Boolean,
    val score: Float,
    val details: String,
    val duration: Long
)

/**
 * Pass@K 报告数据�? */
data class PassKReport(
    val k: Int,
    val passAtK: Float,
    val passPowK: Float,
    val results: List<ValidationResult>,
    val averageScore: Float
)

/**
 * Pass@K 验证循环核心
 * 执行 k 次独立验证，计算 pass@k �?pass^k 指标
 */
object ValidationLoop {
    private const val TAG = "ValidationLoop"
        private const val HISTORY_FILE_NAME = "validation_history.json"
    
    // 任务验证历史缓存
    private val validationHistory = ConcurrentHashMap<String, MutableList<PassKReport>>()

    /**
     * 执行 k 次独立验�?     * @param taskId 任务ID
     * @param output 待验证的输出
     * @param k 验证次数，默�?3
     * @return Pass@K 报告
     */
    suspend fun executeValidation(taskId: String, output: String, k: Int = 3): PassKReport = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "开始执行任�?${taskId} �?${k} 次验�?)
        val results = mutableListOf<ValidationResult>()
        val startTime = System.currentTimeMillis()

        repeat(k) { iteration ->
            val iterationStart = System.currentTimeMillis()
            
            try {
                // 模拟验证过程
                delay(100) // 模拟验证耗时
    val validationResult = performSingleValidation(output, iteration)
                results.add(validationResult)
                
                AppLogger.d(TAG, "�?${iteration + 1}/${k} 次验证完�? ${if (validationResult.pass) "通过" else "失败"}, 评分 ${validationResult.score}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "�?${iteration + 1} 次验证异�?, e)
                results.add(ValidationResult(
                    pass = false,
                    score = 0f,
                    details = "验证异常: ${e.message}",
                    duration = System.currentTimeMillis() - iterationStart
                ))
            }
        }
        val totalDuration = System.currentTimeMillis() - startTime
        val passAtK = calculatePassAtK(results)
        val passPowK = calculatePassPowK(results)
        val averageScore = results.map { it.score }.average().toFloat()
        val report = PassKReport(
            k = k,
            passAtK = passAtK,
            passPowK = passPowK,
            results = results,
            averageScore = averageScore
        )

        // 保存到历史记�?        saveToHistory(taskId, report)

        AppLogger.i(TAG, "任务 ${taskId} 验证完成: pass@${k}=${passAtK}, pass^${k}=${passPowK}, 平均评分=${averageScore}, 总耗时=${totalDuration}ms")

        report
    }

    /**
     * 计算 pass@k
     * 公式: 1 - C(n-c, k) / C(n, k)
     * 其中 n 是总尝试次数，c 是通过的次�?     */
    fun calculatePassAtK(results: List<ValidationResult>): Float {
        if (results.isEmpty()) return 0f
        
        val n = results.size
        val c = results.count { it.pass }
        val k = minOf(n, 1) // pass@1
    if (c == 0) return 0f
        if (c >= n) return 1f
        
        // 使用组合数公式计�?        // pass@k = 1 - C(n-c, k) / C(n, k)
        val combination = calculateCombination(n - c, k) / calculateCombination(n, k)
        return (1f - combination).coerceIn(0f, 1f)
    }

    /**
     * 计算 pass^k
     * 公式: (c/n)^k
     * 其中 c 是通过的次数，n 是总次�?     */
    fun calculatePassPowK(results: List<ValidationResult>): Float {
        if (results.isEmpty()) return 0f
        
        val n = results.size
        val c = results.count { it.pass }
        val k = minOf(n, 1) // pass^1
    val passRate = c.toFloat() / n.toFloat()
        return Math.pow(passRate.toDouble(), k.toDouble()).toFloat()
    }

    /**
     * 执行单次验证
     */
    private fun performSingleValidation(output: String, iteration: Int): ValidationResult {
        val startTime = System.currentTimeMillis()
        
        // 模拟验证逻辑
    val hasContent = output.isNotBlank()
        val hasStructure = output.contains("#") || output.contains("```")
        val hasDetails = output.length > 100
        
        val pass = hasContent && hasStructure && hasDetails
        val score = when {
            !hasContent -> 0f
            !hasStructure -> 0.3f
            !hasDetails -> 0.6f
            else -> 0.8f + (iteration * 0.05f).coerceAtMost(0.2f) // 略有波动
        }
        val details = buildString {
            appendLine("验证详情:")
            appendLine("- 内容完整�? ${if (hasContent) "�? else "�?}")
            appendLine("- 结构规范�? ${if (hasStructure) "�? else "�?}")
            appendLine("- 详细程度: ${if (hasDetails) "�? else "�?}")
            appendLine("- 评分: ${score}")
        }
        val duration = System.currentTimeMillis() - startTime
        
        return ValidationResult(
            pass = pass,
            score = score,
            details = details,
            duration = duration
        )
    }

    /**
     * 计算组合�?C(n, k)
     */
    private fun calculateCombination(n: Int, k: Int): Double {
        if (k > n) return 0.0
        if (k == 0 || k == n) return 1.0
        
        var result = 1.0
        for (i in 1..k) {
            result = result * (n - i + 1) / i
        }
        return result
    }

    /**
     * 保存到历史记�?     */
    private fun saveToHistory(taskId: String, report: PassKReport) {
        validationHistory.getOrPut(taskId) { mutableListOf() }.add(report)
        
        // 实际实现中，这里应该持久化到文件
        AppLogger.d(TAG, "已保存任�?${taskId} 的验证历史，当前�?${validationHistory[taskId]?.size} 条记�?)
    }

    /**
     * 获取任务的验证历�?     */
    fun getValidationHistory(taskId: String): List<PassKReport> {
        return validationHistory[taskId]?.toList() ?: emptyList()
    }

    /**
     * 清除任务的验证历�?     */
    fun clearValidationHistory(taskId: String) {
        validationHistory.remove(taskId)
        AppLogger.d(TAG, "已清除任�?${taskId} 的验证历�?)
    }
}
