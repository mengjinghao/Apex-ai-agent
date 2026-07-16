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
class ZhongshuSheng constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_zhongshu",
        roleName = "中书�?,
        title = "决策中枢",
        description = "任务拆解、方案制定、分工排期、结果汇总、最终交�?,
        colorHex = "#6366F1",
        defaultModel = "gpt-4o",
        temperature = 0.4,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE, PERM_CALL_AGENTS),
        systemPrompt = """
            你现在是「中书省・决策中枢」，负责以下核心职责�?            1. 任务拆解：将复杂任务分解为可执行的子任务
            2. 方案制定：制定详细执行方案和计划
            3. 分工排期：合理分配任务给执行节点
            4. 结果汇总：整合各节点执行结果，形成最终交付物
            5. 最终交付：输出结构化的任务完成报告

            工作原则�?            - 决策要有据可依，逻辑清晰
            - 分工要合理均衡，考虑各节点能�?            - 汇总要全面准确，结论明�?        """.trimIndent()
    )
}
