package com.apex.apk.multiagent

import com.apex.lib.multiagent.CollaborationMode
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

class MultiAgentBridgeImpl(
    private val facade: MultiAgentServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.MULTI_AGENT, "[MultiAgentBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "multiagent/registerAgent" -> {
                        val id = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val name = args["displayName"]?.jsonPrimitive?.content ?: ""
                        val role = args["role"]?.jsonPrimitive?.content ?: "WORKER"
                        buildResult(facade.registerAgent(id, name, role) { input, _ ->
                            com.apex.lib.multiagent.AgentOutput(
                                result = "[${name}] received: ${input.prompt.take(200)}",
                                confidence = 0.8f
                            )
                        }) { JsonObject(emptyMap()) }
                    }
                    "multiagent/runCollaboration" -> {
                        val modeStr = args["mode"]?.jsonPrimitive?.content ?: "PIPELINE"
                        val mode = runCatching { CollaborationMode.valueOf(modeStr) }.getOrDefault(CollaborationMode.PIPELINE)
                        val agentIds = args["agentIds"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() } ?: emptyList()
                        val prompt = args["initialPrompt"]?.jsonPrimitive?.content ?: ""
                        val maxRounds = args["maxRounds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60_000L
                        val moderatorId = args["moderatorId"]?.jsonPrimitive?.content
                        val consensusThreshold = args["consensusThreshold"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
                        val votingThreshold = args["votingThreshold"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f
                        val continueOnFailure = args["continueOnFailure"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        buildResult(facade.runCollaboration(
                            mode.name, agentIds, prompt, maxRounds, timeoutMs,
                            moderatorId, consensusThreshold, votingThreshold, continueOnFailure
                        )) { r ->
                            buildJsonObject {
                                put("sessionId", r.sessionId)
                                put("finalOutput", r.finalOutput)
                                put("agentInvocations", r.agentInvocations)
                                put("durationMs", r.durationMs)
                                put("rounds", r.rounds)
                                put("successRate", r.successRate)
                                put("agentResults", r.agentResults.entries.joinToString("; ") { "${it.key}=${it.value}" })
                            }
                        }
                    }
                    "multiagent/listAgents" -> {
                        buildResult(facade.listAgents()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("agents", list.joinToString("\n") { "${it.id} (${it.role}): ${it.displayName}" })
                            }
                        }
                    }
                    "multiagent/unregisterAgent" -> {
                        val id = args["agentId"]?.jsonPrimitive?.content ?: ""
                        facade.unregisterAgent(id)
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "multiagent/readBlackboard" -> {
                        val key = args["key"]?.jsonPrimitive?.content ?: ""
                        val value = facade.readBlackboard(key)
                        buildJsonObject {
                            put("success", true)
                            put("key", key)
                            put("value", value?.toString() ?: "")
                        }.toString()
                    }
                    "multiagent/writeBlackboard" -> {
                        val key = args["key"]?.jsonPrimitive?.content ?: ""
                        val value = args["value"]?.jsonPrimitive?.content ?: ""
                        facade.writeBlackboard(key, value)
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "multiagent/blackboardSnapshot" -> {
                        val snap = facade.blackboardSnapshot()
                        buildJsonObject {
                            put("success", true)
                            put("size", snap.size)
                            put("keys", snap.keys.joinToString(","))
                        }.toString()
                    }
                    "multiagent/listSessions" -> {
                        val list = facade.listSessions()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("sessions", list.joinToString("\n") { "${it.sessionId}: ${it.mode} (${it.agentCount} agents)" })
                        }.toString()
                    }
                    "multiagent/cancelSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val ok = facade.cancelSession(sessionId)
                        buildJsonObject { put("success", ok) }.toString()
                    }

                    // ===== 增强：协作推荐 + 模板 =====
                    "multiagent/recommendCollaboration" -> {
                        val task = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val rec = facade.recommendCollaboration(task)
                        buildJsonObject {
                            put("success", true)
                            put("mode", rec.mode.name)
                            put("reason", rec.reason)
                            put("templateIds", rec.templateIds.joinToString(","))
                        }.toString()
                    }
                    "multiagent/listTemplates" -> {
                        val list = facade.listTemplates()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("templates", list.joinToString("\n") {
                                "${it.id}|${it.displayName}|${it.role.name}|${it.capabilities.joinToString("/")}"
                            })
                        }.toString()
                    }

                    // ===== 增强：Agent 查询 =====
                    "multiagent/findAgentsByCapability" -> {
                        val cap = args["capability"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.findAgentsByCapability(cap)) { list ->
                            buildJsonObject { put("count", list.size); put("agents", list.joinToString(",") { it.id }) }
                        }
                    }
                    "multiagent/findAgentsByRole" -> {
                        val role = args["role"]?.jsonPrimitive?.content ?: "WORKER"
                        buildResult(facade.findAgentsByRole(role)) { list ->
                            buildJsonObject { put("count", list.size); put("agents", list.joinToString(",") { it.id }) }
                        }
                    }

                    // ===== 增强：消息传递 =====
                    "multiagent/sendMessage" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val from = args["fromAgentId"]?.jsonPrimitive?.content ?: ""
                        val to = args["toAgentId"]?.jsonPrimitive?.content ?: ""
                        val content = args["content"]?.jsonPrimitive?.content ?: ""
                        val type = args["type"]?.jsonPrimitive?.content ?: "DIRECT"
                        val ok = facade.sendMessage(sessionId, from, to, content, type)
                        buildJsonObject { put("success", ok) }.toString()
                    }
                    "multiagent/getSessionMessages" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val list = facade.getSessionMessages(sessionId)
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("messages", list.joinToString("\n") { "${it.fromAgentId}→${it.toAgentId}: ${it.content.take(60)}" })
                        }.toString()
                    }

                    // ===== 增强：Blackboard =====
                    "multiagent/blackboardKeys" -> {
                        buildJsonObject {
                            put("success", true)
                            put("keys", facade.blackboardKeys().joinToString(","))
                            put("size", facade.blackboardKeys().size)
                        }.toString()
                    }
                    "multiagent/clearBlackboard" -> {
                        facade.clearBlackboard()
                        buildJsonObject { put("success", true) }.toString()
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
