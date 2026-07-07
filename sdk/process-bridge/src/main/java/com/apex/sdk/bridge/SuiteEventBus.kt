package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 套件级事件总线 — 任意 APK 发布事件，其他 APK 都能收到。
 *
 * **同进程时**：直接 JVM 内存 SharedFlow，零延迟广播
 * **跨进程时**：通过 AIDL 把事件转发到主 APK，再广播给所有 APK
 *
 * 典型事件：
 *   - 模型选择变更（任意 APK 切换模型，所有 APK 同步）
 *   - 工作文件夹切换
 *   - Agent 会话开始/结束
 *   - 技能安装/卸载
 */
object SuiteEventBus {

    private val _events = MutableSharedFlow<SuiteEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SuiteEvent> = _events.asSharedFlow()

    fun publish(event: SuiteEvent): Boolean {
        ApexLog.v(ApexSuite.ApkId.MAIN, "[EventBus] publish: ${event.type} from ${event.sourceApk}")
        return _events.tryEmit(event)
    }

    /** 简化发布：自动填充 sourceApk 和 timestamp。 */
    fun publish(type: String, payload: Map<String, Any> = emptyMap(), sourceApk: String = ApexSuite.ApkId.MAIN): Boolean {
        return publish(SuiteEvent(type, payload, sourceApk, System.currentTimeMillis()))
    }
}

/**
 * 套件级事件。
 *
 * @param type      事件类型，使用全名避免冲突，如 "model.selected"、"working_folder.changed"
 * @param payload   事件负载（必须是可序列化的：基本类型/String/List/Map）
 * @param sourceApk 发布事件的 APK ID
 * @param timestamp 发布时间戳
 */
data class SuiteEvent(
    val type: String,
    val payload: Map<String, Any>,
    val sourceApk: String,
    val timestamp: Long
)

/** 套件级事件类型常量。 */
object SuiteEventTypes {
    const val MODEL_SELECTED = "model.selected"
    const val MODEL_API_KEY_UPDATED = "model.api_key_updated"

    const val WORKING_FOLDER_BOUND = "working_folder.bound"
    const val WORKING_FOLDER_UNBOUND = "working_folder.unbound"
    const val WORKING_FOLDER_CHANGED = "working_folder.changed"

    const val AGENT_SESSION_STARTED = "agent.session_started"
    const val AGENT_SESSION_ENDED = "agent.session_ended"

    const val SKILL_INSTALLED = "skill.installed"
    const val SKILL_UNINSTALLED = "skill.uninstalled"
    const val SKILL_ENABLED = "skill.enabled"
    const val SKILL_DISABLED = "skill.disabled"

    const val BURST_SESSION_STARTED = "burst.session_started"
    const val BURST_SESSION_PAUSED = "burst.session_paused"
    const val BURST_SESSION_RESUMED = "burst.session_resumed"
    const val BURST_SESSION_STOPPED = "burst.session_stopped"

    const val WORKFLOW_STARTED = "workflow.started"
    const val WORKFLOW_COMPLETED = "workflow.completed"
    const val WORKFLOW_FAILED = "workflow.failed"

    const val TERMINAL_SESSION_CREATED = "terminal.session_created"
    const val TERMINAL_SESSION_DESTROYED = "terminal.session_destroyed"

    const val APK_LAUNCHED = "apk.launched"
    const val APK_CRASHED = "apk.crashed"
    const val APK_RECOVERED = "apk.recovered"
    const val APK_INSTALLED = "apk.installed"
    const val APK_UNINSTALLED = "apk.uninstalled"
    const val APK_UPDATED = "apk.updated"
    const val APK_REQUIRED_MISSING = "apk.required_missing"  // 必须组件缺失
}
