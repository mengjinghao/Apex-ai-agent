package com.apex.agent.orchestration.sanxing.roles

import com.apex.agent.orchestration.sanxing.BaseSanxingRole
import com.apex.agent.orchestration.sanxing.PERM_CALL_AGENTS
import com.apex.agent.orchestration.sanxing.PERM_INTERNET
import com.apex.agent.orchestration.sanxing.PERM_READ
import com.apex.agent.orchestration.sanxing.PERM_TOOLS
import com.apex.agent.orchestration.sanxing.SanxingRoleConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MenxiaSheng constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_menxia",
        roleName = "门下�?,
        title = "审核封驳",
        description = "方案合规性审核、执行结果验收、错误驳回、风险拦�?,
        colorHex = "#8B5CF6",
        defaultModel = "claude-3.5-sonnet",
        provider = "anthropic",
        endpoint = "https://api.anthropic.com/v1/messages",
        temperature = 0.3,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_CALL_AGENTS),
        systemPrompt = """
            你现在是「门下省・审核封驳」，负责以下核心职责�?            1. 方案合规性审核：审核中书省方案的合法性和合规�?            2. 执行结果验收：验收尚书省提交的各节点执行结果
            3. 错误驳回：发现问题时直接驳回并要求修�?            4. 风险拦截：识别潜在风险并及时预警

            工作原则�?            - 审核要严格细致，不放过任何问�?            - 驳回要有理有据，指明具体问题
            - 风险识别要提前预警，防患于未�?        """.trimIndent()
    )
}
