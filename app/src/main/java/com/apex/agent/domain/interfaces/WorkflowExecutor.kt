package com.apex.agent.domain.interfaces

import com.apex.agent.domain.model.*

/**
 * 统一工作流执行器接口
 * 定义了工作流从注册、执行、取消到状态查询的完整生命周期
 */

    /**
     * 取消正在执行的工作流
     * @param workflowId 工作流ID
     * @return true表示取消成功，false表示未找到该工作流
     */
    suspend fun cancel(workflowId: String): Boolean

    /**
     * 查询工作流当前执行状态
     * @param workflowId 工作流ID
     * @return 执行状态对象，未找到时返回null
     */
    suspend fun getStatus(workflowId: String): WorkflowExecution?

    /**
     * 暂停正在运行的工作流
     * @param workflowId 工作流ID
     * @return true表示暂停成功，false表示未找到或无法暂停
     */
    suspend fun pause(workflowId: String): Boolean

    /**
     * 恢复已暂停的工作流
     * @param workflowId 工作流ID
     * @return true表示恢复成功，false表示未找到或无法恢复
     */
    suspend fun resume(workflowId: String): Boolean

    /**
     * 列出所有（含历史）的工作流执行记录
     * @return 执行状态列表
     */
    suspend fun listExecutions(): List<WorkflowExecution>

    /**
     * 注册工作流定义，使其可被按ID执行
     * @param workflow 工作流定义
     */
    fun register(workflow: WorkflowDefinition)

    /**
     * 注销已注册的工作流
     * @param workflowId 工作流ID
     * @return true表示成功注销，false表示未找到
     */
    fun unregister(workflowId: String): Boolean

    /**
     * 获取所有已注册的工作流定义
     * @return 工作流定义列表
     */
    fun getRegistered(): List<WorkflowDefinition>
}
