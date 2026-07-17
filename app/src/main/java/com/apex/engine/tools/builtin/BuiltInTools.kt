package com.apex.engine.tools.builtin

import android.content.Context
import com.apex.core.model.ApexTool

/** 内置工具集合 — 提供默认工具注册 */
object BuiltInTools {
    fun createAll(context: Context): List<ApexTool> {
        return listOf(
            HttpGetTool(),
            AppListTool(context),
            DateTimeTool()
        )
    }
}
