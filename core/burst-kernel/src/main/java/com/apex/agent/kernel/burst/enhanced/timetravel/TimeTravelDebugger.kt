package com.apex.agent.kernel.burst.enhanced.timetravel

import com.apex.agent.kernel.burst.enhanced.battle.BattleRecorder
import java.util.concurrent.ConcurrentHashMap

/**
 * B10: 时间旅行调试（Time-Travel Debugging）
 *
 * 基于战斗日志，支持"时光倒流"：
 * - 从任意历史 frame 重新分支执行（what-if 分析）
 * - 比较两个 frame 的状态差异
 * - 从中间状态 fork 新任务
 */
class TimeTravelDebugger(
    private val recorder: BattleRecorder
) {

    data class TaskSnapshot(
        val frameId: Long,
        val taskId: String,
        val state: String,
        val input: String,
        val output: String?,
        val rage: Int,
        val berserkState: String,
        val timestamp: Long
    )

    data class StateDiff(
        val field: String,
        val valueA: String,
        val valueB: String
    )

    data class TaskModification(
        val inputOverride: String? = null,
        val configOverrides: Map<String, Any> = emptyMap(),
        val strategyOverride: String? = null
    )

    data class ForkResult(
        val newTaskId: String,
        val fromFrameId: Long,
        val modification: TaskModification,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val forks = ConcurrentHashMap<String, ForkResult>()

    /**
     * 跳转到某帧
     */
    fun seekTo(frameId: Long): TaskSnapshot? {
        val frame = recorder.getFrame(frameId) ?: return null
        return TaskSnapshot(
            frameId = frame.frameId, taskId = frame.taskId,
            state = frame.state.name, input = frame.input, output = frame.output,
            rage = frame.rage, berserkState = frame.berserkState, timestamp = frame.timestamp
        )
    }

    /**
     * 从某帧 fork 新任务
     */
    fun fork(frameId: Long, modification: TaskModification = TaskModification()): ForkResult? {
        val frame = recorder.getFrame(frameId) ?: return null
        val newTaskId = "fork_${frame.taskId}_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val result = ForkResult(newTaskId, frameId, modification)
        forks[newTaskId] = result
        return result
    }

    /**
     * 比较两帧差异
     */
    fun diff(frameA: Long, frameB: Long): List<StateDiff> {
        val a = recorder.getFrame(frameA) ?: return emptyList()
        val b = recorder.getFrame(frameB) ?: return emptyList()
        val diffs = mutableListOf<StateDiff>()
        if (a.state != b.state) diffs.add(StateDiff("state", a.state.name, b.state.name))
        if (a.rage != b.rage) diffs.add(StateDiff("rage", a.rage.toString(), b.rage.toString()))
        if (a.berserkState != b.berserkState) diffs.add(StateDiff("berserkState", a.berserkState, b.berserkState))
        if (a.durationMs != b.durationMs) diffs.add(StateDiff("durationMs", a.durationMs.toString(), b.durationMs.toString()))
        if (a.input != b.input) diffs.add(StateDiff("input", a.input.take(50), b.input.take(50)))
        if (a.output != b.output) diffs.add(StateDiff("output", a.output?.take(50) ?: "null", b.output?.take(50) ?: "null"))
        return diffs
    }

    /**
     * 获取任务时间线
     */
    fun getTimeline(taskId: String): List<TaskSnapshot> {
        return recorder.getFrames(taskId).map { frame ->
            TaskSnapshot(
                frameId = frame.frameId, taskId = frame.taskId,
                state = frame.state.name, input = frame.input, output = frame.output,
                rage = frame.rage, berserkState = frame.berserkState, timestamp = frame.timestamp
            )
        }
    }

    /**
     * 获取所有 fork
     */
    fun getForks(): List<ForkResult> = forks.values.toList()

    /**
     * 生成 what-if 分析报告
     */
    fun generateWhatIfReport(frameId: Long, modification: TaskModification): String {
        val snapshot = seekTo(frameId) ?: return "帧不存在"
        val sb = StringBuilder()
        sb.appendLine("═══ What-If 分析 ═══")
        sb.appendLine("基准帧: $frameId")
        sb.appendLine("任务: ${snapshot.taskId}")
        sb.appendLine("状态: ${snapshot.state}")
        sb.appendLine("暴怒值: ${snapshot.rage}")
        sb.appendLine("狂暴态: ${snapshot.berserkState}")
        sb.appendLine()
        sb.appendLine("修改:")
        modification.inputOverride?.let { sb.appendLine("- 输入: ${it.take(50)}") }
        modification.configOverrides.forEach { (k, v) -> sb.appendLine("- 配置 $k: $v") }
        modification.strategyOverride?.let { sb.appendLine("- 策略: $it") }
        sb.appendLine()
        sb.appendLine("预计影响:")
        sb.appendLine("- 重新执行从此帧开始")
        sb.appendLine("- 结果可能不同（取决于修改）")
        return sb.toString()
    }
}
