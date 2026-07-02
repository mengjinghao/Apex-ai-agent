package com.apex.agent.burstmode.api

import com.apex.agent.burstmode.monitor.BurstMetricsSnapshot
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.kernel.burst.KernelState
import com.apex.agent.plugins.burst.base.BurstSkillResult

/**
 * 狂暴模式事件。
 *
 * 业务侧通过 [BurstModeListener] 接收这些事件。
 * 所有事件都是密封类的子类，便于 exhaustive when 匹配。
 */
sealed class BurstModeEvent {

    /** 内核状态变化。 */
    data class StateChanged(val from: KernelState, val to: KernelState) : BurstModeEvent()

    /** 任务入队。 */
    data class TaskEnqueued(val taskId: String, val priority: TaskPriority) : BurstModeEvent()

    /** 任务开始执行。 */
    data class TaskStarted(val taskId: String, val taskDescription: String) : BurstModeEvent()

    /** 任务执行进度。 */
    data class TaskProgress(val taskId: String, val progress: Float, val message: String?) : BurstModeEvent()

    /** 任务执行完成（成功）。 */
    data class TaskSucceeded(val taskId: String, val result: BurstSkillResult) : BurstModeEvent()

    /** 任务执行失败。 */
    data class TaskFailed(val taskId: String, val error: String) : BurstModeEvent()

    /** 任务被取消。 */
    data class TaskCancelled(val taskId: String, val reason: String?) : BurstModeEvent()

    /** 配置更新。 */
    data class ConfigUpdated(val oldConfig: com.apex.agent.burstmode.config.BurstModeConfig, val newConfig: com.apex.agent.burstmode.config.BurstModeConfig) : BurstModeEvent()

    /** 预设切换。 */
    data class PresetSwitched(val from: com.apex.agent.burstmode.preset.BurstPreset, val to: com.apex.agent.burstmode.preset.BurstPreset) : BurstModeEvent()

    /** 技能注册。 */
    data class SkillRegistered(val skillId: String, val skillName: String) : BurstModeEvent()

    /** 技能注销。 */
    data class SkillUnregistered(val skillId: String) : BurstModeEvent()

    /** 指标更新。 */
    data class MetricsUpdated(val snapshot: BurstMetricsSnapshot) : BurstModeEvent()

    /** 健康检查完成。 */
    data class HealthChecked(val healthy: Boolean, val message: String?) : BurstModeEvent()

    /** 内核关闭。 */
    object Shutdown : BurstModeEvent()
}

/**
 * 事件监听器接口。
 *
 * 业务侧实现此接口，通过 [BurstMode.addListener] 注册。
 * 所有方法都有默认空实现，按需 override。
 */
interface BurstModeListener {

    /** 任何事件都会触发此方法。 */
    fun onEvent(event: BurstModeEvent) {}

    fun onStateChanged(event: BurstModeEvent.StateChanged) {}
    fun onTaskEnqueued(event: BurstModeEvent.TaskEnqueued) {}
    fun onTaskStarted(event: BurstModeEvent.TaskStarted) {}
    fun onTaskProgress(event: BurstModeEvent.TaskProgress) {}
    fun onTaskSucceeded(event: BurstModeEvent.TaskSucceeded) {}
    fun onTaskFailed(event: BurstModeEvent.TaskFailed) {}
    fun onTaskCancelled(event: BurstModeEvent.TaskCancelled) {}
    fun onConfigUpdated(event: BurstModeEvent.ConfigUpdated) {}
    fun onPresetSwitched(event: BurstModeEvent.PresetSwitched) {}
    fun onSkillRegistered(event: BurstModeEvent.SkillRegistered) {}
    fun onSkillUnregistered(event: BurstModeEvent.SkillUnregistered) {}
    fun onMetricsUpdated(event: BurstModeEvent.MetricsUpdated) {}
    fun onHealthChecked(event: BurstModeEvent.HealthChecked) {}
    fun onShutdown() {}
}

/**
 * 事件分发器。
 *
 * 管理 [BurstModeListener] 列表，分发 [BurstModeEvent]。
 * 线程安全，所有方法可在任意线程调用。
 */
internal class EventDispatcher {

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<BurstModeListener>()

    /**
     * 添加监听器。
     * @return true 添加成功，false 已存在
     */
    fun addListener(listener: BurstModeListener): Boolean {
        return listeners.addIfAbsent(listener) != null
    }

    /**
     * 移除监听器。
     */
    fun removeListener(listener: BurstModeListener): Boolean {
        return listeners.remove(listener)
    }

    /**
     * 分发事件。
     * 调用所有监听器的对应方法。异常不会中断其他监听器。
     */
    fun dispatch(event: BurstModeEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
                when (event) {
                    is BurstModeEvent.StateChanged -> listener.onStateChanged(event)
                    is BurstModeEvent.TaskEnqueued -> listener.onTaskEnqueued(event)
                    is BurstModeEvent.TaskStarted -> listener.onTaskStarted(event)
                    is BurstModeEvent.TaskProgress -> listener.onTaskProgress(event)
                    is BurstModeEvent.TaskSucceeded -> listener.onTaskSucceeded(event)
                    is BurstModeEvent.TaskFailed -> listener.onTaskFailed(event)
                    is BurstModeEvent.TaskCancelled -> listener.onTaskCancelled(event)
                    is BurstModeEvent.ConfigUpdated -> listener.onConfigUpdated(event)
                    is BurstModeEvent.PresetSwitched -> listener.onPresetSwitched(event)
                    is BurstModeEvent.SkillRegistered -> listener.onSkillRegistered(event)
                    is BurstModeEvent.SkillUnregistered -> listener.onSkillUnregistered(event)
                    is BurstModeEvent.MetricsUpdated -> listener.onMetricsUpdated(event)
                    is BurstModeEvent.HealthChecked -> listener.onHealthChecked(event)
                    is BurstModeEvent.Shutdown -> listener.onShutdown()
                }
            } catch (_: Exception) {
                // 单个监听器异常不影响其他
            }
        }
    }

    /**
     * 清空所有监听器。
     */
    fun clear() {
        listeners.clear()
    }

    /**
     * 当前监听器数量。
     */
    fun count(): Int = listeners.size
}
