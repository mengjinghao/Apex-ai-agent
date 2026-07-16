package com.apex.agent.core.normal.meme

import java.util.concurrent.ConcurrentHashMap

/**
 * F31: 玩梗模式（Meme Mode）
 *
 * 识别、生成、适配网络梗与文化梗：
 * - 梗识别：检测用户输入中的梗
 * - 梗生成：根据上下文生成合适的梗回复
 * - 场景适配：根据场景决定是否使用梗
 * - 梗库管理：用户可添加自定义梗
 * - 梗热度追踪：流行梗更新
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不玩梗
 * - 狂暴只关心执行
 * - 本功能让单 Agent **有趣、有梗、有灵魂**
 */

/**
 * 梗类型
 */
enum class MemeType {
    // 中文网络梗
    CHINESE_INTERNET,    // 中文网络梗（如：绝绝子、yyds、破防了）
    CHINESE_CLASSIC,     // 中文经典梗（如：孔乙己、阿Q精神）
    CHINESE_TV,          // 影视梗（如：臣妾做不到、真香）

    // 英文网络梗
    ENGLISH_INTERNET,    // 英文网络梗（如：lol, rofl, brain moment）
    ENGLISH_CLASSIC,     // 英文经典梗（如：To be or not to be）

    // 程序员梗
    PROGRAMMER,          // 程序员梗（如：hello world, 404, IT Crowd）

    // 游戏梗
    GAMING,              // 游戏梗（如：GG, AFK, 60帧）

    // 动漫梗
    ANIME,               // 动漫梗（如：中二病, 傲娇）

    // 流行文化
    POP_CULTURE,         // 流行文化梗

    // 谐音梗
    PUN,                 // 谐音梗

    // 自定义
    CUSTOM               // 用户自定义梗
}

/**
 * 梗定义
 */
data class Meme(
    val id: String,
    val name: String,              // 梗名（如"yyds"）
    val displayName: String,       // 显示名（如"永远的神"）
    val type: MemeType,
    val keywords: List<String>,    // 触发关键词
    val description: String,       // 梗的含义/出处
    val usage: String,             // 使用场景
    val example: String,           // 示例用法
    val responses: List<String>,   // 可选回复模板
    val appropriateScenes: Set<String>,  // 适用场景
    val inappropriateScenes: Set<String>, // 不适用场景
    val formalityLevel: Int,       // 0=正式可用, 1=半正式, 2=仅休闲
    val popularity: Float = 0.5f,  // 热度 0-1
    val createdAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
)

/**
 * 梗识别结果
 */
data class MemeDetectionResult(
    val detectedMemes: List<DetectedMeme>,
    val totalMemes: Int,
    val density: Float,            // 梗密度（梗数/文本长度）
    val suggestedResponseTone: MemeResponseTone
)

data class DetectedMeme(
    val meme: Meme,
    val matchedKeyword: String,
    val position: IntRange,
    val context: String            // 周围文本
)

/**
 * 梗回复语气
 */
data class MemeResponseTone(
    val useMeme: Boolean,
    val memeDensity: Float,        // 建议梗密度
    val types: Set<MemeType>,      // 建议使用的梗类型
    val reason: String
)

/**
 * 梗生成结果
 */
data class MemeGenerationResult(
    val content: String,
    val usedMemes: List<Meme>,
    val confidence: Float,
    val alternativeOptions: List<String> = emptyList()
)

/**
 * 玩梗模式配置
 */
data class MemeModeConfig(
    val enabled: Boolean = true,
    val intensity: MemeIntensity = MemeIntensity.BALANCED,
    val allowedTypes: Set<MemeType> = MemeType.values().toSet(),
    val blockedMemes: Set<String> = emptySet(),  // 屏蔽的梗 ID
    val formalityOverride: Int? = null,  // 强制正式度
    val autoDetectUserMeme: Boolean = true,
    val respondWithMeme: Boolean = true,
    val maxMemesPerResponse: Int = 2
)

enum class MemeIntensity {
    OFF,          // 关闭玩梗
    SUBTLE,       // 微妙（偶尔一梗）
    BALANCED,     // 平衡（适度玩梗）
    ENTHUSIASTIC, // 热情（经常玩梗）
    MAXIMUM       // 最大化（句句有梗）
}

/**
 * 玩梗模式引擎
 */
class MemeModeEngine(
    private var config: MemeModeConfig = MemeModeConfig(),
    /** 网络搜梗引擎（实时搜索最新梗） */
    val webSearchEngine: com.apex.agent.core.normal.meme.web.MemeWebSearchEngine =
        com.apex.agent.core.normal.meme.web.MemeWebSearchEngine.getInstance(),
    /** 是否启用网络搜梗 */
    private var webSearchEnabled: Boolean = true
) {

    private val memeDatabase = ConcurrentHashMap<String, Meme>()
    private val usageStats = ConcurrentHashMap<String, Int>()  // memeId -> 使用次数

    init {
        registerBuiltinMemes()
    }

    /**
     * 启用/禁用网络搜梗
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        webSearchEnabled = enabled
    }

    /**
     * 网络搜梗是否启用
     */
    fun isWebSearchEnabled(): Boolean = webSearchEnabled

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: MemeModeConfig) {
        config = newConfig
    }

    /**
     * 添加梗
     */
    fun addMeme(meme: Meme) {
        memeDatabase[meme.id] = meme
    }

    /**
     * 识别文本中的梗
     */
    fun detect(text: String): MemeDetectionResult {
        if (!config.enabled || !config.autoDetectUserMeme) {
            return MemeDetectionResult(emptyList(), 0, 0f, MemeResponseTone(false, 0f, emptySet(), "玩梗关闭"))
        }

        val detected = mutableListOf<DetectedMeme>()
        val textLower = text.lowercase()

        for (meme in memeDatabase.values) {
            if (meme.id in config.blockedMemes) continue
            for (keyword in meme.keywords) {
                val idx = textLower.indexOf(keyword.lowercase())
                if (idx >= 0) {
                    val context = text.substring(
                        (idx - 10).coerceAtLeast(0),
                        (idx + keyword.length + 10).coerceAtMost(text.length)
                    )
                    detected.add(DetectedMeme(meme, keyword, idx until idx + keyword.length, context))
                    break  // 每个梗只记录一次
                }
            }
        }

        val density = if (text.isNotEmpty()) detected.size.toFloat() / (text.length / 10).coerceAtLeast(1) else 0f
        val tone = decideResponseTone(detected, density)

        return MemeDetectionResult(detected, detected.size, density, tone)
    }

    /**
     * 生成玩梗回复
     */
    fun generateResponse(
        userMessage: String,
        baseResponse: String,
        scene: String = "casual"
    ): MemeGenerationResult {
        if (!config.enabled || !config.respondWithMeme) {
            return MemeGenerationResult(baseResponse, emptyList(), 1f)
        }

        val detection = detect(userMessage)
        if (!detection.suggestedResponseTone.useMeme) {
            return MemeGenerationResult(baseResponse, emptyList(), 1f)
        }

        val candidates = selectCandidateMemes(detection, scene)
        if (candidates.isEmpty()) {
            return MemeGenerationResult(baseResponse, emptyList(), 0.8f)
        }

        val usedMemes = candidates.take(config.maxMemesPerResponse)
        val enhanced = injectMemes(baseResponse, usedMemes, detection.suggestedResponseTone.memeDensity)

        // 更新使用统计
        usedMemes.forEach { usageStats[it.id] = (usageStats[it.id] ?: 0) + 1 }

        return MemeGenerationResult(
            content = enhanced,
            usedMemes = usedMemes,
            confidence = 0.85f,
            alternativeOptions = generateAlternatives(baseResponse, candidates, usedMemes)
        )
    }

    /**
     * 生成梗 prompt 注入
     */
    fun generateMemePrompt(userMessage: String, scene: String): String {
        if (!config.enabled) return ""

        val detection = detect(userMessage)
        val sb = StringBuilder()

        sb.append("[玩梗模式: ${config.intensity}]")

        if (detection.totalMemes > 0) {
            sb.appendLine()
            sb.append("用户使用了 ${detection.totalMemes} 个梗:")
            detection.detectedMemes.take(3).forEach { dm ->
                sb.appendLine("- ${dm.meme.name}: ${dm.meme.description}")
            }
        }

        if (detection.suggestedResponseTone.useMeme) {
            sb.appendLine()
            sb.append("建议回复梗密度: ${(detection.suggestedResponseTone.memeDensity * 100).toInt()}%")
            sb.append("建议梗类型: ${detection.suggestedResponseTone.types.joinToString { it.name }}")
            if (detection.suggestedResponseTone.reason.isNotBlank()) {
                sb.appendLine()
                sb.append("原因: ${detection.suggestedResponseTone.reason}")
            }
        } else {
            sb.appendLine()
            sb.append("当前场景不建议玩梗: ${detection.suggestedResponseTone.reason}")
        }

        // 提供几个可用的梗
        val availableMemes = memeDatabase.values
            .filter { it.type in detection.suggestedResponseTone.types }
            .filter { it.id !in config.blockedMemes }
            .sortedByDescending { it.popularity }
            .take(5)

        if (availableMemes.isNotEmpty()) {
            sb.appendLine()
            sb.append("可用梗参考:")
            availableMemes.forEach { m ->
                sb.appendLine("- ${m.name}: ${m.example}")
            }
        }

        return sb.toString()
    }

    /**
     * 获取梗的解释（用户问"这是什么梗"时）
     */
    fun explainMeme(query: String): String? {
        val meme = memeDatabase.values.find { meme ->
            meme.name.equals(query, ignoreCase = true) ||
            meme.displayName.contains(query, ignoreCase = true) ||
            meme.keywords.any { it.equals(query, ignoreCase = true) }
        } ?: return null

        return buildString {
            appendLine("【${meme.displayName}】")
            appendLine("类型: ${meme.type}")
            appendLine("含义: ${meme.description}")
            appendLine("使用场景: ${meme.usage}")
            appendLine("示例: ${meme.example}")
            appendLine("正式度: ${when (meme.formalityLevel) {
                0 -> "可在正式场合使用"
                1 -> "半正式场合可用"
                else -> "仅休闲场合使用"
            }}")
            appendLine("热度: ${(meme.popularity * 100).toInt()}%")
        }
    }

    /**
     * 搜索梗
     */
    fun searchMemes(query: String, type: MemeType? = null): List<Meme> {
        val q = query.lowercase()
        return memeDatabase.values
            .filter { type == null || it.type == type }
            .filter { meme ->
                meme.name.contains(q, ignoreCase = true) ||
                meme.displayName.contains(q, ignoreCase = true) ||
                meme.description.contains(q, ignoreCase = true) ||
                meme.keywords.any { it.contains(q, ignoreCase = true) } ||
                meme.tags.any { it.contains(q, ignoreCase = true) }
            }
            .sortedByDescending { it.popularity }
            .toList()
    }

    /**
     * 获取热门梗
     */
    fun getPopularMemes(limit: Int = 10): List<Meme> {
        return memeDatabase.values
            .sortedByDescending { it.popularity }
            .take(limit)
            .toList()
    }

    /**
     * 获取用户最常用的梗
     */
    fun getMostUsedMemes(limit: Int = 10): List<Meme> {
        return usageStats.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { memeDatabase[it.key] }
            .toList()
    }

    // ============ 内部方法 ============

    private fun decideResponseTone(detected: List<DetectedMeme>, density: Float): MemeResponseTone {
        if (config.intensity == MemeIntensity.OFF) {
            return MemeResponseTone(false, 0f, emptySet(), "玩梗已关闭")
        }

        // 如果用户用了梗，鼓励回应
        if (detected.isNotEmpty()) {
            val types = detected.map { it.meme.type }.toSet()
            val intensity = when (config.intensity) {
                MemeIntensity.SUBTLE -> 0.1f
                MemeIntensity.BALANCED -> 0.3f
                MemeIntensity.ENTHUSIASTIC -> 0.5f
                MemeIntensity.MAXIMUM -> 0.8f
                MemeIntensity.OFF -> 0f
            }
            return MemeResponseTone(
                useMeme = true,
                memeDensity = intensity,
                types = types,
                reason = "用户使用了 ${detected.size} 个梗，可以适当回应"
            )
        }

        // 用户没用梗，根据 intensity 决定
        return when (config.intensity) {
            MemeIntensity.SUBTLE -> MemeResponseTone(false, 0f, emptySet(), "低强度模式，不主动玩梗")
            MemeIntensity.BALANCED -> MemeResponseTone(true, 0.15f, setOf(MemeType.CHINESE_INTERNET, MemeType.PROGRAMMER), "平衡模式，偶尔玩梗")
            MemeIntensity.ENTHUSIASTIC -> MemeResponseTone(true, 0.3f, MemeType.values().toSet(), "热情模式，适度玩梗")
            MemeIntensity.MAXIMUM -> MemeResponseTone(true, 0.5f, MemeType.values().toSet(), "最大化模式，尽情玩梗")
            MemeIntensity.OFF -> MemeResponseTone(false, 0f, emptySet(), "关闭")
        }
    }

    private fun selectCandidateMemes(detection: MemeDetectionResult, scene: String): List<Meme> {
        val appropriateTypes = detection.suggestedResponseTone.types
        return memeDatabase.values
            .filter { it.type in appropriateTypes }
            .filter { it.id !in config.blockedMemes }
            .filter { scene in it.appropriateScenes || it.appropriateScenes.isEmpty() }
            .filter { scene !in it.inappropriateScenes }
            .filter { config.formalityOverride == null || it.formalityLevel <= config.formalityOverride }
            .sortedByDescending { it.popularity }
            .toList()
    }

    private fun injectMemes(response: String, memes: List<Meme>, density: Float): String {
        if (memes.isEmpty()) return response

        var result = response
        val memePhrases = memes.map { it.responses.randomOrNull() ?: it.example }

        // 根据密度决定注入位置
        if (density > 0.4f) {
            // 高密度：开头加一个，结尾加一个
            if (memes.size >= 1) {
                result = "${memePhrases[0]} $result"
            }
            if (memes.size >= 2) {
                result = "$result ${memePhrases[1]}"
            }
        } else {
            // 低密度：只在结尾加一个
            result = "$result ${memePhrases.first()}"
        }

        return result
    }

    private fun generateAlternatives(response: String, candidates: List<Meme>, used: List<Meme>): List<String> {
        val alternatives = mutableListOf<String>()
        val unused = candidates.filter { it.id !in used.map { m -> m.id } }.take(3)
        for (meme in unused) {
            val phrase = meme.responses.randomOrNull() ?: meme.example
            alternatives.add("$response $phrase")
        }
        return alternatives
    }

    // ============ 网络搜梗（实时搜索）============

    /**
     * 网络搜索梗的含义（实时）
     *
     * 当本地梗库没有时，通过网络搜索查询
     *
     * @param query 梗关键词（如"绝绝子"、"yyds"）
     * @return 搜索结果（含标题、摘要、来源）
     */
    suspend fun searchMemeOnline(query: String): com.apex.agent.core.normal.meme.web.MemeSearchResult {
        if (!webSearchEnabled) {
            return com.apex.agent.core.normal.meme.web.MemeSearchResult(
                query = query, engine = "disabled", items = emptyList(),
                totalFound = 0, searchTimeMs = 0, success = false,
                error = "网络搜梗已禁用"
            )
        }
        return webSearchEngine.searchMeme(query)
    }

    /**
     * 查询梗百科（详细解释）
     *
     * 从小鸡词典/百度百科查询梗的详细解释
     *
     * @param query 梗关键词
     * @return 梗百科查询结果
     */
    suspend fun lookupMemeOnline(query: String): com.apex.agent.core.normal.meme.web.MemeWikiResult {
        if (!webSearchEnabled) {
            return com.apex.agent.core.normal.meme.web.MemeWikiResult(
                query = query, success = false, error = "网络搜梗已禁用"
            )
        }
        return webSearchEngine.lookupMeme(query)
    }

    /**
     * 获取当前流行梗（来自热搜）
     *
     * 从微博/百度/知乎热搜中识别流行梗
     */
    suspend fun getTrendingMemes(limit: Int = 10): List<com.apex.agent.core.normal.meme.web.HotSearchProvider.TrendingMeme> {
        if (!webSearchEnabled) return emptyList()
        return webSearchEngine.getTrendingMemes(limit)
    }

    /**
     * 自动识别未知梗并查询解释
     *
     * 检测用户消息中可能的梗，自动搜索解释
     *
     * @param text 用户输入文本
     * @return 识别到的梗及其网络解释
     */
    suspend fun explainUnknownMemes(text: String): List<com.apex.agent.core.normal.meme.web.MemeWebSearchEngine.MemeExplanation> {
        if (!webSearchEnabled) return emptyList()
        return webSearchEngine.explainUnknownMemes(text)
    }

    /**
     * 生成网络搜梗 prompt
     *
     * 当用户消息包含未知梗时，自动搜索并注入解释到 prompt
     */
    suspend fun generateWebMemeLookupPrompt(text: String): String {
        if (!webSearchEnabled) return ""
        return webSearchEngine.generateMemeLookupPrompt(text)
    }

    /**
     * 获取搜索建议（自动补全）
     *
     * 用户输入梗的前几个字时，返回搜索建议
     */
    suspend fun suggestMemes(query: String): List<String> {
        if (!webSearchEnabled || query.length < 2) return emptyList()
        return webSearchEngine.suggest(query)
    }

    /**
     * 增强版梗解释（本地 + 网络）
     *
     * 先查本地梗库，没有则网络搜索
     */
    suspend fun explainMemeEnhanced(query: String): String {
        // 1. 先查本地梗库
        val local = explainMeme(query)
        if (local != null) return local

        // 2. 本地没有，网络搜索
        if (webSearchEnabled) {
            val wiki = lookupMemeOnline(query)
            if (wiki.success) {
                return buildString {
                    appendLine("【${wiki.name}】（来源: ${wiki.source}）")
                    appendLine(wiki.definition)
                }
            }

            // 3. 百科没有，用搜索结果
            val search = searchMemeOnline(query)
            if (search.success && search.items.isNotEmpty()) {
                return buildString {
                    appendLine("【$query】（网络搜索结果）")
                    search.items.take(3).forEach { item ->
                        appendLine("- ${item.title}")
                        if (item.snippet.isNotBlank()) {
                            appendLine("  ${item.snippet.take(150)}")
                        }
                    }
                }
            }
        }

        return "未找到「$query」的解释"
    }

    /**
     * 获取网络搜梗引擎状态
     */
    fun getWebSearchStatus(): Map<String, com.apex.agent.core.normal.meme.web.WebSearchProviderRegistry.ProviderStatus> {
        return webSearchEngine.getEngineStatus()
    }

    /**
     * 获取网络搜梗缓存统计
     */
    fun getWebSearchCacheStats(): com.apex.agent.core.normal.meme.web.MemeCacheManager.CacheStats {
        return webSearchEngine.getCacheStats()
    }

    // ============ 预置梗库 ============

    private fun registerBuiltinMemes() {
        // === 中文网络梗 ===
        addMeme(Meme(
            id = "meme_yyds",
            name = "yyds",
            displayName = "永远的神",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("yyds", "永远的神", "YYDS"),
            description = "「永远的神」的缩写，表示极度赞美",
            usage = "称赞某人或某物非常厉害",
            example = "这个功能 yyds！",
            responses = listOf("确实 yyds", "这波 yyds", "永远的神没跑了"),
            appropriateScenes = setOf("casual", "praise"),
            inappropriateScenes = setOf("formal", "sad"),
            formalityLevel = 2,
            popularity = 0.9f,
            tags = listOf("赞美", "缩写")
        ))

        addMeme(Meme(
            id = "meme_juejuezi",
            name = "绝绝子",
            displayName = "绝绝子",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("绝绝子", "绝了"),
            description = "表示极度好或极度坏，「绝了」的加强版",
            usage = "表达强烈赞叹或无语",
            example = "这个设计绝绝子",
            responses = listOf("绝绝子！", "真的是绝了", "太绝了"),
            appropriateScenes = setOf("casual"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.7f,
            tags = listOf("赞叹")
        ))

        addMeme(Meme(
            id = "meme_pofangle",
            name = "破防了",
            displayName = "破防了",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("破防了", "破防", "破大防"),
            description = "心理防线被突破，表示被深深触动或无语",
            usage = "表达被感动、震惊或无语",
            example = "这个故事让我破防了",
            responses = listOf("确实破防", "这波破大防了", "防不住了"),
            appropriateScenes = setOf("casual", "emotional"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.85f,
            tags = listOf("情绪", "感动")
        ))

        addMeme(Meme(
            id = "meme_dage",
            name = "打工人",
            displayName = "打工人",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("打工人", "打工魂"),
            description = "上班族自嘲的称呼，带乐观坚韧色彩",
            usage = "上班族自嘲或互相鼓励",
            example = "打工人，打工魂，打工都是人上人",
            responses = listOf("打工人打工魂", "打工魂不灭", "加油打工人"),
            appropriateScenes = setOf("casual", "work"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.8f,
            tags = listOf("自嘲", "职场")
        ))

        addMeme(Meme(
            id = "meme_wuyu",
            name = "无语子",
            displayName = "无语子",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("无语子", "大无语", "小丑竟是我"),
            description = "表示非常无语",
            usage = "表达无奈或无语",
            example = "大无语事件",
            responses = listOf("大无语", "确实无语子", "小丑竟是我自己"),
            appropriateScenes = setOf("casual"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.7f,
            tags = listOf("无语", "自嘲")
        ))

        addMeme(Meme(
            id = "meme_lebird",
            name = "乐",
            displayName = "乐",
            type = MemeType.CHINESE_INTERNET,
            keywords = listOf("乐", "典", "绷", "赢麻了"),
            description = "单字梗，表达「好笑」「无语」「典中典」等复杂情绪",
            usage = "对荒诞事件的反应",
            example = "乐，这都能赢",
            responses = listOf("乐", "典", "绷不住了", "赢麻了都"),
            appropriateScenes = setOf("casual", "sarcastic"),
            inappropriateScenes = setOf("formal", "serious"),
            formalityLevel = 2,
            popularity = 0.95f,
            tags = listOf("单字梗", "讽刺")
        ))

        addMeme(Meme(
            id = "meme_zhenxiang",
            name = "真香",
            displayName = "真香",
            type = MemeType.CHINESE_TV,
            keywords = listOf("真香", "王境泽", "真香定律"),
            description = "出自《变形计》王境泽，表示打脸后接受",
            usage = "自嘲之前拒绝后来又接受",
            example = "我说不用，结果真香",
            responses = listOf("真香警告", "真香定律生效", "逃不过真香"),
            appropriateScenes = setOf("casual"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.85f,
            tags = listOf("打脸", "自嘲")
        ))

        addMeme(Meme(
            id = "meme_chenqie",
            name = "臣妾做不到",
            displayName = "臣妾做不到啊",
            type = MemeType.CHINESE_TV,
            keywords = listOf("臣妾做不到", "臣妾"),
            description = "出自《甄嬛传》皇后，表示无能为力",
            usage = "表达做不到某事",
            example = "这工作量，臣妾做不到啊",
            responses = listOf("臣妾做不到啊", "臣妾冤枉", "本宫乏了"),
            appropriateScenes = setOf("casual", "work"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.75f,
            tags = listOf("影视", "无能为力")
        ))

        // === 程序员梗 ===
        addMeme(Meme(
            id = "meme_helloworld",
            name = "hello world",
            displayName = "Hello World",
            type = MemeType.PROGRAMMER,
            keywords = listOf("hello world", "helloworld", "Hello World"),
            description = "程序员的入门仪式，第一个程序",
            usage = "编程入门、新开始",
            example = "学新语言先来个 hello world",
            responses = listOf("hello world 入坑", "一切从 hello world 开始", "welcome to the matrix"),
            appropriateScenes = setOf("casual", "programming"),
            inappropriateScenes = emptySet(),
            formalityLevel = 1,
            popularity = 0.9f,
            tags = listOf("编程", "入门")
        ))

        addMeme(Meme(
            id = "meme_404",
            name = "404",
            displayName = "404 Not Found",
            type = MemeType.PROGRAMMER,
            keywords = listOf("404", "not found", "找不到"),
            description = "HTTP 状态码，表示资源不存在",
            usage = "表示找不到、不存在",
            example = "我的脑子 404 了",
            responses = listOf("404 brain not found", "ERROR 404: 知识不存在", "返回 404"),
            appropriateScenes = setOf("casual", "programming"),
            inappropriateScenes = emptySet(),
            formalityLevel = 1,
            popularity = 0.85f,
            tags = listOf("编程", "HTTP")
        ))

        addMeme(Meme(
            id = "meme_rubberduck",
            name = "小黄鸭调试",
            displayName = "小黄鸭调试法",
            type = MemeType.PROGRAMMER,
            keywords = listOf("小黄鸭", "rubber duck", "鸭子调试"),
            description = "对着小黄鸭讲解代码来调试的方法",
            usage = "调试代码时",
            example = "找个小黄鸭聊聊",
            responses = listOf("是时候请出小黄鸭了", "rubber duck debugging 启动", "鸭子说你这有 bug"),
            appropriateScenes = setOf("programming", "debugging"),
            inappropriateScenes = emptySet(),
            formalityLevel = 1,
            popularity = 0.7f,
            tags = listOf("编程", "调试")
        ))

        addMeme(Meme(
            id = "meme_stackoverflow",
            name = "Stack Overflow",
            displayName = "Stack Overflow",
            type = MemeType.PROGRAMMER,
            keywords = listOf("stackoverflow", "stack overflow", "复制粘贴", "CV工程师"),
            description = "程序员的精神家园，CV（复制粘贴）工程师的圣地",
            usage = "解决问题时",
            example = "这个 bug 我从 SO 复制了一段代码解决了",
            responses = listOf("SO 警察会找你的", "CV 工程师上线", "感谢 SO 祖师爷"),
            appropriateScenes = setOf("programming"),
            inappropriateScenes = emptySet(),
            formalityLevel = 1,
            popularity = 0.8f,
            tags = listOf("编程", "Stack Overflow")
        ))

        addMeme(Meme(
            id = "meme_itworks",
            name = "it works",
            displayName = "It works on my machine",
            type = MemeType.PROGRAMMER,
            keywords = listOf("it works", "在我电脑上能跑", "works on my machine"),
            description = "程序员名言：在我电脑上能跑",
            usage = "推脱环境问题时",
            example = "not my bug, it works on my machine",
            responses = listOf("classic: it works on my machine", "那是你环境的问题", "在我这能跑啊"),
            appropriateScenes = setOf("programming"),
            inappropriateScenes = emptySet(),
            formalityLevel = 1,
            popularity = 0.85f,
            tags = listOf("编程", "甩锅")
        ))

        // === 英文网络梗 ===
        addMeme(Meme(
            id = "meme_lol",
            name = "lol",
            displayName = "LOL (Laugh Out Loud)",
            type = MemeType.ENGLISH_INTERNET,
            keywords = listOf("lol", "LOL", "haha"),
            description = "大笑",
            usage = "表达好笑",
            example = "lol that's hilarious",
            responses = listOf("lol", "lmao", "haha classic"),
            appropriateScenes = setOf("casual"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.95f,
            tags = listOf("笑", "缩写")
        ))

        addMeme(Meme(
            id = "meme_brainmoment",
            name = "brain moment",
            displayName = "Smooth Brain Moment",
            type = MemeType.ENGLISH_INTERNET,
            keywords = listOf("brain moment", "smooth brain", "galaxy brain", "big brain"),
            description = "形容突然变笨或突然聪明的时刻",
            usage = "自嘲或夸赞",
            example = "had a smooth brain moment there",
            responses = listOf("smooth brain moment", "galaxy brain move", "big brain time"),
            appropriateScenes = setOf("casual"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.75f,
            tags = listOf("脑力", "自嘲")
        ))

        // === 游戏梗 ===
        addMeme(Meme(
            id = "meme_gg",
            name = "gg",
            displayName = "GG (Good Game)",
            type = MemeType.GAMING,
            keywords = listOf("gg", "GG", "good game"),
            description = "游戏结束时的礼貌用语，也泛指结束",
            usage = "结束、认输",
            example = "gg，这局没了",
            responses = listOf("gg", "ggwp", "well played"),
            appropriateScenes = setOf("casual", "gaming"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.9f,
            tags = listOf("游戏", "结束")
        ))

        addMeme(Meme(
            id = "meme_afk",
            name = "afk",
            displayName = "AFK (Away From Keyboard)",
            type = MemeType.GAMING,
            keywords = listOf("afk", "AFK", "away", "挂机"),
            description = "离开键盘，暂时不在",
            usage = "表示暂时离开",
            example = "afk 一下",
            responses = listOf("afk", "brb", "一会儿回来"),
            appropriateScenes = setOf("casual", "gaming"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 2,
            popularity = 0.8f,
            tags = listOf("游戏", "离开")
        ))

        // === 谐音梗 ===
        addMeme(Meme(
            id = "meme_pun_duck",
            name = "鸭谐音",
            displayName = "鸭谐音梗",
            type = MemeType.PUN,
            keywords = listOf("冲鸭", "加油鸭", "好鸭", "是鸭"),
            description = "用「鸭」代替「呀」，卖萌谐音",
            usage = "卖萌、轻松语气",
            example = "加油鸭！",
            responses = listOf("冲鸭！", "好鸭", "知道鸭"),
            appropriateScenes = setOf("casual", "cute"),
            inappropriateScenes = setOf("formal", "serious"),
            formalityLevel = 2,
            popularity = 0.65f,
            tags = listOf("谐音", "卖萌")
        ))

        // === 经典梗 ===
        addMeme(Meme(
            id = "meme_kongyiji",
            name = "孔乙己",
            displayName = "孔乙己",
            type = MemeType.CHINESE_CLASSIC,
            keywords = listOf("孔乙己", "回字四种写法", "站着喝酒"),
            description = "鲁迅笔下人物，常用于自嘲书生气、迂腐",
            usage = "自嘲学历无用或迂腐",
            example = "现在的我们都是孔乙己",
            responses = listOf("回字有四种写法你知道吗", "孔乙己文学+1", "站着喝酒穿长衫"),
            appropriateScenes = setOf("casual", "sarcastic"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 1,
            popularity = 0.75f,
            tags = listOf("经典", "自嘲")
        ))

        addMeme(Meme(
            id = "meme_ahq",
            name = "阿Q精神",
            displayName = "阿Q精神",
            type = MemeType.CHINESE_CLASSIC,
            keywords = listOf("阿Q", "精神胜利法", "阿Q精神"),
            description = "鲁迅笔下阿Q的自我安慰方式",
            usage = "自嘲精神胜利",
            example = "打不过就阿Q一下",
            responses = listOf("阿Q精神启动", "精神胜利法", "儿子打老子"),
            appropriateScenes = setOf("casual", "sarcastic"),
            inappropriateScenes = setOf("formal"),
            formalityLevel = 1,
            popularity = 0.7f,
            tags = listOf("经典", "自嘲")
        ))
    }
}
