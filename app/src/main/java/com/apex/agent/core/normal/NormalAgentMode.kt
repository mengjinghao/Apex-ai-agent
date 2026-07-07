package com.apex.agent.core.normal

/**
 * 普通 Agent 模式（NORMAL Mode）核心定义
 *
 * 与多 Agent 模式（MULTI_AGENT）和狂暴模式（BERSERK）的本质区别：
 *
 * | 维度 | NORMAL（普通） | MULTI_AGENT | BERSERK |
 * |------|---------------|-------------|---------|
 * | 核心抽象 | 单 Agent + 单 stream + 用户为中心 | 多 Agent + 消息总线 + 协作 | 单 Agent + 推理策略链 + 暴力执行 |
 * | 工具调用 | 串行 + 权限确认 + 预估 | 多 Agent 各自调用 + 冲突解决 | 并行 racing + tool fusion + 无限重试 |
 * | 权限 | 三级 ALLOW/ASK/FORBID + 悬浮确认 | Agent 权限矩阵 | 跳过所有权限检查 |
 * | 记忆 | 用户偏好 + 跨会话 RAG + 遗忘曲线 | Agent 间协作记忆 | 任务级策略记忆 |
 * | 上下文 | 智能分层压缩 + 意图追踪 | 各 Agent 独立 | 无限上下文技能 |
 * | 用户控制 | 高（每步可干预） | 中（监督协作） | 低（启动后放任） |
 *
 * 普通 Agent 模式的 15 项独有功能：
 *  1. 对话意图状态机 - 跨轮次追踪用户意图
 *  2. 回答深度自适应 - 根据问题难度调节深度
 *  3. 智能上下文压缩器 - 分层压缩长对话
 *  4. 用户偏好画像 - 长期学习用户风格
 *  5. 跨会话记忆检索 - 向量检索历史对话
 *  6. 流式 Markdown 渲染器 - 增量渲染无抖动
 *  7. 工具调用预估与确认 - 执行前预览
 *  8. 工具调用宏 - 用户自定义工具序列
 *  9. 对话分支与回溯 - Git 式对话分支
 *  10. 思考链注解 - 可解释性展示
 *  11. 主动澄清机制 - 歧义时反问
 *  12. 个人化工具集 - 用户私有工具
 *  13. 场景化对话模板 - 编程/写作/翻译等
 *  14. 敏感信息脱敏 - 自动识别密钥密码
 *  15. 对话健康度仪表盘 - 实时体验指标
 */

/**
 * 普通 Agent 模式配置
 */
data class NormalAgentConfig(
    val enableIntentTracking: Boolean = true,
    val enableAdaptiveDepth: Boolean = true,
    val enableSmartCompression: Boolean = true,
    val enableUserProfile: Boolean = true,
    val enableCrossSessionMemory: Boolean = true,
    val enableStreamingRendering: Boolean = true,
    val enableToolPreview: Boolean = true,
    val enableToolMacro: Boolean = true,
    val enableConversationBranching: Boolean = true,
    val enableThinkingAnnotation: Boolean = true,
    val enableProactiveClarification: Boolean = true,
    val enablePersonalTools: Boolean = true,
    val enableSceneTemplates: Boolean = true,
    val enableSensitiveRedaction: Boolean = true,
    val enableHealthDashboard: Boolean = true,
    val maxContextTokens: Int = 32_000,
    val maxHistoryMessages: Int = 50,
    val defaultResponseDepth: ResponseDepth = ResponseDepth.STANDARD
)

/**
 * 回答深度等级
 */
enum class ResponseDepth {
    /** 一句话回答 */
    BRIEF,
    /** 标准段落 */
    STANDARD,
    /** 详细分点 */
    DETAILED,
    /** 深度长文 */
    COMPREHENSIVE
}

/**
 * 普通 Agent 模式运行时上下文
 */
data class NormalAgentContext(
    val chatId: String,
    val userId: String = "default",
    val sessionId: String = "session_${System.currentTimeMillis()}",
    val config: NormalAgentConfig = NormalAgentConfig(),
    val variables: MutableMap<String, Any> = mutableMapOf()
)

/**
 * 普通 Agent 模式处理结果
 */
sealed class NormalAgentResult {
    data class Success(
        val content: String,
        val depth: ResponseDepth,
        val intent: String,
        val toolsUsed: List<String> = emptyList(),
        val thinkingChain: List<String>? = null,
        val metadata: Map<String, Any> = emptyMap()
    ) : NormalAgentResult()

    data class NeedsClarification(
        val question: String,
        val options: List<String> = emptyList()
    ) : NormalAgentResult()

    data class NeedsToolConfirmation(
        val toolName: String,
        val params: Map<String, Any>,
        val estimatedDurationMs: Long,
        val requiredPermission: String
    ) : NormalAgentResult()

    data class Failure(val error: String, val throwable: Throwable? = null) : NormalAgentResult()
}
