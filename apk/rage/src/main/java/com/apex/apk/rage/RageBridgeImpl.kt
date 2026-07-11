package com.apex.apk.rage
import kotlinx.serialization.json.jsonPrimitive

import com.apex.agent.plugins.burst.base.IBurstSkill
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

/**
 * Rage Mode APK 的 Bridge 实现 — 路由全部 40+ 方法。
 */
class RageBridgeImpl(
    private val facade: RageServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.RAGE, "[RageBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    // ===== 初始化 =====
                    "rage/initialize" -> {
                        val preset = args["preset"]?.jsonPrimitive?.content?.let {
                            runCatching { RagePreset.valueOf(it) }.getOrDefault(RagePreset.BALANCED)
                        } ?: RagePreset.BALANCED
                        buildResult(facade.initialize(preset = preset)) { JsonObject(emptyMap()) }
                    }

                    // ===== 会话管理 =====
                    "rage/startSession" -> {
                        val task = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val skill = args["skillId"]?.jsonPrimitive?.content
                        val preset = args["preset"]?.jsonPrimitive?.content?.let {
                            runCatching { RagePreset.valueOf(it) }.getOrDefault(RagePreset.BALANCED)
                        } ?: RagePreset.BALANCED
                        buildResult(facade.startSession(task, skill, preset)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/executeTask" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.executeTask(sessionId)) { it.toJson() }
                    }
                    "rage/pauseSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.pauseSession(sessionId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/resumeSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.resumeSession(sessionId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/stopSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.stopSession(sessionId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/listSessions" -> {
                        val list = facade.listSessions()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("sessions", list.joinToString("\n") {
                                "${it.sessionId}: ${it.taskName} (paused=${it.paused}, completed=${it.completed})"
                            })
                        }.toString()
                    }

                    // ===== 4 种执行模式（P0 增强） =====
                    "rage/executeBatch" -> {
                        val tasks = args["tasks"]?.jsonPrimitive?.content?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
                        val skillId = args["skillId"]?.jsonPrimitive?.content
                        val preset = args["preset"]?.jsonPrimitive?.content?.let {
                            runCatching { RagePreset.valueOf(it) }.getOrDefault(RagePreset.BALANCED)
                        } ?: RagePreset.BALANCED
                        buildResult(facade.executeBatch(tasks, skillId, preset)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("successCount", list.count { it.success })
                                put("results", list.joinToString("\n") {
                                    "${it.sessionId}: success=${it.success} (${it.executionTimeMs}ms)"
                                })
                            }
                        }
                    }
                    "rage/executeWithDependencyGraph" -> {
                        // tasks 格式："taskId1|desc1\ntaskId2|desc2"
                        // dependencies 格式："taskId1|taskId2;taskId2|taskId3"
                        val tasksStr = args["tasks"]?.jsonPrimitive?.content ?: ""
                        val depsStr = args["dependencies"]?.jsonPrimitive?.content ?: ""
                        val strategy = args["strategy"]?.jsonPrimitive?.content ?: "SKIP_ON_FAILURE"
                        val skillId = args["skillId"]?.jsonPrimitive?.content
                        val tasks = tasksStr.split("\n").filter { it.isNotBlank() }.map { line ->
                            val parts = line.split("|", limit = 2)
                            Pair(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
                        }
                        val deps = depsStr.split(";").filter { it.isNotBlank() }.map { entry ->
                            val parts = entry.split("|", limit = 2)
                            Pair(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
                        }
                        buildResult(facade.executeWithDependencyGraph(tasks, deps, strategy, skillId)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("successCount", list.count { it.success })
                                put("skippedCount", list.count { it.skipped })
                                put("results", list.joinToString("\n") {
                                    "${it.taskId}: success=${it.success} skipped=${it.skipped}"
                                })
                            }
                        }
                    }
                    "rage/executeWithChain" -> {
                        val initialTask = args["initialTask"]?.jsonPrimitive?.content ?: ""
                        val stepsStr = args["steps"]?.jsonPrimitive?.content ?: ""
                        val skillId = args["skillId"]?.jsonPrimitive?.content
                        val steps = stepsStr.split("\n").filter { it.isNotBlank() }.map { line ->
                            val parts = line.split("|", limit = 2)
                            ChainStepDto(
                                name = parts.getOrNull(0) ?: "step",
                                description = parts.getOrNull(1) ?: "",
                                skillId = skillId
                            )
                        }
                        buildResult(facade.executeWithChain(initialTask, steps, skillId)) { it.toJson() }
                    }
                    "rage/executeAsync" -> {
                        val taskDesc = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val skillId = args["skillId"]?.jsonPrimitive?.content
                        buildResult(facade.executeAsync(taskDesc, skillId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/awaitAsyncTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60_000L
                        buildResult(facade.awaitAsyncTask(taskId, timeoutMs)) { r ->
                            if (r == null) buildJsonObject { put("found", false) }
                            else r.toJson()
                        }
                    }
                    "rage/cancelAsyncTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val ok = facade.cancelAsyncTask(taskId)
                        buildJsonObject { put("success", ok) }.toString()
                    }

                    // ===== 任务队列（P1 增强） =====
                    "rage/enqueueTask" -> {
                        val desc = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val priority = args["priority"]?.jsonPrimitive?.content ?: "NORMAL"
                        val skillId = args["skillId"]?.jsonPrimitive?.content
                        buildResult(facade.enqueueTask(desc, priority, skillId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/cancelQueuedTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.cancelQueuedTask(taskId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/peekQueue" -> {
                        buildResult(facade.peekQueue()) { JsonPrimitive(it ?: "") }
                    }
                    "rage/pendingTaskCount" -> {
                        buildResult(facade.pendingTaskCount()) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/clearQueue" -> {
                        buildResult(facade.clearQueue()) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/getQueueSnapshot" -> {
                        buildResult(facade.getQueueSnapshot()) { s ->
                            if (s == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("pendingCount", s.pendingCount)
                                put("completedCount", s.completedCount)
                                put("failedCount", s.failedCount)
                                put("cancelledCount", s.cancelledCount)
                            }
                        }
                    }

                    // ===== 断点续传（P0 增强） =====
                    "rage/saveCheckpoint" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val completedSteps = args["completedSteps"]?.jsonPrimitive?.content?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        val totalSteps = args["totalSteps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val intermediateResult = args["intermediateResult"]?.jsonPrimitive?.content
                        buildResult(facade.saveCheckpoint(taskId, completedSteps, totalSteps, intermediateResult)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/loadCheckpoint" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.loadCheckpoint(taskId)) { c ->
                            if (c == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("taskId", c.taskId)
                                put("completedSteps", c.completedSteps.size)
                                put("totalSteps", c.totalSteps)
                                put("progress", c.progress)
                                put("isComplete", c.isComplete)
                            }
                        }
                    }
                    "rage/resumeFromCheckpoint" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.resumeFromCheckpoint(taskId)) { it.toJson() }
                    }
                    "rage/listCheckpoints" -> {
                        buildResult(facade.listCheckpoints()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("checkpoints", list.joinToString("\n") {
                                    "${it.taskId}: ${it.completedSteps}/${it.totalSteps}"
                                })
                            }
                        }
                    }
                    "rage/listIncompleteTasks" -> {
                        buildResult(facade.listIncompleteTasks()) { list ->
                            buildJsonObject { put("count", list.size) }
                        }
                    }
                    "rage/canResume" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.canResume(taskId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/getResumePoint" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getResumePoint(taskId)) { JsonPrimitive(it ?: "") }
                    }
                    "rage/deleteCheckpoint" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteCheckpoint(taskId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/clearCheckpoints" -> {
                        buildResult(facade.clearCheckpoints()) { JsonObject(emptyMap()) }
                    }

                    // ===== 技能管理（P2 增强 — 多维查询） =====
                    "rage/listSkills" -> {
                        buildResult(facade.listSkills()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("skills", list.joinToString("\n") {
                                    "${it.skillId}: ${it.skillName} - ${it.description.take(60)}"
                                })
                            }
                        }
                    }
                    "rage/getSkillsByTag" -> {
                        val tag = args["tag"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getSkillsByTag(tag)) { list ->
                            buildJsonObject { put("count", list.size) }
                        }
                    }
                    "rage/getSkillsByCapability" -> {
                        val cap = args["capability"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getSkillsByCapability(cap)) { list ->
                            buildJsonObject { put("count", list.size) }
                        }
                    }
                    "rage/loadSkill" -> {
                        // 注意：IBurstSkill 实例化需要业务侧注入，这里返回提示
                        buildJsonObject {
                            put("success", false)
                            put("errorMessage", "loadSkill 需要通过 TypedServiceRegistry 直接调用 Facade，无法通过 Bridge 传递 IBurstSkill 实例")
                        }.toString()
                    }
                    "rage/unloadSkill" -> {
                        val skillId = args["skillId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.unloadSkill(skillId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/getSkillCount" -> {
                        buildResult(facade.getSkillCount()) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/isSkillLoaded" -> {
                        val skillId = args["skillId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.isSkillLoaded(skillId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }

                    // ===== 预设与配置（P2 增强） =====
                    "rage/switchPreset" -> {
                        val presetName = args["preset"]?.jsonPrimitive?.content ?: "BALANCED"
                        val preset = runCatching { RagePreset.valueOf(presetName) }.getOrDefault(RagePreset.BALANCED)
                        buildResult(facade.switchPreset(preset)) { JsonObject(emptyMap()) }
                    }
                    "rage/updateConfig" -> {
                        val maxConcurrency = args["maxConcurrency"]?.jsonPrimitive?.content?.toIntOrNull()
                        val defaultTimeoutMs = args["defaultTimeoutMs"]?.jsonPrimitive?.content?.toLongOrNull()
                        val enableAdaptive = args["enableAdaptiveOptimization"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val enableMetrics = args["enableMetricsCollection"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val memoryBudgetMb = args["memoryBudgetMb"]?.jsonPrimitive?.content?.toIntOrNull()
                        buildResult(facade.updateConfig(maxConcurrency, defaultTimeoutMs, enableAdaptive, enableMetrics, memoryBudgetMb)) { JsonObject(emptyMap()) }
                    }
                    "rage/getCurrentConfig" -> {
                        buildResult(facade.getCurrentConfig()) { c ->
                            buildJsonObject {
                                put("maxConcurrency", c.maxConcurrency)
                                put("defaultTimeoutMs", c.defaultTimeoutMs)
                                put("enableAdaptiveOptimization", c.enableAdaptiveOptimization)
                                put("enableMetricsCollection", c.enableMetricsCollection)
                                put("memoryBudgetMb", c.memoryBudgetMb)
                                put("preset", c.preset)
                            }
                        }
                    }

                    // ===== 指标与状态（P1 增强） =====
                    "rage/getMetrics" -> {
                        val m = facade.getMetrics()
                        if (m != null) {
                            buildJsonObject {
                                put("success", true)
                                put("totalTasks", m.totalTasks)
                                put("successfulTasks", m.successfulTasks)
                                put("failedTasks", m.failedTasks)
                                put("cancelledTasks", m.cancelledTasks)
                                put("averageExecutionTimeMs", m.averageExecutionTimeMs)
                                put("successRate", m.successRate)
                                put("currentConcurrency", m.currentConcurrency)
                                put("peakConcurrency", m.peakConcurrency)
                                put("totalTokensProcessed", m.totalTokensProcessed)
                                put("totalMemoryUsedMb", m.totalMemoryUsedMb)
                            }.toString()
                        } else {
                            buildJsonObject { put("success", false); put("errorMessage", "BurstMode not initialized") }.toString()
                        }
                    }
                    "rage/observeNextMetrics" -> {
                        buildResult(facade.observeNextMetrics()) { m ->
                            if (m == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("totalTasks", m.totalTasks)
                                put("successRate", m.successRate)
                                put("currentConcurrency", m.currentConcurrency)
                            }
                        }
                    }
                    "rage/resetMetrics" -> {
                        buildResult(facade.resetMetrics()) { JsonObject(emptyMap()) }
                    }
                    "rage/getKernelState" -> {
                        buildJsonObject { put("success", true); put("state", facade.getKernelState()) }.toString()
                    }
                    "rage/getHealthStatus" -> {
                        buildResult(facade.getHealthStatus()) { h ->
                            buildJsonObject {
                                put("healthy", h.healthy)
                                put("usedMemoryMb", h.usedMemoryMb)
                                put("currentConcurrency", h.currentConcurrency)
                                put("maxConcurrency", h.maxConcurrency)
                                put("shouldDegrade", h.shouldDegrade)
                            }
                        }
                    }

                    // ===== 基础设施（P2 增强） =====
                    "rage/clearResultCache" -> {
                        val prefix = args["prefix"]?.jsonPrimitive?.content
                        buildResult(facade.clearResultCache(prefix)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/getResultCacheStats" -> {
                        buildResult(facade.getResultCacheStats()) { s ->
                            buildJsonObject {
                                put("size", s.size)
                                put("hitCount", s.hitCount)
                                put("missCount", s.missCount)
                                put("hitRate", s.hitRate)
                            }
                        }
                    }
                    "rage/setSkillSelectionStrategy" -> {
                        val strategy = args["strategy"]?.jsonPrimitive?.content ?: "priority"
                        buildResult(facade.setSkillSelectionStrategy(strategy)) { JsonObject(emptyMap()) }
                    }

                    // ===== AR/VR 可视化 =====
                    "rage/enableSpatialVisualization" -> {
                        buildResult(facade.enableSpatialVisualization()) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }

                    // ===== 关闭 =====
                    "rage/shutdown" -> {
                        buildResult(facade.shutdown()) { JsonObject(emptyMap()) }
                    }

                    // ===== 4 Agent 架构师 =====
                    "rage/architect/execute" -> {
                        val task = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val preset = args["preset"]?.jsonPrimitive?.content ?: "BALANCED"
                        buildResult(facade.executeArchitectTask(task, preset)) { r ->
                            buildJsonObject {
                                put("taskId", r.taskId)
                                put("success", r.success)
                                put("stepCount", r.steps.size)
                                put("durationMs", r.durationMs)
                                put("retryCount", r.retryCount)
                                put("agentInvocations", r.agentInvocations)
                                put("dynamicAgentCount", r.dynamicAgentCount)
                                if (r.errorMessage != null) put("errorMessage", r.errorMessage)
                                put("steps", r.steps.joinToString("\n---\n") { s ->
                                    "${s.agentName}|${s.action}|${s.success}|${s.durationMs}ms\n${s.thought}\n${s.output.take(200)}"
                                })
                            }
                        }
                    }
                    "rage/architect/coreAgents" -> {
                        val agents = facade.getCoreAgents()
                        buildJsonObject {
                            put("success", true)
                            put("count", agents.size)
                            put("agents", agents.values.joinToString("\n") { "${it.id}|${it.displayName}|${it.roleDisplay}|enabled=${it.enabled}" })
                        }.toString()
                    }
                    "rage/architect/toggleAgent" -> {
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val enabled = facade.toggleCoreAgent(agentId)
                        buildJsonObject { put("success", true); put("enabled", enabled) }.toString()
                    }
                    "rage/architect/setStrategy" -> {
                        val autoExpand = args["autoExpand"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val gitBranching = args["gitBranching"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val sandboxExec = args["sandboxExec"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val githubSearch = args["githubSearch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        val codeRag = args["codeRag"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        facade.setExpandStrategy(autoExpand, gitBranching, sandboxExec, githubSearch, codeRag)
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "rage/architect/getStrategy" -> {
                        val s = facade.getExpandStrategy()
                        buildJsonObject {
                            put("success", true)
                            put("autoExpand", s.autoExpand)
                            put("gitBranching", s.gitBranching)
                            put("sandboxExec", s.sandboxExec)
                            put("githubSearch", s.githubSearch)
                            put("codeRag", s.codeRag)
                            put("maxRetries", s.maxRetries)
                        }.toString()
                    }
                    "rage/architect/dynamicAgents" -> {
                        val list = facade.getDynamicAgents()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("agents", list.joinToString("\n") { "${it.name}|${it.systemPrompt}|${it.status}" })
                        }.toString()
                    }
                    "rage/architect/blackboard" -> {
                        val bb = facade.getArchitectBlackboard()
                        buildJsonObject {
                            put("success", true)
                            put("count", bb.size)
                            bb.forEach { (k, v) -> put(k, v) }
                        }.toString()
                    }
                    "rage/architect/history" -> {
                        val list = facade.getTaskHistory()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("tasks", list.joinToString("\n") {
                                "${it.taskId}|${it.description.take(30)}|${it.success}|${it.stepCount}步|${it.durationMs}ms"
                            })
                        }.toString()
                    }
                    "rage/architect/taskDetail" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val detail = facade.getTaskDetail(taskId)
                        if (detail != null) {
                            buildJsonObject {
                                put("success", true)
                                put("found", true)
                                put("taskId", detail.taskId)
                                put("taskSuccess", detail.success)
                                put("stepCount", detail.steps.size)
                                put("durationMs", detail.durationMs)
                                put("retryCount", detail.retryCount)
                                put("steps", detail.steps.joinToString("\n---\n") { s ->
                                    "${s.agentName}|${s.action}|${s.success}|${s.durationMs}ms\n${s.thought}\n${s.output.take(300)}"
                                })
                            }.toString()
                        } else {
                            buildJsonObject { put("success", true); put("found", false) }.toString()
                        }
                    }
                    "rage/architect/deleteTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteTask(taskId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/architect/clearHistory" -> {
                        buildResult(facade.clearTaskHistory()) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "rage/architect/spawnAgent" -> {
                        val name = args["name"]?.jsonPrimitive?.content ?: ""
                        val prompt = args["systemPrompt"]?.jsonPrimitive?.content ?: ""
                        val tools = args["tools"]?.jsonPrimitive?.content?.split(",") ?: emptyList()
                        val agent = facade.spawnAgent(name, prompt, tools)
                        buildJsonObject { put("success", true); put("agentId", agent.id); put("name", agent.name) }.toString()
                    }
                    "rage/architect/terminateAgent" -> {
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.terminateAgent(agentId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }

                    // ===== lib:rage 引擎新能力（任务/技能/架构师/配置/预设） =====

                    // --- 技能查询（lib:rage 31 内置技能目录） ---
                    "rage/findSkill" -> {
                        val idOrName = args["skillId"]?.jsonPrimitive?.content
                            ?: args["name"]?.jsonPrimitive?.content ?: ""
                        val skill = facade.findRageSkill(idOrName)
                        if (skill != null) {
                            buildJsonObject {
                                put("success", true)
                                put("found", true)
                                put("skillId", skill.id)
                                put("name", skill.name)
                                put("description", skill.description)
                                put("category", skill.category.name)
                                put("priority", skill.priority)
                                put("tags", skill.tags.joinToString(","))
                                put("parameters", skill.parameters.entries.joinToString(";") { "${it.key}=${it.value}" })
                            }.toString()
                        } else {
                            buildJsonObject { put("success", true); put("found", false) }.toString()
                        }
                    }
                    "rage/findSkillsByCategory" -> {
                        val catStr = args["category"]?.jsonPrimitive?.content ?: ""
                        val category = runCatching {
                            com.apex.lib.rage.RageSkillCategory.valueOf(catStr.uppercase())
                        }.getOrNull()
                        if (category == null) {
                            buildJsonObject {
                                put("success", false)
                                put("errorMessage", "unknown category: $catStr (valid: ${com.apex.lib.rage.RageSkillCategory.values().joinToString(",") { it.name }})")
                            }.toString()
                        } else {
                            val list = facade.findRageSkillsByCategory(category)
                            buildJsonObject {
                                put("success", true)
                                put("category", category.name)
                                put("count", list.size)
                                put("skills", list.joinToString("\n") {
                                    "${it.id}: ${it.name} - ${it.description.take(60)} (priority=${it.priority})"
                                })
                            }.toString()
                        }
                    }
                    "rage/listCategories" -> {
                        val cats = facade.listRageSkillCategories()
                        buildJsonObject {
                            put("success", true)
                            put("count", cats.size)
                            put("categories", cats.joinToString("\n") { c ->
                                "${c.name}: ${facade.findRageSkillsByCategory(c).size} skills"
                            })
                        }.toString()
                    }

                    // --- 引擎内存任务（RageTask） ---
                    "rage/listTasks" -> {
                        val statusStr = args["status"]?.jsonPrimitive?.content
                        val status = statusStr?.let {
                            runCatching { com.apex.lib.rage.RageTaskStatus.valueOf(it.uppercase()) }.getOrNull()
                        }
                        val list = facade.listRageTasks(status)
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            if (status != null) put("filter", status.name)
                            put("tasks", list.joinToString("\n") { t ->
                                "${t.id}|${t.status.name}|${t.preset}|${t.description.take(40)}|prog=${t.progress}|dur=${t.durationMs}ms"
                            })
                        }.toString()
                    }
                    "rage/getTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val task = facade.getRageTask(taskId)
                        if (task != null) {
                            buildJsonObject {
                                put("success", true)
                                put("found", true)
                                put("taskId", task.id)
                                put("description", task.description)
                                put("preset", task.preset)
                                put("status", task.status.name)
                                put("progress", task.progress)
                                put("createdAt", task.createdAt)
                                put("startedAt", task.startedAt ?: 0L)
                                put("completedAt", task.completedAt ?: 0L)
                                put("agentInvocations", task.agentInvocations)
                                put("retryCount", task.retryCount)
                                put("durationMs", task.durationMs)
                                if (task.result != null) put("result", task.result)
                                if (task.errorMessage != null) put("errorMessage", task.errorMessage)
                            }.toString()
                        } else {
                            buildJsonObject { put("success", true); put("found", false) }.toString()
                        }
                    }

                    // --- 架构师 Agent 管理（flat 路由，与 rage/architect/* 等价） ---
                    "rage/toggleAgent" -> {
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: ""
                        val enabled = facade.toggleCoreAgent(agentId)
                        buildJsonObject { put("success", true); put("enabled", enabled) }.toString()
                    }
                    "rage/spawnAgent" -> {
                        val displayName = args["displayName"]?.jsonPrimitive?.content ?: ""
                        val role = args["role"]?.jsonPrimitive?.content ?: ""
                        val capabilities = args["capabilities"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                            ?: emptyList()
                        val name = if (displayName.isNotBlank()) displayName else role
                        val systemPrompt = if (role.isNotBlank()) role else displayName
                        val agent = facade.spawnAgent(name, systemPrompt, capabilities)
                        buildJsonObject {
                            put("success", true)
                            put("agentId", agent.id)
                            put("name", agent.name)
                            put("systemPrompt", agent.systemPrompt)
                            put("tools", agent.tools.joinToString(","))
                            put("status", agent.status)
                        }.toString()
                    }
                    "rage/getCoreAgents" -> {
                        val agents = facade.getCoreAgents()
                        buildJsonObject {
                            put("success", true)
                            put("count", agents.size)
                            put("agents", agents.values.joinToString("\n") { "${it.id}|${it.displayName}|${it.roleDisplay}|enabled=${it.enabled}" })
                        }.toString()
                    }
                    "rage/getArchitectState" -> {
                        val s = facade.getRageArchitectState()
                        buildJsonObject {
                            put("success", true)
                            put("coreAgentCount", s.coreAgentCount)
                            put("activeCoreAgentCount", s.activeCoreAgentCount)
                            put("dynamicAgentCount", s.dynamicAgentCount)
                            put("blackboardKeys", s.blackboardKeys)
                            put("executionHistoryCount", s.executionHistoryCount)
                            put("currentConcurrency", s.currentConcurrency)
                            put("peakConcurrency", s.peakConcurrency)
                            put("maxRetries", s.maxRetries)
                            put("autoExpand", s.autoExpand)
                        }.toString()
                    }

                    // --- 配置与预设（lib:rage RageModeConfig / RagePresets） ---
                    "rage/applyConfig" -> {
                        val maxConcurrency = args["maxConcurrency"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4
                        val defaultTimeoutMs = args["defaultTimeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60_000L
                        val maxRetries = args["maxRetries"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3
                        val enableAutoExpand = args["enableAutoExpand"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        val enableGitBranching = args["enableGitBranching"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        val enableSandboxExec = args["enableSandboxExec"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        val enableGithubSearch = args["enableGithubSearch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val enableCodeRag = args["enableCodeRag"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        val config = com.apex.lib.rage.RageModeConfig(
                            maxConcurrency = maxConcurrency,
                            defaultTimeoutMs = defaultTimeoutMs,
                            maxRetries = maxRetries,
                            enableAutoExpand = enableAutoExpand,
                            enableGitBranching = enableGitBranching,
                            enableSandboxExec = enableSandboxExec,
                            enableGithubSearch = enableGithubSearch,
                            enableCodeRag = enableCodeRag
                        )
                        facade.applyRageConfig(config)
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "rage/listPresets" -> {
                        val presets = facade.listRagePresets()
                        buildJsonObject {
                            put("success", true)
                            put("count", presets.size)
                            put("presets", presets.joinToString("\n") { p ->
                                val cfg = facade.getRagePreset(p.name)
                                "${p.name}|conc=${cfg.maxConcurrency}|timeout=${cfg.defaultTimeoutMs}ms|retries=${cfg.maxRetries}|autoExpand=${cfg.enableAutoExpand}|sandbox=${cfg.enableSandboxExec}"
                            })
                        }.toString()
                    }
                    "rage/getPreset" -> {
                        val name = args["name"]?.jsonPrimitive?.content ?: "BALANCED"
                        val cfg = facade.getRagePreset(name)
                        buildJsonObject {
                            put("success", true)
                            put("name", name.uppercase())
                            put("maxConcurrency", cfg.maxConcurrency)
                            put("defaultTimeoutMs", cfg.defaultTimeoutMs)
                            put("maxRetries", cfg.maxRetries)
                            put("enableAutoExpand", cfg.enableAutoExpand)
                            put("enableGitBranching", cfg.enableGitBranching)
                            put("enableSandboxExec", cfg.enableSandboxExec)
                            put("enableGithubSearch", cfg.enableGithubSearch)
                            put("enableCodeRag", cfg.enableCodeRag)
                        }.toString()
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

// JSON 扩展
private fun RageExecutionResult.toJson(): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    put("skillId", skillId)
    put("success", success)
    put("output", output)
    put("errorMessage", errorMessage ?: "")
    put("executionTimeMs", executionTimeMs)
    put("tokensProcessed", tokensProcessed)
}
