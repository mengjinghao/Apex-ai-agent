package com.apex.lib.workingfiles.replay

import com.apex.lib.workingfiles.agent.AgentFlow
import com.apex.lib.workingfiles.agent.AgentFlowStorage
import com.apex.lib.workingfiles.agent.AgentStep
import com.apex.lib.workingfiles.snapshot.SnapshotStorage
import com.apex.sdk.common.ApexLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 变更回放器 — Apex 独有的"按视频方式回放 Agent 变更"。
 *
 * **创新点**（VSCode/Cline/Aider 都没有）：
 *   - 像看视频一样回放 Agent 的所有变更
 *   - 可调速度（0.5x / 1x / 2x / 5x / 10x）
 *   - 跳到下一个变更点
 *   - 暂停 / 继续 / 重置
 *   - 回放到任意步骤时显示该步骤的文件状态
 *
 * **典型用法**：
 *   ```kotlin
 *   val replayer = ChangeReplayer(snapshotStorage, flowStorage)
 *   replayer.load(sessionId)
 *   replayer.events.collect { event ->
 *       when (event) {
 *           is ReplayEvent.StepStarted -> showStep(event.step)
 *           is ReplayEvent.FileChanged -> updateEditor(event.content)
 *           is ReplayEvent.Completed -> showToast("回放完成")
 *       }
 *   }
 *   replayer.play(speed = 2.0f)
 *   ```
 */
class ChangeReplayer(
    private val snapshotStorage: SnapshotStorage,
    private val flowStorage: AgentFlowStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null

    private val _events = MutableSharedFlow<ReplayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ReplayEvent> = _events.asSharedFlow()

    private var flow: AgentFlow? = null
    private var currentStepIndex: Int = -1
    private var speed: Float = 1.0f
    private var playing: Boolean = false

    /**
     * 加载会话。
     */
    fun load(sessionId: String): Boolean {
        flow = flowStorage.getFlow(sessionId)
        currentStepIndex = -1
        return flow != null
    }

    /**
     * 播放。
     * @param speed 播放速度
     */
    fun play(speed: Float = 1.0f) {
        if (playing) return
        val f = flow ?: return
        this.speed = speed
        playing = true
        playJob = scope.launch {
            while (playing && currentStepIndex < f.steps.size - 1) {
                currentStepIndex++
                val step = f.steps[currentStepIndex]
                _events.tryEmit(ReplayEvent.StepStarted(step, currentStepIndex, f.steps.size))

                // 应用此步骤的快照
                applyStep(step)

                // 等待
                val wait = (BASE_INTERVAL_MS / speed).toLong().coerceAtLeast(100L)
                delay(wait)
            }
            playing = false
            _events.tryEmit(ReplayEvent.Completed)
        }
    }

    /**
     * 暂停。
     */
    fun pause() {
        playing = false
        playJob?.cancel()
        playJob = null
        _events.tryEmit(ReplayEvent.Paused)
    }

    /**
     * 重置到开头。
     */
    fun reset() {
        pause()
        currentStepIndex = -1
        _events.tryEmit(ReplayEvent.Reset)
    }

    /**
     * 跳到指定步骤。
     */
    fun jumpTo(stepIndex: Int) {
        val f = flow ?: return
        if (stepIndex < 0 || stepIndex >= f.steps.size) return
        val wasPlaying = playing
        pause()
        currentStepIndex = stepIndex
        // 应用从开始到此步骤的所有快照（取最新状态）
        applyAccumulatedState(stepIndex)
        _events.tryEmit(ReplayEvent.StepStarted(f.steps[stepIndex], stepIndex, f.steps.size))
        if (wasPlaying) play(speed)
    }

    /**
     * 下一步。
     */
    fun nextStep() {
        val f = flow ?: return
        if (currentStepIndex < f.steps.size - 1) {
            jumpTo(currentStepIndex + 1)
        }
    }

    /**
     * 上一步。
     */
    fun previousStep() {
        if (currentStepIndex > 0) {
            jumpTo(currentStepIndex - 1)
        } else if (currentStepIndex == 0) {
            jumpTo(0)
        }
    }

    /**
     * 设置播放速度。
     */
    fun setSpeed(speed: Float) {
        this.speed = speed
    }

    fun isPlaying(): Boolean = playing
    fun getCurrentStepIndex(): Int = currentStepIndex
    fun getTotalSteps(): Int = flow?.steps?.size ?: 0
    fun getProgress(): Float {
        val total = getTotalSteps()
        if (total == 0) return 0f
        return (currentStepIndex + 1).toFloat() / total
    }

    private fun applyStep(step: AgentStep) {
        // 加载此步骤的所有快照内容
        for (snapshotId in step.snapshotIds) {
            val snap = snapshotStorage.load(snapshotId) ?: continue
            _events.tryEmit(ReplayEvent.FileChanged(
                filePath = snap.filePath,
                content = snap.content,
                snapshotId = snap.id,
                description = snap.description
            ))
        }
    }

    private fun applyAccumulatedState(stepIndex: Int) {
        val f = flow ?: return
        // 对每个文件，找到截止此步骤的最新快照
        val fileToLatestSnap = mutableMapOf<String, String>()
        for (i in 0..stepIndex) {
            val step = f.steps[i]
            for (snapId in step.snapshotIds) {
                val snap = snapshotStorage.load(snapId) ?: continue
                fileToLatestSnap[snap.filePath] = snapId
            }
        }
        // 应用每个文件的最新快照
        for ((filePath, snapId) in fileToLatestSnap) {
            val snap = snapshotStorage.load(snapId) ?: continue
            _events.tryEmit(ReplayEvent.FileChanged(
                filePath = filePath,
                content = snap.content,
                snapshotId = snapId,
                description = snap.description
            ))
        }
    }

    companion object {
        private const val BASE_INTERVAL_MS = 1000L  // 1x 速度时每步 1 秒
    }
}

/** 回放事件。 */
sealed class ReplayEvent {
    data class StepStarted(val step: AgentStep, val index: Int, val total: Int) : ReplayEvent()
    data class FileChanged(
        val filePath: String,
        val content: String,
        val snapshotId: String,
        val description: String
    ) : ReplayEvent()
    object Paused : ReplayEvent()
    object Reset : ReplayEvent()
    object Completed : ReplayEvent()
}
