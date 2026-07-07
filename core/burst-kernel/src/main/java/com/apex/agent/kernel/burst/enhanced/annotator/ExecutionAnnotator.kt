package com.apex.agent.kernel.burst.enhanced.annotator

import java.util.concurrent.ConcurrentHashMap

/**
 * B53: 执行注解系统
 *
 * 为执行附加注解/备注：
 * - 用户备注
 * - 系统注解
 * - 调试信息
 * - 审计追踪
 */
class ExecutionAnnotator {

    data class Annotation(
        val id: String,
        val executionId: String,
        val type: AnnotationType,
        val content: String,
        val author: String,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )

    enum class AnnotationType {
        USER_NOTE,       // 用户备注
        SYSTEM_NOTE,     // 系统注解
        DEBUG_INFO,      // 调试信息
        AUDIT_TRAIL,     // 审计追踪
        WARNING,         // 警告
        OPTIMIZATION,    // 优化建议
        KNOWN_ISSUE      // 已知问题
    }

    private val annotations = ConcurrentHashMap<String, MutableList<Annotation>>()
    private val annotationIndex = ConcurrentHashMap<AnnotationType, MutableList<Annotation>>()

    /**
     * 添加注解
     */
    fun annotate(
        executionId: String,
        type: AnnotationType,
        content: String,
        author: String = "system",
        metadata: Map<String, String> = emptyMap()
    ): Annotation {
        val annotation = Annotation(
            id = "ann_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            executionId = executionId, type = type, content = content,
            author = author, metadata = metadata
        )
        annotations.computeIfAbsent(executionId) { mutableListOf() }.add(annotation)
        annotationIndex.computeIfAbsent(type) { mutableListOf() }.add(annotation)
        return annotation
    }

    fun addUserNote(executionId: String, note: String) =
        annotate(executionId, AnnotationType.USER_NOTE, note, "user")

    fun addDebugInfo(executionId: String, info: String) =
        annotate(executionId, AnnotationType.DEBUG_INFO, info, "system")

    fun addWarning(executionId: String, warning: String) =
        annotate(executionId, AnnotationType.WARNING, warning, "system")

    fun addOptimizationSuggestion(executionId: String, suggestion: String) =
        annotate(executionId, AnnotationType.OPTIMIZATION, suggestion, "system")

    fun addKnownIssue(executionId: String, issue: String) =
        annotate(executionId, AnnotationType.KNOWN_ISSUE, issue, "system")

    fun getAnnotations(executionId: String): List<Annotation> =
        annotations[executionId]?.toList() ?: emptyList()

    fun getByType(type: AnnotationType): List<Annotation> =
        annotationIndex[type]?.toList() ?: emptyList()

    fun removeAnnotation(annotationId: String): Boolean {
        for ((_, list) in annotations) {
            val idx = list.indexOfFirst { it.id == annotationId }
            if (idx >= 0) {
                val removed = list.removeAt(idx)
                annotationIndex[removed.type]?.removeAll { it.id == annotationId }
                return true
            }
        }
        return false
    }

    fun clear(executionId: String) {
        annotations.remove(executionId)?.forEach { ann ->
            annotationIndex[ann.type]?.removeAll { it.executionId == executionId }
        }
    }

    fun getStats(): AnnotatorStats {
        return AnnotatorStats(
            totalAnnotations = annotations.values.sumOf { it.size },
            byType = annotationIndex.mapValues { it.value.size },
            executionsWithAnnotations = annotations.size
        )
    }

    data class AnnotatorStats(
        val totalAnnotations: Int,
        val byType: Map<AnnotationType, Int>,
        val executionsWithAnnotations: Int
    )
}
