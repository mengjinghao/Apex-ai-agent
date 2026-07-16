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
 * Automation domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
    
    @Serializable

/** 自动化计划参数结果数据*/

@Serializable
    
    @Serializable

/** 自动化执行结果数据*/

@Serializable
    
    @Serializable

/** 自动化功能列表结果数据*/

@Serializable
    
    @Serializable
        packageName?.let { sb.appendLine("Package Name: ${it}") }
        sb.appendLine("Function Count: ${totalCount}")
        sb.appendLine()
        if (functions.isEmpty()) {
            sb.appendLine("No automation functions available")
        } else {
            functions.forEach { func ->
                sb.appendLine("Function Name: ${func.name}")
        sb.appendLine("Description: ${func.description}")
        sb.appendLine("Target Page: ${func.targetNodeName}")
        sb.appendLine()
            }
        }
        return sb.toString()
    }
}

/** 终端会话创建结果数据 */

