package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * Web domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable

        // 添加Cookie信息
    if (cookies.isNotEmpty()) {
            sb.appendLine("Cookies: ${cookies.size}")
        cookies.entries.take(5).forEach { (name, value) ->
                val _kaptFix26 = if (value.length > 30) "..." else ""
                sb.appendLine("  ${name}: ${value.take(30)}${_kaptFix26}")
            }
        if (cookies.size > 5) {
                sb.appendLine("  ... and ${cookies.size - 5} more cookies")
            }
        }
        sb.appendLine()
        sb.appendLine("Content Summary:")
        sb.append(content)
        return sb.toString()
    }
}

/** 系统设置数据 */

@Serializable

    @Serializable

/** Intent execution result data */

@Serializable

/** 文件查找结果数据 */

@Serializable

    @Serializable

/** 通知数据结构 */

