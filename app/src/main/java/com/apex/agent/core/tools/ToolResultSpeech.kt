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
 * Speech domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable

/** 语音服务 STT HTTP 配置条目 */

@Serializable

/** 获取语音服务配置结果 */

@Serializable

/** 更新语音服务配置结果 */

@Serializable

/** TTS 单次播放测试结果 */

@Serializable

/** 模型配置条目 */

