package com.apex.agent.kernel.burst.enhanced.battle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * B2: 战斗日志与回放（Battle Log & Replay）
 *
 * 记录狂暴模式所有执行细节，支持时间旅行回放：
 * - 每个 Skill 执行/工具调用/LLM 推理都是一帧
 * - 支持慢放/快放/跳转/分支
 * - 战斗复盘分析
 *
 * 区别于现有 ExecutionLogger（仅事件流），Battle Log 是完整状态快照流
 */
class BattleRecorder(
    private val maxFramesPerTask: Int = 10_000,
    private val maxTotalFrames: Int = 100_000
) {

    /**
     * 战斗帧（一个执行步骤的完整快照）
     */
    data class BattleFrame(
        val frameId: Long,
        val timestamp: Long,
        val taskId: String,
        val skillId: String,
        val skillName: String,
        val state: FrameState,
        val input: String,           // 序列化的输入
        val output: String?,         // 序列化的输出
        val durationMs: Long,
        val rage: Int,               // 当时的暴怒值
        val berserkState: String,    // 当时的狂暴状态
        val strategy: String,        // 执行策略
        val parentFrameId: Long?,    // 父帧（子任务引用）
        val metadata: Map<String, String> = emptyMap()
    )

    enum class FrameState {
        STARTED,     // 开始执行
        IN_PROGRESS, // 进行中
        SUCCESS,     // 成功
        FAILED,      // 失败
        CANCELLED,   // 取消
        TIMEOUT,     // 超时
        RETRYING,    // 重试中
        SKIPPED      // 跳过
    }

    /**
     * 战斗统计
     */
    data class BattleStats(
        val totalFrames: Int,
        val framesByState: Map<FrameState, Int>,
        val framesBySkill: Map<String, Int>,
        val totalDurationMs: Long,
        val avgFrameDurationMs: Long,
        val successRate: Float,
        val failureRate: Float,
        val rageTimeline: List<Pair<Long, Int>>,  // (timestamp, rage)
        val berserkPeriods: List<LongRange>        // 狂暴时间段
    )

    /**
     * 回放控制
     */
    data class ReplayControl(
        val speed: Float = 1.0f,         // 0.25x ~ 4x
        val fromFrame: Long = 0,
        val toFrame: Long? = null,
        val filterSkill: String? = null,
        val filterState: FrameState? = null
    )

    // ============ 存储 ============

    private val framesByTask = ConcurrentHashMap<String, ConcurrentLinkedQueue<BattleFrame>>()
    private val allFrames = ConcurrentLinkedQueue<BattleFrame>()
    private val frameById = ConcurrentHashMap<Long, BattleFrame>()
    private val frameCounter = AtomicLong(0)
    private val _liveStream = MutableSharedFlow<BattleFrame>(extraBufferCapacity = 256)
    val liveStream: SharedFlow<BattleFrame> = _liveStream.asSharedFlow()

    // ============ 公共 API ============

    /**
     * 捕获一帧
     */
    fun capture(frame: BattleFrame): BattleFrame {
        val frameWithId = frame.copy(frameId = frameCounter.incrementAndGet())

        // 存入任务级
        val taskFrames = framesByTask.computeIfAbsent(frame.taskId) { ConcurrentLinkedQueue() }
        taskFrames.add(frameWithId)
        while (taskFrames.size > maxFramesPerTask) taskFrames.poll()

        // 存入全局
        allFrames.add(frameWithId)
        while (allFrames.size > maxTotalFrames) allFrames.poll()

        // 索引
        frameById[frameWithId.frameId] = frameWithId

        // 实时流
        kotlinx.coroutines.runBlocking { _liveStream.emit(frameWithId) }

        return frameWithId
    }

    /**
     * 便捷方法：记录开始
     */
    fun recordStart(
        taskId: String, skillId: String, skillName: String,
        input: String, rage: Int, berserkState: String, strategy: String,
        parentFrameId: Long? = null
    ): BattleFrame {
        return capture(BattleFrame(
            frameId = 0, timestamp = System.currentTimeMillis(),
            taskId = taskId, skillId = skillId, skillName = skillName,
            state = FrameState.STARTED, input = input, output = null,
            durationMs = 0, rage = rage, berserkState = berserkState,
            strategy = strategy, parentFrameId = parentFrameId
        ))
    }

    /**
     * 便捷方法：记录结束
     */
    fun recordEnd(
        startFrame: BattleFrame, state: FrameState, output: String?,
        rage: Int, berserkState: String
    ): BattleFrame {
        val duration = System.currentTimeMillis() - startFrame.timestamp
        return capture(startFrame.copy(
            frameId = 0,
            timestamp = System.currentTimeMillis(),
            state = state,
            output = output,
            durationMs = duration,
            rage = rage,
            berserkState = berserkState
        ))
    }

    /**
     * 获取任务的所有帧
     */
    fun getFrames(taskId: String): List<BattleFrame> {
        return framesByTask[taskId]?.toList()?.sortedBy { it.timestamp } ?: emptyList()
    }

    /**
     * 获取所有帧
     */
    fun getAllFrames(): List<BattleFrame> = allFrames.toList().sortedBy { it.timestamp }

    /**
     * 获取某帧
     */
    fun getFrame(frameId: Long): BattleFrame? = frameById[frameId]

    /**
     * 回放
     */
    suspend fun replay(control: ReplayControl = ReplayControl()): Flow<BattleFrame> {
        return kotlinx.coroutines.flow.flow {
            val frames = getAllFrames()
                .filter { it.frameId >= control.fromFrame }
                .filter { control.toFrame == null || it.frameId <= control.toFrame }
                .filter { control.filterSkill == null || it.skillId == control.filterSkill }
                .filter { control.filterState == null || it.state == control.filterState }

            for (frame in frames) {
                emit(frame)
                // 按速度延迟
                val delayMs = (frame.durationMs / control.speed).toLong().coerceAtMost(1000)
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    /**
     * 获取任务统计
     */
    fun getTaskStats(taskId: String): BattleStats {
        val frames = getFrames(taskId)
        return computeStats(frames)
    }

    /**
     * 获取全局统计
     */
    fun getGlobalStats(): BattleStats {
        return computeStats(allFrames.toList())
    }

    /**
     * 导出为 JSON
     */
    fun exportToJson(taskId: String? = null): String {
        val frames = if (taskId != null) getFrames(taskId) else getAllFrames()
        val sb = StringBuilder()
        sb.append("[")
        frames.forEachIndexed { i, frame ->
            if (i > 0) sb.append(",")
            sb.append("""{"frameId":${frame.frameId},"timestamp":${frame.timestamp},"taskId":"${frame.taskId}","skillId":"${frame.skillId}","state":"${frame.state}","durationMs":${frame.durationMs},"rage":${frame.rage},"berserkState":"${frame.berserkState}"}""")
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * 清空
     */
    fun clear(taskId: String? = null) {
        if (taskId != null) {
            val taskFrames = framesByTask.remove(taskId)
            taskFrames?.forEach { allFrames.remove(it); frameById.remove(it.frameId) }
        } else {
            framesByTask.clear()
            allFrames.clear()
            frameById.clear()
        }
    }

    /**
     * 获取帧数
     */
    fun frameCount(): Int = allFrames.size

    // ============ 内部方法 ============

    private fun computeStats(frames: List<BattleFrame>): BattleStats {
        if (frames.isEmpty()) {
            return BattleStats(0, emptyMap(), emptyMap(), 0, 0, 0f, 0f, emptyList(), emptyList())
        }

        val byState = frames.groupingBy { it.state }.eachCount()
        val bySkill = frames.groupingBy { it.skillId }.eachCount()
        val totalDuration = frames.sumOf { it.durationMs }
        val avgDuration = if (frames.isNotEmpty()) totalDuration / frames.size else 0
        val successCount = byState[FrameState.SUCCESS] ?: 0
        val failureCount = byState[FrameState.FAILED] ?: 0
        val total = frames.size
        val rageTimeline = frames.map { it.timestamp to it.rage }
        val berserkPeriods = findBerserkPeriods(frames)

        return BattleStats(
            totalFrames = total,
            framesByState = byState,
            framesBySkill = bySkill,
            totalDurationMs = totalDuration,
            avgFrameDurationMs = avgDuration,
            successRate = if (total > 0) successCount.toFloat() / total else 0f,
            failureRate = if (total > 0) failureCount.toFloat() / total else 0f,
            rageTimeline = rageTimeline,
            berserkPeriods = berserkPeriods
        )
    }

    private fun findBerserkPeriods(frames: List<BattleFrame>): List<LongRange> {
        val periods = mutableListOf<LongRange>()
        var start: Long? = null
        for (frame in frames) {
            if (frame.berserkState == "BERSERK" && start == null) {
                start = frame.timestamp
            } else if (frame.berserkState != "BERSERK" && start != null) {
                periods.add(start..frame.timestamp)
                start = null
            }
        }
        if (start != null) {
            periods.add(start..frames.last().timestamp)
        }
        return periods
    }
}
