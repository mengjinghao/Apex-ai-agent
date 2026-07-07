package com.apex.agent.data.burstmode.visualization

import android.app.Application
import android.util.Log
import com.apex.agent.core.arvr.ARVRInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Burst 空间可视化桥
 *
 * 把 Burst 微内核的运行状态（任务执行 / 健康指标 / 设备状态）投影到 AR/VR 空间，
 * 用于调试和可观测性。
 *
 * 设计：
 * 1) 通过 [ARVRInteractionManager] 启动 AR 会话（若设备不支持会优雅降级）
 * 2) 周期性把指标快照渲染为空间中的"全息面板"（骨架实现仅记录心跳日志，
 *    实际空间渲染需接入 ARCore / OpenXR 等 SDK）
 * 3) 不阻塞调用方 —— 所有可视化工作在独立的 [scope] 协程中执行
 * 4) [startVisualization] 是 suspend，但内部立即返回 —— 实际可视化循环在 launch 中跑
 *
 * 该类被 [com.apex.agent.kernel.burst.BurstKernel.enableSpatialVisualization] 使用。
 * 当前实现是骨架实现：当 AR 后端可用时启动会话，并在循环中周期性"提交"指标快照；
 * 实际的空间渲染需要接入具体的 AR SDK（ARCore / OpenXR），这部分留给上层实现。
 */
class BurstSpatialBridge(
    private val app: Application,
    private val arvrManager: ARVRInteractionManager
) {
    companion object {
        private const val TAG = "BurstSpatialBridge"
        private const val RENDER_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderLock = Mutex()
    private var renderJob: Job? = null
    private var started = false
    private var released = false

    /**
     * 启动空间可视化。
     *
     * 调用语义：BurstKernel 在 `scope.launch { spatialBridge!!.startVisualization() }`
     * 中调用 —— 因此本方法是 suspend 但内部不阻塞，立即返回。
     *
     * 失败处理：AR 后端不可用时记录日志但不抛异常（BurstKernel 的 try-catch 已经兜底）。
     */
    suspend fun startVisualization() {
        if (started || released) return
        val ok = runCatching { arvrManager.start(ARVRInteractionManager.InteractionMode.AR_WORLD) }
            .getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "startVisualization: AR backend refused to start, visualization disabled")
            return
        }
        started = true
        Log.i(TAG, "startVisualization: AR session started")

        // 启动渲染循环 —— 周期性把 BurstHealthMonitor 的指标快照"提交"到空间
        // 当前实现仅记录日志；实际空间渲染需接入 ARCore / OpenXR 等 SDK
        renderJob = scope.launch {
            while (isActive && !released) {
                runCatching { renderFrame() }
                    .onFailure { Log.w(TAG, "renderFrame failed: ${it.message}") }
                delay(RENDER_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止可视化（不释放 ARVRInteractionManager —— 后者由所有者管理生命周期）。
     * 幂等。
     */
    fun stopVisualization() {
        renderJob?.cancel()
        renderJob = null
        if (started) {
            runCatching { arvrManager.stop() }
            started = false
        }
    }

    /**
     * 释放资源。调用后对象不可再用。
     * 不释放 arvrManager —— 由外部所有者释放。
     */
    fun release() {
        if (released) return
        released = true
        stopVisualization()
        scope.cancel()
    }

    /**
     * 渲染一帧 —— 当前是骨架实现，仅记录日志。
     *
     * 未来扩展点：
     * 1) 从 BurstKernel.healthMonitor.metrics 拉取最新 HealthMetrics
     * 2) 把指标转换为空间坐标（如悬浮在用户视野上方的"全息面板"）
     * 3) 通过 arvrManager.createAnchor() 创建锚点
     * 4) 调用底层 AR SDK 绘制文本 / 进度条 / 图表
     */
    private suspend fun renderFrame() {
        renderLock.withLock {
            // 骨架实现：仅在 DEBUG 级别记录心跳，证明循环存活
            // 实际可视化逻辑留待接入 AR SDK 后实现
            Log.v(TAG, "renderFrame: tick (AR visualization skeleton)")
        }
    }
}
