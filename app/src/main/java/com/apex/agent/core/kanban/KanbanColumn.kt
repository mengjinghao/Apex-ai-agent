package com.apex.agent.core.kanban

import com.apex.agent.core.collaboration.AgentCollaborationFramework.AgentRole
import java.util.UUID

/**
 * KanbanColumn - 列定�? *
 * 看板中的每一列代表一个工作阶段，可配�?
 * - 专业 Agent 角色
 * - 入口/出口条件
 * - 自动处理规则
 */
class KanbanColumn(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var description: String = "",
    val order: Int = 0,
    val tasks: MutableList<KanbanTask> = mutableListOf(),
    // Worker 配置
    var assignedWorker: String? = null,  // Worker ID
    var requiredAgentRoles: List<AgentRole> = emptyList(),  // 要求�?Agent 角色
    var requiredCapabilities: List<String> = emptyList(),     // 要求的能�?    // 流转规则
    var entryConditions: List<ColumnCondition> = emptyList(),  // 进入条件
    var exitConditions: List<ColumnCondition> = emptyList(),   // 离开条件
    // 自动处理
    var autoProcessEnabled: Boolean = false,
    var autoAssignEnabled: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 检查任务是否满足进入条�?     */
    fun canEnter(task: KanbanTask): Boolean {
        if (entryConditions.isEmpty()) return true
        return entryConditions.all { condition ->
            condition.evaluate(task)
        }
    }

    /**
     * 检查任务是否满足离开条件
     */
    fun canExit(task: KanbanTask): Boolean {
        if (exitConditions.isEmpty()) return true
        return exitConditions.all { condition ->
            condition.evaluate(task)
        }
    }

    /**
     * 获取列中任务数量
     */
    fun getTaskCount(): Int = tasks.size

    /**
     * 获取列中指定状态的任务
     */
    fun getTasksByStatus(status: KanbanTaskStatus): List<KanbanTask> {
        return tasks.filter { it.status == status }
    }

    /**
     * 检查是否需要特定角�?     */
    fun requiresRole(role: AgentRole): Boolean {
        return requiredAgentRoles.contains(role)
    }

    /**
     * 检查是否需要特定能�?     */
    fun requiresCapability(capability: String): Boolean {
        return requiredCapabilities.any {
            it.equals(capability, ignoreCase = true)
        }
    }

    companion object {
        /**
         * 创建标准开发流程列
         */
        fun createStandardColumns(): List<KanbanColumn> {
            return listOf(
                KanbanColumn(
                    name = "需�?,
                    description = "收集和分析需�?,
                    order = 0,
                    requiredAgentRoles = listOf(AgentRole.ANALYST, AgentRole.RESEARCHER)
                ),
                KanbanColumn(
                    name = "设计",
                    description = "系统设计和方案制�?,
                    order = 1,
                    requiredAgentRoles = listOf(AgentRole.DESIGNER)
                ),
                KanbanColumn(
                    name = "开�?,
                    description = "代码实现",
                    order = 2,
                    requiredAgentRoles = listOf(AgentRole.DEVELOPER),
                    autoAssignEnabled = true
                ),
                KanbanColumn(
                    name = "测试",
                    description = "测试验证",
                    order = 3,
                    requiredAgentRoles = listOf(AgentRole.TESTER)
                ),
                KanbanColumn(
                    name = "部署",
                    description = "部署上线",
                    order = 4,
                    requiredAgentRoles = listOf(AgentRole.DEVELOPER, AgentRole.SUPPORT)
                )
            )
        }
    }
}

/**
 * 列流转条�? */
sealed class ColumnCondition {
    abstract fun evaluate(task: KanbanTask): Boolean

    /**
     * 基于任务优先级的条件
     */
    data class PriorityCondition(
        val operator: ConditionOperator,
        val priority: Int
    ) : ColumnCondition() {
        override fun evaluate(task: KanbanTask): Boolean {
            return when (operator) {
                ConditionOperator.GREATER_THAN -> task.priority > priority
                ConditionOperator.LESS_THAN -> task.priority < priority
                ConditionOperator.EQUALS -> task.priority == priority
                ConditionOperator.GREATER_OR_EQUAL -> task.priority >= priority
                ConditionOperator.LESS_OR_EQUAL -> task.priority <= priority
            }
        }
    }

    /**
     * 基于任务标签的条�?     */
    data class TagCondition(
        val tag: String,
        val mustHave: Boolean = true
    ) : ColumnCondition() {
        override fun evaluate(task: KanbanTask): Boolean {
            val hasTag = task.tags.any { it.equals(tag, ignoreCase = true) }
            return mustHave == hasTag
        }
    }

    /**
     * 基于任务类型
     */
    data class TypeCondition(
        val taskType: String
    ) : ColumnCondition() {
        override fun evaluate(task: KanbanTask): Boolean {
            return task.taskType.equals(taskType, ignoreCase = true)
        }
    }

    /**
     * 基于描述关键�?     */
    data class DescriptionKeywordCondition(
        val keywords: List<String>,
        val matchAll: Boolean = false
    ) : ColumnCondition() {
        override fun evaluate(task: KanbanTask): Boolean {
            val description = task.description.lowercase()
            return if (matchAll) {
                keywords.all { description.contains(it.lowercase()) }
            } else {
                keywords.any { description.contains(it.lowercase()) }
            }
        }
    }
}

/**
     * 条件操作�?     */
enum class ConditionOperator {
    GREATER_THAN,
    LESS_THAN,
    EQUALS,
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL
}
