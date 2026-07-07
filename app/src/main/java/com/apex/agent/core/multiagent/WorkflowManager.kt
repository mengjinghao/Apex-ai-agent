package com.apex.agent.core.multiagent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

private val Context.workflowDataStore by preferencesDataStore("agent_workflows")

class WorkflowManager(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowManager"
        private val KEY_WORKFLOWS = stringPreferencesKey("agent_workflows")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val _workflows = mutableMapOf<String, Workflow>()

    val workflows: List<Workflow>
        get() = _workflows.values.sortedByDescending { it.updated }

    val enabledWorkflows: List<Workflow>
        get() = workflows.filter { it.isEnabled }

    val categories: List<String>
        get() = _workflows.values.map { it.category }.distinct()

    val executor: WorkflowExecutor by lazy { WorkflowExecutor(context) }

    init {
        scope.launch { loadFromDataStore() }
    }
    
    fun cleanup() {
        scope.cancel()
    }

    private suspend fun loadFromDataStore() {
        try {
            val prefs = context.workflowDataStore.data.first()
            prefs[KEY_WORKFLOWS]?.let { json ->
                val type = object : TypeToken<List<Workflow>>() {}.type
                val loadedWorkflows: List<Workflow> = gson.fromJson(json, type)
                _workflows.clear()
                loadedWorkflows.forEach { workflow -> _workflows[workflow.id] = workflow }
            }
            if (_workflows.isEmpty()) createDefaultWorkflows()
        } catch (e: Exception) {
            createDefaultWorkflows()
        }
    }

    private suspend fun saveToDataStore() {
        context.workflowDataStore.edit { prefs ->
            prefs[KEY_WORKFLOWS] = gson.toJson(_workflows.values.toList())
        }
    }

    private suspend fun createDefaultWorkflows() {
        val workflowId = UUID.randomUUID().toString()
        val startNode = WorkflowNode(type = NodeType.START, x = 100f, y = 300f, title = "开�?
        val agentNode = WorkflowNode(type = NodeType.AGENT, agentRole = AgentRole.EXECUTOR, x = 400f, y = 300f, title = "执行任务", description = "Agent执行任务")
        val endNode = WorkflowNode(type = NodeType.END, x = 700f, y = 300f, title = "结束")

        val default = Workflow(
            id = workflowId,
            name = "示例工作�?
            description = "一个简单的示例工作�?
            category = "示例",
            nodes = mutableListOf(startNode, agentNode, endNode),
            edges = mutableListOf(
                WorkflowEdge(fromNodeId = startNode.id, toNodeId = agentNode.id),
                WorkflowEdge(fromNodeId = agentNode.id, toNodeId = endNode.id)
            ),
            isEnabled = true,
            tags = setOf("示例", "基础")
        )

        _workflows[default.id] = default
        saveToDataStore()
    }

    fun getWorkflowById(id: String): Workflow? = _workflows[id]

    fun getWorkflowsByCategory(category: String): List<Workflow> = workflows.filter { it.category == category }

    fun searchWorkflows(query: String): List<Workflow> {
        val lowerQuery = query.lowercase()
        return workflows.filter { workflow ->
            workflow.name.lowercase().contains(lowerQuery) ||
            workflow.description.lowercase().contains(lowerQuery) ||
            workflow.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    fun getMostUsedWorkflows(limit: Int = 5): List<Workflow> = workflows.sortedByDescending { it.executionCount }.take(limit)

    fun createNewWorkflow(name: String = "新工作流", category: String = "通用"): Workflow {
        val workflow = Workflow(
            name = name,
            category = category,
            nodes = mutableListOf(WorkflowNode(type = NodeType.START, x = 100f, y = 300f, title = "开�?)
        )
        _workflows[workflow.id] = workflow
        scope.launch { saveToDataStore() }
        return workflow
    }

    suspend fun saveWorkflow(workflow: Workflow): Boolean {
        _workflows[workflow.id] = workflow.copy(updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun deleteWorkflow(workflowId: String): Boolean {
        if (!_workflows.containsKey(workflowId)) return false
        _workflows.remove(workflowId)
        saveToDataStore()
        return true
    }

    suspend fun duplicateWorkflow(workflowId: String, newName: String? = null): Workflow? {
        val original = _workflows[workflowId] ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = newName ?: "${original.name} (副本�?,
            executionCount = 0,
            lastExecution = null,
            created = System.currentTimeMillis(),
            updated = System.currentTimeMillis(),
            tags = original.tags.toMutableSet().apply { add("duplicate") }
        ).also { workflow ->
            workflow.nodes = workflow.nodes.map { it.copy(id = UUID.randomUUID().toString()) }.toMutableList()
        }
        _workflows[copy.id] = copy
        saveToDataStore()
        return copy
    }

    fun executeWorkflow(
        workflowId: String,
        inputVariables: Map<String, Any> = emptyMap(),
        onProgress: ((WorkflowExecution) -> Unit)? = null
    ): WorkflowExecution? {
        val workflow = _workflows[workflowId] ?: return null
        val execution = executor.startExecution(workflow, inputVariables, onProgress)

        scope.launch {
            _workflows[workflowId]?.let {
                _workflows[workflowId] = it.copy(executionCount = it.executionCount + 1, lastExecution = System.currentTimeMillis(), updated = System.currentTimeMillis())
                saveToDataStore()
            }
        }
        return execution
    }

    suspend fun exportWorkflow(workflowId: String): String? {
        val workflow = _workflows[workflowId] ?: return null
        return workflow.toJson()
    }

    suspend fun importWorkflow(json: String): Boolean {
        val workflow = Workflow.fromJson(json) ?: return false
        return if (workflow.id in _workflows) {
            saveWorkflow(workflow)
        } else {
            _workflows[workflow.id] = workflow
            saveToDataStore()
            true
        }
    }
}
