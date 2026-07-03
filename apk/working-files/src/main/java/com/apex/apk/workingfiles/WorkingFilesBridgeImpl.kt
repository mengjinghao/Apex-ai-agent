package com.apex.apk.workingfiles

import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkingFilesBridgeImpl(
    private val facade: WorkingFilesServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.WORKING_FILES, "[WorkingFilesBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "workingfiles/bindFolder" -> {
                        val id = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val name = args["displayName"]?.jsonPrimitive?.content ?: ""
                        val path = args["path"]?.jsonPrimitive?.content ?: ""
                        val mode = args["mode"]?.jsonPrimitive?.content ?: "ALL"
                        buildResult(facade.bindFolder(id, name, path, mode)) { JsonObject(emptyMap()) }
                    }
                    "workingfiles/unbindFolder" -> {
                        val id = args["folderId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.unbindFolder(id)) { JsonPrimitive(it) }
                    }
                    "workingfiles/listFolders" -> {
                        val list = facade.listFolders()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("folders", list.joinToString("\n") { "${it.id}: ${it.displayName} (${it.path})" })
                        }.toString()
                    }
                    "workingfiles/listFoldersForMode" -> {
                        val mode = args["mode"]?.jsonPrimitive?.content ?: "ALL"
                        val list = facade.listFoldersForMode(mode)
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("mode", mode)
                        }.toString()
                    }
                    "workingfiles/listFiles" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listFiles(folderId, relPath)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") {
                                    (if (it.isDirectory) "[DIR] " else "[FILE] ") + it.relativePath + " (${it.size}B)"
                                })
                            }
                        }
                    }
                    "workingfiles/readFile" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.readFile(folderId, relPath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/writeFile" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        val content = args["content"]?.jsonPrimitive?.content ?: ""
                        val append = args["append"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        buildResult(facade.writeFile(folderId, relPath, content, append)) { JsonPrimitive(it) }
                    }
                    "workingfiles/deleteFile" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteFile(folderId, relPath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/createDirectory" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.createDirectory(folderId, relPath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/exists" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.exists(folderId, relPath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/loadCodeFile" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.loadCodeFile(folderId, relPath)) { cf ->
                            buildJsonObject {
                                put("path", cf.path)
                                put("language", cf.language)
                                put("lineCount", cf.lineCount)
                                put("totalChars", cf.totalChars)
                                put("contentPreview", cf.content.take(500))
                                put("tokenCount", cf.tokens.size)
                            }
                        }
                    }
                    "workingfiles/subscribeChanges" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val channel = facade.subscribeChanges(folderId)
                        buildJsonObject {
                            put("success", channel != null)
                            put("channelName", channel ?: "")
                        }.toString()
                    }
                    "workingfiles/unsubscribeChanges" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        facade.unsubscribeChanges(folderId)
                        buildJsonObject { put("success", true) }.toString()
                    }

                    // ===== VSCode 式代码查看 + Agent 执行流程 + 回退 =====

                    "workingfiles/getFileTree" -> {
                        val root = args["rootPath"]?.jsonPrimitive?.content ?: ""
                        val depth = args["maxDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                        buildResult(facade.getFileTree(root, depth)) { node ->
                            buildJsonObject {
                                put("name", node.name)
                                put("path", node.path)
                                put("relativePath", node.relativePath)
                                put("isDirectory", node.isDirectory)
                                put("childCount", node.children.size)
                                put("tree", serializeFileTree(node, 0))
                            }
                        }
                    }
                    "workingfiles/loadCodeFileWithTokens" -> {
                        val path = args["filePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.loadCodeFileWithTokens(path)) { cf ->
                            if (cf == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("path", cf.path)
                                put("language", cf.language)
                                put("lineCount", cf.lineCount)
                                put("totalChars", cf.totalChars)
                                put("tokenCount", cf.tokens.size)
                                put("contentPreview", cf.content.take(2000))
                            }
                        }
                    }
                    "workingfiles/takeSnapshot" -> {
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        val rootPath = args["rootPath"]?.jsonPrimitive?.content ?: ""
                        val source = args["source"]?.jsonPrimitive?.content ?: "MANUAL"
                        val agentId = args["agentId"]?.jsonPrimitive?.content
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content
                        val desc = args["description"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.takeSnapshot(filePath, rootPath, source, agentId, sessionId, null, desc)) { snap ->
                            if (snap == null) buildJsonObject { put("success", false) }
                            else buildJsonObject {
                                put("id", snap.id)
                                put("timestamp", snap.timestamp)
                                put("hash", snap.contentHash)
                                put("lineCount", snap.lineCount)
                            }
                        }
                    }
                    "workingfiles/writeWithSnapshot" -> {
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        val rootPath = args["rootPath"]?.jsonPrimitive?.content ?: ""
                        val content = args["content"]?.jsonPrimitive?.content ?: ""
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val agentName = args["agentName"]?.jsonPrimitive?.content ?: ""
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val desc = args["description"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.writeWithSnapshot(filePath, rootPath, content, agentId, agentName, sessionId, desc)) { r ->
                            buildJsonObject {
                                put("afterSnapshotId", r.afterSnapshot.id)
                                put("beforeSnapshotId", r.beforeSnapshot?.id ?: "")
                                put("stepId", r.step?.id ?: "")
                            }
                        }
                    }
                    "workingfiles/listSnapshots" -> {
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listSnapshots(filePath)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("snapshots", list.joinToString("\n") { s ->
                                    "${s.id}|${s.timestamp}|${s.source.name}|${s.changeType.name}|${s.description.take(50)}|${s.lineCount}L"
                                })
                            }
                        }
                    }
                    "workingfiles/getSnapshot" -> {
                        val id = args["snapshotId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getSnapshot(id)) { snap ->
                            if (snap == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("id", snap.id)
                                put("filePath", snap.filePath)
                                put("relativePath", snap.relativePath)
                                put("timestamp", snap.timestamp)
                                put("hash", snap.contentHash)
                                put("changeType", snap.changeType.name)
                                put("source", snap.source.name)
                                put("agentId", snap.agentId ?: "")
                                put("sessionId", snap.sessionId ?: "")
                                put("description", snap.description)
                                put("lineCount", snap.lineCount)
                                put("charCount", snap.charCount)
                                put("contentPreview", snap.content.take(2000))
                            }
                        }
                    }
                    "workingfiles/getLatestSnapshot" -> {
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getLatestSnapshot(filePath)) { snap ->
                            if (snap == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("id", snap.id)
                                put("timestamp", snap.timestamp)
                                put("description", snap.description)
                            }
                        }
                    }
                    "workingfiles/restoreSnapshot" -> {
                        val id = args["snapshotId"]?.jsonPrimitive?.content ?: ""
                        val operator = args["operator"]?.jsonPrimitive?.content ?: "user"
                        buildResult(facade.restoreSnapshot(id, operator)) { JsonPrimitive(it) }
                    }
                    "workingfiles/deleteAllSnapshots" -> {
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteAllSnapshots(filePath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/computeDiff" -> {
                        val old = args["oldContent"]?.jsonPrimitive?.content ?: ""
                        val new = args["newContent"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.computeDiff(old, new)) { diff ->
                            serializeDiff(diff)
                        }
                    }
                    "workingfiles/diffSnapshots" -> {
                        val beforeId = args["beforeId"]?.jsonPrimitive?.content ?: ""
                        val afterId = args["afterId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.diffSnapshots(beforeId, afterId)) { diff ->
                            if (diff == null) buildJsonObject { put("found", false) }
                            else serializeDiff(diff)
                        }
                    }
                    "workingfiles/diffWithCurrent" -> {
                        val id = args["snapshotId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.diffWithCurrent(id)) { diff ->
                            if (diff == null) buildJsonObject { put("found", false) }
                            else serializeDiff(diff)
                        }
                    }
                    "workingfiles/diffForStep" -> {
                        val stepId = args["stepId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.diffForStep(stepId)) { diff ->
                            if (diff == null) buildJsonObject { put("found", false) }
                            else serializeDiff(diff)
                        }
                    }
                    "workingfiles/startAgentSession" -> {
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val agentName = args["agentName"]?.jsonPrimitive?.content ?: ""
                        val taskDesc = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val mode = args["mode"]?.jsonPrimitive?.content ?: "NORMAL"
                        buildResult(facade.startAgentSession(agentId, agentName, taskDesc, mode)) { s ->
                            buildJsonObject {
                                put("sessionId", s.id)
                                put("startTime", s.startTime)
                                put("mode", s.mode.name)
                            }
                        }
                    }
                    "workingfiles/recordAgentStep" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val agentName = args["agentName"]?.jsonPrimitive?.content ?: ""
                        val type = args["type"]?.jsonPrimitive?.content ?: "CUSTOM"
                        val title = args["title"]?.jsonPrimitive?.content ?: ""
                        val desc = args["description"]?.jsonPrimitive?.content ?: ""
                        val thought = args["thought"]?.jsonPrimitive?.content
                        val action = args["action"]?.jsonPrimitive?.content
                        val result = args["result"]?.jsonPrimitive?.content
                        val isSuccess = args["isSuccess"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        val errorMsg = args["errorMessage"]?.jsonPrimitive?.content
                        val affectedFiles = args["affectedFiles"]?.jsonPrimitive?.content?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        val snapshotIds = args["snapshotIds"]?.jsonPrimitive?.content?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        val durationMs = args["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        buildResult(facade.recordAgentStep(
                            sessionId, agentId, agentName, type, title, desc, thought, action, result,
                            isSuccess, errorMsg, affectedFiles, snapshotIds, durationMs
                        )) { step ->
                            if (step == null) buildJsonObject { put("success", false) }
                            else buildJsonObject {
                                put("stepId", step.id)
                                put("order", step.order)
                                put("timestamp", step.timestamp)
                            }
                        }
                    }
                    "workingfiles/finishAgentSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val finalResult = args["finalResult"]?.jsonPrimitive?.content
                        val status = args["status"]?.jsonPrimitive?.content ?: "COMPLETED"
                        buildResult(facade.finishAgentSession(sessionId, finalResult, status)) { JsonPrimitive(it) }
                    }
                    "workingfiles/getAgentFlow" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getAgentFlow(sessionId)) { flow ->
                            if (flow == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("sessionId", flow.session.id)
                                put("agentName", flow.session.agentName)
                                put("taskDescription", flow.session.taskDescription)
                                put("mode", flow.session.mode.name)
                                put("status", flow.session.status.name)
                                put("stepCount", flow.steps.size)
                                put("totalFileChanges", flow.totalFileChanges)
                                put("hasErrors", flow.hasErrors)
                                put("errorCount", flow.errorCount)
                                put("durationMs", flow.totalDurationMs)
                                put("steps", flow.steps.joinToString("\n---\n") { step ->
                                    "#${step.order} [${step.type.displayName}] ${step.title}\n" +
                                    "  agent: ${step.agentName}\n" +
                                    "  time: ${step.timestamp}\n" +
                                    "  success: ${step.isSuccess}\n" +
                                    (if (step.affectedFiles.isNotEmpty()) "  files: ${step.affectedFiles.joinToString(",")}\n" else "") +
                                    (if (step.snapshotIds.isNotEmpty()) "  snapshots: ${step.snapshotIds.size}\n" else "") +
                                    (step.description.ifEmpty { "" })
                                })
                            }
                        }
                    }
                    "workingfiles/listAgentSessions" -> {
                        buildResult(facade.listAgentSessions()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("sessions", list.joinToString("\n") { s ->
                                    "${s.id}|${s.agentName}|${s.taskDescription.take(40)}|${s.status.name}|${s.startTime}|step=${s.stepCount}"
                                })
                            }
                        }
                    }
                    "workingfiles/listActiveAgentSessions" -> {
                        buildResult(facade.listActiveAgentSessions()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("sessions", list.joinToString("\n") { s -> "${s.id}|${s.agentName}|${s.taskDescription.take(40)}" })
                            }
                        }
                    }
                    "workingfiles/listAgentSteps" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listAgentSteps(sessionId)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("steps", list.joinToString("\n") { s ->
                                    "#${s.order} [${s.type.displayName}] ${s.title} (success=${s.isSuccess})"
                                })
                            }
                        }
                    }
                    "workingfiles/listAgentStepsForFile" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val filePath = args["filePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listAgentStepsForFile(sessionId, filePath)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("steps", list.joinToString("\n") { s -> "#${s.order} ${s.title}" })
                            }
                        }
                    }
                    "workingfiles/deleteAgentSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteAgentSession(sessionId)) { JsonPrimitive(it) }
                    }
                    "workingfiles/getSnapshotStats" -> {
                        val stats = facade.getSnapshotStats()
                        buildJsonObject {
                            put("success", true)
                            put("fileCount", stats.fileCount)
                            put("totalSnapshots", stats.totalSnapshots)
                            put("totalSizeMb", stats.totalSizeMb)
                        }.toString()
                    }
                    "workingfiles/bindFolderByUri" -> {
                        val id = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val name = args["displayName"]?.jsonPrimitive?.content ?: ""
                        val uri = args["uri"]?.jsonPrimitive?.content ?: ""
                        val mode = args["mode"]?.jsonPrimitive?.content ?: "ALL"
                        buildResult(facade.bindFolderByUri(id, name, uri, mode)) { JsonObject(emptyMap()) }
                    }
                    "workingfiles/listFilesByUri" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listFilesByUri(folderId, relPath)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") {
                                    (if (it.isDirectory) "[DIR] " else "[FILE] ") + it.relativePath + " (${it.size}B)"
                                })
                            }
                        }
                    }
                    "workingfiles/readFileByUri" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.readFileByUri(folderId, relPath)) { JsonPrimitive(it) }
                    }
                    "workingfiles/writeFileByUri" -> {
                        val folderId = args["folderId"]?.jsonPrimitive?.content ?: ""
                        val relPath = args["relativePath"]?.jsonPrimitive?.content ?: ""
                        val content = args["content"]?.jsonPrimitive?.content ?: ""
                        val append = args["append"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        buildResult(facade.writeFileByUri(folderId, relPath, content, append)) { JsonPrimitive(it) }
                    }
                    else -> errorResponse("unknown method: $method")
                }
            }
        }.getOrElse { t -> errorResponse(t.message ?: t.javaClass.simpleName) }
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        onProgress(50, "executing")
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = channelName
    override fun closeStream(channelName: String) {}

    private fun <T> buildResult(result: BridgeResult<T>, transform: (T) -> JsonObject): String = when (result) {
        is BridgeResult.Success -> buildJsonObject {
            put("success", true)
            put("data", transform(result.value))
        }.toString()
        is BridgeResult.Failure -> buildJsonObject {
            put("success", false)
            put("errorCode", result.error.code)
            put("errorMessage", result.error.message)
        }.toString()
    }

    private fun errorResponse(message: String): String = buildJsonObject {
        put("success", false)
        put("errorMessage", message)
    }.toString()

    /** 序列化文件树为缩进文本（VSCode explorer 风格）。 */
    private fun serializeFileTree(node: com.apex.lib.workingfiles.FileTreeNode, depth: Int): String {
        val indent = "  ".repeat(depth)
        val icon = if (node.isDirectory) "📁" else "📄"
        val sizeStr = if (!node.isDirectory && node.size > 0) " (${formatSize(node.size)})" else ""
        val sb = StringBuilder("$indent$icon ${node.name}$sizeStr\n")
        for (child in node.children) {
            sb.append(serializeFileTree(child, depth + 1))
        }
        return sb.toString()
    }

    /** 格式化文件大小。 */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }

    /** 序列化 diff 为 JSON。 */
    private fun serializeDiff(diff: com.apex.lib.workingfiles.diff.FileDiff): JsonObject = buildJsonObject {
        put("oldFilePath", diff.oldFilePath)
        put("newFilePath", diff.newFilePath)
        put("hasChanges", diff.hasChanges)
        put("addedLines", diff.summary.addedLines)
        put("removedLines", diff.summary.removedLines)
        put("modifiedLines", diff.summary.modifiedLines)
        put("netChange", diff.summary.netChange)
        put("shortStat", diff.summary.shortStat)
        put("hunkCount", diff.hunks.size)
        put("unifiedDiff", com.apex.lib.workingfiles.diff.DiffComputer.toUnifiedDiffText(diff))
        // 也输出结构化 hunks（便于 UI 渲染）
        val hunksStr = diff.hunks.joinToString("\n===\n") { hunk ->
            "${hunk.header}\n" + hunk.lines.joinToString("\n") { line ->
                "${line.type.prefix}${line.content}"
            }
        }
        put("hunksText", hunksStr)
    }
}
