package com.apex.agent.orchestration.collaboration.modes

import android.content.Context
import com.apex.agent.R
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.collaboration.AgentStatus
import com.apex.agent.orchestration.core.AllocationModels.AllocationRequest
import com.apex.agent.orchestration.core.IntelligentTaskAllocator
import com.apex.agent.orchestration.core.TaskComplexityQuantifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisorExecutionMode @Inject constructor(
    @ApplicationContext context: Context,
    agentManager: AgentManager,
    private val complexityQuantifier: TaskComplexityQuantifier,
    private val taskAllocator: IntelligentTaskAllocator
) : AbstractCollaborationMode<SupervisorExecutionMode.SupervisorState>(context, agentManager) {

    class SupervisorState(task: Task, agents: List<Agent>) : ExecutionState(task, agents) {
        var phase: SupervisorPhase = SupervisorPhase.TASK_ANALYSIS
        val assignedTasks = ConcurrentHashMap<String, String>()
        var analysisResult: String = ""
    }

    override fun createState(task: Task, agents: List<Agent>): SupervisorState {
        return SupervisorState(task, agents)
    }

    override suspend fun runStep(state: SupervisorState) {
        when (state.phase) {
            SupervisorPhase.TASK_ANALYSIS -> executeTaskAnalysis(state)
            SupervisorPhase.TASK_DECOMPOSITION -> executeTaskDecomposition(state)
            SupervisorPhase.TASK_ASSIGNMENT -> executeTaskAssignment(state)
            SupervisorPhase.EXECUTION_MONITORING -> executeExecutionMonitoring(state)
            SupervisorPhase.RESULT_AGGREGATION -> executeResultAggregation(state)
            SupervisorPhase.FINAL_REVIEW -> executeFinalReview(state)
        }
    }
        private suspend fun executeTaskAnalysis(state: SupervisorState) {
        val supervisor = getSupervisorAgent(state) ?: return
        updateAgentStatus(state, supervisor.id, AgentStatus.WORKING)
        val report = complexityQuantifier.quantifyTask(state.task.description)
        state.analysisResult = report.reasoning
        val analysisContent = context.getString(R.string.task_analysis_prefix, state.task.description)
        broadcastMessage(state, "$analysisContent\n[Analysis: ${report.reasoning}]", "system")
        state.phase = SupervisorPhase.TASK_DECOMPOSITION
    }
        private suspend fun executeTaskDecomposition(state: SupervisorState) {
        val supervisor = getSupervisorAgent(state) ?: return
        val report = complexityQuantifier.quantifyTask(state.task.description)
        val subTasks = decomposeTask(state.task.description, report)
        subTasks.forEachIndexed { index, subTask ->
            state.assignedTasks["task_${index}"] = subTask
        }
        updateAgentProgress(state, supervisor.id, 0.3f)
        state.phase = SupervisorPhase.TASK_ASSIGNMENT
    }
        private suspend fun executeTaskAssignment(state: SupervisorState) {
        val supervisor = getSupervisorAgent(state) ?: return
        val report = complexityQuantifier.quantifyTask(state.task.description)

        state.assignedTasks.forEach { (taskKey, subTask) ->
            val allocationResult = taskAllocator.allocate(
                AllocationRequest(
                    taskDescription = subTask,
                    requiredSkills = report.requiredSkills,
                    complexityReport = report
                )
            )
        val availableAgent = allocationResult?.let {
                val agentId = it.selectedAgentId
                state.agents.find { a -> a.id == agentId }
            } ?: getNextAgent(state)
        if (availableAgent != null) {
                sendToAgent(
                    state = state,
                    agentId = availableAgent.id,
                    content = context.getString(R.string.task_assignment_prefix, subTask),
                    senderId = supervisor.id
                )
                updateAgentStatus(state, availableAgent.id, AgentStatus.WORKING)
            }
        }

        updateAgentProgress(state, supervisor.id, 0.5f)
        state.phase = SupervisorPhase.EXECUTION_MONITORING
    }
        private fun executeExecutionMonitoring(state: SupervisorState) {
        if (areAllAgentsFinished(state)) {
            state.phase = SupervisorPhase.RESULT_AGGREGATION
        }
    }
        private fun executeResultAggregation(state: SupervisorState) {
        val supervisor = getSupervisorAgent(state) ?: return
        updateAgentProgress(state, supervisor.id, 0.9f)
        state.phase = SupervisorPhase.FINAL_REVIEW
    }
        private fun executeFinalReview(state: SupervisorState) {
        val supervisor = getSupervisorAgent(state) ?: return
        updateAgentProgress(state, supervisor.id, 1.0f)
        updateAgentStatus(state, supervisor.id, AgentStatus.FINISHED)
        state.running.set(false)
    }
        private fun decomposeTask(taskDescription: String, report: com.apex.agent.orchestration.core.AllocationModels.ComplexityReport): List<String> {
        val difficulty = report.difficulty
        val category = report.category
        val subtaskCount = when {
            difficulty <= 3 -> 3
            difficulty <= 6 -> 5
            else -> 7
        }
        return when (category) {
            "coding" -> buildCodingSubtasks(taskDescription, subtaskCount)
            "debugging" -> buildDebuggingSubtasks(taskDescription, subtaskCount)
            "testing" -> buildTestingSubtasks(taskDescription, subtaskCount)
            "writing" -> buildWritingSubtasks(taskDescription, subtaskCount)
            "research" -> buildResearchSubtasks(taskDescription, subtaskCount)
            "data" -> buildDataSubtasks(taskDescription, subtaskCount)
            "design" -> buildDesignSubtasks(taskDescription, subtaskCount)
            "planning" -> buildPlanningSubtasks(taskDescription, subtaskCount)
            "devops" -> buildDevopsSubtasks(taskDescription, subtaskCount)
            "security" -> buildSecuritySubtasks(taskDescription, subtaskCount)
            else -> buildGeneralSubtasks(taskDescription, subtaskCount)
        }
    }
        private fun buildCodingSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "需求分�? $task",
            "核心实现: $task",
            "测试验证: $task"
        )
        count <= 5 -> listOf(
            "需求与架构分析: $task",
            "数据模型设计",
            "核心功能实现: $task",
            "错误处理与边界情�?,
            "测试与文�?
        )
        else -> listOf(
            "需求分�? $task",
            "系统架构设计",
            "数据模型与接口设�?,
            "核心模块实现: $task",
            "辅助功能实现",
            "集成测试",
            "性能优化与文�?
        )
    }
        private fun buildDebuggingSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "问题复现与日志分�? $task",
            "根因定位与修�? $task",
            "回归验证: $task"
        )
        else -> listOf(
            "环境检查与问题复现: $task",
            "日志与堆栈分�?,
            "根因定位: $task",
            "修复方案设计与实�?,
            "单元测试与回归验�?
        )
    }
        private fun buildTestingSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "测试计划制定: $task",
            "测试用例编写与执�?,
            "测试报告生成: $task"
        )
        else -> listOf(
            "需求分析与测试计划: $task",
            "单元测试编写",
            "集成测试场景设计",
            "测试执行与缺陷跟�?,
            "测试报告与质量评�?
        )
    }
        private fun buildWritingSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "内容大纲规划: $task",
            "内容撰写: $task",
            "编辑与润�?
        )
        else -> listOf(
            "主题调研与大�? $task",
            "初稿撰写: $task",
            "配图与排版设�?,
            "内容审核与修�?,
            "最终定�?
        )
    }
        private fun buildResearchSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "信息收集: $task",
            "分析与综�? $task",
            "结论与建�?
        )
        else -> listOf(
            "研究范围确定: $task",
            "文献/信息收集",
            "数据分析与整�?,
            "洞察提炼",
            "研究报告撰写"
        )
    }
        private fun buildDataSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "数据理解与探�? $task",
            "数据清洗与转�?,
            "数据处理实施: $task"
        )
        else -> listOf(
            "数据源分析与探查: $task",
            "数据清洗规则制定",
            "ETL管道实现",
            "数据质量验证",
            "结果输出与文�?
        )
    }
        private fun buildDesignSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "需求分析与设计思路: $task",
            "原型设计: $task",
            "设计评审与交�?
        )
        else -> listOf(
            "需求梳理与用户研究: $task",
            "信息架构设计",
            "交互流程设计",
            "视觉设计: $task",
            "设计规范与交�?
        )
    }
        private fun buildPlanningSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "现状分析: $task",
            "计划制定: $task",
            "风险评估"
        )
        else -> listOf(
            "背景与现状调�? $task",
            "目标与关键结果设�?,
            "执行计划制定: $task",
            "资源配置规划",
            "风险预案制定"
        )
    }
        private fun buildDevopsSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "环境评估: $task",
            "部署实施: $task",
            "监控验证"
        )
        else -> listOf(
            "基础设施评估: $task",
            "CI/CD管道配置",
            "容器�部署实施",
            "监控与告警设�?,
            "运维文档与交�?
        )
    }
        private fun buildSecuritySubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "安全评估范围界定: $task",
            "安全审计执行: $task",
            "修复建议报告"
        )
        else -> listOf(
            "资产盘点与范围界�? $task",
            "威胁建模分析",
            "安全测试执行: $task",
            "漏洞评估与优先级排序",
            "修复方案与安全加固建�?
        )
    }
        private fun buildGeneralSubtasks(task: String, count: Int): List<String> = when {
        count <= 3 -> listOf(
            "任务分析: $task",
            "执行实施: $task",
            "结果验证"
        )
        else -> listOf(
            "任务拆解与分�? $task",
            "实施方案设计",
            "分步实施: $task",
            "质量检�?,
            "结果汇总与交付"
        )
    }

    override suspend fun onMessage(state: SupervisorState, message: AgentMessage) {
        when (state.phase) {
            SupervisorPhase.EXECUTION_MONITORING -> {
                updateAgentStatus(state, message.senderId, AgentStatus.IDLE)
            }
            else -> {}
        }
    }

    enum class SupervisorPhase {
        TASK_ANALYSIS,
        TASK_DECOMPOSITION,
        TASK_ASSIGNMENT,
        EXECUTION_MONITORING,
        RESULT_AGGREGATION,
        FINAL_REVIEW
    }
}
