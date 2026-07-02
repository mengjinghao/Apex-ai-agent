package com.apex.agent.core.tools.skill

import org.json.JSONObject

/**
 * 技能来源类型枚�? */
enum class ProvenanceSource {
    MANUAL,           // 手动创建
    AUTO_EXTRACTED,   // 自动提取
    IMPORTED,         // 外部导入
    EVOLVED           // 演化生成
}

/**
 * 技能来源追踪数据类
 * 记录技能的创建来源、作者、提取方法等元数�? */
data class SkillProvenance(
    val createdAt: Long,
    val sourceType: ProvenanceSource,
    val sourceSessionId: String,
    val confidence: Float,
    val author: String,
    val extractionMethod: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("createdAt", createdAt)
        put("sourceType", sourceType.name)
        put("sourceSessionId", sourceSessionId)
        put("confidence", confidence)
        put("author", author)
        put("extractionMethod", extractionMethod)
    }

    companion object {
        fun fromJson(json: JSONObject): SkillProvenance = SkillProvenance(
            createdAt = json.getLong("createdAt"),
            sourceType = ProvenanceSource.valueOf(json.getString("sourceType")),
            sourceSessionId = json.getString("sourceSessionId"),
            confidence = json.getDouble("confidence").toFloat(),
            author = json.getString("author"),
            extractionMethod = json.getString("extractionMethod")
        )

        fun createAutoExtracted(
            sessionId: String,
            confidence: Float,
            method: String = "n-gram pattern matching"
        ): SkillProvenance = SkillProvenance(
            createdAt = System.currentTimeMillis(),
            sourceType = ProvenanceSource.AUTO_EXTRACTED,
            sourceSessionId = sessionId,
            confidence = confidence,
            author = "AutoSkillExtractor",
            extractionMethod = method
        )

        fun createManual(author: String): SkillProvenance = SkillProvenance(
            createdAt = System.currentTimeMillis(),
            sourceType = ProvenanceSource.MANUAL,
            sourceSessionId = "",
            confidence = 1.0f,
            author = author,
            extractionMethod = "manual creation"
        )

        fun createImported(sourceSessionId: String, author: String): SkillProvenance = SkillProvenance(
            createdAt = System.currentTimeMillis(),
            sourceType = ProvenanceSource.IMPORTED,
            sourceSessionId = sourceSessionId,
            confidence = 1.0f,
            author = author,
            extractionMethod = "imported"
        )

        fun createEvolved(parentSessionId: String, confidence: Float): SkillProvenance = SkillProvenance(
            createdAt = System.currentTimeMillis(),
            sourceType = ProvenanceSource.EVOLVED,
            sourceSessionId = parentSessionId,
            confidence = confidence,
            author = "SkillEvolutionManager",
            extractionMethod = "skill evolution"
        )
    }
}
