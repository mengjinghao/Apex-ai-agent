package com.apex.agent.presentation.multiagent.action

import com.apex.agent.presentation.multiagent.data.*
import com.apex.agent.presentation.multiagent.state.MultiAgentPageState
import java.util.UUID

/**
 * 多 Agent 模式页面 — 预设场景。
 *
 * 提供开箱即用的多 Agent 协作场景模板，
 * 用户一键加载即可开始协作。
 */
object MultiAgentPresets {

    /**
     * 预设场景。
     */
    enum class Preset(val displayName: String, val description: String, val icon: String) {
        CODE_REVIEW("代码审查团队", "多 Agent 协作审查代码，发现潜在问题", "🔍"),
        BUG_FIX("Bug 修复团队", "分析→定位→修复→验证，流水线修复 Bug", "🐛"),
        ARCHITECTURE_DESIGN("架构设计团队", "设计→审查→批评→优化，多轮架构设计", "🏗️"),
        DOCUMENTATION("文档编写团队", "分析代码→生成文档→审查→完善", "📄"),
        SECURITY_AUDIT("安全审计团队", "红蓝对抗，攻击方找漏洞，防守方修复", "🛡️"),
        DATA_ANALYSIS("数据分析团队", "采集→清洗→分析→可视化，并行处理", "📊"),
        RESEARCH("研究团队", "多 Agent 分头研究，汇总综合结论", "🔬"),
        CREATIVE("创意团队", "多 Agent 头脑风暴，辩论出最佳方案", "💡")
    }

    /**
     * 加载预设场景。
     *
     * @param state 页面状态
     * @param preset 预设场景
     */
    fun loadPreset(state: MultiAgentPageState, preset: Preset) {
        state.reset()
        when (preset) {
            Preset.CODE_REVIEW -> loadCodeReviewPreset(state)
            Preset.BUG_FIX -> loadBugFixPreset(state)
            Preset.ARCHITECTURE_DESIGN -> loadArchitecturePreset(state)
            Preset.DOCUMENTATION -> loadDocumentationPreset(state)
            Preset.SECURITY_AUDIT -> loadSecurityAuditPreset(state)
            Preset.DATA_ANALYSIS -> loadDataAnalysisPreset(state)
            Preset.RESEARCH -> loadResearchPreset(state)
            Preset.CREATIVE -> loadCreativePreset(state)
        }
    }
        private fun loadCodeReviewPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.DEBATE_REVIEW)

        state.addAgents(listOf(
            AgentCardData(name = "代码分析者", role = AgentRoleType.WORKER, specialties = listOf("代码分析", "模式识别")),
            AgentCardData(name = "安全审查者", role = AgentRoleType.REVIEWER, specialties = listOf("安全", "漏洞检测")),
            AgentCardData(name = "性能审查者", role = AgentRoleType.REVIEWER, specialties = listOf("性能", "优化")),
            AgentCardData(name = "风格审查者", role = AgentRoleType.CRITIC, specialties = listOf("代码风格", "最佳实践")),
            AgentCardData(name = "决策者", role = AgentRoleType.SUPERVISOR, specialties = listOf("决策", "综合"))
        ))
        val taskId = state.createTask("代码审查", "多维度审查代码质量", TaskPriority.HIGH)
        state.addSubtask(taskId, "代码结构分析")
        state.addSubtask(taskId, "安全漏洞扫描")
        state.addSubtask(taskId, "性能瓶颈识别")
        state.addSubtask(taskId, "代码风格检查")
        state.addSubtask(taskId, "综合评审报告")
    }
        private fun loadBugFixPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.SERIAL_PIPELINE)

        state.addAgents(listOf(
            AgentCardData(name = "问题分析者", role = AgentRoleType.WORKER, specialties = listOf("Bug 分析", "日志分析")),
            AgentCardData(name = "根因定位者", role = AgentRoleType.WORKER, specialties = listOf("调试", "根因分析")),
            AgentCardData(name = "修复执行者", role = AgentRoleType.WORKER, specialties = listOf("编码", "修复")),
            AgentCardData(name = "测试验证者", role = AgentRoleType.REVIEWER, specialties = listOf("测试", "验证"))
        ))
        val taskId = state.createTask("Bug 修复", "从分析到验证的流水线修复", TaskPriority.URGENT)
        state.addSubtask(taskId, "复现并分析问题")
        state.addSubtask(taskId, "定位根因")
        state.addSubtask(taskId, "编写修复代码")
        state.addSubtask(taskId, "测试验证修复")
    }
        private fun loadArchitecturePreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.DEBATE_REVIEW)

        state.addAgents(listOf(
            AgentCardData(name = "架构师", role = AgentRoleType.SUPERVISOR, specialties = listOf("架构设计", "DDD")),
            AgentCardData(name = "方案设计者", role = AgentRoleType.WORKER, specialties = listOf("设计模式", "微服务")),
            AgentCardData(name = "批评者", role = AgentRoleType.CRITIC, specialties = listOf("架构评审", "反模式")),
            AgentCardData(name = "优化建议者", role = AgentRoleType.REVIEWER, specialties = listOf("性能", "可扩展性"))
        ))
        val taskId = state.createTask("架构设计", "多轮迭代的架构设计方案", TaskPriority.HIGH)
        state.addSubtask(taskId, "需求分析与约束识别")
        state.addSubtask(taskId, "初始架构方案设计")
        state.addSubtask(taskId, "架构批评与挑战")
        state.addSubtask(taskId, "优化与最终方案")
    }
        private fun loadDocumentationPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.SERIAL_PIPELINE)

        state.addAgents(listOf(
            AgentCardData(name = "代码分析者", role = AgentRoleType.WORKER, specialties = listOf("代码理解")),
            AgentCardData(name = "文档编写者", role = AgentRoleType.WORKER, specialties = listOf("技术写作")),
            AgentCardData(name = "文档审查者", role = AgentRoleType.REVIEWER, specialties = listOf("文档质量"))
        ))
        val taskId = state.createTask("文档编写", "分析代码并生成技术文档", TaskPriority.NORMAL)
        state.addSubtask(taskId, "分析代码结构")
        state.addSubtask(taskId, "生成 API 文档")
        state.addSubtask(taskId, "审查并完善文档")
    }
        private fun loadSecurityAuditPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.ADVERSARIAL)

        state.addAgents(listOf(
            AgentCardData(name = "红队-攻击者", role = AgentRoleType.RED_TEAM, specialties = listOf("渗透测试", "漏洞利用")),
            AgentCardData(name = "红队-漏洞挖掘", role = AgentRoleType.RED_TEAM, specialties = listOf("SQL注入", "XSS", "CSRF")),
            AgentCardData(name = "蓝队-防御者", role = AgentRoleType.BLUE_TEAM, specialties = listOf("安全加固", "防御策略")),
            AgentCardData(name = "蓝队-修复者", role = AgentRoleType.BLUE_TEAM, specialties = listOf("漏洞修复", "代码审计")),
            AgentCardData(name = "裁判", role = AgentRoleType.SUPERVISOR, specialties = listOf("评估", "裁决"))
        ))
        val taskId = state.createTask("安全审计", "红蓝对抗式安全审计", TaskPriority.CRITICAL)
        state.addSubtask(taskId, "红队攻击尝试")
        state.addSubtask(taskId, "漏洞报告")
        state.addSubtask(taskId, "蓝队防御加固")
        state.addSubtask(taskId, "复测验证")
        state.addSubtask(taskId, "裁决与报告")
    }
        private fun loadDataAnalysisPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.PARALLEL_EXECUTION)

        state.addAgents(listOf(
            AgentCardData(name = "数据采集者", role = AgentRoleType.WORKER, specialties = listOf("爬虫", "API")),
            AgentCardData(name = "数据清洗者", role = AgentRoleType.WORKER, specialties = listOf("ETL", "数据质量")),
            AgentCardData(name = "分析师", role = AgentRoleType.WORKER, specialties = listOf("统计分析", "机器学习")),
            AgentCardData(name = "可视化者", role = AgentRoleType.WORKER, specialties = listOf("图表", "Dashboard")),
            AgentCardData(name = "汇总者", role = AgentRoleType.SUPERVISOR, specialties = listOf("综合", "报告"))
        ))
        val taskId = state.createTask("数据分析", "并行数据分析流水线", TaskPriority.HIGH)
        state.addSubtask(taskId, "数据采集")
        state.addSubtask(taskId, "数据清洗")
        state.addSubtask(taskId, "数据分析")
        state.addSubtask(taskId, "可视化")
        state.addSubtask(taskId, "汇总报告")
    }
        private fun loadResearchPreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.SWARM)

        state.addAgents(listOf(
            AgentCardData(name = "研究者-A", role = AgentRoleType.WORKER, specialties = listOf("文献检索")),
            AgentCardData(name = "研究者-B", role = AgentRoleType.WORKER, specialties = listOf("实验设计")),
            AgentCardData(name = "研究者-C", role = AgentRoleType.WORKER, specialties = listOf("数据分析")),
            AgentCardData(name = "研究者-D", role = AgentRoleType.WORKER, specialties = listOf("对比研究")),
            AgentCardData(name = "综合者", role = AgentRoleType.COORDINATOR, specialties = listOf("综合", "结论"))
        ))

        state.createTask("多角度研究", "多 Agent 分头研究后汇总", TaskPriority.NORMAL)
    }
        private fun loadCreativePreset(state: MultiAgentPageState) {
        state.setCollaborationMode(CollaborationMode.FREE_DIALOG)

        state.addAgents(listOf(
            AgentCardData(name = "创意生成者", role = AgentRoleType.WORKER, specialties = listOf("创意", "头脑风暴")),
            AgentCardData(name = "可行性评估者", role = AgentRoleType.REVIEWER, specialties = listOf("可行性", "评估")),
            AgentCardData(name = "质疑者", role = AgentRoleType.CRITIC, specialties = listOf("挑战", "风险")),
            AgentCardData(name = "优化者", role = AgentRoleType.COORDINATOR, specialties = listOf("改进", "融合"))
        ))

        state.createTask("创意头脑风暴", "自由对话，辩论出最佳方案", TaskPriority.NORMAL)
    }
}

/**
 * 多 Agent 模式页面 — 快捷操作。
 *
 * 提供常用的快捷操作方法。
 */
object MultiAgentQuickActions {

    /**
     * 快速创建一个标准团队。
     *
     * @param state 页面状态
     * @param taskTitle 任务标题
     * @param mode 协作模式
     */
    fun quickCreateTeam(
        state: MultiAgentPageState,
        taskTitle: String,
        mode: CollaborationMode = CollaborationMode.SUPERVISOR
    ): String {
        state.setCollaborationMode(mode)

        // 创建标准 3 人团队
        state.addAgent(AgentCardData(
            name = "主管 Agent",
            role = AgentRoleType.SUPERVISOR,
            specialties = listOf("协调", "决策")
        ))
        state.addAgent(AgentCardData(
            name = "执行 Agent",
            role = AgentRoleType.WORKER,
            specialties = listOf("执行", "编码")
        ))
        state.addAgent(AgentCardData(
            name = "审查 Agent",
            role = AgentRoleType.REVIEWER,
            specialties = listOf("审查", "验证")
        ))

        // 创建任务
    val taskId = state.createTask(taskTitle, "由快捷操作创建", TaskPriority.NORMAL)

        // 分配
        state.assignTask(taskId, state.agents.value.map { it.id })
        return taskId
    }

    /**
     * 发送系统消息。
     */
    fun sendSystemMessage(state: MultiAgentPageState, content: String) {
        // 系统消息以广播形式发送
    if (state.agents.value.isNotEmpty()) {
            state.broadcastMessage(
                fromAgentId = state.agents.value.first().id,
                content = "⚙️ [系统] $content",
                type = AgentMessageType.SYSTEM
            )
        }
    }

    /**
     * 获取活跃 Agent 列表。
     */
    fun getActiveAgents(state: MultiAgentPageState): List<AgentCardData> {
        return state.agents.value.filter { it.isActive }
    }

    /**
     * 获取待处理任务。
     */
    fun getPendingTasks(state: MultiAgentPageState): List<CollaborationTaskCard> {
        return state.tasks.value.filter {
            it.status == TaskCardStatus.PENDING || it.status == TaskCardStatus.ASSIGNED
        }
    }
}
