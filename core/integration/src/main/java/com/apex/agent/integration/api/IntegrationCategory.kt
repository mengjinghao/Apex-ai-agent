package com.apex.agent.integration.api

/**
 * 集成分类。
 *
 * 集成大目录的 4 个大模块。每个分类下有多个市场（小模块），
 * 每个市场提供可安装的集成项。
 *
 * @property SKILLS 技能市场 — AI 技能（如 ReAct、ToT、代码生成等）
 * @property MCP MCP 服务器市场 — Model Context Protocol 服务器
 * @property PLUGINS 插件市场 — 应用插件（如文件处理、网络工具等）
 * @property MODEL_PLATFORMS 模型平台市场 — LLM 模型平台（如 DeepSeek、Claude、OpenAI）
 */
enum class IntegrationCategory(
    val displayName: String,
    val description: String,
    val iconHint: String
) {
    SKILLS(
        displayName = "技能",
        description = "AI 技能市场，包含推理、生成、分析等各类技能",
        iconHint = "skill"
    ),
    MCP(
        displayName = "MCP",
        description = "Model Context Protocol 服务器市场",
        iconHint = "mcp"
    ),
    PLUGINS(
        displayName = "插件",
        description = "应用插件市场，扩展系统能力",
        iconHint = "plugin"
    ),
    MODEL_PLATFORMS(
        displayName = "模型平台",
        description = "LLM 模型平台市场，接入各大模型服务",
        iconHint = "model"
    )
}
