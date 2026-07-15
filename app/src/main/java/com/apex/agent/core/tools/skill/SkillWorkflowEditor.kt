package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class SkillWorkflowEditor private constructor() {

    companion object {
        private const val TAG = "SkillWorkflowEditor"
        private const val MAX_UNDO_STACK_SIZE = 50

        @Volatile private var INSTANCE: SkillWorkflowEditor? = null

        fun getInstance(): SkillWorkflowEditor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillWorkflowEditor().also { INSTANCE = it }
            }
        }
    }

    sealed class EditorEvent {
        data class WorkflowCreated(val workflow: WorkflowDefinition) : EditorEvent()
        data class WorkflowUpdated(val workflow: WorkflowDefinition) : EditorEvent()
        data class WorkflowDeleted(val workflowId: String) : EditorEvent()
        data class NodeAdded(val workflowId: String, val node: WorkflowNode) : EditorEvent()
        data class NodeUpdated(val workflowId: String, val node: WorkflowNode) : EditorEvent()
        data class NodeRemoved(val workflowId: String, val nodeId: String) : EditorEvent()
        data class ConnectionAdded(val workflowId: String, val connection: WorkflowConnection) : EditorEvent()
        data class ConnectionRemoved(val workflowId: String, val connectionId: String) : EditorEvent()
        data class NodeMoved(val workflowId: String, val nodeId: String, val newPosition: NodePosition) : EditorEvent()
        data class UndoPerformed(val workflowId: String, val action: EditorAction) : EditorEvent()
        data class RedoPerformed(val workflowId: String, val action: EditorAction) : EditorEvent()
    }

    sealed class EditorAction {
        abstract val workflowId: String
        abstract val timestamp: Long

        data class AddNode(override val workflowId: String, val node: WorkflowNode) : EditorAction() {
            override val timestamp: Long = System.currentTimeMillis()
        }
        data class RemoveNode(override val workflowId: String, val node: WorkflowNode) : EditorAction()
        data class UpdateNode(override val workflowId: String, val oldNode: WorkflowNode, val newNode: WorkflowNode) : EditorAction()
        data class MoveNode(override val workflowId: String, val nodeId: String, val oldPosition: NodePosition, val newPosition: NodePosition) : EditorAction()
        data class AddConnection(override val workflowId: String, val connection: WorkflowConnection) : EditorAction()
        data class RemoveConnection(override val workflowId: String, val connection: WorkflowConnection) : EditorAction()
        data class UpdateWorkflow(override val workflowId: String, val oldWorkflow: WorkflowDefinition, val newWorkflow: WorkflowDefinition) : EditorAction()
    }

    data class EditorState(
        val currentWorkflowId: String? = null,
        val selectedNodeId: String? = null,
        val selectedConnectionId: String? = null,
        val isDirty: Boolean = false,
        val zoomLevel: Float = 1.0f,
        val panOffset: NodePosition = NodePosition()
    )
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutex = Mutex()
        private val workflows = ConcurrentHashMap<String, WorkflowDefinition>()
        private val editorStates = ConcurrentHashMap<String, EditorState>()
        private val undoStacks = ConcurrentHashMap<String, MutableList<EditorAction>>()
        private val redoStacks = ConcurrentHashMap<String, MutableList<EditorAction>>()
        private val _editorState = MutableStateFlow(EditorState())
        val editorState: StateFlow<EditorState> = _editorState.asStateFlow()
        private val _editorEvents = MutableSharedFlow<EditorEvent>()
        val editorEvents: SharedFlow<EditorEvent> = _editorEvents.asSharedFlow()
        private val _workflowsFlow = MutableStateFlow<List<WorkflowDefinition>>(emptyList())
        val workflowsFlow: StateFlow<List<WorkflowDefinition>> = _workflowsFlow.asStateFlow()
        private val json = Json { prettyPrint = true; encodeDefaults = true }
        fun createWorkflow(name: String, description: String = ""): WorkflowDefinition {
        val workflow = WorkflowDefinition(
            name = name,
            description = description,
            nodes = emptyList(),
            connections = emptyList()
        )

        workflows[workflow.id] = workflow
        undoStacks[workflow.id] = mutableListOf()
        redoStacks[workflow.id] = mutableListOf()

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.WorkflowCreated(workflow))
        }

        AppLogger.i(TAG, "Workflow created: ${workflow.name} [${workflow.id}]")
        return workflow
    }
        fun loadWorkflow(workflow: WorkflowDefinition) {
        workflows[workflow.id] = workflow
        undoStacks.getOrPut(workflow.id) { mutableListOf() }.clear()
        redoStacks.getOrPut(workflow.id) { mutableListOf() }.clear()
        updateWorkflowsFlow()
        AppLogger.d(TAG, "Workflow loaded: ${workflow.name} [${workflow.id}]")
    }
        fun deleteWorkflow(workflowId: String): Boolean {
        val removed = workflows.remove(workflowId) ?: return false
        editorStates.remove(workflowId)
        undoStacks.remove(workflowId)
        redoStacks.remove(workflowId)

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.WorkflowDeleted(workflowId))
        }

        AppLogger.i(TAG, "Workflow deleted: ${workflowId}")
        return true
    }
        fun getWorkflow(workflowId: String): WorkflowDefinition? = workflows[workflowId]

    fun getAllWorkflows(): List<WorkflowDefinition> = workflows.values.toList()
        fun updateWorkflowMetadata(workflowId: String, name: String? = null, description: String? = null, enabled: Boolean? = null): WorkflowDefinition? {
        val workflow = workflows[workflowId] ?: return null
        val oldWorkflow = workflow

        val updatedWorkflow = workflow.copy(
            name = name ?: workflow.name,
            description = description ?: workflow.description,
            enabled = enabled ?: workflow.enabled,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.UpdateWorkflow(workflowId, oldWorkflow, updatedWorkflow))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.WorkflowUpdated(updatedWorkflow))
        }

        AppLogger.d(TAG, "Workflow metadata updated: ${workflowId}")
        return updatedWorkflow
    }
        fun addNode(workflowId: String, node: WorkflowNode): WorkflowNode? {
        val workflow = workflows[workflowId] ?: return null

        val updatedWorkflow = workflow.copy(
            nodes = workflow.nodes + node,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.AddNode(workflowId, node))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.NodeAdded(workflowId, node))
        }

        AppLogger.d(TAG, "Node added to workflow ${workflowId}: ${node.name} [${node.id}]")
        return node
    }
        fun createAndAddNode(
        workflowId: String,
        name: String,
        type: NodeType,
        position: NodePosition = NodePosition(),
        config: NodeConfig = NodeConfig()
    ): WorkflowNode? {
        val node = WorkflowNode(
            name = name,
            type = type,
            position = position,
            config = config
        )
        return addNode(workflowId, node)
    }
        fun updateNode(workflowId: String, nodeId: String, updates: (NodeConfig) -> NodeConfig): WorkflowNode? {
        val workflow = workflows[workflowId] ?: return null
        val oldNode = workflow.getNodeById(nodeId) ?: return null

        val newConfig = updates(oldNode.config)
        val newNode = oldNode.copy(config = newConfig)
        val updatedNodes = workflow.nodes.map { if (it.id == nodeId) newNode else it }
        val updatedWorkflow = workflow.copy(
            nodes = updatedNodes,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.UpdateNode(workflowId, oldNode, newNode))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.NodeUpdated(workflowId, newNode))
        }

        AppLogger.d(TAG, "Node updated in workflow ${workflowId}: ${newNode.name} [${newNode.id}]")
        return newNode
    }
        fun removeNode(workflowId: String, nodeId: String): Boolean {
        val workflow = workflows[workflowId] ?: return false
        val node = workflow.getNodeById(nodeId) ?: return false

        val updatedNodes = workflow.nodes.filter { it.id != nodeId }
        val updatedConnections = workflow.connections.filter { it.sourceNodeId != nodeId && it.targetNodeId != nodeId }
        val updatedWorkflow = workflow.copy(
            nodes = updatedNodes,
            connections = updatedConnections,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.RemoveNode(workflowId, node))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.NodeRemoved(workflowId, nodeId))
        }

        AppLogger.d(TAG, "Node removed from workflow ${workflowId}: ${nodeId}")
        return true
    }
        fun moveNode(workflowId: String, nodeId: String, newPosition: NodePosition): Boolean {
        val workflow = workflows[workflowId] ?: return false
        val node = workflow.getNodeById(nodeId) ?: return false

        val oldPosition = node.position
        val updatedNode = node.copy(position = newPosition)
        val updatedNodes = workflow.nodes.map { if (it.id == nodeId) updatedNode else it }
        val updatedWorkflow = workflow.copy(
            nodes = updatedNodes,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.MoveNode(workflowId, nodeId, oldPosition, newPosition))

        scope.launch {
            _editorEvents.emit(EditorEvent.NodeMoved(workflowId, nodeId, newPosition))
        }

        AppLogger.d(TAG, "Node moved in workflow ${workflowId}: ${nodeId} to ${newPosition}")
        return true
    }
        fun addConnection(workflowId: String, connection: WorkflowConnection): WorkflowConnection? {
        val workflow = workflows[workflowId] ?: return null

        val sourceNode = workflow.getNodeById(connection.sourceNodeId) ?: return null
        val targetNode = workflow.getNodeById(connection.targetNodeId) ?: return null

        val existingConnection = workflow.connections.find {
            it.sourceNodeId == connection.sourceNodeId && it.targetNodeId == connection.targetNodeId
        }
        if (existingConnection != null) {
            AppLogger.w(TAG, "Connection already exists between ${connection.sourceNodeId} and ${connection.targetNodeId}")
        return null
        }
        val updatedWorkflow = workflow.copy(
            connections = workflow.connections + connection,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.AddConnection(workflowId, connection))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.ConnectionAdded(workflowId, connection))
        }

        AppLogger.d(TAG, "Connection added to workflow ${workflowId}: ${connection.id}")
        return connection
    }
        fun createAndAddConnection(
        workflowId: String,
        sourceNodeId: String,
        targetNodeId: String,
        condition: ConnectionCondition = ConnectionCondition.OnSuccess
    ): WorkflowConnection? {
        val connection = WorkflowConnection(
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            condition = condition
        )
        return addConnection(workflowId, connection)
    }
        fun removeConnection(workflowId: String, connectionId: String): Boolean {
        val workflow = workflows[workflowId] ?: return false
        val connection = workflow.connections.find { it.id == connectionId } ?: return false

        val updatedWorkflow = workflow.copy(
            connections = workflow.connections.filter { it.id != connectionId },
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow

        pushUndoAction(workflowId, EditorAction.RemoveConnection(workflowId, connection))

        updateWorkflowsFlow()

        scope.launch {
            _editorEvents.emit(EditorEvent.ConnectionRemoved(workflowId, connectionId))
        }

        AppLogger.d(TAG, "Connection removed from workflow ${workflowId}: ${connectionId}")
        return true
    }
        fun updateConnectionCondition(workflowId: String, connectionId: String, newCondition: ConnectionCondition): Boolean {
        val workflow = workflows[workflowId] ?: return false
        val connection = workflow.connections.find { it.id == connectionId } ?: return false

        val updatedConnection = connection.copy(condition = newCondition)
        val updatedConnections = workflow.connections.map { if (it.id == connectionId) updatedConnection else it }
        val updatedWorkflow = workflow.copy(
            connections = updatedConnections,
            updatedAt = System.currentTimeMillis()
        )

        workflows[workflowId] = updatedWorkflow
        updateWorkflowsFlow()

        AppLogger.d(TAG, "Connection condition updated: ${connectionId} to ${newCondition}")
        return true
    }
        private fun pushUndoAction(workflowId: String, action: EditorAction) {
        scope.launch {
            mutex.withLock {
                val undoStack = undoStacks.getOrPut(workflowId) { mutableListOf() }
                undoStack.add(action)
        if (undoStack.size > MAX_UNDO_STACK_SIZE) {
                    undoStack.removeAt(0)
                }

                redoStacks.getOrPut(workflowId) { mutableListOf() }.clear()
            }
        }
    }
        fun undo(workflowId: String): Boolean {
        var action: EditorAction? = null

        scope.launch {
            mutex.withLock {
                val undoStack = undoStacks.getOrPut(workflowId) { mutableListOf() }
        if (undoStack.isEmpty()) return@launch

                action = undoStack.removeAt(undoStack.lastIndex)
            }
        }
        val currentAction = action ?: return false

        scope.launch {
            mutex.withLock {
                val redoStack = redoStacks.getOrPut(workflowId) { mutableListOf() }
                redoStack.add(currentAction)
            }
        }

        applyUndoAction(workflowId, currentAction)

        scope.launch {
            _editorEvents.emit(EditorEvent.UndoPerformed(workflowId, currentAction))
        }

        AppLogger.d(TAG, "Undo performed for workflow ${workflowId}")
        return true
    }
        fun redo(workflowId: String): Boolean {
        var action: EditorAction? = null

        scope.launch {
            mutex.withLock {
                val redoStack = redoStacks.getOrPut(workflowId) { mutableListOf() }
        if (redoStack.isEmpty()) return@launch

                action = redoStack.removeAt(redoStack.lastIndex)
            }
        }
        val currentAction = action ?: return false

        scope.launch {
            mutex.withLock {
                val undoStack = undoStacks.getOrPut(workflowId) { mutableListOf() }
                undoStack.add(currentAction)
            }
        }

        applyRedoAction(workflowId, currentAction)

        scope.launch {
            _editorEvents.emit(EditorEvent.RedoPerformed(workflowId, currentAction))
        }

        AppLogger.d(TAG, "Redo performed for workflow ${workflowId}")
        return true
    }
        private fun applyUndoAction(workflowId: String, action: EditorAction) {
        when (action) {
            is EditorAction.AddNode -> removeNode(workflowId, action.node.id)
            is EditorAction.RemoveNode -> addNode(workflowId, action.node)
            is EditorAction.UpdateNode -> {
                val workflow = workflows[workflowId] ?: return
                val updatedNodes = workflow.nodes.map { if (it.id == action.newNode.id) action.oldNode else it }
                workflows[workflowId] = workflow.copy(nodes = updatedNodes)
            }
            is EditorAction.MoveNode -> {
                val workflow = workflows[workflowId] ?: return
                val updatedNodes = workflow.nodes.map {
                    if (it.id == action.nodeId) it.copy(position = action.oldPosition) else it
                }
                workflows[workflowId] = workflow.copy(nodes = updatedNodes)
            }
            is EditorAction.AddConnection -> removeConnection(workflowId, action.connection.id)
            is EditorAction.RemoveConnection -> addConnection(workflowId, action.connection)
            is EditorAction.UpdateWorkflow -> {
                workflows[workflowId] = action.oldWorkflow
            }
        }
        updateWorkflowsFlow()
    }
        private fun applyRedoAction(workflowId: String, action: EditorAction) {
        when (action) {
            is EditorAction.AddNode -> addNode(workflowId, action.node)
            is EditorAction.RemoveNode -> removeNode(workflowId, action.node.id)
            is EditorAction.UpdateNode -> {
                val workflow = workflows[workflowId] ?: return
                val updatedNodes = workflow.nodes.map { if (it.id == action.oldNode.id) action.newNode else it }
                workflows[workflowId] = workflow.copy(nodes = updatedNodes)
            }
            is EditorAction.MoveNode -> {
                val workflow = workflows[workflowId] ?: return
                val updatedNodes = workflow.nodes.map {
                    if (it.id == action.nodeId) it.copy(position = action.newPosition) else it
                }
                workflows[workflowId] = workflow.copy(nodes = updatedNodes)
            }
            is EditorAction.AddConnection -> addConnection(workflowId, action.connection)
            is EditorAction.RemoveConnection -> removeConnection(workflowId, action.connection.id)
            is EditorAction.UpdateWorkflow -> {
                workflows[workflowId] = action.newWorkflow
            }
        }
        updateWorkflowsFlow()
    }
        fun canUndo(workflowId: String): Boolean {
        return undoStacks[workflowId]?.isNotEmpty() ?: false
    }
        fun canRedo(workflowId: String): Boolean {
        return redoStacks[workflowId]?.isNotEmpty() ?: false
    }
        fun setEditorState(updates: (EditorState) -> EditorState) {
        _editorState.value = updates(_editorState.value)
    }
        fun selectNode(nodeId: String) {
        setEditorState { it.copy(selectedNodeId = nodeId, selectedConnectionId = null) }
    }
        fun selectConnection(connectionId: String) {
        setEditorState { it.copy(selectedConnectionId = connectionId, selectedNodeId = null) }
    }
        fun setZoom(zoomLevel: Float) {
        setEditorState { it.copy(zoomLevel = zoomLevel.coerceIn(0.1f, 3.0f)) }
    }
        fun setPan(panOffset: NodePosition) {
        setEditorState { it.copy(panOffset = panOffset) }
    }
        fun exportWorkflowToJson(workflowId: String): String? {
        val workflow = workflows[workflowId] ?: return null
        return json.encodeToString(workflow)
    }
        fun importWorkflowFromJson(jsonString: String): WorkflowDefinition? {
        return try {
            val workflow = json.decodeFromString<WorkflowDefinition>(jsonString)
            loadWorkflow(workflow)
            workflow
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import workflow from JSON", e)
            null
        }
    }
        fun duplicateWorkflow(workflowId: String, newName: String? = null): WorkflowDefinition? {
        val original = workflows[workflowId] ?: return null

        val duplicated = original.copy(
            id = WorkflowDefinition.generateWorkflowId(),
            name = newName ?: "${original.name} (Copy)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        workflows[duplicated.id] = duplicated
        undoStacks[duplicated.id] = mutableListOf()
        redoStacks[duplicated.id] = mutableListOf()

        updateWorkflowsFlow()

        AppLogger.i(TAG, "Workflow duplicated: ${original.name} -> ${duplicated.name}")
        return duplicated
    }
        fun validateWorkflow(workflowId: String): WorkflowValidationResult? {
        return workflows[workflowId]?.validate()
    }
        fun getAvailableNodeTypes(): List<NodeType> = NodeType.entries

    fun createDefaultNodeConfig(type: NodeType): NodeConfig {
        return when (type) {
            NodeType.TRIGGER -> NodeConfig(
                triggerConfig = TriggerConfig(
                    triggerType = TriggerType.MANUAL
                )
            )
            NodeType.EXECUTE -> NodeConfig(
                actionType = "log",
                actionConfig = mapOf("message" to ParameterValue.static("Hello"))
            )
            NodeType.CONDITION -> NodeConfig(
                left = ParameterValue.static(""),
                right = ParameterValue.static(""),
                operator = "EQ"
            )
            NodeType.LOGIC -> NodeConfig(
                operator = "AND",
                inputs = emptyList()
            )
            NodeType.EXTRACT -> NodeConfig(
                mode = ExtractMode.REGEX,
                source = ParameterValue.static(""),
                expression = ".*"
            )
        }
    }
        private fun updateWorkflowsFlow() {
        _workflowsFlow.value = workflows.values.toList()
    }
        fun clearAllWorkflows() {
        workflows.clear()
        editorStates.clear()
        undoStacks.clear()
        redoStacks.clear()
        updateWorkflowsFlow()
        AppLogger.i(TAG, "All workflows cleared")
    }
        fun getStats(): EditorStats {
        return EditorStats(
            totalWorkflows = workflows.size,
            totalNodes = workflows.values.sumOf { it.nodes.size },
            totalConnections = workflows.values.sumOf { it.connections.size }
        )
    }

    data class EditorStats(
        val totalWorkflows: Int,
        val totalNodes: Int,
        val totalConnections: Int
    )
}
