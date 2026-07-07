package com.apex.lib.workingfiles.snapshot

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * 文件快照 — 文件在某时刻的完整状态。
 *
 * **设计参考**：
 *   - VSCode Timeline：自动 + 手动保存文件历史，每个版本一个快照
 *   - JetBrains Local History：每次外部修改触发快照
 *   - Cline (VSCode AI 插件)：每次 AI 工具调用前快照，支持 checkpoint 回退
 *   - Aider：用 git commit 跟踪每次 AI 编辑，可 /undo 回退
 *
 * **存储策略**：本实现存储文件**全文**（不是 diff），原因：
 *   - 代码文件通常 < 100KB，全文存储成本可接受
 *   - 全文存储支持 O(1) 时间恢复，无需重放历史
 *   - 简化实现，避免 diff 累积误差
 *
 * **快照触发时机**：
 *   1. Agent 写入前自动快照（AutoSnapshotOnWrite）
 *   2. 文件监听检测到外部修改（debounce 1s）
 *   3. 手动调用 takeSnapshot()
 *
 * @property id 快照 ID（UUID）
 * @property filePath 文件绝对路径
 * @property relativePath 相对工作区根目录的路径（便于展示）
 * @property timestamp 时间戳
 * @property content 文件全文内容
 * @property contentHash 内容 SHA-256（用于去重和快速比较）
 * @property changeType 变更类型（CREATE / MODIFY / DELETE）
 * @property source 变更来源（AGENT / USER / EXTERNAL / MANUAL）
 * @property agentId 触发变更的 Agent ID（source=AGENT 时有值）
 * @property sessionId Agent 会话 ID（source=AGENT 时有值）
 * @property stepId 关联的 Agent 步骤 ID（source=AGENT 时有值）
 * @property description 人类可读的变更描述
 * @property lineCount 行数
 * @property charCount 字符数
 */
@Serializable
data class FileSnapshot(
    val id: String,
    val filePath: String,
    val relativePath: String,
    val timestamp: Long,
    val content: String,
    val contentHash: String,
    val changeType: ChangeType,
    val source: ChangeSource,
    val agentId: String? = null,
    val sessionId: String? = null,
    val stepId: String? = null,
    val description: String = "",
    val lineCount: Int = 0,
    val charCount: Int = 0
) {
    companion object {
        /**
         * 创建一个快照。
         * 自动计算 hash / lineCount / charCount。
         */
        fun create(
            filePath: String,
            relativePath: String,
            content: String,
            changeType: ChangeType,
            source: ChangeSource,
            agentId: String? = null,
            sessionId: String? = null,
            stepId: String? = null,
            description: String = ""
        ): FileSnapshot {
            val hash = sha256(content)
            return FileSnapshot(
                id = generateId(),
                filePath = filePath,
                relativePath = relativePath,
                timestamp = System.currentTimeMillis(),
                content = content,
                contentHash = hash,
                changeType = changeType,
                source = source,
                agentId = agentId,
                sessionId = sessionId,
                stepId = stepId,
                description = description,
                lineCount = content.count { it == '\n' } + 1,
                charCount = content.length
            )
        }

        /**
         * 从现有文件创建快照（用于首次打开文件时建立基线）。
         */
        fun fromFile(
            file: File,
            rootPath: String,
            source: ChangeSource = ChangeSource.EXTERNAL,
            description: String = "初始基线快照"
        ): FileSnapshot? {
            if (!file.exists() || !file.isFile) return null
            val content = try { file.readText() } catch (t: Throwable) { return null }
            return create(
                filePath = file.absolutePath,
                relativePath = file.absolutePath.removePrefix(rootPath).removePrefix("/"),
                content = content,
                changeType = ChangeType.CREATE,
                source = source,
                description = description
            )
        }

        private fun sha256(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun generateId(): String {
            val ts = System.currentTimeMillis().toString(36)
            val random = (0 until 8).map { ('a'..'z').random() }.joinToString("")
            return "snap-$ts-$random"
        }
    }
}

/** 变更类型。 */
@Serializable
enum class ChangeType {
    CREATE,   // 文件被创建
    MODIFY,   // 文件被修改
    DELETE,   // 文件被删除
    RENAME    // 文件被重命名（暂未使用，预留）
}

/** 变更来源。 */
@Serializable
enum class ChangeSource(val displayName: String) {
    AGENT("Agent"),       // Agent 主动写入
    USER("用户"),         // 用户手动操作
    EXTERNAL("外部"),     // 文件监听检测到外部修改
    MANUAL("手动")        // 手动调用 takeSnapshot
}
