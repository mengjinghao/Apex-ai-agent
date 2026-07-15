package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import com.apex.agent.core.tools.skill.WorkflowTriggered

/**
 * 技能执行可视化模块
 *
 * 功能�?
 * - 实时执行流程可视�?
 * - 节点耗时分析
 * - 执行状态追�?
 * - 性能瓶颈定位
 * - 执行历史回放
 */
class ExecutionVisualizer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ExecutionVisualizer"
        private const val HISTORY_DIR = "execution_history"
        private const val MAX_HISTORY_SIZE = 100
        private const val REFRESH_INTERVAL_MS = 100L

        @Volatile private var INSTANCE: ExecutionVisualizer? = null

        fun getInstance(context: Context): ExecutionVisualizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExecutionVisualizer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    /**
     * 执行记录
     */
    data class ExecutionRecord(
        val id: String,
        val skillId: String,
        val skillName: String,
        val workflowId: String?,
        val startTime: Long,
        val endTime: Long?,
        val status: ExecutionStatus,
        val nodeExecutions: List<NodeExecution>,
        val totalDurationMs: Long,
        val metrics: ExecutionMetrics,
        val errorMessage: String? = null
    ) {
        val nodeCount: Int get() = nodeExecutions.size
        val successCount: Int get() = nodeExecutions.count { it.status == NodeStatus.SUCCESS }
        val failedCount: Int get() = nodeExecutions.count { it.status == NodeStatus.FAILED }
    }

    enum class ExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED,
        TIMEOUT
    }

    /**
     * 节点执行记录
     */
    data class NodeExecution(
        val nodeId: String,
        val nodeName: String,
        val nodeType: String,
        val startTime: Long,
        val endTime: Long?,
        val status: NodeStatus,
        val durationMs: Long = 0,
        val inputData: Map<String, Any?>? = null,
        val outputData: Any? = null,
        val errorMessage: String? = null,
        val position: NodePosition = NodePosition(0f, 0f)
    ) {
        val isRunning: Boolean get() = status == NodeStatus.RUNNING
        val isComplete: Boolean get() = status in listOf(NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED)
    }

    enum class NodeStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        SKIPPED,
        TIMEOUT
    }

    /**
     * 执行指标
     */
    data class ExecutionMetrics(
        val cpuUsagePercent: Float = 0f,
        val memoryUsageMb: Long = 0,
        val networkCalls: Int = 0,
        val cacheHits: Int = 0,
        val cacheMisses: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val stepCount: Int = 0
    )

    /**
     * 可视化数�?
     */
    data class VisualizationData(
        val record: ExecutionRecord,
        val currentNodeId: String?,
        val elapsedMs: Long,
        val progress: Float,
        val nodeStates: Map<String, NodeVisualState>,
        val connections: List<ConnectionVisual>,
        val statistics: ExecutionStatistics
    )

    data class NodeVisualState(
        val nodeId: String,
        val nodeName: String,
        val status: NodeStatus,
        val progress: Float,  // 0-1 for running nodes
    val elapsedMs: Long,
        val durationMs: Long?,
        val color: Int,
        val x: Float,
        val y: Float,
        val label: String
    )

    data class ConnectionVisual(
        val id: String,
        val fromNodeId: String,
        val toNodeId: String,
        val status: ConnectionStatus,
        val flowProgress: Float = 0f,  // 0-1 for active flow animation
    val color: Int
    )

    enum class ConnectionStatus {
        PENDING,
        ACTIVE,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    /**
     * 执行统计
     */
    data class ExecutionStatistics(
        val totalNodes: Int,
        val completedNodes: Int,
        val failedNodes: Int,
        val averageNodeTimeMs: Long,
        val slowestNode: NodeExecution?,
        val fastestNode: NodeExecution?,
        val estimatedRemainingMs: Long,
        val throughput: Float  // nodes per second
    )

    /**
     * 性能分析
     */
    data class PerformanceAnalysis(
        val bottlenecks: List<Bottleneck>,
        val recommendations: List<PerformanceRecommendation>,
        val nodeTimings: Map<String, Long>,  // nodeId -> avg time
    val timeDistribution: Map<String, Float>  // stage -> percentage
    )

    data class Bottleneck(
        val nodeId: String,
        val nodeName: String,
        val durationMs: Long,
        val percentageOfTotal: Float,
        val severity: BottleneckSeverity
    )

    enum class BottleneckSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    data class PerformanceRecommendation(
        val type: RecommendationType,
        val priority: Priority,
        val title: String,
        val description: String,
        val expectedImprovement: String
    )

    enum class RecommendationType {
        OPTIMIZE_NODE,
        ADD_CACHING,
        PARALLELIZE,
        REDUCE_DATA,
        INCREASE_TIMEOUT,
        FIX_ERROR
    }

    enum class Priority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    // ========== 状�?==========
    private val _currentExecution = MutableStateFlow<ExecutionRecord?>(null)
        val currentExecution: StateFlow<ExecutionRecord?> = _currentExecution.asStateFlow()
        private val _visualizationData = MutableStateFlow<VisualizationData?>(null)
        val visualizationData: StateFlow<VisualizationData?> = _visualizationData.asStateFlow()
        private val _executionHistory = MutableStateFlow<List<ExecutionRecord>>(emptyList())
        val executionHistory: StateFlow<List<ExecutionRecord>> = _executionHistory.asStateFlow()
        private val _performanceAnalysis = MutableStateFlow<PerformanceAnalysis?>(null)
        val performanceAnalysis: StateFlow<PerformanceAnalysis?> = _performanceAnalysis.asStateFlow()
        private val _isLiveMode = MutableStateFlow(false)
        val isLiveMode: StateFlow<Boolean> = _isLiveMode.asStateFlow()
        private val _liveUpdates = MutableSharedFlow<NodeExecution>(replay = 1)
        val liveUpdates: SharedFlow<NodeExecution> = _liveUpdates.asSharedFlow()
        private var refreshJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val workflowEngine by lazy { WorkflowEngine.getInstance() }

    init {
        initializeHistory()
        observeWorkflowExecution()
    }

    // ========== 实时可视�?API ==========

    /**
     * 开始实时可视化
     */
    fun startLiveVisualization(skillId: String, skillName: String, workflowId: String) {
        val record = ExecutionRecord(
            id = generateId(),
            skillId = skillId,
            skillName = skillName,
            workflowId = workflowId,
            startTime = System.currentTimeMillis(),
            endTime = null,
            status = ExecutionStatus.RUNNING,
            nodeExecutions = emptyList(),
            totalDurationMs = 0,
            metrics = ExecutionMetrics()
        )

        _currentExecution.value = record
        _isLiveMode.value = true

        // 启动刷新循环
        startRefreshLoop()

        AppLogger.d(TAG, "Started live visualization for: ${skillName}")
    }

    /**
     * 更新节点状�?
     */
    suspend fun updateNodeExecution(execution: NodeExecution) {
        val current = _currentExecution.value ?: return

        val updatedExecutions = current.nodeExecutions.toMutableList()
        val existingIndex = updatedExecutions.indexOfFirst { it.nodeId == execution.nodeId }
        if (existingIndex >= 0) {
            updatedExecutions[existingIndex] = execution
        } else {
            updatedExecutions.add(execution)
        }
        val updatedRecord = current.copy(
            nodeExecutions = updatedExecutions,
            endTime = if (execution.status == NodeStatus.SUCCESS || execution.status == NodeStatus.FAILED) {
                System.currentTimeMillis()
            } else null
        )

        _currentExecution.value = updatedRecord

        // 检查是否完�?
    if (updatedExecutions.all { it.isComplete }) {
            finishVisualization(
                if (updatedExecutions.any { it.status == NodeStatus.FAILED })
                    ExecutionStatus.FAILED else ExecutionStatus.SUCCESS
            )
        }

        // 发送实时更�?
        _liveUpdates.emit(execution)

        // 更新可视化数�?
        updateVisualizationData()
    }

    /**
     * 记录节点开�?
     */
    suspend fun recordNodeStart(
        nodeId: String,
        nodeName: String,
        nodeType: String,
        inputData: Map<String, Any?>? = null,
        position: NodePosition = NodePosition(0f, 0f)
    ) {
        val execution = NodeExecution(
            nodeId = nodeId,
            nodeName = nodeName,
            nodeType = nodeType,
            startTime = System.currentTimeMillis(),
            endTime = null,
            status = NodeStatus.RUNNING,
            inputData = inputData,
            position = position
        )
        updateNodeExecution(execution)
    }

    /**
     * 记录节点完成
     */
    suspend fun recordNodeEnd(
        nodeId: String,
        status: NodeStatus,
        outputData: Any? = null,
        errorMessage: String? = null
    ) {
        val current = _currentExecution.value ?: return
        val nodeExec = current.nodeExecutions.find { it.nodeId == nodeId } ?: return

        val durationMs = System.currentTimeMillis() - nodeExec.startTime

        val execution = nodeExec.copy(
            endTime = System.currentTimeMillis(),
            status = status,
            durationMs = durationMs,
            outputData = outputData,
            errorMessage = errorMessage
        )

        updateNodeExecution(execution)
    }

    /**
     * 完成可视�?
     */
    fun finishVisualization(status: ExecutionStatus, errorMessage: String? = null) {
        val current = _currentExecution.value ?: return

        _isLiveMode.value = false
        refreshJob?.cancel()
        val durationMs = System.currentTimeMillis() - current.startTime

        val finalRecord = current.copy(
            endTime = System.currentTimeMillis(),
            status = status,
            totalDurationMs = durationMs,
            errorMessage = errorMessage
        )

        _currentExecution.value = finalRecord

        // 保存到历�?
        saveToHistory(finalRecord)

        // 生成性能分析
        generatePerformanceAnalysis(finalRecord)

        AppLogger.d(TAG, "Finished visualization: ${status}, duration: ${durationMs}ms")
    }

    // ========== 历史回放 API ==========

    /**
     * 获取执行历史
     */
    fun getExecutionHistory(skillId: String? = null, limit: Int = MAX_HISTORY_SIZE): List<ExecutionRecord> {
        val history = _executionHistory.value
        return if (skillId != null) {
            history.filter { it.skillId == skillId }.take(limit)
        } else {
            history.take(limit)
        }
    }

    /**
     * 获取执行详情
     */
    fun getExecutionRecord(recordId: String): ExecutionRecord? {
        return _executionHistory.value.find { it.id == recordId }
            ?: _currentExecution.value?.takeIf { it.id == recordId }
    }

    /**
     * 回放执行记录
     */
    suspend fun replayExecution(recordId: String, speed: Float = 1f): Flow<VisualizationData> = flow {
        val record = getExecutionRecord(recordId) ?: return@flow

        val sortedNodes = record.nodeExecutions.sortedBy { it.startTime }
        var currentIndex = 0

        while (currentIndex < sortedNodes.size) {
            val node = sortedNodes[currentIndex]

            // 计算当前时间点之前所有节点的状�?
    val currentTime = System.currentTimeMillis()
        val nodeStates = sortedNodes.map { exec ->
                val isComplete = exec.endTime != null && exec.endTime <= node.endTime
                val isRunning = exec.startTime <= currentTime &&
                    (exec.endTime == null || exec.endTime > currentTime)
        val state = when {
                    exec == node -> NodeStatus.RUNNING
                    isComplete && exec.status == NodeStatus.SUCCESS -> NodeStatus.SUCCESS
                    isComplete && exec.status == NodeStatus.FAILED -> NodeStatus.FAILED
                    isRunning -> NodeStatus.RUNNING
                    else -> NodeStatus.PENDING
                }

                NodeVisualState(
                    nodeId = exec.nodeId,
                    nodeName = exec.nodeName,
                    status = state,
                    progress = if (state == NodeStatus.RUNNING) 0.5f else if (isComplete) 1f else 0f,
                    elapsedMs = if (isComplete) exec.durationMs else 0,
                    durationMs = exec.durationMs,
                    color = getNodeColor(state),
                    x = exec.position.x,
                    y = exec.position.y,
                    label = if (isComplete) "${exec.durationMs}ms" else ""
                )
            }.associateBy { it.nodeId }
        val visualization = VisualizationData(
                record = record,
                currentNodeId = node.nodeId,
                elapsedMs = node.endTime ?: node.startTime,
                progress = (currentIndex + 1).toFloat() / sortedNodes.size,
                nodeStates = nodeStates,
                connections = buildConnectionVisuals(record, nodeStates),
                statistics = calculateStatistics(record, nodeStates)
            )

            emit(visualization)

            delay((100 / speed).toLong())
            currentIndex++
        }
    }

    /**
     * 导出执行记录
     */
    suspend fun exportRecord(recordId: String, format: ExportFormat): ByteArray? = withContext(Dispatchers.IO) {
        val record = getExecutionRecord(recordId) ?: return@withContext null

        when (format) {
            ExportFormat.JSON -> exportAsJson(record)
            ExportFormat.CSV -> exportAsCsv(record)
            ExportFormat.HTML -> exportAsHtml(record)
        }
    }

    enum class ExportFormat {
        JSON, CSV, HTML
    }

    // ========== 可视化数�?API ==========

    /**
     * 获取实时可视化数�?
     */
    fun getVisualizationData(): VisualizationData? {
        return _visualizationData.value
    }

    /**
     * 获取性能分析
     */
    fun getPerformanceAnalysis(): PerformanceAnalysis? {
        return _performanceAnalysis.value
    }

    /**
     * 获取节点颜色
     */
    fun getNodeColor(status: NodeStatus): Int {
        return when (status) {
            NodeStatus.PENDING -> 0xFF9E9E9E.toInt()    // 灰色
            NodeStatus.RUNNING -> 0xFF2196F3.toInt()    // 蓝色
            NodeStatus.SUCCESS -> 0xFF4CAF50.toInt()    // 绿色
            NodeStatus.FAILED -> 0xFFF44336.toInt()     // 红色
            NodeStatus.SKIPPED -> 0xFFFF9800.toInt()   // 橙色
            NodeStatus.TIMEOUT -> 0xFF9C27B0.toInt()    // 紫色
        }
    }

    // ========== 私有方法 ==========
    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive && _isLiveMode.value) {
                updateVisualizationData()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
        private fun updateVisualizationData() {
        val record = _currentExecution.value ?: return

        val now = System.currentTimeMillis()
        val elapsedMs = now - record.startTime

        val nodeStates = record.nodeExecutions.associate { exec ->
            val isComplete = exec.endTime != null
            val isRunning = !isComplete && exec.startTime <= now

            val status = when {
                exec.status == NodeStatus.RUNNING && isRunning -> NodeStatus.RUNNING
                isComplete -> exec.status
                else -> NodeStatus.PENDING
            }
        val progress = when {
                isComplete -> 1f
                isRunning -> {
                    val nodeElapsed = now - exec.startTime
                    val estimated = exec.durationMs.takeIf { it > 0 } ?: 1000L
                    min(0.95f, nodeElapsed.toFloat() / estimated)
                }
                else -> 0f
            }

            exec.nodeId to NodeVisualState(
                nodeId = exec.nodeId,
                nodeName = exec.nodeName,
                status = status,
                progress = progress,
                elapsedMs = if (isComplete) exec.durationMs else now - exec.startTime,
                durationMs = exec.durationMs.takeIf { isComplete },
                color = getNodeColor(status),
                x = exec.position.x,
                y = exec.position.y,
                label = if (isComplete) "${exec.durationMs}ms" else if (isRunning) "..." else ""
            )
        }
        val connections = buildConnectionVisuals(record, nodeStates)
        val completedCount = nodeStates.values.count { it.status in listOf(NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED) }
        val progress = if (nodeStates.isNotEmpty()) {
            completedCount.toFloat() / nodeStates.size
        } else 0f

        val visualization = VisualizationData(
            record = record,
            currentNodeId = record.nodeExecutions.find { it.status == NodeStatus.RUNNING }?.nodeId,
            elapsedMs = elapsedMs,
            progress = progress,
            nodeStates = nodeStates,
            connections = connections,
            statistics = calculateStatistics(record, nodeStates)
        )

        _visualizationData.value = visualization
    }
        private fun buildConnectionVisuals(
        record: ExecutionRecord,
        nodeStates: Map<String, NodeVisualState>
    ): List<ConnectionVisual> {
        // 从工作流定义获取连接
    val workflow = workflowEngine.getWorkflow(record.workflowId ?: return emptyList())
            ?: return emptyList()
        return workflow.connections.map { conn ->
            val fromState = nodeStates[conn.sourceNodeId]
            val toState = nodeStates[conn.targetNodeId]

            val status = when {
                fromState?.status == NodeStatus.FAILED -> ConnectionStatus.FAILED
                toState?.status == NodeStatus.SKIPPED -> ConnectionStatus.SKIPPED
                fromState?.status == NodeStatus.SUCCESS && toState?.status == NodeStatus.SUCCESS ->
                    ConnectionStatus.COMPLETED
                fromState?.status == NodeStatus.RUNNING -> ConnectionStatus.ACTIVE
                else -> ConnectionStatus.PENDING
            }

            ConnectionVisual(
                id = conn.id,
                fromNodeId = conn.sourceNodeId,
                toNodeId = conn.targetNodeId,
                status = status,
                color = when (status) {
                    ConnectionStatus.COMPLETED -> 0xFF4CAF50.toInt()
                    ConnectionStatus.ACTIVE -> 0xFF2196F3.toInt()
                    ConnectionStatus.FAILED -> 0xFFF44336.toInt()
                    ConnectionStatus.SKIPPED -> 0xFFFF9800.toInt()
                    ConnectionStatus.PENDING -> 0xFF9E9E9E.toInt()
                }
            )
        }
    }
        private fun calculateStatistics(
        record: ExecutionRecord,
        nodeStates: Map<String, NodeVisualState>
    ): ExecutionStatistics {
        val completedNodes = record.nodeExecutions.filter { it.isComplete }
        val failedNodes = record.nodeExecutions.filter { it.status == NodeStatus.FAILED }
        val avgTime = if (completedNodes.isNotEmpty()) {
            completedNodes.map { it.durationMs }.average().toLong()
        } else 0L

        val slowest = completedNodes.maxByOrNull { it.durationMs }
        val fastest = completedNodes.minByOrNull { it.durationMs }
        val throughput = if (record.totalDurationMs > 0) {
            record.nodeExecutions.size.toFloat() / (record.totalDurationMs / 1000f)
        } else 0f

        // 估算剩余时间
    val completedTime = completedNodes.sumOf { it.durationMs }
        val remainingNodes = nodeStates.count { it.value.status == NodeStatus.PENDING || it.value.status == NodeStatus.RUNNING }
        val estimatedRemaining = if (completedNodes.isNotEmpty() && remainingNodes > 0) {
            (avgTime * remainingNodes)
        } else 0L

        return ExecutionStatistics(
            totalNodes = record.nodeExecutions.size,
            completedNodes = completedNodes.size,
            failedNodes = failedNodes.size,
            averageNodeTimeMs = avgTime,
            slowestNode = slowest,
            fastestNode = fastest,
            estimatedRemainingMs = estimatedRemaining,
            throughput = throughput
        )
    }
        private fun observeWorkflowExecution() {
        scope.launch {
            workflowEngine.executionEvents.collect { event ->
                when (event) {
                    is WorkflowEngine.ExecutionEvent.WorkflowTriggered -> {
                        // 可以在这里初始化可视�?
                    }
                    is WorkflowEngine.ExecutionEvent.NodeStarted -> {
                        // 记录节点开�?
                    }
                    is WorkflowEngine.ExecutionEvent.NodeCompleted -> {
                        // 记录节点完成
                    }
                    is WorkflowEngine.ExecutionEvent.Completed -> {
                        // 工作流完�?
                    }
                    else -> {}
                }
            }
        }
    }
        private fun generatePerformanceAnalysis(record: ExecutionRecord) {
        if (record.nodeExecutions.isEmpty()) {
            _performanceAnalysis.value = null
            return
        }
        val totalDuration = record.totalDurationMs

        // 识别瓶颈
    val bottlenecks = record.nodeExecutions
            .filter { it.isComplete && it.durationMs > 0 }
            .sortedByDescending { it.durationMs }
            .take(5)
            .map { node ->
                Bottleneck(
                    nodeId = node.nodeId,
                    nodeName = node.nodeName,
                    durationMs = node.durationMs,
                    percentageOfTotal = if (totalDuration > 0) {
                        (node.durationMs.toFloat() / totalDuration) * 100
                    } else 0f,
                    severity = when {
                        node.durationMs > totalDuration * 0.5 -> BottleneckSeverity.CRITICAL
                        node.durationMs > totalDuration * 0.3 -> BottleneckSeverity.HIGH
                        node.durationMs > totalDuration * 0.15 -> BottleneckSeverity.MEDIUM
                        else -> BottleneckSeverity.LOW
                    }
                )
            }

        // 生成建议
    val recommendations = mutableListOf<PerformanceRecommendation>()

        bottlenecks.forEach { bottleneck ->
            if (bottleneck.severity >= BottleneckSeverity.HIGH) {
                recommendations.add(
                    PerformanceRecommendation(
                        type = RecommendationType.OPTIMIZE_NODE,
                        priority = when (bottleneck.severity) {
                            BottleneckSeverity.CRITICAL -> Priority.CRITICAL
                            BottleneckSeverity.HIGH -> Priority.HIGH
                            else -> Priority.MEDIUM
                        },
                        title = "优化节点: ${bottleneck.nodeName}",
                        description = "此节点耗时 ${bottleneck.durationMs}ms，占总执行时间的 ${"%.1f".format(bottleneck.percentageOfTotal)}%",
                        expectedImprovement = "优化后可减少 ${(bottleneck.percentageOfTotal * 0.3).toInt()}% 总执行时�?
                    )
                )
            }
        }

        // 节点平均时间
    val nodeTimings = record.nodeExecutions
            .filter { it.isComplete }
            .groupBy { it.nodeId }
            .mapValues { (_, nodes) -> nodes.map { it.durationMs }.average().toLong() }

        // 时间分布
    val timeDistribution = mapOf(
            "node_execution" to record.nodeExecutions.map { it.durationMs }.sum().toFloat() / max(1, totalDuration) * 100,
            "overhead" to 5f,  // 简�?
            "waiting" to 10f
        )

        _performanceAnalysis.value = PerformanceAnalysis(
            bottlenecks = bottlenecks,
            recommendations = recommendations,
            nodeTimings = nodeTimings,
            timeDistribution = timeDistribution
        )
    }
        private fun saveToHistory(record: ExecutionRecord) {
        val history = _executionHistory.value.toMutableList()
        history.add(0, record)

        // 保持大小限制
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.size - 1)
        }

        _executionHistory.value = history

        // 持久�?
        scope.launch {
            persistRecord(record)
        }
    }
        private suspend fun persistRecord(record: ExecutionRecord) = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, HISTORY_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${record.id}.json")
            file.writeText(record.toString())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist record", e)
        }
    }
        private fun initializeHistory() {
        try {
            val dir = File(context.filesDir, HISTORY_DIR)
        if (dir.exists()) {
                val files = dir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        val records = files.mapNotNull { file ->
                    try {
                        // 简化：实际应该解析 JSON
                        null
                    } catch (e: Exception) {
                        null
                    }
                }.take(MAX_HISTORY_SIZE)

                _executionHistory.value = records
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize history", e)
        }
    }
        private fun exportAsJson(record: ExecutionRecord): ByteArray {
        return """
            {
                "id": "${record.id}",
                "skillId": "${record.skillId}",
                "skillName": "${record.skillName}",
                "status": "${record.status}",
                "startTime": ${record.startTime},
                "endTime": ${record.endTime},
                "durationMs": ${record.totalDurationMs},
                "nodeCount": ${record.nodeCount},
                "successCount": ${record.successCount},
                "failedCount": ${record.failedCount},
                "nodes": ${record.nodeExecutions.map { """
                    {
                        "nodeId": "${it.nodeId}",
                        "nodeName": "${it.nodeName}",
                        "status": "${it.status}",
                        "durationMs": ${it.durationMs}
                    }
                """ }}
            }
        """.trimIndent().toByteArray()
    }
        private fun exportAsCsv(record: ExecutionRecord): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("Node ID,Node Name,Status,Duration (ms),Start Time,End Time")
        record.nodeExecutions.forEach { node ->
            sb.appendLine("${node.nodeId},${node.nodeName},${node.status},${node.durationMs},${node.startTime},${node.endTime ?: ""}")
        }
        return sb.toString().toByteArray()
    }
        private fun exportAsHtml(record: ExecutionRecord): ByteArray {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Execution Report: ${record.skillName}</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; }
                    .header { background: #2196F3; color: white; padding: 20px; border-radius: 5px; }
                    .summary { display: flex; gap: 20px; margin: 20px 0; }
                    .card { background: #f5f5f5; padding: 15px; border-radius: 5px; flex: 1; }
                    .card h3 { margin-top: 0; color: #333; }
                    .card .value { font-size: 24px; font-weight: bold; color: #2196F3; }
                    table { width: 100%; border-collapse: collapse; margin-top: 20px; }
                    th, td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }
                    th { background: #2196F3; color: white; }
                    .success { color: #4CAF50; }
                    .failed { color: #F44336; }
                    .running { color: #2196F3; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>${record.skillName}</h1>
                    <p>Execution ID: ${record.id}</p>
                    <p>${dateFormat.format(Date(record.startTime))}</p>
                </div>

                <div class="summary">
                    <div class="card">
                        <h3>Duration</h3>
                        <div class="value">${record.totalDurationMs}ms</div>
                    </div>
                    <div class="card">
                        <h3>Nodes</h3>
                        <div class="value">${record.nodeCount}</div>
                    </div>
                    <div class="card">
                        <h3>Status</h3>
                        <div class="value ${record.status.name.lowercase()}">${record.status.name}</div>
                    </div>
                </div>

                <h2>Node Executions</h2>
                <table>
                    <tr>
                        <th>Node</th>
                        <th>Type</th>
                        <th>Status</th>
                        <th>Duration</th>
                    </tr>
                    ${record.nodeExecutions.joinToString("\n") { node ->
                        """<tr>
                            <td>${node.nodeName}</td>
                            <td>${node.nodeType}</td>
                            <td class="${node.status.name.lowercase()}">${node.status.name}</td>
                            <td>${node.durationMs}ms</td>
                        </tr>"""
                    }}
                </table>
            </body>
            </html>
        """.trimIndent().toByteArray()
    }
        private fun generateId(): String = "exec_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    // ========== 工具方法 ==========
    fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> "${ms / 1000}s"
            else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        }
    }
        fun getProgressPercentage(progress: Float): String {
        return "${(progress * 100).toInt()}%"
    }
}
