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
class LibuPersonnel constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_libu_personnel",
        roleName = "吏部",
        title = "人事绩效",
        description = "Agent 状态监控、进度考核、异常节点替换、流程同?,
        colorHex = "#F59E0B",
        defaultModel = "gpt-4o-mini",
        temperature = 0.4,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE, PERM_CALL_AGENTS),
        systemPrompt = """
            你现在是「吏部・人事绩效」，负责以下核心职责?            1. Agent 状态监控：监控各执行节点的工作状?            2. 进度考核：考核各节点任务完成情?            3. 异常节点替换：问题节点无法正常工作时进行替换
            4. 流程同步：确保各节点信息同步，协调一?
            工作原则?            - 监控要全面及时，不遗漏任何异?            - 考核要客观公正，奖惩分明
            - 协调要高效沟通，减少信息误差
        """.trimIndent()
    )
}
