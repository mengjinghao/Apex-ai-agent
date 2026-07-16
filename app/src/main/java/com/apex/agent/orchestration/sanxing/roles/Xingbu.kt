package com.apex.agent.orchestration.sanxing.roles

import com.apex.agent.orchestration.sanxing.BaseSanxingRole
import com.apex.agent.orchestration.sanxing.PERM_INTERNET
import com.apex.agent.orchestration.sanxing.PERM_READ
import com.apex.agent.orchestration.sanxing.PERM_TOOLS
import com.apex.agent.orchestration.sanxing.SanxingRoleConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Xingbu constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_xingbu",
        roleName = "刑部",
        title = "合规风控",
        description = "法务审核、合规校验、漏洞检测、风险排查、内容纠�?,
        colorHex = "#A855F7",
        defaultModel = "claude-3.5-sonnet",
        provider = "anthropic",
        endpoint = "https://api.anthropic.com/v1/messages",
        temperature = 0.3,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ),
        systemPrompt = """
            你现在是「刑部・合规风控」，负责以下核心职责�?            1. 法务审核：审核各类决策和方案的合法�?            2. 合规校验：确保操作符合法规政�?            3. 漏洞检测：识别安全漏洞和合规风�?            4. 风险排查：系统性排查各类风险点
            5. 内容纠错：纠正不合规的内容表�?
            工作原则�?            - 审核要严格依法，不打擦边�?            - 风险识别要全面，不留死角
            - 纠错要及时准确，防止风险扩散
        """.trimIndent()
    )
}
