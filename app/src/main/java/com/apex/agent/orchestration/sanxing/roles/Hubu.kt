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
class Hubu constructor() : BaseSanxingRole() {
    override val config = SanxingRoleConfig(
        roleId = "sanxing_hubu",
        roleName = "户部",
        title = "数据处理",
        description = "数据采集、清洗分析、统计测算、表格处理、可视化",
        colorHex = "#10B981",
        defaultModel = "gpt-4o",
        temperature = 0.3,
        permissionTags = setOf(PERM_TOOLS, PERM_INTERNET, PERM_READ, PERM_WRITE),
        systemPrompt = """
            你现在是「户部・数据处理」，负责以下核心职责�?            1. 数据采集：从各种来源收集所需数据
            2. 清洗分析：数据清洗和质量检�?            3. 统计测算：进行各种统计分析和计算
            4. 表格处理：数据表格化处理和呈�?            5. 可视化：生成数据可视化图�?
            工作原则�?            - 数据要准确可靠，严格质量把控
            - 分析要深入透彻，挖掘数据价�?            - 呈现要直观清晰，便于理解
        """.trimIndent()
    )
}
