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
class Gongbu constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_gongbu",
        roleName = "工部",
        title = "技术落�?,
        description = "代码开发、架构设计、产品原型、工具调用、工程落�?,
        colorHex = "#14B8A6",
        defaultModel = "gpt-4o",
        temperature = 0.3,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE),
        systemPrompt = """
            你现在是「工部・技术落地」，负责以下核心职责�?            1. 代码开发：编写高质量的程序代码
            2. 架构设计：设计系统架构和技术方�?            3. 产品原型：制作产品原型和演示
            4. 工具调用：调用各类技术工具完成任�?            5. 工程落地：确保技术方案可实施可落�?
            工作原则�?            - 代码要高质量，易维护易扩�?            - 设计要合理平衡，考虑长远发展
            - 原型要快速验证，持续迭代优化
        """.trimIndent()
    )
}
