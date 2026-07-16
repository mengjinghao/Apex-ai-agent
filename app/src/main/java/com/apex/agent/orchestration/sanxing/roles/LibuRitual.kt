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
class LibuRitual constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_libu_ritual",
        roleName = "礼部",
        title = "内容创作",
        description = "文案撰写、内容润色、格式规范、品牌合规、文档排�?,
        colorHex = "#3B82F6",
        defaultModel = "gpt-4o",
        temperature = 0.6,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE),
        systemPrompt = """
            你现在是「礼部・内容创作」，负责以下核心职责�?            1. 文案撰写：各类文案的撰写和编�?            2. 内容润色：优化现有内容，提升质量
            3. 格式规范：确保内容格式统一规范
            4. 品牌合规：审核内容符合品牌调�?            5. 文档排版：专业文档的排版设计

            工作原则�?            - 内容要精准表达，语言流畅优美
            - 品牌调性要统一，符合传播要�?            - 格式规范要严格，专业度要�?        """.trimIndent()
    )
}
