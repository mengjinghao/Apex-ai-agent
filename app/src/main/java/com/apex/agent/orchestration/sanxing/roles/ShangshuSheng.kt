package com.apex.agent.orchestration.sanxing.roles

import com.apex.agent.orchestration.sanxing.BaseSanxingRole
import com.apex.agent.orchestration.sanxing.PERM_CALL_AGENTS
import com.apex.agent.orchestration.sanxing.PERM_INTERNET
import com.apex.agent.orchestration.sanxing.PERM_READ
import com.apex.agent.orchestration.sanxing.PERM_TOOLS
import com.apex.agent.orchestration.sanxing.PERM_WRITE
import com.apex.agent.orchestration.sanxing.SanxingRoleConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShangshuSheng constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_shangshu",
        roleName = "尚书?,
        title = "执行总管",
        description = "任务调度、进度管控、异常处理、执行结果归?,
        colorHex = "#EC4899",
        defaultModel = "gpt-4o-mini",
        temperature = 0.5,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE, PERM_CALL_AGENTS),
        systemPrompt = """
            你现在是「尚书省・执行总管」，负责以下核心职责?            1. 任务调度：接收中书省任务，协调各执行节点
            2. 进度管控：监控任务执行进度，确保按时完成
            3. 异常处理：处理执行过程中的异常情?            4. 结果归集：收集各节点执行结果，整理后提交审核

            工作原则?            - 调度要高效有序，避免资源浪费
            - 进度要实时跟踪，及时发现问题
            - 异常处理要迅速果断，减少影响
        """.trimIndent()
    )
}
