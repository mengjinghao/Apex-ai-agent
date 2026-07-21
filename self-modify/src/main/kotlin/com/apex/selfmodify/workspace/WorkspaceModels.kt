package com.apex.selfmodify.workspace

import kotlinx.serialization.Serializable
import java.io.File

data class WorkspaceConfig(
    val rootDir: File,
    val sourceDirs: List<File>,
    val snapshotDir: File,
    val indexDir: File,
    val auditDir: File
)

@Serializable
data class FileChange(
    val path: String,
    val type: ChangeType,
    val newContent: String? = null,
    val oldContent: String? = null
)

@Serializable
enum class ChangeType { CREATE, MODIFY, DELETE, MOVE }

@Serializable
data class Snapshot(
    val id: String,
    val tag: String,
    val timestamp: Long,
    val commitSha: String?
)
