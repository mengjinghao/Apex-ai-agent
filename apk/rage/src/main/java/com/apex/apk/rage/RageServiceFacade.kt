package com.apex.apk.rage

import android.app.Application
import android.content.Context
import com.apex.agent.burstmode.api.BurstMode
import com.apex.agent.burstmode.preset.BurstPreset
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.BurstInput
import com.apex.agent.kernel.burst.LLMProvider
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.plugins.burst.base.BurstSkillResult
import com.apex.agent.plugins.burst.base.IBurstSkill
import com.apex.agent.plugins.burst.base.LLMConfig
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rage Mode APK 的核心服务实现。
 *
 * **职责**：
 *   - 启动并管理 [BurstKernel] 微内核
 *   - 通过 [BurstMode] 业务门面暴露能力
 *   - 加载 31 个内置 [IBurstSkill]
 *   - 对其他 APK 暴露统一的 Kotlin API
 *
 * **能力清单**：
 *   1. 启动 / 停止 / 暂停 / 恢复 狂暴模式会话
 *   2. 执行任务（自动选择最佳技能 + 多策略推理）
 *   3. 列出 / 加载 / 卸载技能
 *   4. 切换预设（性能模式 / 平衡模式 / 极限模式）
 *   5. 任务调度（优先级队列 + 并发控制）
 *   6. 断点续传（CheckpointManager）
 *   7. 监控指标（BurstMetrics）
 *
 * **使用方式**（其他 APK）：
 *   ```kotlin
 *   val rage = TypedServiceRegistry.get<RageServiceFacade>() ?: error("rage not available")
 *   val result = rage.executeTask("分析这份代码并优化", "reasoning.react")
 *   ```
 */
class RageServiceFacade(private val context: Context) {

    private const val TAG_SUB = "RageFacade"

    private var burstMode: BurstMode? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** 当前活跃会话：sessionId → RageSession。 */
    private val sessions = mutableMapOf<String, RageSession>()

    /**
     * 初始化狂暴模式（懒加载，首次执行任务时触发）。
     *
     * @param llmConfig LLM 配置（可选，未提供则用默认本地配置）
     * @param preset 预设（PERFORMANCE / BALANCED / EXTREME）
     */
    suspend fun initialize(
        llmConfig: LLMConfig? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<Unit> = bridgeRun {
        if (_isInitialized.value) return@bridgeRun

        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] initializing BurstKernel...")
        BurstKernel.start(
            app = context as Application,
            llmConfig = llmConfig ?: defaultLlmConfig(),
            provider = LLMProvider.LOCAL_LLAMA,
            collaborationFramework = null
        )

        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] creating BurstMode with preset=$preset")
        burstMode = BurstMode.create(context)
            .withPreset(preset.toBurstPreset())
            .enableAutoStart(true)
            .enableMetrics(true)
            .enableHealthCheck(true)
            .initialize()

        _isInitialized.value = true
        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] initialized; available skills: ${burstMode?.skillManager?.count() ?: 0}")
    }

    /**
     * 启动一个狂暴模式会话。
     * @param taskDescription 任务描述
     * @param preferredSkillId 优先使用的技能 ID（可选）
     * @return sessionId
     */
    suspend fun startSession(
        taskDescription: String,
        preferredSkillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized(preset)

        val sessionId = com.apex.sdk.common.Trace.newId("rage")
        val task = BurstTask(
            id = sessionId,
            name = taskDescription.take(80),
            description = taskDescription,
            input = BurstInput(text = taskDescription),
            skillId = preferredSkillId
        )

        val session = RageSession(
            sessionId = sessionId,
            task = task,
            preset = preset,
            createdAt = System.currentTimeMillis()
        )
        sessions[sessionId] = session

        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] session started: $sessionId (skill=${preferredSkillId ?: "auto"})")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_STARTED,
            mapOf("sessionId" to sessionId, "skillId" to (preferredSkillId ?: "auto")),
            ApexSuite.ApkId.RAGE
        )

        sessionId
    }

    /**
     * 异步执行一个任务（在指定会话中）。
     * @return 执行结果
     */
    suspend fun executeTask(
        sessionId: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): BridgeResult<RageExecutionResult> = bridgeRun {
        val session = sessions[sessionId] ?: throw IllegalStateException("session not found: $sessionId")
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")

        onProgress?.invoke(0.1f, "selecting skill")
        val skill = session.task.skillId?.let { mode.skillManager.get(it) }
            ?: mode.skillSelector.selectSkill(session.task)
        val skillId = skill?.manifest?.skillId ?: "reasoning.react"

        onProgress?.invoke(0.3f, "executing with skill: $skillId")
        val result = mode.execute(session.task.copy(skillId = skillId))

        onProgress?.invoke(1.0f, "done")
        session.completed = true
        session.result = result

        RageExecutionResult(
            sessionId = sessionId,
            skillId = skillId,
            success = result.success,
            output = result.output ?: "",
            errorMessage = result.errorMessage,
            executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
            tokensProcessed = result.metrics?.tokensProcessed ?: 0
        )
    }

    /**
     * 暂停会话。
     */
    suspend fun pauseSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.pause()
        sessions[sessionId]?.paused = true
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_PAUSED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    /**
     * 恢复会话。
     */
    suspend fun resumeSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.resume()
        sessions[sessionId]?.paused = false
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_RESUMED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    /**
     * 终止会话。
     */
    suspend fun stopSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        sessions.remove(sessionId)
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_STOPPED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    /**
     * 列出所有可用技能。
     */
    suspend fun listSkills(): BridgeResult<List<SkillDescriptor>> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.skillManager.getAllManifests().map { m ->
            SkillDescriptor(
                skillId = m.skillId,
                skillName = m.skillName,
                version = m.version,
                description = m.description,
                author = m.author,
                tags = m.tags,
                capabilities = m.capabilities,
                priority = m.priority
            )
        }
    }

    /**
     * 加载自定义技能。
     * 当前实现仅支持注册内存中的 IBurstSkill 实例（动态加载外部 APK 的能力待扩展）。
     */
    suspend fun loadSkill(skill: IBurstSkill): BridgeResult<String> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        val registered = mode.skillManager.register(skill)
        if (!registered) throw IllegalStateException("skill registration failed: ${skill.manifest.skillId}")
        skill.manifest.skillId
    }

    /**
     * 卸载技能。
     */
    suspend fun unloadSkill(skillId: String): BridgeResult<Boolean> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.skillManager.unregister(skillId)
    }

    /**
     * 切换预设。
     */
    suspend fun switchPreset(preset: RagePreset): BridgeResult<Unit> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.switchPreset(preset.toBurstPreset())
    }

    /**
     * 获取监控指标快照。
     */
    fun getMetrics(): RageMetricsSnapshot? {
        val mode = burstMode ?: return null
        val snapshot = mode.getMetrics()
        return RageMetricsSnapshot(
            totalTasks = snapshot.totalTasks,
            successfulTasks = snapshot.successfulTasks,
            failedTasks = snapshot.failedTasks,
            cancelledTasks = snapshot.cancelledTasks,
            averageExecutionTimeMs = snapshot.averageExecutionTimeMs,
            successRate = snapshot.successRate,
            currentConcurrency = snapshot.currentConcurrency,
            peakConcurrency = snapshot.peakConcurrency,
            totalTokensProcessed = snapshot.totalTokensProcessed,
            totalMemoryUsedMb = snapshot.totalMemoryUsedMb
        )
    }

    /**
     * 列出所有活跃会话。
     */
    fun listSessions(): List<SessionInfo> = sessions.map { (id, s) ->
        SessionInfo(
            sessionId = id,
            taskName = s.task.name,
            skillId = s.task.skillId ?: "auto",
            createdAt = s.createdAt,
            paused = s.paused,
            completed = s.completed
        )
    }

    /**
     * 列出所有可恢复的断点。
     */
    suspend fun listCheckpoints(): BridgeResult<List<CheckpointInfo>> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.checkpointManager.listAllCheckpoints().map { c ->
            CheckpointInfo(
                taskId = c.taskId,
                completedSteps = c.completedSteps.size,
                totalSteps = c.totalSteps,
                canResume = true
            )
        }
    }

    /**
     * 关闭狂暴模式。
     */
    suspend fun shutdown(): BridgeResult<Unit> = bridgeRun {
        burstMode?.shutdown()
        BurstKernel.stop()
        _isInitialized.value = false
        burstMode = null
        sessions.clear()
    }

    private suspend fun ensureInitialized(preset: RagePreset) {
        if (!_isInitialized.value) {
            initialize(preset = preset)
        }
    }

    private fun defaultLlmConfig(): LLMConfig = LLMConfig(
        nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
        nCtx = 8192,
        temperature = 0.7f,
        maxTokens = 4096
    )
}

/** Rage 会话。 */
private data class RageSession(
    val sessionId: String,
    val task: BurstTask,
    val preset: RagePreset,
    val createdAt: Long,
    var paused: Boolean = false,
    var completed: Boolean = false,
    var result: BurstSkillResult? = null
)

/** 狂暴模式预设（简化版）。
 * 映射到 BurstPreset 的具体取值。 */
enum class RagePreset {
    PERFORMANCE,    // 性能模式：最大并发，长超时
    BALANCED,       // 平衡模式：默认
    POWER_SAVER,    // 省电模式：低并发，短超时
    LOCAL_INFERENCE,// 本地推理：离线运行
    CLOUD_INFERENCE,// 云端推理：DeepSeek API
    STREAMING,      // 流式处理：超大文本
    TEST;           // 测试模式：无 LLM

    fun toBurstPreset(): BurstPreset = when (this) {
        PERFORMANCE -> BurstPreset.PERFORMANCE
        BALANCED -> BurstPreset.BALANCED
        POWER_SAVER -> BurstPreset.POWER_SAVER
        LOCAL_INFERENCE -> BurstPreset.LOCAL_INFERENCE
        CLOUD_INFERENCE -> BurstPreset.CLOUD_INFERENCE
        STREAMING -> BurstPreset.STREAMING
        TEST -> BurstPreset.TEST
    }
}

/** 执行结果。 */
data class RageExecutionResult(
    val sessionId: String,
    val skillId: String,
    val success: Boolean,
    val output: String,
    val errorMessage: String?,
    val executionTimeMs: Long,
    val tokensProcessed: Int
)

/** 技能描述。 */
data class SkillDescriptor(
    val skillId: String,
    val skillName: String,
    val version: String,
    val description: String,
    val author: String,
    val tags: List<String>,
    val capabilities: List<String>,
    val priority: Int
)

/** 监控指标。 */
data class RageMetricsSnapshot(
    val totalTasks: Long,
    val successfulTasks: Long,
    val failedTasks: Long,
    val cancelledTasks: Long,
    val averageExecutionTimeMs: Double,
    val successRate: Double,
    val currentConcurrency: Int,
    val peakConcurrency: Int,
    val totalTokensProcessed: Long,
    val totalMemoryUsedMb: Long
)

/** 会话信息。 */
data class SessionInfo(
    val sessionId: String,
    val taskName: String,
    val skillId: String,
    val createdAt: Long,
    val paused: Boolean,
    val completed: Boolean
)

/** 断点信息。 */
data class CheckpointInfo(
    val taskId: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val canResume: Boolean
)
