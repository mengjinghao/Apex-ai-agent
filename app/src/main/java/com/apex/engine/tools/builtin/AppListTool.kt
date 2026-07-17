package com.apex.engine.tools.builtin

import android.content.Context
import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCategory
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolResult

/** 应用列表工具 */
class AppListTool(private val context: Context) : ApexTool {
    override val metadata = ToolMetadata(
        id = "app_list",
        name = "应用列表",
        description = "列出设备上已安装的应用",
        category = ToolCategory.APP,
        isReadOnly = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val sb = StringBuilder()
        sb.append("已安装应用共 ${packages.size} 个：\n\n")
        for (pkg in packages.take(50)) {
            val label = pm.getApplicationLabel(pkg)
            sb.append("• $label (${pkg.packageName})\n")
        }
        if (packages.size > 50) {
            sb.append("\n... 还有 ${packages.size - 50} 个")
        }
        return ToolResult.Success(sb.toString())
    }
}
