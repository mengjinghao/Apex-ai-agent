package com.apex.engine.tools.builtin

import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCategory
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolResult
import java.text.SimpleDateFormat
import java.util.*

/** 日期时间工具 */
class DateTimeTool : ApexTool {
    override val metadata = ToolMetadata(
        id = "datetime",
        name = "日期时间",
        description = "获取当前日期和时间",
        category = ToolCategory.GENERAL,
        isReadOnly = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val now = Date()
        val dateFmt = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        val result = "当前日期：${dateFmt.format(now)}\n当前时间：${timeFmt.format(now)}"
        return ToolResult.Success(result)
    }
}
