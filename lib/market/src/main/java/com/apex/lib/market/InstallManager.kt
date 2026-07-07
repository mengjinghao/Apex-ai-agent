package com.apex.lib.market

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 安装管理器 — 安装流程状态机 + 任务队列 + 进度事件流。
 *
 * **状态机**：
 *   QUEUED → DOWNLOADING → INSTALLING → INSTALLED
 *                                       ↘ FAILED
 *   任意进行中状态 → CANCELED
 *
 * **设计**：
 *   - lib:market 不直接做网络 / 文件 IO，所有实际工作交给 [Installer]（由 APK 注入）
 *   - 任务串行执行（保证下载带宽 / 避免并发写文件）
 *   - 进度通过 [events] 暴露（与 [MarketEvent] 对齐）
 *
 * @property installer APK 注入的真实下载 + 安装实现
 */
class InstallManager(
    private val installer: Installer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()

    /** taskId → 任务状态。 */
    private val tasks = ConcurrentHashMap<String, InstallTask>()
    /** itemId → 进行中的 taskId（避免重复入队）。 */
    private val inFlightByItem = ConcurrentHashMap<String, String>()
    /** 串行执行队列。 */
    private val pending = ArrayDeque<String>()
    /** 当前正在执行的任务 jobId。 */
    private var currentJob: Job? = null

    private val _events = MutableSharedFlow<MarketEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MarketEvent> = _events.asSharedFlow()

    /**
     * 入队一个安装任务。
     *
     * @return taskId（若该 item 已在队列中或正在安装，返回已存在的 taskId）
     */
    suspend fun enqueue(
        item: MarketItem,
        targetPath: String? = null,
        config: Map<String, String> = emptyMap()
    ): String {
        // 同一 item 不重复入队
        inFlightByItem[item.id]?.let { return it }

        val taskId = Trace.newId("install")
        val task = InstallTask(
            taskId = taskId,
            itemId = item.id,
            itemName = item.name,
            category = item.categoryEnum,
            status = InstallStatus.QUEUED,
            progress = 0,
            stage = "queued",
            startedAt = System.currentTimeMillis(),
            targetPath = targetPath,
            config = config,
            item = item
        )
        tasks[taskId] = task
        inFlightByItem[item.id] = taskId

        ApexLog.i(ApexSuite.ApkId.MARKET,
            "[Install] enqueued: taskId=$taskId item=${item.id} (${item.name})")
        _events.tryEmit(MarketEvent.InstallQueued(taskId, item.id))

        queueMutex.withLock {
            pending.addLast(taskId)
            // 若当前没有正在执行的任务，立即启动
            if (currentJob == null || currentJob?.isActive != true) {
                pumpNext()
            }
        }
        return taskId
    }

    /**
     * 取消任务（仅 QUEUED / 进行中可取消）。
     */
    suspend fun cancel(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        if (task.status.isTerminal) return false
        tasks[taskId] = task.copy(status = InstallStatus.CANCELED, completedAt = System.currentTimeMillis())
        inFlightByItem.remove(task.itemId)
        queueMutex.withLock { pending.remove(taskId) }
        // 当前任务取消：currentJob 由协程内的状态检查处理
        ApexLog.w(ApexSuite.ApkId.MARKET, "[Install] canceled: taskId=$taskId item=${task.itemId}")
        return true
    }

    /** 获取任务状态。 */
    fun getTask(taskId: String): InstallTask? = tasks[taskId]

    /** 按 itemId 查找任务。 */
    fun getTaskByItem(itemId: String): InstallTask? {
        val tid = inFlightByItem[itemId] ?: return null
        return tasks[tid]
    }

    /** 列出所有任务（按时间倒序）。 */
    fun listTasks(limit: Int = 100): List<InstallTask> =
        tasks.values.sortedByDescending { it.startedAt }.take(limit)

    /** 列出最近 N 个已完成任务。 */
    fun listRecentCompleted(limit: Int = 20): List<InstallTask> =
        tasks.values.filter { it.status.isTerminal }
            .sortedByDescending { it.completedAt ?: it.startedAt }
            .take(limit)

    /**
     * 触发队列推进（若空闲）。
     *
     * 注意：本函数应在 [queueMutex] 持有上下文中调用，或在协程启动后通过锁再次进入。
     */
    private fun pumpNext() {
        val nextId = pending.removeFirstOrNull()
        if (nextId == null) {
            currentJob = null
            return
        }
        val task = tasks[nextId]
        if (task == null || task.status == InstallStatus.CANCELED) {
            // 已取消或丢失，递归取下一个
            pumpNext()
            return
        }

        currentJob = scope.launch {
            runInstall(task)
            // 推进下一个
            queueMutex.withLock { pumpNext() }
        }
    }

    private suspend fun runInstall(task: InstallTask) {
        val item = task.item ?: run {
            updateTask(task.taskId) {
                it.copy(status = InstallStatus.FAILED, error = "item snapshot missing",
                    completedAt = System.currentTimeMillis())
            }
            _events.tryEmit(MarketEvent.InstallFailed(task.taskId, task.itemId, "item snapshot missing"))
            inFlightByItem.remove(task.itemId)
            return
        }

        try {
            // DOWNLOADING
            updateTask(task.taskId) { it.copy(status = InstallStatus.DOWNLOADING, stage = "downloading") }

            val outcome = installer.install(item, task.targetPath, task.config) { progress, stage ->
                updateTask(task.taskId) { t ->
                    t.copy(progress = progress.coerceIn(0, 100), stage = stage,
                        status = if (progress >= 100) InstallStatus.INSTALLING else t.status)
                }
                _events.tryEmit(MarketEvent.InstallProgress(task.taskId, task.itemId, progress, stage))
            }

            if (outcome.success) {
                updateTask(task.taskId) {
                    it.copy(status = InstallStatus.INSTALLED, progress = 100, stage = "done",
                        installedPath = outcome.installedPath, message = outcome.message,
                        completedAt = System.currentTimeMillis())
                }
                _events.tryEmit(MarketEvent.InstallCompleted(task.taskId, task.itemId, outcome.installedPath))
                ApexLog.i(ApexSuite.ApkId.MARKET,
                    "[Install] completed: taskId=${task.taskId} item=${task.itemId} -> ${outcome.installedPath}")
            } else {
                updateTask(task.taskId) {
                    it.copy(status = InstallStatus.FAILED, error = outcome.error ?: outcome.message,
                        completedAt = System.currentTimeMillis())
                }
                _events.tryEmit(MarketEvent.InstallFailed(task.taskId, task.itemId,
                    outcome.error ?: outcome.message ?: "unknown"))
                ApexLog.w(ApexSuite.ApkId.MARKET,
                    "[Install] failed: taskId=${task.taskId} item=${task.itemId} err=${outcome.error}")
            }
        } catch (t: Throwable) {
            updateTask(task.taskId) {
                it.copy(status = InstallStatus.FAILED, error = t.message ?: t.javaClass.simpleName,
                    completedAt = System.currentTimeMillis())
            }
            _events.tryEmit(MarketEvent.InstallFailed(task.taskId, task.itemId, t.message ?: "exception"))
            ApexLog.e(ApexSuite.ApkId.MARKET,
                "[Install] exception: taskId=${task.taskId} item=${task.itemId}", t)
        } finally {
            inFlightByItem.remove(task.itemId)
        }
    }

    private fun updateTask(taskId: String, transform: (InstallTask) -> InstallTask) {
        val cur = tasks[taskId] ?: return
        tasks[taskId] = transform(cur)
    }
}

/**
 * 安装任务（不可变快照；更新时整体替换）。
 */
data class InstallTask(
    val taskId: String,
    val itemId: String,
    val itemName: String,
    val category: MarketCategory,
    val status: InstallStatus,
    val progress: Int,                 // 0-100
    val stage: String,                 // "queued" / "downloading" / "installing" / "done" / ...
    val startedAt: Long,
    val completedAt: Long? = null,
    val targetPath: String? = null,
    val config: Map<String, String> = emptyMap(),
    val installedPath: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** 入队时的市场项快照（执行时透传给 Installer）。 */
    val item: MarketItem? = null
)
