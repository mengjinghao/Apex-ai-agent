package com.apex.agent.core.normal

import com.apex.agent.core.normal.branching.ConversationBranching
import com.apex.agent.core.normal.clarification.ProactiveClarification
import com.apex.agent.core.normal.context.SmartContextCompressor
import com.apex.agent.core.normal.depth.AdaptiveResponseDepth
import com.apex.agent.core.normal.dependency.ToolCallDependencyAnalyzer
import com.apex.agent.core.normal.emotion.EmotionRecognitionEngine
import com.apex.agent.core.normal.export.ConversationExporter
import com.apex.agent.core.normal.feedback.UserFeedbackLearningSystem
import com.apex.agent.core.normal.health.ConversationHealthCollector
import com.apex.agent.core.normal.health.format
import com.apex.agent.core.normal.intent.ConversationIntentStateMachine
import com.apex.agent.core.normal.knowledge.KnowledgeGraphManager
import com.apex.agent.core.normal.mac.ToolMacroRegistry
import com.apex.agent.core.normal.memory.CrossSessionMemoryRAG
import com.apex.agent.core.normal.multilingual.MultilingualManager
import com.apex.agent.core.normal.multimodal.MultimodalInputParser
import com.apex.agent.core.normal.privacy.PrivacyManager
import com.apex.agent.core.normal.profile.UserProfileManager
import com.apex.agent.core.normal.quality.ConversationQualityEvaluator
import com.apex.agent.core.normal.quality.format
import com.apex.agent.core.normal.redactor.SensitiveDataRedactor
import com.apex.agent.core.normal.reminder.SmartReminderManager
import com.apex.agent.core.normal.rendering.StreamingMarkdownRenderer
import com.apex.agent.core.normal.scene.SceneTemplateRegistry
import com.apex.agent.core.normal.search.ConversationSearchEngine
import com.apex.agent.core.normal.shortcut.ShortcutCommandRegistry
import com.apex.agent.core.normal.suggestion.SmartSuggestionEngine
import com.apex.agent.core.normal.summary.ConversationSummaryGenerator
import com.apex.agent.core.normal.thinking.AnnotatedThinkingChain
import com.apex.agent.core.normal.thinking.ThinkingChainParser
import com.apex.agent.core.normal.toolpreview.ToolConfirmationGateway
import com.apex.agent.core.normal.toolpreview.ToolPreviewGenerator
import com.apex.agent.core.normal.tools.PersonalToolRegistry
import com.apex.agent.core.normal.visualization.ContextWindowVisualizer

/**
 * 普通 Agent 模式编排器
 *
 * 整合 45 项普通模式独有功能，提供统一的处理流程
 *
 * v1 功能 (F1-F15)：基础对话体验
 * v2 新增功能 (F16-F30)：企业级能力
 * v3 新增功能 (F31-F45)：趣味与个性化
 *   31. 玩梗模式      32. 人格化角色    33. 对话游戏
 *   34. 创意写作      35. 成就系统      36. 每日问候
 *   37. 冷知识百科    38. 表情包建议    39. 彩蛋系统
 *   40. 语气模仿      41. 辩论练习      42. 速读模式
 *   43. 节日感知      44. 对战模式      45. 昵称关系
 */
class NormalAgentOrchestrator(
    val config: NormalAgentConfig = NormalAgentConfig()
) {
    // ===== v1 组件 (F1-F15) =====
    val intentStateMachine = ConversationIntentStateMachine()
    val adaptiveDepth = AdaptiveResponseDepth()
    val contextCompressor = SmartContextCompressor(
        maxTokens = config.maxContextTokens,
        maxHistoryMessages = config.maxHistoryMessages
    )
    val userProfileManager = UserProfileManager()
    val crossSessionMemory = CrossSessionMemoryRAG()
    val markdownRenderer = StreamingMarkdownRenderer()
    val toolPreviewGenerator = ToolPreviewGenerator()
    val toolConfirmationGateway = ToolConfirmationGateway()
    val toolMacroRegistry = ToolMacroRegistry().apply { registerBuiltinMacros() }
    val conversationBranching = ConversationBranching()
    val thinkingChainParser = ThinkingChainParser()
    val proactiveClarification = ProactiveClarification()
    val personalToolRegistry = PersonalToolRegistry()
    val sceneTemplateRegistry = SceneTemplateRegistry()
    val sensitiveRedactor = SensitiveDataRedactor()
    val healthCollector = ConversationHealthCollector()

    // ===== v2 新增组件 (F16-F30) =====
    /** F16: 对话摘要生成器 */
    val summaryGenerator = ConversationSummaryGenerator()
    /** F17: 多模态输入解析器 */
    val multimodalParser = MultimodalInputParser()
    /** F18: 对话搜索引擎 */
    val searchEngine = ConversationSearchEngine()
    /** F19: 智能建议引擎 */
    val suggestionEngine = SmartSuggestionEngine()
    /** F20: 情感识别引擎 */
    val emotionEngine = EmotionRecognitionEngine()
    /** F21: 对话质量评估器 */
    val qualityEvaluator = ConversationQualityEvaluator()
    /** F22: 知识图谱管理器 */
    val knowledgeGraph = KnowledgeGraphManager()
    /** F23: 隐私管理器 */
    val privacyManager = PrivacyManager()
    /** F24: 对话导出器 */
    val exporter = ConversationExporter()
    /** F25: 智能提醒管理器 */
    val reminderManager = SmartReminderManager()
    /** F26: 快捷指令注册表 */
    val shortcutRegistry = ShortcutCommandRegistry()
    /** F27: 多语言管理器 */
    val multilingualManager = MultilingualManager()
    /** F28: 上下文窗口可视化器 */
    val contextVisualizer = ContextWindowVisualizer(contextCompressor)
    /** F29: 工具调用依赖分析器 */
    val toolCallAnalyzer = ToolCallDependencyAnalyzer()
    /** F30: 用户反馈学习系统 */
    val feedbackLearning = UserFeedbackLearningSystem()

    // ===== v3 新增组件 (F31-F45) =====
    /** F31: 玩梗模式引擎 */
    val memeEngine = com.apex.agent.core.normal.meme.MemeModeEngine()
    /** F32: 人格化角色注册表 */
    val personaRegistry = com.apex.agent.core.normal.persona.PersonaRegistry()
    /** F33: 对话游戏引擎 */
    val gameEngine = com.apex.agent.core.normal.game.ConversationGameEngine()
    /** F34: 创意写作工坊 */
    val writingWorkshop = com.apex.agent.core.normal.creative.CreativeWritingWorkshop()
    /** F35: 成就系统 */
    val achievementSystem = com.apex.agent.core.normal.achievement.AchievementSystem()
    /** F36: 每日问候系统 */
    val greetingSystem = com.apex.agent.core.normal.greeting.DailyGreetingSystem()
    /** F37: 冷知识与趣味问答 */
    val triviaSystem = com.apex.agent.core.normal.trivia.TriviaFunFactsSystem()
    /** F38: 表情包建议系统 */
    val stickerSystem = com.apex.agent.core.normal.sticker.StickerSuggestionSystem()
    /** F39: 彩蛋系统 */
    val easterEggSystem = com.apex.agent.core.normal.easter.EasterEggSystem()
    /** F40: 语气模仿器 */
    val impersonationEngine = com.apex.agent.core.normal.impersonation.ImpersonationEngine()
    /** F41: 辩论练习伙伴 */
    val debatePartner = com.apex.agent.core.normal.debate.DebatePartner()
    /** F42: 速读模式处理器 */
    val skimProcessor = com.apex.agent.core.normal.skimming.SkimModeProcessor()
    /** F43: 节日感知系统 */
    val festivalSystem = com.apex.agent.core.normal.festival.FestivalAwarenessSystem()
    /** F44: 对战模式系统 */
    val battleSystem = com.apex.agent.core.normal.battle.BattleModeSystem()
    /** F45: 昵称与关系记忆 */
    val nicknameSystem = com.apex.agent.core.normal.nickname.NicknameRelationshipSystem()

    /**
     * v2 增强版输入处理 - 在 v1 基础上增加情感识别、语言检测、知识图谱、智能建议
     */
    fun processInputEnhanced(
        userMessage: String,
        context: NormalAgentContext,
        recentMessages: List<Pair<String, String>> = emptyList()
    ): EnhancedInputProcessResult {
        // 先执行 v1 基础处理
        val baseResult = processInput(userMessage, context)

        val additionalInjections = mutableListOf<String>()
        val additionalActions = mutableListOf<NormalAction>()

        // F20: 情感识别
        val emotionAnalysis = emotionEngine.analyze(userMessage, context.chatId)
        if (emotionAnalysis.primaryEmotion.name != "NEUTRAL") {
            additionalInjections.add(emotionEngine.generateEmpathyPrompt(emotionAnalysis))
            additionalActions.add(NormalAction.EmotionDetected(emotionAnalysis.primaryEmotion))
        }

        // F27: 多语言检测
        val langDetection = multilingualManager.detect(userMessage)
        val responseLang = multilingualManager.decideResponseLanguage(langDetection, context.userId)
        val langPrompt = multilingualManager.generateLanguagePrompt(responseLang)
        if (langPrompt.isNotBlank()) additionalInjections.add(langPrompt)

        // F22: 知识图谱抽取
        val extraction = knowledgeGraph.extractFromText(userMessage, context.chatId)
        if (extraction.extractedNodes.isNotEmpty()) {
            val knowledgePrompt = knowledgeGraph.generateKnowledgePrompt(userMessage)
            if (knowledgePrompt.isNotBlank()) additionalInjections.add(knowledgePrompt)
        }

        // F25: 提醒提取
        val reminders = kotlinx.coroutines.runBlocking {
            reminderManager.extractFromMessage(userMessage, context.chatId, "current")
        }

        // F30: 反馈学习 prompt
        val feedbackPrompt = feedbackLearning.generateOptimizationPrompt(context.userId)
        if (feedbackPrompt.isNotBlank()) additionalInjections.add(feedbackPrompt)

        // F19: 智能建议
        val suggestionContext = com.apex.agent.core.normal.suggestion.SuggestionContext(
            currentInput = userMessage,
            recentMessages = recentMessages.map { (role, content) ->
                com.apex.agent.core.normal.suggestion.RecentMessage(role, content, System.currentTimeMillis())
            },
            userId = context.userId
        )
        val suggestions = suggestionEngine.suggest(suggestionContext)

        return EnhancedInputProcessResult(
            base = baseResult,
            emotionAnalysis = emotionAnalysis,
            languageDetection = langDetection,
            responseLanguage = responseLang,
            extractedEntities = extraction.extractedNodes.map { it.name },
            extractedRelations = extraction.extractedEdges.size,
            extractedReminders = reminders.size,
            suggestions = suggestions.suggestions,
            additionalInjections = additionalInjections
        )
    }

    /**
     * v2 增强版输出处理 - 增加质量评估、知识抽取、反馈记录
     */
    fun processOutputEnhanced(
        response: String,
        context: NormalAgentContext,
        latencyMs: Long,
        inputTokens: Long,
        outputTokens: Long,
        userMessage: String = "",
        roundIndex: Int = 0
    ): EnhancedOutputProcessResult {
        // 先执行 v1 基础处理
        val baseResult = processOutput(response, context, latencyMs, inputTokens, outputTokens)

        // F21: 质量评估
        val quality = qualityEvaluator.evaluateRound(
            chatId = context.chatId,
            roundIndex = roundIndex,
            userMessage = userMessage,
            assistantResponse = response,
            responseTimeMs = latencyMs,
            tokensUsed = inputTokens + outputTokens
        )

        // F22: 从 AI 回答中抽取知识
        val extraction = knowledgeGraph.extractFromText(response, context.chatId)

        // F16: 生成对话摘要（每 5 轮触发）
        var summary: com.apex.agent.core.normal.summary.ConversationSummary? = null
        if (roundIndex > 0 && roundIndex % 5 == 0) {
            // 实际应传入完整历史，这里简化
            summary = summaryGenerator.generate(
                chatId = context.chatId,
                messages = listOf(
                    com.apex.agent.core.normal.context.ConversationMessage(
                        id = "msg_$roundIndex",
                        role = com.apex.agent.core.normal.context.ConversationMessage.Role.ASSISTANT,
                        content = response
                    )
                ),
                strategy = com.apex.agent.core.normal.summary.SummaryStrategy.HYBRID
            )
        }

        return EnhancedOutputProcessResult(
            base = baseResult,
            quality = quality,
            extractedEntities = extraction.extractedNodes.map { it.name },
            summary = summary
        )
    }

    /**
     * 记录用户反馈
     */
    fun recordFeedback(
        type: com.apex.agent.core.normal.feedback.FeedbackType,
        context: NormalAgentContext,
        messageId: String,
        userMessage: String,
        assistantResponse: String,
        responseTimeMs: Long,
        originalContent: String? = null,
        editedContent: String? = null
    ) {
        val feedbackContext = com.apex.agent.core.normal.feedback.FeedbackContext(
            userMessage = userMessage,
            assistantResponse = assistantResponse,
            responseTimeMs = responseTimeMs
        )
        feedbackLearning.record(
            com.apex.agent.core.normal.feedback.FeedbackRecord(
                id = "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
                userId = context.userId,
                chatId = context.chatId,
                messageId = messageId,
                type = type,
                originalContent = originalContent,
                editedContent = editedContent,
                context = feedbackContext
            )
        )
    }

    /**
     * 生成全面状态报告
     */
    fun generateFullStatusReport(context: NormalAgentContext): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════")
        sb.appendLine("    普通 Agent 模式状态报告")
        sb.appendLine("═══════════════════════════════════")
        sb.appendLine()

        // 健康度
        healthCollector.getHealth(context.chatId)?.let {
            sb.appendLine(it.format())
            sb.appendLine()
        }

        // 质量评估
        val quality = qualityEvaluator.evaluateConversation(context.chatId)
        sb.appendLine(quality.format())
        sb.appendLine()

        // 情感追踪
        val emotionTrack = emotionEngine.getEmotionTrack(context.chatId)
        sb.appendLine("═══ 情感状态 ═══")
        sb.appendLine("主导情感: ${emotionTrack.dominantEmotion}")
        sb.appendLine("平均情感: ${emotionTrack.averageEmotion}")
        sb.appendLine("趋势: ${emotionTrack.emotionTrend}")
        sb.appendLine("追踪点数: ${emotionTrack.timeline.size}")
        sb.appendLine()

        // 隐私状态
        sb.appendLine(privacyManager.generateStatusReport())
        sb.appendLine()

        // 知识图谱统计
        val graphStats = knowledgeGraph.getFullGraph().stats
        sb.appendLine("═══ 知识图谱 ═══")
        sb.appendLine("节点: ${graphStats.totalNodes} | 边: ${graphStats.totalEdges}")
        sb.appendLine("平均连接数: ${graphStats.avgConnections}")
        sb.appendLine()

        // 反馈学习洞察
        val insights = feedbackLearning.getInsights(context.userId)
        if (insights.isNotEmpty()) {
            sb.appendLine("═══ 学习洞察 ═══")
            insights.take(5).forEach { insight ->
                sb.appendLine("- [${insight.type}] ${insight.description}")
            }
            sb.appendLine()
        }

        sb.appendLine("═══════════════════════════════════")
        return sb.toString()
    }

    /**
     * v3 增强版输入处理 - 增加玩梗识别、彩蛋检测、节日感知、昵称关系
     */
    fun processInputV3(
        userMessage: String,
        context: NormalAgentContext,
        recentMessages: List<Pair<String, String>> = emptyList()
    ): V3InputProcessResult {
        // 先执行 v2 增强
        val v2Result = processInputEnhanced(userMessage, context, recentMessages)

        val additionalInjections = mutableListOf<String>()
        val actions = mutableListOf<NormalAction>()

        // F31: 玩梗识别
        val memeDetection = memeEngine.detect(userMessage)
        if (memeDetection.totalMemes > 0) {
            val memePrompt = memeEngine.generateMemePrompt(userMessage, "casual")
            if (memePrompt.isNotBlank()) additionalInjections.add(memePrompt)
            actions.add(NormalAction.MemeDetected(memeDetection.totalMemes))
            achievementSystem.recordMetric(context.userId, "memes_used", memeDetection.totalMemes.toLong())
        }

        // F39: 彩蛋检测
        val easterEgg = easterEggSystem.check(userMessage, context.userId)
        if (easterEgg != null) {
            additionalInjections.add("[彩蛋触发] ${easterEgg.egg.emoji} ${easterEgg.message}")
            actions.add(NormalAction.EasterEggTriggered(easterEgg.egg.id))
        }

        // F43: 节日感知
        val festivalPrompt = festivalSystem.generateFestivalPrompt()
        if (festivalPrompt.isNotBlank()) additionalInjections.add(festivalPrompt)

        // F36: 每日问候（首次对话）
        if (recentMessages.isEmpty()) {
            val greetingPrompt = greetingSystem.generateGreetingPrompt(context.userId)
            if (greetingPrompt.isNotBlank()) additionalInjections.add(greetingPrompt)
        }

        // F45: 昵称关系
        val relationshipPrompt = nicknameSystem.generateRelationshipPrompt(context.userId)
        if (relationshipPrompt.isNotBlank()) additionalInjections.add(relationshipPrompt)

        // F32: 人格化角色
        val personaPrompt = personaRegistry.generatePersonaPrompt(context.chatId)
        if (personaPrompt.isNotBlank()) additionalInjections.add(personaPrompt)

        // F40: 语气模仿
        val impersonationPrompt = impersonationEngine.generateImpersonationPrompt(context.chatId)
        if (impersonationPrompt.isNotBlank()) additionalInjections.add(impersonationPrompt)

        // F38: 表情包建议
        val stickerPrompt = stickerSystem.generateStickerPrompt(userMessage, v2Result.emotionAnalysis.primaryEmotion.name)
        if (stickerPrompt.isNotBlank()) additionalInjections.add(stickerPrompt)

        return V3InputProcessResult(
            v2 = v2Result,
            memeDetection = memeDetection,
            easterEgg = easterEgg,
            additionalInjections = additionalInjections,
            actions = actions
        )
    }

    /**
     * v3 增强版输入处理（含网络搜梗）- suspend 版本
     *
     * 在 processInputV3 基础上增加网络实时搜梗：
     * - 自动识别未知梗并网络搜索解释
     * - 注入流行梗提示
     * - 网络搜梗结果注入 prompt
     */
    suspend fun processInputV3WithWebSearch(
        userMessage: String,
        context: NormalAgentContext,
        recentMessages: List<Pair<String, String>> = emptyList()
    ): V3InputProcessResult {
        // 先执行 v3 基础处理
        val result = processInputV3(userMessage, context, recentMessages)

        val webInjections = mutableListOf<String>()

        // F31 增强: 网络搜梗 - 自动识别未知梗并查询解释
        if (memeEngine.isWebSearchEnabled()) {
            val webMemePrompt = memeEngine.generateWebMemeLookupPrompt(userMessage)
            if (webMemePrompt.isNotBlank()) {
                webInjections.add(webMemePrompt)
            }
        }

        // 如果本地没识别到梗，但网络搜梗发现了，也加入 actions
        if (result.memeDetection.totalMemes == 0 && webInjections.isNotEmpty()) {
            // 网络发现了梗
        }

        return result.copy(
            additionalInjections = result.additionalInjections + webInjections
        )
    }

    /**
     * 网络搜梗：搜索梗的含义
     */
    suspend fun searchMemeOnline(query: String): com.apex.agent.core.normal.meme.web.MemeSearchResult {
        return memeEngine.searchMemeOnline(query)
    }

    /**
     * 网络搜梗：梗百科查询
     */
    suspend fun lookupMemeOnline(query: String): com.apex.agent.core.normal.meme.web.MemeWikiResult {
        return memeEngine.lookupMemeOnline(query)
    }

    /**
     * 网络搜梗：获取流行梗
     */
    suspend fun getTrendingMemes(limit: Int = 10): List<com.apex.agent.core.normal.meme.web.HotSearchProvider.TrendingMeme> {
        return memeEngine.getTrendingMemes(limit)
    }

    /**
     * 网络搜梗：增强版梗解释（本地 + 网络）
     */
    suspend fun explainMemeEnhanced(query: String): String {
        return memeEngine.explainMemeEnhanced(query)
    }

    /**
     * 网络搜梗：搜索建议
     */
    suspend fun suggestMemes(query: String): List<String> {
        return memeEngine.suggestMemes(query)
    }

    /**
     * v3 增强版输出处理 - 增加玩梗注入、表情建议、成就记录
     */
    fun processOutputV3(
        response: String,
        context: NormalAgentContext,
        latencyMs: Long,
        inputTokens: Long,
        outputTokens: Long,
        userMessage: String = "",
        roundIndex: Int = 0
    ): V3OutputProcessResult {
        // 先执行 v2
        val v2Result = processOutputEnhanced(response, context, latencyMs, inputTokens, outputTokens, userMessage, roundIndex)

        // F31: 玩梗注入
        val memeResult = memeEngine.generateResponse(userMessage, response, "casual")

        // F38: 表情建议
        val stickerSuggestions = stickerSystem.suggest(response, v2Result.quality.scores.keys.firstOrNull()?.name)

        // F35: 记录成就指标
        achievementSystem.recordMetric(context.userId, "messages", 1)
        if (roundIndex == 0) achievementSystem.recordMetric(context.userId, "daily_chat", 1)

        return V3OutputProcessResult(
            v2 = v2Result,
            memeEnhancedResponse = memeResult.content,
            usedMemes = memeResult.usedMemes,
            stickerSuggestions = stickerSuggestions
        )
    }

    /**
     * 生成 v3 全面状态报告（含玩梗统计、成就、彩蛋进度）
     */
    fun generateV3FullStatusReport(context: NormalAgentContext): String {
        val base = generateFullStatusReport(context)
        val sb = StringBuilder(base)

        // 玩梗统计
        sb.appendLine()
        sb.appendLine("═══ 玩梗统计 ═══")
        val popularMemes = memeEngine.getPopularMemes(3)
        sb.appendLine("热门梗:")
        popularMemes.forEach { sb.appendLine("  ${it.name}: ${it.displayName} (热度 ${(it.popularity * 100).toInt()}%)") }
        val usedMemes = memeEngine.getMostUsedMemes(3)
        if (usedMemes.isNotEmpty()) {
            sb.appendLine("你常用的梗:")
            usedMemes.forEach { sb.appendLine("  ${it.name}") }
        }

        // 成就系统
        sb.appendLine()
        sb.appendLine(achievementSystem.generateReport(context.userId))

        // 彩蛋进度
        sb.appendLine()
        val eggProgress = easterEggSystem.getProgress(context.userId)
        sb.appendLine("═══ 彩蛋进度 ═══")
        sb.appendLine("已发现: ${eggProgress.discovered}/${eggProgress.total} (${(eggProgress.ratio * 100).toInt()}%)")
        val hint = easterEggSystem.generateHint()
        if (hint.isNotBlank()) sb.appendLine("提示: $hint")

        // 关系记忆
        sb.appendLine()
        val profile = nicknameSystem.getUserProfile(context.userId)
        sb.appendLine("═══ 关系记忆 ═══")
        profile.nickname?.let { sb.appendLine("昵称: ${it.nickname}") }
        profile.relationship?.let { sb.appendLine("关系: ${it.type}") }
        sb.appendLine("共同记忆: ${profile.memoryCount} 条")
        sb.appendLine("里程碑: ${profile.milestoneCount} 个")

        return sb.toString()
    }

    /**
     * 处理用户输入（生成 prompt 注入）
     */
    fun processInput(
        userMessage: String,
        context: NormalAgentContext
    ): InputProcessResult {
        val chatId = context.chatId
        val userId = context.userId
        val injections = mutableListOf<String>()
        val actions = mutableListOf<NormalAction>()

        // 1. 敏感信息脱敏
        val redactedMessage = if (config.enableSensitiveRedaction) {
            val redacted = sensitiveRedactor.redact(userMessage, context.sessionId)
            if (redacted.detectedTypes.isNotEmpty()) {
                actions.add(NormalAction.SensitiveDetected(redacted.detectedTypes))
            }
            redacted.redacted
        } else userMessage

        // 2. 用户偏好画像
        if (config.enableUserProfile) {
            val profileSnippet = userProfileManager.generatePromptSnippet(userId)
            if (profileSnippet.isNotBlank()) injections.add(profileSnippet)
            // 学习用户偏好
            userProfileManager.learnFromMessage(userId, userMessage, "")
        }

        // 3. 场景模板
        if (config.enableSceneTemplates) {
            val scenePrompt = sceneTemplateRegistry.generateScenePrompt(chatId)
            if (scenePrompt.isNotBlank()) injections.add(scenePrompt)
        }

        // 4. 跨会话记忆检索
        if (config.enableCrossSessionMemory) {
            val memoryPrompt = crossSessionMemory.generateRelatedHistoryPrompt(
                userMessage, excludeSessionId = context.sessionId
            )
            if (memoryPrompt.isNotBlank()) injections.add(memoryPrompt)
        }

        // 5. 对话意图状态机
        if (config.enableIntentTracking) {
            val intentState = intentStateMachine.detect(chatId, userMessage, emptyList())
            val intentPrompt = intentStateMachine.getIntentPrompt(chatId)
            if (intentPrompt.isNotBlank()) injections.add(intentPrompt)
            actions.add(NormalAction.IntentDetected(intentState.currentIntent))
        }

        // 6. 主动澄清检测
        if (config.enableProactiveClarification) {
            val clarification = proactiveClarification.detect(userMessage, mapOf())
            if (clarification.needed) {
                val clarPrompt = proactiveClarification.generateClarificationPrompt(clarification)
                injections.add(clarPrompt)
                actions.add(NormalAction.ClarificationNeeded(clarification.combinedQuestion))
                healthCollector.onClarification(chatId)
            }
        }

        // 7. 回答深度自适应
        if (config.enableAdaptiveDepth) {
            val userPref = userProfileManager.get(userId).responsePreference.depth
            val depth = adaptiveDepth.resolve(
                userMessage,
                userPreference = runCatching { ResponseDepth.valueOf(userPref.uppercase()) }.getOrNull(),
                isFollowUp = intentStateMachine.getCurrentState(chatId)?.currentIntent?.name == "FOLLOW_UP"
            )
            val depthPrompt = adaptiveDepth.generateDepthPrompt(depth)
            injections.add(depthPrompt)
            actions.add(NormalAction.DepthResolved(depth))
        }

        // 8. 个人工具集
        if (config.enablePersonalTools) {
            val toolsPrompt = personalToolRegistry.generateToolsPrompt()
            if (toolsPrompt.isNotBlank()) injections.add(toolsPrompt)
        }

        return InputProcessResult(
            originalMessage = userMessage,
            redactedMessage = redactedMessage,
            injections = injections,
            actions = actions
        )
    }

    /**
     * 处理 AI 输出
     */
    fun processOutput(
        response: String,
        context: NormalAgentContext,
        latencyMs: Long,
        inputTokens: Long,
        outputTokens: Long
    ): OutputProcessResult {
        val chatId = context.chatId

        // 10. 流式渲染（这里处理完整响应）
        val renderTree = if (config.enableStreamingRendering) {
            markdownRenderer.reset()
            response.split("\n").forEach { markdownRenderer.feed(it + "\n") }
            markdownRenderer.finalize()
        } else null

        // 11. 思考链解析
        val thinkingChain = if (config.enableThinkingAnnotation) {
            thinkingChainParser.extractFromResponse(response)
        } else null

        // 记录到跨会话记忆
        if (config.enableCrossSessionMemory) {
            crossSessionMemory.remember(
                sessionId = context.sessionId,
                role = "assistant",
                content = response,
                importance = 1.0f
            )
        }

        // 还原脱敏
        val restoredResponse = if (config.enableSensitiveRedaction) {
            sensitiveRedactor.restoreWithSession(response, context.sessionId)
        } else response

        // 更新健康度
        if (config.enableHealthDashboard) {
            healthCollector.onAssistantResponse(
                chatId = chatId,
                latencyMs = latencyMs,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                contextTokensUsed = inputTokens.toInt(),
                contextTokensMax = config.maxContextTokens
            )
        }

        return OutputProcessResult(
            originalResponse = response,
            restoredResponse = restoredResponse,
            renderTree = renderTree,
            thinkingChain = thinkingChain
        )
    }

    /**
     * 处理工具调用（预估与确认）
     */
    suspend fun processToolCall(
        toolCallId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        context: NormalAgentContext
    ): ToolCallProcessResult {
        val chatId = context.chatId

        if (!config.enableToolPreview) {
            return ToolCallProcessResult(approved = true, preview = null)
        }

        // 生成预览
        val preview = toolPreviewGenerator.generate(toolCallId, toolName, arguments)
        val previewText = toolPreviewGenerator.formatPreview(preview)

        // 请求确认
        val result = toolConfirmationGateway.requestConfirmation(preview)

        val approved = when (result) {
            is com.apex.agent.core.normal.toolpreview.ConfirmationResult.Approved -> {
                healthCollector.onToolCall(chatId, true)
                true
            }
            is com.apex.agent.core.normal.toolpreview.ConfirmationResult.Rejected -> {
                healthCollector.onToolCall(chatId, false)
                false
            }
            is com.apex.agent.core.normal.toolpreview.ConfirmationResult.TimedOut -> false
        }

        return ToolCallProcessResult(
            approved = approved,
            preview = preview,
            previewText = previewText
        )
    }

    /**
     * 添加对话消息（分支管理）
     */
    fun addMessage(
        context: NormalAgentContext,
        role: com.apex.agent.core.normal.branching.BranchMessage.Role,
        content: String,
        branchLabel: String? = null
    ) {
        if (config.enableConversationBranching) {
            conversationBranching.addMessage(context.chatId, role, content, branchLabel)
        }
    }

    /**
     * 从某消息分叉
     */
    fun forkConversation(context: NormalAgentContext, fromMessageId: String, label: String) {
        if (config.enableConversationBranching) {
            conversationBranching.fork(context.chatId, fromMessageId, label)
            healthCollector.onBranch(context.chatId)
        }
    }

    /**
     * 获取健康度报告
     */
    fun getHealthReport(context: NormalAgentContext): String? {
        if (!config.enableHealthDashboard) return null
        val health = healthCollector.getHealth(context.chatId) ?: return null
        return health.format()
    }

    /**
     * 重置会话
     */
    fun resetSession(context: NormalAgentContext) {
        intentStateMachine.reset(context.chatId)
        sceneTemplateRegistry.clear(context.chatId)
        sensitiveRedactor.clearSession(context.sessionId)
        healthCollector.reset(context.chatId)
    }
}

/**
 * 输入处理结果
 */
data class InputProcessResult(
    val originalMessage: String,
    val redactedMessage: String,
    val injections: List<String>,
    val actions: List<NormalAction>
) {
    /**
     * 生成完整 prompt 注入文本
     */
    fun generateInjectionsText(): String {
        return injections.joinToString("\n\n") { it }
    }
}

/**
 * 输出处理结果
 */
data class OutputProcessResult(
    val originalResponse: String,
    val restoredResponse: String,
    val renderTree: com.apex.agent.core.normal.rendering.RenderTree?,
    val thinkingChain: com.apex.agent.core.normal.thinking.ThinkingChain?
)

/**
 * 工具调用处理结果
 */
data class ToolCallProcessResult(
    val approved: Boolean,
    val preview: com.apex.agent.core.normal.toolpreview.ToolPreview?,
    val previewText: String? = null
)

/**
 * 普通 Agent 动作
 */
sealed class NormalAction {
    data class IntentDetected(val intent: com.apex.agent.core.normal.intent.ConversationIntent) : NormalAction()
    data class DepthResolved(val depth: ResponseDepth) : NormalAction()
    data class ClarificationNeeded(val question: String) : NormalAction()
    data class SensitiveDetected(val types: Set<com.apex.agent.core.normal.redactor.SensitiveType>) : NormalAction()
    data class EmotionDetected(val emotion: com.apex.agent.core.normal.emotion.Emotion) : NormalAction()
    data class MemeDetected(val count: Int) : NormalAction()
    data class EasterEggTriggered(val eggId: String) : NormalAction()
}

/**
 * v2 增强版输入处理结果
 */
data class EnhancedInputProcessResult(
    val base: InputProcessResult,
    val emotionAnalysis: com.apex.agent.core.normal.emotion.EmotionAnalysis,
    val languageDetection: com.apex.agent.core.normal.multilingual.LanguageDetectionResult,
    val responseLanguage: com.apex.agent.core.normal.multilingual.Language,
    val extractedEntities: List<String>,
    val extractedRelations: Int,
    val extractedReminders: Int,
    val suggestions: List<com.apex.agent.core.normal.suggestion.Suggestion>,
    val additionalInjections: List<String>
) {
    fun generateFullInjectionsText(): String {
        return (base.injections + additionalInjections).joinToString("\n\n") { it }
    }
}

/**
 * v2 增强版输出处理结果
 */
data class EnhancedOutputProcessResult(
    val base: OutputProcessResult,
    val quality: com.apex.agent.core.normal.quality.RoundQuality,
    val extractedEntities: List<String>,
    val summary: com.apex.agent.core.normal.summary.ConversationSummary?
)

/**
 * v3 增强版输入处理结果
 */
data class V3InputProcessResult(
    val v2: EnhancedInputProcessResult,
    val memeDetection: com.apex.agent.core.normal.meme.MemeDetectionResult,
    val easterEgg: com.apex.agent.core.normal.easter.EasterEggResult?,
    val additionalInjections: List<String>,
    val actions: List<NormalAction>
) {
    fun generateFullInjectionsText(): String {
        return (v2.generateFullInjectionsText() + "\n" + additionalInjections.joinToString("\n\n")).trim()
    }
}

/**
 * v3 增强版输出处理结果
 */
data class V3OutputProcessResult(
    val v2: EnhancedOutputProcessResult,
    val memeEnhancedResponse: String,
    val usedMemes: List<com.apex.agent.core.normal.meme.Meme>,
    val stickerSuggestions: List<com.apex.agent.core.normal.sticker.StickerSuggestion>
)
