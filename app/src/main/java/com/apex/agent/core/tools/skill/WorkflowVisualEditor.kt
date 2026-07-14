package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 工作流可视化编辑�?
 *
 * 功能�?
 * - 可视化拖拽设�?
 * - 节点编辑
 * - 连接线管�?
 * - 缩放和平�?
 * - 导出/导入
 * - 实时预览
 */
class WorkflowVisualEditor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowVisualEditor"
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 2.0f
        private const val GRID_SIZE = 20f

        @Volatile private var INSTANCE: WorkflowVisualEditor? = null

        fun getInstance(context: Context): WorkflowVisualEditor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkflowVisualEditor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    @Serializable
    data class EditorState(
        val workflow: WorkflowDefinition,
        val selectedNodeId: String? = null,
        val selectedConnectionId: String? = null,
        val zoom: Float = 1.0f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val showGrid: Boolean = true,
        val showMinimap: Boolean = true,
        val isModified: Boolean = false,
        val undoStack: List<EditorAction> = emptyList(),
        val redoStack: List<EditorAction> = emptyList()
    )

    @Serializable
    data class EditorAction(
        val type: ActionType,
        val nodeId: String? = null,
        val connectionId: String? = null,
        val before: String? = null,
        val after: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    enum class ActionType {
        ADD_NODE,
        DELETE_NODE,
        MOVE_NODE,
        UPDATE_NODE,
        ADD_CONNECTION,
        DELETE_CONNECTION,
        UPDATE_CONNECTION,
        PASTE,
        UNDO,
        REDO
    }

    @Serializable
    data class NodeTemplate(
        val type: NodeType,
        val name: String,
        val description: String,
        val icon: String,
        val defaultConfig: Map<String, String>,
        val outputPorts: Int = 1,
        val inputPorts: Int = 1,
        val category: NodeTemplateCategory = NodeTemplateCategory.BASIC
    )

    @Serializable
    enum class NodeTemplateCategory {
        BASIC,
        TRIGGER,
        ACTION,
        LOGIC,
        DATA,
        FLOW_CONTROL
    }

    @Serializable
    data class ConnectionPoint(
        val nodeId: String,
        val portIndex: Int,
        val isOutput: Boolean,
        val positionX: Float,
        val positionY: Float
    )

    @Serializable
    data class DragState(
        val isDragging: Boolean = false,
        val draggedNodeId: String? = null,
        val draggedConnectionId: String? = null,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val currentX: Float = 0f,
        val currentY: Float = 0f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    @Serializable
    data class SelectionBox(
        val isSelecting: Boolean = false,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 0f,
        val endY: Float = 0f
    )

    data class ValidationIssue(
        val severity: IssueSeverity,
        val message: String,
        val nodeId: String? = null,
        val connectionId: String? = null
    )

    enum class IssueSeverity {
        ERROR,
        WARNING,
        INFO
    }

    // ========== 状�?==========
    private val _editorState = MutableStateFlow<EditorState?>(null)
    val editorState: StateFlow<EditorState?> = _editorState.asStateFlow()

    private val _nodeTemplates = MutableStateFlow<List<NodeTemplate>>(emptyList())
    val nodeTemplates: StateFlow<List<NodeTemplate>> = _nodeTemplates.asStateFlow()

    private val _validationIssues = MutableStateFlow<List<ValidationIssue>>(emptyList())
    val validationIssues: StateFlow<List<ValidationIssue>> = _validationIssues.asStateFlow()

    private val _dragState = MutableStateFlow(DragState())
    val dragState: StateFlow<DragState> = _dragState.asStateFlow()

    private val _selectionBox = MutableStateFlow(SelectionBox())
    val selectionBox: StateFlow<SelectionBox> = _selectionBox.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _executionLogs = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val executionLogs: StateFlow<List<ExecutionLogEntry>> = _executionLogs.asStateFlow()

    data class ExecutionLogEntry(
        val timestamp: Long,
        val nodeId: String,
        val nodeName: String,
        val status: ExecutionStatus,
        val message: String,
        val durationMs: Long? = null
    )

    enum class ExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private val workflowEngine by lazy { WorkflowEngine.getInstance() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        initializeNodeTemplates()
    }

    // ========== 节点模板 ==========
    private fun initializeNodeTemplates() {
        _nodeTemplates.value = listOf(
            // Trigger 节点
            NodeTemplate(
                type = NodeType.TRIGGER,
                name = "触发�?,
                description = "工作流入口点",
                icon = "▶️",
                defaultConfig = mapOf("triggerType" to "MANUAL"),
                outputPorts = 1,
                inputPorts = 0,
                category = NodeTemplateCategory.TRIGGER
            ),

            // 执行节点
            NodeTemplate(
                type = NodeType.EXECUTE,
                name = "执行动作",
                description = "执行特定的操作或工具",
                icon = "�?,
                defaultConfig = mapOf("actionType" to "log"),
                outputPorts = 1,
                inputPorts = 1,
                category = NodeTemplateCategory.ACTION
            ),

            // 条件节点
            NodeTemplate(
                type = NodeType.CONDITION,
                name = "条件判断",
                description = "根据条件选择分支",
                icon = "�?,
                defaultConfig = mapOf("operator" to "EQ", "left" to "", "right" to ""),
                outputPorts = 2,
                inputPorts = 1,
                category = NodeTemplateCategory.LOGIC
            ),

            // 逻辑节点
            NodeTemplate(
                type = NodeType.LOGIC,
                name = "逻辑运算",
                description = "AND/OR 逻辑运算",
                icon = "🔀",
                defaultConfig = mapOf("operator" to "AND"),
                outputPorts = 1,
                inputPorts = 2,
                category = NodeTemplateCategory.LOGIC
            ),

            // 提取节点
            NodeTemplate(
                type = NodeType.EXTRACT,
                name = "数据提取",
                description = "从数据中提取信息",
                icon = "🔍",
                defaultConfig = mapOf("mode" to "REGEX", "expression" to "", "source" to ""),
                outputPorts = 1,
                inputPorts = 1,
                category = NodeTemplateCategory.DATA
            )
        )
    }

    // ========== 公开 API ==========

    /**
     * 创建新工作流
     */
    fun createNewWorkflow(name: String = "New Workflow"): EditorState {
        val workflow = WorkflowDefinition(
            id = "wf_${System.currentTimeMillis()}",
            name = name,
            description = "",
            nodes = emptyList(),
            connections = emptyList()
        )

        val state = EditorState(workflow = workflow)
        _editorState.value = state
        return state
    }

    /**
     * 加载现有工作�?
     */
    fun loadWorkflow(workflow: WorkflowDefinition): EditorState {
        val state = EditorState(workflow = workflow)
        _editorState.value = state
        validateWorkflow()
        return state
    }

    /**
     * �?JSON 加载工作�?
     */
    fun loadFromJson(jsonString: String): EditorState? {
        return try {
            val workflow = Json.decodeFromString<WorkflowDefinition>(jsonString)
            loadWorkflow(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load workflow from JSON", e)
            null
        }
    }

    /**
     * 添加节点
     */
    fun addNode(
        type: NodeType,
        x: Float,
        y: Float,
        name: String? = null
    ): WorkflowNode? {
        val state = _editorState.value ?: return null
        val template = _nodeTemplates.value.find { it.type == type } ?: return null

        val nodeId = WorkflowNode.generateNodeId()
        val nodeName = name ?: "${template.name}_${state.workflow.nodes.size + 1}"

        val config = when (type) {
            NodeType.TRIGGER -> NodeConfig(
                triggerConfig = TriggerConfig(TriggerType.valueOf(template.defaultConfig["triggerType"] ?: "MANUAL"))
            )
            NodeType.EXECUTE -> NodeConfig(
                actionType = template.defaultConfig["actionType"]
            )
            NodeType.CONDITION -> NodeConfig(
                operator = template.defaultConfig["operator"],
                left = ParameterValue.StaticValue(template.defaultConfig["left"] ?: ""),
                right = ParameterValue.StaticValue(template.defaultConfig["right"] ?: "")
            )
            NodeType.LOGIC -> NodeConfig(
                operator = template.defaultConfig["operator"]
            )
            NodeType.EXTRACT -> NodeConfig(
                mode = ExtractMode.valueOf(template.defaultConfig["mode"] ?: "REGEX"),
                expression = template.defaultConfig["expression"],
                source = ParameterValue.StaticValue(template.defaultConfig["source"] ?: "")
            )
        }

        val newNode = WorkflowNode(
            id = nodeId,
            name = nodeName,
            type = type,
            position = NodePosition(x, y),
            config = config
        )

        val updatedWorkflow = state.workflow.copy(
            nodes = state.workflow.nodes + newNode
        )

        // 记录撤销操作
    val action = EditorAction(
            type = ActionType.ADD_NODE,
            nodeId = nodeId,
            after = Json.encodeToString(newNode)
        )

        _editorState.value = state.copy(
            workflow = updatedWorkflow,
            isModified = true,
            undoStack = state.undoStack + action,
            redoStack = emptyList(),
            selectedNodeId = nodeId
        )

        validateWorkflow()
        return newNode
    }

    /**
     * 删除节点
     */
    fun deleteNode(nodeId: String): Boolean {
        val state = _editorState.value ?: return false

        val node = state.workflow.nodes.find { it.id == nodeId } ?: return false

        // 删除节点及其连接
    val updatedNodes = state.workflow.nodes.filter { it.id != nodeId }
        val updatedConnections = state.workflow.connections.filter {
            it.sourceNodeId != nodeId && it.targetNodeId != nodeId
        }

        val action = EditorAction(
            type = ActionType.DELETE_NODE,
            nodeId = nodeId,
            before = Json.encodeToString(node)
        )

        _editorState.value = state.copy(
            workflow = state.workflow.copy(
                nodes = updatedNodes,
                connections = updatedConnections
            ),
            isModified = true,
            undoStack = state.undoStack + action,
            redoStack = emptyList(),
            selectedNodeId = if (state.selectedNodeId == nodeId) null else state.selectedNodeId
        )

        validateWorkflow()
        return true
    }

    /**
     * 移动节点
     */
    fun moveNode(nodeId: String, newX: Float, newY: Float) {
        val state = _editorState.value ?: return

        val updatedNodes = state.workflow.nodes.map { node ->
            if (node.id == nodeId) {
                node.copy(position = NodePosition(newX, newY))
            } else node
        }

        _editorState.value = state.copy(
            workflow = state.workflow.copy(nodes = updatedNodes),
            isModified = true
        )
    }

    /**
     * 更新节点配置
     */
    fun updateNodeConfig(nodeId: String, config: NodeConfig) {
        val state = _editorState.value ?: return

        val updatedNodes = state.workflow.nodes.map { node ->
            if (node.id == nodeId) {
                node.copy(config = config)
            } else node
        }

        val action = EditorAction(
            type = ActionType.UPDATE_NODE,
            nodeId = nodeId,
            before = Json.encodeToString(state.workflow.nodes.find { it.id == nodeId }),
            after = Json.encodeToString(updatedNodes.find { it.id == nodeId })
        )

        _editorState.value = state.copy(
            workflow = state.workflow.copy(nodes = updatedNodes),
            isModified = true,
            undoStack = state.undoStack + action,
            redoStack = emptyList()
        )

        validateWorkflow()
    }

    /**
     * 添加连接
     */
    fun addConnection(
        sourceNodeId: String,
        targetNodeId: String,
        condition: ConnectionCondition = ConnectionCondition.OnSuccess
    ): WorkflowConnection? {
        val state = _editorState.value ?: return null

        // 验证节点存在
    val sourceNode = state.workflow.nodes.find { it.id == sourceNodeId } ?: return null
        val targetNode = state.workflow.nodes.find { it.id == targetNodeId } ?: return null

        // 验证不会创建循环（简单检查）
    if (wouldCreateCycle(state.workflow, sourceNodeId, targetNodeId)) {
            AppLogger.w(TAG, "Connection would create a cycle")
            return null
        }

        val connection = WorkflowConnection(
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            condition = condition
        )

        val action = EditorAction(
            type = ActionType.ADD_CONNECTION,
            connectionId = connection.id,
            after = Json.encodeToString(connection)
        )

        _editorState.value = state.copy(
            workflow = state.workflow.copy(
                connections = state.workflow.connections + connection
            ),
            isModified = true,
            undoStack = state.undoStack + action,
            redoStack = emptyList(),
            selectedConnectionId = connection.id
        )

        validateWorkflow()
        return connection
    }

    /**
     * 删除连接
     */
    fun deleteConnection(connectionId: String): Boolean {
        val state = _editorState.value ?: return false

        val connection = state.workflow.connections.find { it.id == connectionId } ?: return false

        val action = EditorAction(
            type = ActionType.DELETE_CONNECTION,
            connectionId = connectionId,
            before = Json.encodeToString(connection)
        )

        _editorState.value = state.copy(
            workflow = state.workflow.copy(
                connections = state.workflow.connections.filter { it.id != connectionId }
            ),
            isModified = true,
            undoStack = state.undoStack + action,
            redoStack = emptyList(),
            selectedConnectionId = if (state.selectedConnectionId == connectionId) null else state.selectedConnectionId
        )

        validateWorkflow()
        return true
    }

    /**
     * 撤销
     */
    fun undo(): Boolean {
        val state = _editorState.value ?: return false
        if (state.undoStack.isEmpty()) return false

        val action = state.undoStack.last()
        val newUndoStack = state.undoStack.dropLast(1)

        // 根据操作类型恢复状�?
    val updatedWorkflow = when (action.type) {
            ActionType.ADD_NODE -> {
                val nodeId = action.nodeId ?: return false
                state.workflow.copy(
                    nodes = state.workflow.nodes.filter { it.id != nodeId }
                )
            }
            ActionType.DELETE_NODE -> {
                val node = action.before?.let { Json.decodeFromString<WorkflowNode>(it) } ?: return false
                state.workflow.copy(
                    nodes = state.workflow.nodes + node
                )
            }
            ActionType.MOVE_NODE -> state.workflow // 需要更复杂的处�?
            ActionType.UPDATE_NODE -> {
                val nodeId = action.nodeId ?: return false
                val beforeNode = action.before?.let { Json.decodeFromString<WorkflowNode>(it) } ?: return false
                state.workflow.copy(
                    nodes = state.workflow.nodes.map { if (it.id == nodeId) beforeNode else it }
                )
            }
            ActionType.ADD_CONNECTION -> {
                val connId = action.connectionId ?: return false
                state.workflow.copy(
                    connections = state.workflow.connections.filter { it.id != connId }
                )
            }
            ActionType.DELETE_CONNECTION -> {
                val conn = action.before?.let { Json.decodeFromString<WorkflowConnection>(it) } ?: return false
                state.workflow.copy(
                    connections = state.workflow.connections + conn
                )
            }
            else -> state.workflow
        }

        _editorState.value = state.copy(
            workflow = updatedWorkflow,
            undoStack = newUndoStack,
            redoStack = state.redoStack + action.copy(type = ActionType.UNDO)
        )

        validateWorkflow()
        return true
    }

    /**
     * 重做
     */
    fun redo(): Boolean {
        val state = _editorState.value ?: return false
        if (state.redoStack.isEmpty()) return false

        val action = state.redoStack.last()
        val newRedoStack = state.redoStack.dropLast(1)

        // 根据操作类型恢复状�?
    val updatedWorkflow = when (action.type) {
            ActionType.UNDO -> {
                // 执行相反的操�?
    when (action.type) {
                    ActionType.ADD_NODE -> {
                        val nodeId = action.nodeId ?: return false
                        state.workflow.copy(
                            nodes = state.workflow.nodes.filter { it.id != nodeId }
                        )
                    }
                    ActionType.DELETE_NODE -> {
                        val node = action.before?.let { Json.decodeFromString<WorkflowNode>(it) } ?: return false
                        state.workflow.copy(
                            nodes = state.workflow.nodes + node
                        )
                    }
                    else -> state.workflow
                }
            }
            else -> state.workflow
        }

        _editorState.value = state.copy(
            workflow = updatedWorkflow,
            undoStack = state.undoStack + action,
            redoStack = newRedoStack
        )

        validateWorkflow()
        return true
    }

    /**
     * 选择节点
     */
    fun selectNode(nodeId: String) {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            selectedNodeId = nodeId,
            selectedConnectionId = null
        )
    }

    /**
     * 选择连接
     */
    fun selectConnection(connectionId: String) {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            selectedNodeId = null,
            selectedConnectionId = connectionId
        )
    }

    /**
     * 清空选择
     */
    fun clearSelection() {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            selectedNodeId = null,
            selectedConnectionId = null
        )
    }

    /**
     * 全�?
     */
    fun selectAll() {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            selectedNodeId = state.workflow.nodes.firstOrNull()?.id
        )
    }

    /**
     * 删除选中
     */
    fun deleteSelected(): Boolean {
        val state = _editorState.value ?: return false

        var deleted = false

        state.selectedNodeId?.let { nodeId ->
            if (deleteNode(nodeId)) deleted = true
        }

        state.selectedConnectionId?.let { connId ->
            if (deleteConnection(connId)) deleted = true
        }

        return deleted
    }

    /**
     * 缩放
     */
    fun setZoom(zoom: Float) {
        val state = _editorState.value ?: return
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        _editorState.value = state.copy(zoom = clampedZoom)
    }

    /**
     * 平移
     */
    fun pan(deltaX: Float, deltaY: Float) {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            panX = state.panX + deltaX,
            panY = state.panY + deltaY
        )
    }

    /**
     * 重置视图
     */
    fun resetView() {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(
            zoom = 1.0f,
            panX = 0f,
            panY = 0f
        )
    }

    /**
     * 自动布局
     */
    fun autoLayout() {
        val state = _editorState.value ?: return

        // 简单的分层布局算法
    val layers = mutableMapOf<String, Int>()
        val positioned = mutableSetOf<String>()

        // 找出入口节点（没有入边的节点�?
    val entryNodes = state.workflow.nodes.filter { node ->
            state.workflow.connections.none { it.targetNodeId == node.id }
        }

        if (entryNodes.isEmpty()) {
            // 如果没有明确的入口，选择第一个节�?
            entryNodes.firstOrNull()?.let {
                layers[it.id] = 0
                positioned.add(it.id)
            }
        } else {
            entryNodes.forEach { layers[it.id] = 0 }
            positioned.addAll(entryNodes.map { it.id })
        }

        // BFS 分层
    var currentLayer = 0
        while (positioned.size < state.workflow.nodes.size) {
            val nodesInLayer = state.workflow.nodes.filter { layers[it.id] == currentLayer }

            for (node in nodesInLayer) {
                val outgoing = state.workflow.connections.filter { it.sourceNodeId == node.id }
                for (conn in outgoing) {
                    if (conn.targetNodeId !in positioned) {
                        layers[conn.targetNodeId] = currentLayer + 1
                        positioned.add(conn.targetNodeId)
                    }
                }
            }

            currentLayer++
            if (currentLayer > 100) break // 防止无限循环
        }

        // 根据层级定位节点
    val layerNodes = state.workflow.nodes.groupBy { layers[it.id] ?: 0 }
        val updatedNodes = state.workflow.nodes.map { node ->
            val layer = layers[node.id] ?: 0
            val indexInLayer = layerNodes[layer]?.indexOf(node) ?: 0
            val nodesInSameLayer = layerNodes[layer]?.size ?: 1

            val x = 100f + layer * 250f
            val y = 100f + (indexInLayer - nodesInSameLayer / 2f) * 120f

            node.copy(position = NodePosition(x, y))
        }

        _editorState.value = state.copy(
            workflow = state.workflow.copy(nodes = updatedNodes),
            isModified = true
        )
    }

    /**
     * 验证工作�?
     */
    fun validateWorkflow(): List<ValidationIssue> {
        val state = _editorState.value ?: return emptyList()
        val issues = mutableListOf<ValidationIssue>()

        // 检查是否有节点
    if (state.workflow.nodes.isEmpty()) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                message = "Workflow has no nodes",
                nodeId = null
            ))
        }

        // 检查每个节�?
    for (node in state.workflow.nodes) {
            // 验证触发�?
    if (node.type == NodeType.TRIGGER) {
                if (node.config.triggerConfig == null) {
                    issues.add(ValidationIssue(
                        severity = IssueSeverity.ERROR,
                        message = "Trigger node missing triggerConfig",
                        nodeId = node.id
                    ))
                }
            }

            // 验证执行节点
    if (node.type == NodeType.EXECUTE) {
                if (node.config.actionType.isNullOrBlank()) {
                    issues.add(ValidationIssue(
                        severity = IssueSeverity.ERROR,
                        message = "Execute node missing actionType",
                        nodeId = node.id
                    ))
                }
            }

            // 检查孤立的节点
    val hasIncoming = state.workflow.connections.any { it.targetNodeId == node.id }
            val hasOutgoing = state.workflow.connections.any { it.sourceNodeId == node.id }
            val isEntry = node.type == NodeType.TRIGGER

            if (!hasIncoming && !isEntry && state.workflow.nodes.size > 1) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    message = "Node is not connected",
                    nodeId = node.id
                ))
            }
        }

        // 检查连�?
    for (conn in state.workflow.connections) {
            val sourceExists = state.workflow.nodes.any { it.id == conn.sourceNodeId }
            val targetExists = state.workflow.nodes.any { it.id == conn.targetNodeId }

            if (!sourceExists || !targetExists) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    message = "Connection references non-existent node",
                    connectionId = conn.id
                ))
            }
        }

        // 检查循�?
    if (hasCycle(state.workflow)) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                message = "Workflow contains a cycle (might cause infinite loop)",
                nodeId = null
            ))
        }

        _validationIssues.value = issues
        return issues
    }

    /**
     * 导出�?JSON
     */
    fun exportToJson(): String? {
        val state = _editorState.value ?: return null
        return try {
            json.encodeToString(state.workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export workflow", e)
            null
        }
    }

    /**
     * 导出为图片（占位，实际需�?Canvas 绑定�?
     */
fun exportToImage(): ByteArray? {
        val state = _editorState.value ?: return null
        val workflow = state.workflow

        // 生成 SVG 作为图片导出的简化实现。
        // 真正的 Canvas/Compose 导出需要 AndroidBitmap + Canvas API，
        // 但由于 VisualEditor 本身不持有 Canvas 引用，
        // 我们改为生成结构清晰的 SVG，业务侧可以再转 PNG。
        //
        // WorkflowDefinition.nodes/edges 是 List<Any>，
        // 我们通过反射读取常用字段（id/name/type/sourceId/targetId），
        // 任何字段缺失都使用空串占位，保证导出不抛异常。
    return try {
            val svg = buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"800\" viewBox=\"0 0 1200 800\">")
                appendLine("  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>")
                appendLine("  <text x=\"20\" y=\"30\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#111827\">${escapeXml(workflow.name)}</text>")

                // 节点：按网格位置渲染
                workflow.nodes.forEachIndexed { i, node ->
                    val x = 40 + (i % 6) * 180
                    val y = 80 + (i / 6) * 140
                    val label = reflectStringField(node, "label") ?: reflectStringField(node, "name") ?: "node_$i"
                    val typeStr = reflectStringField(node, "type") ?: ""
                    val fill = if (typeStr.contains("START") || typeStr.contains("END")) "#dbeafe" else "#ffffff"
                    appendLine("  <rect x=\"$x\" y=\"$y\" width=\"160\" height=\"80\" rx=\"8\" fill=\"$fill\" stroke=\"#94a3b8\" stroke-width=\"1\"/>")
                    appendLine("  <text x=\"${x + 10}\" y=\"${y + 30}\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"600\" fill=\"#1f2937\">${escapeXml(label)}</text>")
                    appendLine("  <text x=\"${x + 10}\" y=\"${y + 50}\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#64748b\">${escapeXml(typeStr)}</text>")
                }

                // 边：从 source 到 target 画曲线
                workflow.edges.forEach { edge ->
                    val srcId = reflectStringField(edge, "sourceId") ?: reflectStringField(edge, "from")
                    val tgtId = reflectStringField(edge, "targetId") ?: reflectStringField(edge, "to")
                    if (srcId != null && tgtId != null) {
                        val srcIdx = workflow.nodes.indexOfFirst { reflectStringField(it, "id") == srcId }
                        val tgtIdx = workflow.nodes.indexOfFirst { reflectStringField(it, "id") == tgtId }
                        if (srcIdx >= 0 && tgtIdx >= 0) {
                            val sx = 40 + (srcIdx % 6) * 180 + 160
                            val sy = 80 + (srcIdx / 6) * 140 + 40
                            val tx = 40 + (tgtIdx % 6) * 180
                            val ty = 80 + (tgtIdx / 6) * 140 + 40
                            appendLine("  <path d=\"M$sx,$sy C${sx + 40},$sy ${tx - 40},$ty $tx,$ty\" stroke=\"#64748b\" stroke-width=\"1.5\" fill=\"none\"/>")
                        }
                    }
                }

                appendLine("</svg>")
            }
            svg.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export workflow to image", e)
            null
        }
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun reflectStringField(obj: Any?, fieldName: String): String? {
        if (obj == null) return null
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 执行工作�?
     */
    suspend fun executeWorkflow(): ExecutionResult? {
        val state = _editorState.value ?: return null
        val validationIssues = validateWorkflow()

        if (validationIssues.any { it.severity == IssueSeverity.ERROR }) {
            return ExecutionResult(
                success = false,
                message = "Cannot execute workflow with errors",
                nodeResults = emptyMap()
            )
        }

        _isExecuting.value = true
        _executionLogs.value = emptyList()

        try {
            val workflow = state.workflow
            val result = workflowEngine.executeWorkflow(workflow.id)

            // 记录执行日志
            result?.nodeResults?.forEach { (nodeId, nodeResult) ->
                val node = workflow.nodes.find { it.id == nodeId }
                _executionLogs.value = _executionLogs.value + ExecutionLogEntry(
                    timestamp = System.currentTimeMillis(),
                    nodeId = nodeId,
                    nodeName = node?.name ?: "Unknown",
                    status = if (nodeResult.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                    message = if (nodeResult.success) "Node executed successfully" else (nodeResult.errorMessage ?: "Node failed"),
                    durationMs = nodeResult.executionTimeMs
                )
            }

            return result?.let {
                ExecutionResult(
                    success = it.success,
                    message = if (it.success) "Workflow executed successfully" else "Workflow execution failed",
                    nodeResults = it.nodeResults,
                    totalTimeMs = it.totalExecutionTimeMs
                )
            } ?: ExecutionResult(
                success = false,
                message = "Workflow execution returned null",
                nodeResults = emptyMap()
            )
        } finally {
            _isExecuting.value = false
        }
    }

    /**
     * 停止执行
     */
    fun stopExecution() {
        _isExecuting.value = false
    }

    // ========== 私有方法 ==========
    private fun wouldCreateCycle(workflow: WorkflowDefinition, sourceId: String, targetId: String): Boolean {
        // 简单检查：�?target 能否到达 source
    val visited = mutableSetOf<String>()
        fun canReach(from: String, to: String): Boolean {
            if (from == to) return true
            if (from in visited) return false
            visited.add(from)

            val outgoing = workflow.connections.filter { it.sourceNodeId == from }
            return outgoing.any { canReach(it.targetNodeId, to) }
        }

        return canReach(targetId, sourceId)
    }

    private fun hasCycle(workflow: WorkflowDefinition): Boolean {
        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()

        fun hasCycleUtil(nodeId: String): Boolean {
            visited.add(nodeId)
            recStack.add(nodeId)

            val outgoing = workflow.connections.filter { it.sourceNodeId == nodeId }
            for (conn in outgoing) {
                if (conn.targetNodeId !in visited) {
                    if (hasCycleUtil(conn.targetNodeId)) return true
                } else if (conn.targetNodeId in recStack) {
                    return true
                }
            }

            recStack.remove(nodeId)
            return false
        }

        for (node in workflow.nodes) {
            if (node.id !in visited) {
                if (hasCycleUtil(node.id)) return true
            }
        }

        return false
    }

    // ========== 工具方法 ==========
    fun getNodeTemplate(type: NodeType): NodeTemplate? {
        return _nodeTemplates.value.find { it.type == type }
    }

    fun getNodeColor(type: NodeType): Int {
        return when (type) {
            NodeType.TRIGGER -> 0xFF4CAF50.toInt() // 绿色
            NodeType.EXECUTE -> 0xFF2196F3.toInt() // 蓝色
            NodeType.CONDITION -> 0xFFFF9800.toInt() // 橙色
            NodeType.LOGIC -> 0xFF9C27B0.toInt() // 紫色
            NodeType.EXTRACT -> 0xFF00BCD4.toInt() // 青色
        }
    }

    fun snapToGrid(value: Float): Float {
        return (value / GRID_SIZE).toInt() * GRID_SIZE
    }

    // ========== 数据�?==========

    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val nodeResults: Map<String, WorkflowEngine.NodeResult>,
        val totalTimeMs: Long = 0
    )
}
