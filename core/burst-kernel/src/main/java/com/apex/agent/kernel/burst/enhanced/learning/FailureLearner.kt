package com.apex.agent.kernel.burst.enhanced.learning

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * B3: 失败学习系统（Failure Learning System）
 *
 * 跨任务/跨会话的失败记忆：
 * - 收集所有失败任务的 (input, error, stacktrace, context)
 * - 自动聚类、提取"失败模式"
 * - 下次相似任务时注入"避坑提示"到 prompt
 *
 * 区别于现有 Reflexion（单任务内自省），Failure Learning 是长期记忆
 */
class FailureLearner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxPatterns: Int = 1000,
    private val maxRecordsPerPattern: Int = 50,
    private val similarityThreshold: Float = 0.7f
) {

    /**
     * 失败记录
     */
    data class FailureRecord(
        val id: String,
        val timestamp: Long,
        val taskId: String,
        val skillId: String,
        val input: String,
        val errorType: String,
        val errorMessage: String,
        val stacktrace: String?,
        val context: Map<String, String>,
        val signature: String,          // 输入特征签名
        val patternId: String? = null   // 归属的失败模式
    )

    /**
     * 失败模式（聚类后）
     */
    data class FailurePattern(
        val patternId: String,
        val signature: String,
        val errorType: String,
        val frequency: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val avoidanceHint: String,      // LLM 总结的避坑建议
        val examples: List<String>,     // 示例错误消息
        val affectedSkills: Set<String>,
        val severity: PatternSeverity
    )

    enum class PatternSeverity { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * 学习统计
     */
    data class LearningStats(
        val totalFailures: Int,
        val totalPatterns: Int,
        val topPatterns: List<FailurePattern>,
        val failureRateBySkill: Map<String, Float>,
        val mostCommonError: String?
    )

    // ============ 存储 ============

    private val records = ConcurrentHashMap<String, FailureRecord>()
    private val patterns = ConcurrentHashMap<String, FailurePattern>()
    private val recordsByPattern = ConcurrentHashMap<String, MutableList<FailureRecord>>()

    // ============ 公共 API ============

    /**
     * 记录失败
     */
    fun record(
        taskId: String,
        skillId: String,
        input: String,
        error: Throwable,
        context: Map<String, String> = emptyMap()
    ): FailureRecord {
        val signature = computeSignature(input, skillId)
        val record = FailureRecord(
            id = "fail_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            timestamp = System.currentTimeMillis(),
            taskId = taskId,
            skillId = skillId,
            input = input.take(500),
            errorType = error::class.simpleName ?: "Unknown",
            errorMessage = error.message ?: "",
            stacktrace = error.stackTraceToString().take(1000),
            context = context,
            signature = signature
        )
        records[record.id] = record

        // 尝试归类到现有模式
        val matchedPattern = findMatchingPattern(record)
        if (matchedPattern != null) {
            val updated = matchedPattern.copy(
                frequency = matchedPattern.frequency + 1,
                lastSeen = record.timestamp,
                examples = (matchedPattern.examples + record.errorMessage.take(100)).distinct().take(5),
                affectedSkills = matchedPattern.affectedSkills + skillId
            )
            patterns[matchedPattern.patternId] = updated
            recordsByPattern.computeIfAbsent(matchedPattern.patternId) { mutableListOf() }.add(record)
        } else {
            // 创建新模式
            val patternId = "pattern_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
            val severity = classifySeverity(error)
            val hint = generateAvoidanceHint(record)
            val pattern = FailurePattern(
                patternId = patternId,
                signature = signature,
                errorType = record.errorType,
                frequency = 1,
                firstSeen = record.timestamp,
                lastSeen = record.timestamp,
                avoidanceHint = hint,
                examples = listOf(record.errorMessage.take(100)),
                affectedSkills = setOf(skillId),
                severity = severity
            )
            patterns[patternId] = pattern
            recordsByPattern.computeIfAbsent(patternId) { mutableListOf() }.add(record)
        }

        // 限制大小
        if (records.size > maxPatterns * maxRecordsPerPattern) {
            val oldest = records.entries.minByOrNull { it.value.timestamp }
            oldest?.let { records.remove(it.key) }
        }

        return record
    }

    /**
     * 查询相关失败模式（注入到下次 prompt）
     */
    fun lookup(taskId: String, skillId: String, input: String): List<FailurePattern> {
        val signature = computeSignature(input, skillId)
        return patterns.values
            .filter { p ->
                // 相似度高的模式
                computeSimilarity(p.signature, signature) >= similarityThreshold ||
                // 同 Skill 的近期失败
                (skillId in p.affectedSkills && System.currentTimeMillis() - p.lastSeen < 24 * 60 * 60_000L)
            }
            .sortedByDescending { it.frequency * it.severity.ordinal }
            .take(3)
    }

    /**
     * 生成避坑提示 prompt
     */
    fun generateAvoidancePrompt(taskId: String, skillId: String, input: String): String {
        val relatedPatterns = lookup(taskId, skillId, input)
        if (relatedPatterns.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[失败学习提示]")
        sb.appendLine("根据历史失败记录，执行此任务时需注意：")
        relatedPatterns.forEach { pattern ->
            sb.appendLine("- ${pattern.avoidanceHint}")
            sb.appendLine("  (失败 ${pattern.frequency} 次，严重度: ${pattern.severity})")
        }
        return sb.toString()
    }

    /**
     * 获取所有失败模式
     */
    fun getAllPatterns(): List<FailurePattern> = patterns.values.sortedByDescending { it.frequency }.toList()

    /**
     * 获取统计
     */
    fun getStats(): LearningStats {
        val totalFailures = records.size
        val totalPatterns = patterns.size
        val topPatterns = patterns.values.sortedByDescending { it.frequency }.take(5)

        val skillFailures = records.values.groupingBy { it.skillId }.eachCount()
        val failureRateBySkill = skillFailures.mapValues { (_, count) ->
            count.toFloat() / totalFailures.coerceAtLeast(1)
        }

        val mostCommon = patterns.values.maxByOrNull { it.frequency }?.errorType

        return LearningStats(totalFailures, totalPatterns, topPatterns, failureRateBySkill, mostCommon)
    }

    /**
     * 重新聚类所有失败记录
     */
    suspend fun recluster() {
        scope.launch {
            val allRecords = records.values.toList()
            patterns.clear()
            recordsByPattern.clear()

            // 简化聚类：按 signature 分组
            val grouped = allRecords.groupBy { it.signature }
            for ((signature, group) in grouped) {
                if (group.isEmpty()) continue
                val first = group.first()
                val patternId = "pattern_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
                val severity = classifySeverity(first.errorType)
                val hint = generateAvoidanceHint(first)
                val pattern = FailurePattern(
                    patternId = patternId,
                    signature = signature,
                    errorType = first.errorType,
                    frequency = group.size,
                    firstSeen = group.minOf { it.timestamp },
                    lastSeen = group.maxOf { it.timestamp },
                    avoidanceHint = hint,
                    examples = group.map { it.errorMessage.take(100) }.distinct().take(5),
                    affectedSkills = group.map { it.skillId }.toSet(),
                    severity = severity
                )
                patterns[patternId] = pattern
                recordsByPattern[patternId] = group.toMutableList()
            }
        }.join()
    }

    /**
     * 清空
     */
    fun clear() {
        records.clear()
        patterns.clear()
        recordsByPattern.clear()
    }

    // ============ 内部方法 ============

    private fun computeSignature(input: String, skillId: String): String {
        // 提取输入特征：长度 + 关键词 + skillId
        val keywords = input.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】]+"))
            .filter { it.length >= 3 }
            .take(5)
            .sorted()
            .joinToString("|")
        val features = "$skillId:${input.length}:${keywords}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(features.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun findMatchingPattern(record: FailureRecord): FailurePattern? {
        return patterns.values
            .filter { it.errorType == record.errorType }
            .maxByOrNull { computeSimilarity(it.signature, record.signature) }
            ?.takeIf { computeSimilarity(it.signature, record.signature) >= similarityThreshold }
    }

    private fun computeSimilarity(sig1: String, sig2: String): Float {
        if (sig1 == sig2) return 1.0f
        // 简化：汉明距离
        if (sig1.length != sig2.length) return 0f
        val matching = sig1.zip(sig2).count { it.first == it.second }
        return matching.toFloat() / sig1.length
    }

    private fun classifySeverity(error: Throwable): PatternSeverity {
        val msg = error.message?.lowercase() ?: ""
        val type = error::class.simpleName?.lowercase() ?: ""
        return when {
            type.contains("outofmemory", true) || type.contains("oom") -> PatternSeverity.CRITICAL
            type.contains("security", true) || type.contains("permission") -> PatternSeverity.CRITICAL
            type.contains("timeout", true) || msg.contains("timeout") -> PatternSeverity.HIGH
            type.contains("ioexception", true) || type.contains("network") -> PatternSeverity.MEDIUM
            type.contains("illegalargument", true) || type.contains("validation") -> PatternSeverity.LOW
            else -> PatternSeverity.MEDIUM
        }
    }

    private fun classifySeverity(errorType: String): PatternSeverity {
        val t = errorType.lowercase()
        return when {
            t.contains("outofmemory") || t.contains("oom") -> PatternSeverity.CRITICAL
            t.contains("security") || t.contains("permission") -> PatternSeverity.CRITICAL
            t.contains("timeout") -> PatternSeverity.HIGH
            t.contains("ioexception") || t.contains("network") -> PatternSeverity.MEDIUM
            t.contains("illegalargument") || t.contains("validation") -> PatternSeverity.LOW
            else -> PatternSeverity.MEDIUM
        }
    }

    private fun generateAvoidanceHint(record: FailureRecord): String {
        // 基于错误类型生成避坑建议（实际可调用 LLM 生成更精准的）
        val errorType = record.errorType
        val msg = record.errorMessage
        return when {
            errorType.contains("Timeout", true) -> "注意超时：考虑减少任务范围或增加超时时间"
            errorType.contains("OutOfMemory", true) -> "内存不足：减少上下文大小或分批处理"
            errorType.contains("Network", true) || errorType.contains("IOException") ->
                "网络问题：检查连接，准备重试或降级"
            errorType.contains("Security", true) || errorType.contains("Permission") ->
                "权限不足：检查权限配置"
            errorType.contains("IllegalArgument", true) ->
                "参数错误：校验输入: ${msg.take(100)}"
            else -> "已知风险: ${msg.take(100)}"
        }
    }
}
