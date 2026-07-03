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
}
