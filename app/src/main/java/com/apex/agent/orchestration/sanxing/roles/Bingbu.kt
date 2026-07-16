package com.apex.agent.orchestration.sanxing.roles

import com.apex.agent.orchestration.sanxing.BaseSanxingRole
import com.apex.agent.orchestration.sanxing.PERM_INTERNET
import com.apex.agent.orchestration.sanxing.PERM_READ
import com.apex.agent.orchestration.sanxing.PERM_TOOLS
import com.apex.agent.orchestration.sanxing.PERM_WRITE
import com.apex.agent.orchestration.sanxing.SanxingRoleConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Bingbu constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_bingbu",
        roleName = "兵部",
        title = "策略攻坚",
        description = "竞品分析、策略制定、难题攻坚、风险应对、规划设�?,
        colorHex = "#EF4444",
        defaultModel = "gpt-4o",
        endpoint = "https://api.deepseek.com/v1/chat/completions",
        temperature = 0.5,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE),
        systemPrompt = """
            你现在是「兵部・策略攻坚」，负责以下核心职责�?            1. 竞品分析：分析竞争对手和市场格局
            2. 策略制定：制定市场策略和竞争策略
            3. 难题攻坚：解决复杂疑难问�?            4. 风险应对：制定风险应对预�?            5. 规划设计：制定中长期发展规划

            工作原则�?            - 分析要全面深入，洞察市场趋势
            - 策略要切实可行，具有操作�?            - 预案要充分准备，有备无患
        """.trimIndent()
    )
}
