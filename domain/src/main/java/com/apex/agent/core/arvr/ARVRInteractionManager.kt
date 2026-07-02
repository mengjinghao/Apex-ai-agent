package com.apex.agent.core.arvr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AR/VR 交互管理器。
 *
 * 该类抽象了 ARCore / VR 引擎（OpenXR、ARVRActivity）等不同后端的交互入口。
 * 上层（如 Agent UI、工具调用、技能执行）通过统一接口与 AR/VR 子系统交互，
 * 不必关心底层是 ARCore、OpenXR 还是 mock 实现。
 *
 * 设计要点：
 * - 后端可插拔：通过 [Backend] 接口注入具体实现；默认提供 [NoopBackend]。
 * - 事件总线：所有 AR/VR 事件（手势、注视、空间锚点变化等）通过 [events] 暴露。
 * - 生命周期：[start]/[stop] 必须成对调用，避免传感器/锚点资源泄漏。
 *
 * 当前实现为骨架版本，提供完整的 API 表面与默认行为；
 * 后续接入 ARCore/OpenXR 时，只需替换 [Backend] 实现。
 */
class ARVRInteractionManager(private val context: Context) {

    companion object {
        private const val TAG = "ARVRInteractionMgr"
        private const val EVENT_BUFFER = 64
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<ARVREvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER
    )
    val events: SharedFlow<ARVREvent> = _events.asSharedFlow()

    private val listeners = CopyOnWriteArrayList<ARVRInteractionListener>()

    @Volatile
    private var backend: Backend = NoopBackend

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentMode: InteractionMode = InteractionMode.DISABLED

    /**
     * 注入 AR/VR 后端实现。必须在 [start] 之前调用。
     * 如果不注入，使用 [NoopBackend]，所有交互返回默认/不支持结果。
     */
    fun setBackend(backend: Backend) {
        if (isRunning) {
            Log.w(TAG, "setBackend called while running; the new backend will be used after restart")
        }
        this.backend = backend
    }

    /** 启动 AR/VR 子系统。重复调用安全。 */
    fun start(mode: InteractionMode = InteractionMode.AR_WORLD): Boolean {
        if (isRunning) {
            Log.i(TAG, "Already running in mode=$currentMode; ignoring start($mode)")
            return true
        }
        val ok = try {
            backend.start(context, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Backend start failed: ${e.message}", e)
            false
        }
        if (ok) {
            isRunning = true
            currentMode = mode
            emitEvent(ARVREvent.SessionStarted(mode))
            Log.i(TAG, "AR/VR session started, mode=$mode, backend=${backend::class.simpleName}")
        } else {
            Log.w(TAG, "Backend refused to start in mode=$mode")
        }
        return ok
    }

    /** 停止 AR/VR 子系统。重复调用安全。 */
    fun stop() {
        if (!isRunning) return
        try {
            backend.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Backend stop failed: ${e.message}", e)
        }
        isRunning = false
        currentMode = InteractionMode.DISABLED
        emitEvent(ARVREvent.SessionStopped)
        Log.i(TAG, "AR/VR session stopped")
    }

    /** 注册交互监听器。重复注册同一实例会被忽略。 */
    fun addListener(listener: ARVRInteractionListener) {
        if (listeners.addIfAbsent(listener)) {
            Log.d(TAG, "Listener added: ${listener::class.simpleName}, total=${listeners.size}")
        }
    }

    /** 注销交互监听器。 */
    fun removeListener(listener: ARVRInteractionListener) {
        if (listeners.remove(listener)) {
            Log.d(TAG, "Listener removed: ${listener::class.simpleName}, total=${listeners.size}")
        }
    }

    /**
     * 投递一个手势事件到管理器。
     * 后端实现通常会主动调用此方法，将底层传感器/识别结果转成上层事件。
     */
    fun dispatchGesture(gesture: GestureEvent) {
        if (!isRunning) return
        emitEvent(ARVREvent.GestureDetected(gesture))
        listeners.forEach { listener ->
            try {
                listener.onGesture(gesture)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onGesture failed: ${e.message}", e)
            }
        }
    }

    /**
     * 投递一个注视事件。
     * @param x 屏幕空间 X（0..1）
     * @param y 屏幕空间 Y（0..1）
     * @param confidence 0..1
     */
    fun dispatchGaze(x: Float, y: Float, confidence: Float) {
        if (!isRunning) return
        if (confidence < 0f || confidence > 1f) {
            Log.w(TAG, "Gaze confidence out of range: $confidence")
            return
        }
        emitEvent(ARVREvent.GazeUpdate(x, y, confidence))
        listeners.forEach { listener ->
            try {
                listener.onGaze(x, y, confidence)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onGaze failed: ${e.message}", e)
            }
        }
    }

    /**
     * 创建一个空间锚点。
     * @return 锚点 ID，null 表示后端不可用或当前模式不支持。
     */
    suspend fun createAnchor(pose: Pose): String? {
        if (!isRunning) return null
        return try {
            val anchorId = backend.createAnchor(pose)
            if (anchorId != null) {
                emitEvent(ARVREvent.AnchorCreated(anchorId, pose))
            }
            anchorId
        } catch (e: Exception) {
            Log.e(TAG, "createAnchor failed: ${e.message}", e)
            null
        }
    }

    /**
     * 移除指定锚点。
     */
    fun removeAnchor(anchorId: String) {
        if (!isRunning) return
        try {
            backend.removeAnchor(anchorId)
            emitEvent(ARVREvent.AnchorRemoved(anchorId))
        } catch (e: Exception) {
            Log.e(TAG, "removeAnchor failed: ${e.message}", e)
        }
    }

    /** 释放所有资源。 */
    fun release() {
        stop()
        listeners.clear()
        scope.cancel()
        try {
            (backend as? AutoCloseable)?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Backend close failed: ${e.message}")
        }
    }

    private fun emitEvent(event: ARVREvent) {
        scope.launch { _events.emit(event) }
    }

    // ===== 类型定义 =====

    /** AR/VR 交互模式。 */
    enum class InteractionMode {
        DISABLED,
        AR_WORLD,       // AR 世界跟踪
        AR_FACE,        // AR 面部识别
        VR_IMMERSIVE    // VR 沉浸式
    }

    /** 6DoF 位姿。 */
    data class Pose(
        val x: Float, val y: Float, val z: Float,
        val qx: Float, val qy: Float, val qz: Float, val qw: Float
    )

    /** 手势事件。 */
    data class GestureEvent(
        val type: GestureType,
        val confidence: Float,
        val timestampMs: Long = System.currentTimeMillis()
    )

    enum class GestureType {
        TAP, DOUBLE_TAP, LONG_PRESS,
        SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN,
        PINCH, GRAB, OPEN_PALM, POINT, THUMBS_UP
    }

    /** AR/VR 事件流。 */
    sealed class ARVREvent {
        data class SessionStarted(val mode: InteractionMode) : ARVREvent()
        object SessionStopped : ARVREvent()
        data class GestureDetected(val gesture: GestureEvent) : ARVREvent()
        data class GazeUpdate(val x: Float, val y: Float, val confidence: Float) : ARVREvent()
        data class AnchorCreated(val anchorId: String, val pose: Pose) : ARVREvent()
        data class AnchorRemoved(val anchorId: String) : ARVREvent()
    }

    /** 交互监听器接口。 */
    interface ARVRInteractionListener {
        fun onGesture(gesture: GestureEvent) {}
        fun onGaze(x: Float, y: Float, confidence: Float) {}
    }

    /** 后端接口：具体 AR/VR 引擎实现此接口。 */
    interface Backend {
        fun start(context: Context, mode: InteractionMode): Boolean
        fun stop()
        suspend fun createAnchor(pose: Pose): String?
        fun removeAnchor(anchorId: String)
    }

    /** 默认空实现：所有操作返回不支持。 */
    object NoopBackend : Backend {
        override fun start(context: Context, mode: InteractionMode): Boolean {
            Log.i(TAG, "NoopBackend start: mode=$mode (no-op)")
            return true
        }
        override fun stop() = Unit
        override suspend fun createAnchor(pose: Pose): String? = null
        override fun removeAnchor(anchorId: String) = Unit
    }
}
