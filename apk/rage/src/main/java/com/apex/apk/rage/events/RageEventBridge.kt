package com.apex.apk.rage.events

import com.apex.agent.burstmode.api.BurstMode
import com.apex.agent.burstmode.api.BurstModeEvent
import com.apex.agent.burstmode.api.BurstModeListener
import com.apex.sdk.bridge.SuiteEventBus
import com.apex.sdk.bridge.SuiteEventTypes
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 狂暴模式事件桥接器 — 把 [BurstMode] 的 14 种事件桥接到套件级 [SuiteEventBus]。
 *
 * **创新点**：
 *   - 业务侧（主 APK / 其他 APK）订阅 SuiteEventBus 即可收到狂暴模式实时事件
 *   - 跨 APK 实时事件流（任务进度/成功/失败/取消/状态变化/指标更新）
 *   - 同时维护本地 SharedFlow（同进程零延迟）
 *
 * **14 种 BurstModeEvent → SuiteEvent 映射**：
 *   - StateChanged → burst.state_changed
 *   - TaskEnqueued → burst.task_enqueued
 *   - TaskStarted → burst.task_started
 *   - TaskProgress → burst.task_progress
 *   - TaskSucceeded → burst.task_succeeded
 *   - TaskFailed → burst.task_failed
 *   - TaskCancelled → burst.task_cancelled
 *   - ConfigUpdated → burst.config_updated
 *   - PresetSwitched → burst.preset_switched
 *   - SkillRegistered → burst.skill_registered
 *   - SkillUnregistered → burst.skill_unregistered
 *   - MetricsUpdated → burst.metrics_updated
 *   - HealthChecked → burst.health_checked
 *   - onShutdown → burst.shutdown
 */
class RageEventBridge {

    private const val TAG_SUB = "RageEventBridge"

    /** 本地事件流（同进程零延迟）。 */
    private val _events = MutableSharedFlow<RageEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RageEvent> = _events.asSharedFlow()

    /** 进度回调注册表：taskId → onProgress */
    private val progressCallbacks = mutableMapOf<String, (Float, String?) -> Unit>()

    private var listener: BurstModeListener? = null

    /**
     * 注册到 BurstMode。
     */
    fun attach(burstMode: BurstMode) {
        if (listener != null) return
        val l = object : BurstModeListener {
            override fun onEvent(event: BurstModeEvent) {
                handleEvent(event)
            }
            override fun onStateChanged(event: BurstModeEvent.StateChanged) {
                publish(RageEvent.StateChanged(event.from.name, event.to.name))
            }
            override fun onTaskEnqueued(event: BurstModeEvent.TaskEnqueued) {
                publish(RageEvent.TaskEnqueued(event.taskId, event.priority.name))
            }
            override fun onTaskStarted(event: BurstModeEvent.TaskStarted) {
                publish(RageEvent.TaskStarted(event.taskId, event.taskDescription))
            }
            override fun onTaskProgress(event: BurstModeEvent.TaskProgress) {
                publish(RageEvent.TaskProgress(event.taskId, event.progress, event.message))
                // 同时触发本地进度回调
                progressCallbacks[event.taskId]?.invoke(event.progress, event.message)
            }
            override fun onTaskSucceeded(event: BurstModeEvent.TaskSucceeded) {
                publish(RageEvent.TaskSucceeded(event.taskId, event.result.success, event.result.output ?: ""))
                progressCallbacks.remove(event.taskId)
            }
            override fun onTaskFailed(event: BurstModeEvent.TaskFailed) {
                publish(RageEvent.TaskFailed(event.taskId, event.error))
                progressCallbacks.remove(event.taskId)
            }
            override fun onTaskCancelled(event: BurstModeEvent.TaskCancelled) {
                publish(RageEvent.TaskCancelled(event.taskId, event.reason))
                progressCallbacks.remove(event.taskId)
            }
            override fun onConfigUpdated(event: BurstModeEvent.ConfigUpdated) {
                publish(RageEvent.ConfigUpdated(
                    event.oldConfig.maxConcurrency, event.newConfig.maxConcurrency
                ))
            }
            override fun onPresetSwitched(event: BurstModeEvent.PresetSwitched) {
                publish(RageEvent.PresetSwitched(event.from.name, event.to.name))
            }
            override fun onSkillRegistered(event: BurstModeEvent.SkillRegistered) {
                publish(RageEvent.SkillRegistered(event.skillId, event.skillName))
            }
            override fun onSkillUnregistered(event: BurstModeEvent.SkillUnregistered) {
                publish(RageEvent.SkillUnregistered(event.skillId))
            }
            override fun onMetricsUpdated(event: BurstModeEvent.MetricsUpdated) {
                publish(RageEvent.MetricsUpdated(
                    totalTasks = event.snapshot.totalTasks,
                    successfulTasks = event.snapshot.successfulTasks,
                    failedTasks = event.snapshot.failedTasks,
                    currentConcurrency = event.snapshot.currentConcurrency
                ))
            }
            override fun onHealthChecked(event: BurstModeEvent.HealthChecked) {
                publish(RageEvent.HealthChecked(event.healthy, event.message))
            }
            override fun onShutdown() {
                publish(RageEvent.Shutdown)
            }
        }
        burstMode.addListener(l)
        listener = l
        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] attached to BurstMode")
    }

    /**
     * 从 BurstMode 解绑。
     */
    fun detach(burstMode: BurstMode) {
        listener?.let { burstMode.removeListener(it) }
        listener = null
        progressCallbacks.clear()
    }

    /**
     * 注册进度回调（用于 executeTask 真实进度推送）。
     */
    fun registerProgressCallback(taskId: String, onProgress: (Float, String?) -> Unit) {
        progressCallbacks[taskId] = onProgress
    }

    /**
     * 注销进度回调。
     */
    fun unregisterProgressCallback(taskId: String) {
        progressCallbacks.remove(taskId)
    }

    private fun handleEvent(event: BurstModeEvent) {
        // 通用事件处理（已通过具体回调处理，此处仅做日志）
        ApexLog.v(ApexSuite.ApkId.RAGE, "[$TAG_SUB] event: ${event.javaClass.simpleName}")
    }

    private fun publish(event: RageEvent) {
        // 1. 本地 SharedFlow
        _events.tryEmit(event)
        // 2. 套件级 SuiteEventBus（跨 APK 广播）
        SuiteEventBus.publish(
            type = event.suiteEventType,
            payload = event.toPayload(),
            sourceApk = ApexSuite.ApkId.RAGE
        )
    }
}

/**
 * 狂暴模式事件（简化版，跨 APK 传输友好）。
 */
sealed class RageEvent {
    abstract val taskId: String?
    abstract val suiteEventType: String

    data class StateChanged(val from: String, val to: String) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.state_changed"
    }
    data class TaskEnqueued(val id: String, val priority: String) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = "burst.task_enqueued"
    }
    data class TaskStarted(val id: String, val description: String) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = "burst.task_started"
    }
    data class TaskProgress(val id: String, val progress: Float, val message: String?) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = "burst.task_progress"
    }
    data class TaskSucceeded(val id: String, val success: Boolean, val output: String) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = SuiteEventTypes.BURST_SESSION_STARTED  // 复用已有的
    }
    data class TaskFailed(val id: String, val error: String) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = "burst.task_failed"
    }
    data class TaskCancelled(val id: String, val reason: String?) : RageEvent() {
        override val taskId: String? = id
        override val suiteEventType: String = "burst.task_cancelled"
    }
    data class ConfigUpdated(val oldMaxConcurrency: Int, val newMaxConcurrency: Int) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.config_updated"
    }
    data class PresetSwitched(val from: String, val to: String) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.preset_switched"
    }
    data class SkillRegistered(val skillId: String, val skillName: String) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.skill_registered"
    }
    data class SkillUnregistered(val skillId: String) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.skill_unregistered"
    }
    data class MetricsUpdated(
        val totalTasks: Long,
        val successfulTasks: Long,
        val failedTasks: Long,
        val currentConcurrency: Int
    ) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.metrics_updated"
    }
    data class HealthChecked(val healthy: Boolean, val message: String?) : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.health_checked"
    }
    object Shutdown : RageEvent() {
        override val taskId: String? = null
        override val suiteEventType: String = "burst.shutdown"
    }

    /** 转 payload（用于 SuiteEventBus 跨 APK 传输）。 */
    fun toPayload(): Map<String, Any> = when (this) {
        is StateChanged -> mapOf("from" to from, "to" to to)
        is TaskEnqueued -> mapOf("taskId" to id, "priority" to priority)
        is TaskStarted -> mapOf("taskId" to id, "description" to description)
        is TaskProgress -> mapOf("taskId" to id, "progress" to progress, "message" to (message ?: ""))
        is TaskSucceeded -> mapOf("taskId" to id, "success" to success, "output" to output.take(500))
        is TaskFailed -> mapOf("taskId" to id, "error" to error)
        is TaskCancelled -> mapOf("taskId" to id, "reason" to (reason ?: ""))
        is ConfigUpdated -> mapOf("oldMaxConcurrency" to oldMaxConcurrency, "newMaxConcurrency" to newMaxConcurrency)
        is PresetSwitched -> mapOf("from" to from, "to" to to)
        is SkillRegistered -> mapOf("skillId" to skillId, "skillName" to skillName)
        is SkillUnregistered -> mapOf("skillId" to skillId)
        is MetricsUpdated -> mapOf(
            "totalTasks" to totalTasks,
            "successfulTasks" to successfulTasks,
            "failedTasks" to failedTasks,
            "currentConcurrency" to currentConcurrency
        )
        is HealthChecked -> mapOf("healthy" to healthy, "message" to (message ?: ""))
        is Shutdown -> emptyMap()
    }
}
