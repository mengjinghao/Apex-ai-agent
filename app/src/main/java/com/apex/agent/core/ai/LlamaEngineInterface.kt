package com.apex.agent.core.ai

/**
 * LLM 引擎接口 - 所有需要 LLM 生成能力的组件共享此接口
 *
 * 位置: 移除了 NaturalLanguageTaskParser 和 QualityAssuredCodeGenerator 中的重复定义，
 * 统一放在 com.apex.agent.core.ai 包下供各子模块引用
 */
interface LlamaEngineInterface {
    suspend fun generate(prompt: String): String
}
