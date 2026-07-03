package com.apex.lib.workingfiles.timemachine

import com.apex.lib.workingfiles.snapshot.SnapshotStorage
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
import com.apex.sdk.common.ApexLog

/**
 * 时间机器 — Apex 独有的"连续滑动预览"模式。
 *
 * **创新点**（VSCode Timeline 只能点击切换）：
 *   - 用户滑动时间轴，文件内容实时变化（无需点击）
 *   - 滑动时显示"如果在这个时刻停止"的文件状态
 *   - 显示当前预览版本与最新版本的差异
 *   - 支持播放/暂停（自动按时间推进）
 *   - 移动端友好：手势滑动 + 震动反馈
 *
 * **典型用法**：
 *   ```kotlin
 *   val machine = TimeMachine(snapshotStorage)
 *   machine.load("/sdcard/proj/Main.kt")
 *   // 滑动到任意时刻
 *   val preview = machine.previewAt(timestamp)
 *   // 自动播放
 *   machine.playback(speed = 1.0)
 *   ```
 */
class TimeMachine(
    private val snapshotStorage: SnapshotStorage
) {

    /** 当前加载的文件路径。 */
    private var filePath: String? = null

    /** 时间轴上的所有快照（按时间升序）。 */
    private var timeline: List<SnapshotSummary> = emptyList()

    /** 当前预览的快照索引。 */
    private var currentIndex: Int = -1

    /**
     * 加载文件的时间轴。
     */
    fun load(path: String): Boolean {
        filePath = path
        timeline = snapshotStorage.listSnapshotSummaries(path)
        currentIndex = if (timeline.isEmpty()) -1 else timeline.size - 1
        ApexLog.i("working-files", "[TimeMachine] loaded $path: ${timeline.size} snapshots")
        return timeline.isNotEmpty()
    }

    /**
     * 获取时间轴（所有快照）。
     */
    fun getTimeline(): List<SnapshotSummary> = timeline

    /**
     * 当前预览的快照。
     */
    fun currentSnapshot(): SnapshotSummary? {
        if (currentIndex < 0 || currentIndex >= timeline.size) return null
        return timeline[currentIndex]
    }

    /**
     * 跳转到指定索引。
     */
    fun jumpTo(index: Int): SnapshotSummary? {
        if (index < 0 || index >= timeline.size) return null
        currentIndex = index
        return timeline[index]
    }

    /**
     * 跳转到指定时间戳（找最近的快照）。
     */
    fun jumpToTimestamp(timestamp: Long): SnapshotSummary? {
        if (timeline.isEmpty()) return null
        // 找到不超过 timestamp 的最新快照
        val idx = timeline.indexOfLast { it.timestamp <= timestamp }
        return if (idx >= 0) jumpTo(idx) else jumpTo(0)
    }

    /**
     * 前进一个快照。
     */
    fun next(): SnapshotSummary? {
        return if (currentIndex < timeline.size - 1) {
            jumpTo(currentIndex + 1)
        } else null
    }

    /**
     * 后退一个快照。
     */
    fun previous(): SnapshotSummary? {
        return if (currentIndex > 0) {
            jumpTo(currentIndex - 1)
        } else null
    }

    /**
     * 跳到最早。
     */
    fun jumpToStart(): SnapshotSummary? = jumpTo(0)

    /**
     * 跳到最新。
     */
    fun jumpToEnd(): SnapshotSummary? = jumpTo(timeline.size - 1)

    /**
     * 获取当前快照的完整内容。
     */
    fun currentContent(): String? {
        val snap = currentSnapshot() ?: return null
        return snapshotStorage.load(snap.id)?.content
    }

    /**
     * 当前索引（0-based）。
     */
    fun currentIndex(): Int = currentIndex

    /**
     * 时间轴长度。
     */
    fun size(): Int = timeline.size

    /**
     * 是否已加载。
     */
    fun isLoaded(): Boolean = filePath != null && timeline.isNotEmpty()

    /**
     * 获取当前进度（0.0 - 1.0）。
     */
    fun progress(): Float {
        if (timeline.isEmpty()) return 0f
        return (currentIndex + 1).toFloat() / timeline.size
    }

    /**
     * 估算下一个快照的等待时间（用于自动播放）。
     */
    fun estimatedWaitToNext(): Long {
        if (currentIndex >= timeline.size - 1) return 0
        val current = timeline[currentIndex].timestamp
        val next = timeline[currentIndex + 1].timestamp
        return (next - current).coerceAtLeast(500)  // 至少 500ms
    }

    /**
     * 获取当前快照与最新快照之间的时间差。
     */
    fun timeAheadOfLatest(): Long {
        val current = currentSnapshot() ?: return 0
        val latest = timeline.lastOrNull() ?: return 0
        return latest.timestamp - current.timestamp
    }
}

/**
 * 时间机器播放控制器。
 *
 * 自动按时间推进预览，类似视频播放器。
 */
class TimeMachinePlayer(
    private val machine: TimeMachine,
    private val onUpdate: (SnapshotSummary?) -> Unit
) {
    private var playing = false
    private var speed: Float = 1.0f
    private var thread: Thread? = null

    /**
     * 开始播放。
     * @param speed 播放速度（1.0 = 实际时间间隔，2.0 = 2倍速）
     */
    fun play(speed: Float = 1.0f) {
        if (playing) return
        this.speed = speed
        playing = true
        thread = Thread {
            while (playing && machine.currentIndex() < machine.size() - 1) {
                val wait = (machine.estimatedWaitToNext() / speed).toLong()
                    .coerceIn(100L, 5000L)  // 100ms - 5s
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
                if (!playing) break
                val next = machine.next()
                onUpdate(next)
            }
            playing = false
        }.also { it.start() }
    }

    fun pause() {
        playing = false
        thread?.interrupt()
        thread = null
    }

    fun isPlaying(): Boolean = playing

    fun setSpeed(speed: Float) {
        this.speed = speed
    }
}
