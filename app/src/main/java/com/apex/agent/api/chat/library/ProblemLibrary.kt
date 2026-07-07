package com.apex.agent.api.chat.library

import android.content.Context
import com.apex.util.AppLogger
import com.apex.util.ChatMarkupRegex
import com.apex.agent.R
import com.apex.api.chat.llmprovider.AIService
import com.apex.core.tools.AIToolHandler
import com.apex.data.model.Memory
import com.apex.data.preferences.ApiPreferences
import com.apex.data.preferences.MemorySearchSettingsPreferences
import com.apex.data.preferences.preferencesManager
import com.apex.agent.data.repository.MemoryRepository
import com.apex.util.ChatUtils
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.core.config.FunctionalPrompts
import com.apex.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 问题库管理类 - 提供分析对话内容并存储为结构化记忆图谱的功能�?/
object ProblemLibrary {
    private const val TAG = "ProblemLibrary"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiPreferences: ApiPreferences? = null
    private val mutex = Mutex()

    @Volatile private var isInitialized = false

    // --- Data classes for parsing the new structured analysis ---
    private data class ParsedLink(val sourceTitle: String, val targetTitle: String, val type: String, val description: String, val weight: Float = 1.0f)
    private data class ParsedEntity(val title: String, val content: String, val tags: List<String>, val aliasFor: String?, val folderPath: String)
    private data class ParsedUpdate(val titleToUpdate: String, val newContent: String, val reason: String, val newCredibility: Float?, val newImportance: Float)
    private data class ParsedMerge(val sourceTitles: List<String>, val newTitle: String, val newContent: String, val newTags: List<String>, val folderPath: String, val reason: String)
    private data class ParsedAnalysis(
        val mainProblem: ParsedEntity?,
        val extractedEntities: List<ParsedEntity> = emptyList(),
        val links: List<ParsedLink> = emptyList(),
        val updatedEntities: List<ParsedUpdate> = emptyList(),
        val mergedEntities: List<ParsedMerge> = emptyList(),
        val userPreferences: String = ""
    )


    fun initialize(context: Context) {
        synchronized(ProblemLibrary::class.java) {
            if (isInitialized) return
            AppLogger.d(TAG, "正在初始的ProblemLibrary")
            apiPreferences = ApiPreferences.getInstance(context.applicationContext)
            isInitialized = true
            AppLogger.d(TAG, "ProblemLibrary 初始化完的）
        }
    }

    /**
     * 自动为未分类的记忆分配文件夹路径
     * 在后台异步执行，不阻塞主线程
     */
    fun autoCategorizeMemoriesAsync(context: Context, aiService: AIService) {
        ensureInitialized(context)
        
        coroutineScope.launch {
            try {
                autoCategorizeMemories(context, aiService)
            } catch (e: Exception) {
                AppLogger.e(TAG, "自动分类记忆失败", e)
            }
        }
    }

    fun saveProblemAsync(
            context: Context,
            toolHandler: AIToolHandler,
            conversationHistory: List<Pair<String, String>>,
            content: String,
            aiService: AIService
    ) {
        ensureInitialized(context)

        coroutineScope.launch {
            try {
                saveProblem(
                    context,
                    toolHandler,
                    conversationHistory,
                    content,
                    aiService
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "保存问题记录失败", e)
            }
        }
    }

    private fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    /**
     * 查询未分类记忆并批量调用 AI 进行分类
     */
    private suspend fun autoCategorizeMemories(context: Context, aiService: AIService) {
        mutex.withLock {
            val profileId = preferencesManager.activeProfileIdFlow.first()
            val memoryRepository = MemoryRepository(context, profileId)
            
            val allMemories = memoryRepository.getAllMemories()
            val uncategorizedMemories = allMemories.filter { memory ->
                memory.folderPath.isNullOrEmpty()
            }
            
            if (uncategorizedMemories.isEmpty()) {
                AppLogger.d(TAG, "没有未分类的记忆，跳过自动分析）
                return@withLock
            }
            
            AppLogger.d(TAG, "找到 ${uncategorizedMemories.size} 条未分类记忆，开始批量分�?.")
            
            // 获取现有文件夹列�?          val existingFolders = memoryRepository.getAllFolderPaths()
            
            // 分批处理（每�?条）
            val batches = uncategorizedMemories.chunked(10)
            batches.forEachIndexed { batchIndex: Int, batch: List<Memory> ->
                try {
                    AppLogger.d(TAG, "处理${{batchIndex + 1} 批记忆（${{batch.size} 条）...")
                    categorizeBatch(context, batch, existingFolders, memoryRepository, aiService)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "处理${{batchIndex + 1} 批记忆失败：${e.message})
                }
            }
            
            AppLogger.d(TAG, "自动分类完成")
        }
    }

    /**
     * 使用 AI 为一批记忆分�?   */
    private suspend fun categorizeBatch(
        context: Context,
        memories: List<Memory>,
        existingFolders: List<String>,
        repository: MemoryRepository,
        aiService: AIService
    ) {
        val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
        val memoriesDigest = memories.joinToString("\n") { "- title: ${it.title}, content: ${it.content.take(100)}..." }
        val systemPrompt = FunctionalPrompts.buildMemoryAutoCategorizePrompt(
            existingFolders = existingFolders,
            memoriesDigest = memoriesDigest,
            useEnglish = useEnglish
        )

        val userMessage = FunctionalPrompts.memoryAutoCategorizeUserMessage(useEnglish)
            val messages = listOf(Pair("system", systemPrompt), Pair("user", userMessage)).toPromptTurns()
        val result = StringBuilder()
        
        withContext(Dispatchers.IO) {
            val stream =
                aiService.sendMessage(
                    context = context,
                    chatHistory = messages
                )
            stream.collect { content -> result.append(content) }
        }
        
        // 更新 token 统计
        apiPreferences?.updateTokensForProviderModel(
            aiService.providerModel,
            aiService.inputTokenCount,
            aiService.outputTokenCount,
            aiService.cachedInputTokenCount
        )
        
        // Update request count
        apiPreferences?.incrementRequestCountForProviderModel(aiService.providerModel)
        
        // 解析 AI 返回复JSON 并更新记�?       parseAndApplyCategorization(result.toString(), memories, repository)
    }

    /**
     * 解析 AI 返回的分类结果并更新记忆
     */
    private suspend fun parseAndApplyCategorization(
        jsonString: String,
        memories: List<Memory>,
        repository: MemoryRepository
    ) {
        try {
            val cleanJson = ChatUtils.extractJsonArray(jsonString)
            if (cleanJson.isEmpty() || !cleanJson.startsWith("[")) return
            
            val jsonArray = JSONArray(cleanJson)
            val titleToFolderMap = mutableMapOf<String, String>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val title = obj.getString("title")
                val folder = obj.getString("folder")
                titleToFolderMap[title] = folder
            }
            
            // 为每个记忆更新分类和重新生成 embedding
            memories.forEach { memory ->
                val newFolder = titleToFolderMap[memory.title]
                if (newFolder != null) {
                    AppLogger.d(TAG, "更新记忆 '${memory.title}' 的分类为: ${newFolder}")
                    
                    // 直接调用 updateMemory，它会自动重新生成embedding
                    repository.updateMemory(
                        memory = memory,
                        newTitle = memory.title,
                        newContent = memory.content,
                        newFolderPath = newFolder
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析分类结果失败: ${jsonString}", e)
        }
    }

    /**
     * Analyzes conversation and saves it as a structured Memory graph.
     */
    private suspend fun saveProblem(
            context: Context,
            toolHandler: AIToolHandler,
            conversationHistory: List<Pair<String, String>>,
            content: String,
            aiService: AIService
    ) {
        mutex.withLock {
            val profileId = preferencesManager.activeProfileIdFlow.first()
            val memoryRepository = MemoryRepository(context, profileId)

            // Prune tool results to reduce token usage
            val prunedContent =
                ChatUtils.stripGeminiThoughtSignatureMeta(
                    pruneToolResultContent(context, content)
                )

            // Process conversation history: remove system messages and clean user messages
            val processedHistory = conversationHistory
                .filter { it.first != "system" }
                .map { (role, msgContent) ->
                    val cleanedContent = if (role == "user") {
                        msgContent.replace(Regex("<memory>.*?</memory>", RegexOption.DOT_MATCHES_ALL), "").trim()
                    } else {
                        msgContent
                    }
                    role to ChatUtils.stripGeminiThoughtSignatureMeta(
                        pruneToolResultContent(context, cleanedContent)
                    )
                }

            if (processedHistory.isEmpty()) {
                AppLogger.w(TAG, "处理后的会話历史为空，跳过保存问题记的）
                return@withLock
            }

            val query = processedHistory.lastOrNull { it.first == "user" }?.second ?: ""
            if (query.isEmpty()) {
                AppLogger.w(TAG, "未找到用户查询消息，跳过保存")
                return@withLock
            }

            // Generate the graph analysis from the conversation
            val analysis = generateAnalysis(
                context = context,
                aiService = aiService,
                query = query,
                solution = prunedContent,
                conversationHistory = processedHistory,
                memoryRepository = memoryRepository,
                profileId = profileId
            )

            // If analysis is empty (trivial conversation), abort early.
            if (analysis.mainProblem == null && analysis.extractedEntities.isEmpty() && analysis.updatedEntities.isEmpty() && analysis.mergedEntities.isEmpty()) {
                AppLogger.d(TAG, "分析结果为空，判断为无需记忆的对话，跳过保存的）
                return@withLock
            }

            // Create a map to track all memories (new and updated) for linking
            val createdMemories = mutableMapOf<String, Memory>()

            // First, apply any merges to existing memories
            if (analysis.mergedEntities.isNotEmpty()) {
                AppLogger.d(TAG, "开始合${{analysis.mergedEntities.size} 组记�?.")
                analysis.mergedEntities.forEach { merge ->
                    AppLogger.d(TAG, "正在合并: ${merge.sourceTitles.joinToString(", ")} -> '${merge.newTitle}'. 原因: ${merge.reason}")
                    val mergedMemory = memoryRepository.mergeMemories(
                        sourceTitles = merge.sourceTitles,
                        newTitle = merge.newTitle,
                        newContent = merge.newContent,
                        newTags = merge.newTags,
                        folderPath = merge.folderPath
                    )
                    if (mergedMemory != null) {
                        createdMemories[mergedMemory.title] = mergedMemory
                    }
                }
            }

            // Second, apply any updates to existing memories
            if (analysis.updatedEntities.isNotEmpty()) {
                AppLogger.d(TAG, "开始更�?{analysis.updatedEntities.size} 个现有记�?.")
                analysis.updatedEntities.forEach { update ->
                    val memoryToUpdate = memoryRepository.findMemoryByTitle(update.titleToUpdate)
                    if (memoryToUpdate != null) {
                        AppLogger.d(TAG, "正在更新记忆: '${update.titleToUpdate}'. 原因: ${update.reason}")
                        val updatedMemory = memoryRepository.updateMemory(
                                memory = memoryToUpdate,
                                newTitle = memoryToUpdate.title, // For now, let's not change the title
                                newContent = update.newContent,
                                newCredibility = update.newCredibility ?: memoryToUpdate.credibility,
                                newImportance = update.newImportance ?: memoryToUpdate.importance
                        )
                        if (updatedMemory != null) {
                            createdMemories[updatedMemory.title] = updatedMemory
                        }
                    } else {
                        AppLogger.w(TAG, "想要更新的记忆未找到: '${update.titleToUpdate}'")
                    }
                }
            }

            // Update user preferences (this logic remains)
            if (analysis.userPreferences.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) {
                        updateUserPreferencesFromAnalysis(context, analysis.userPreferences)
                        AppLogger.d(TAG, "用户偏好已更多）
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "更新用户偏好失败", e)
                }
            }

            // Save the graph structure to the MemoryRepository
            if (analysis.mainProblem == null) {
                AppLogger.w(TAG, "分析结果中缺少main_problem，跳过保存记忆图的）
                return@withLock
            }

            AppLogger.d(TAG, "开始构建记忆图�?.")
            AppLogger.d(TAG, "AI分析结果 - 主要问题: '${analysis.mainProblem.title}', 实体: ${analysis.extractedEntities.size}, 链接: ${analysis.links.size}, 文文�?${analysis.mainProblem.folderPath}'")


            try {
                // 1. Create main problem memory
                val mainProblemMemory = analysis.mainProblem?.let { mainProblem ->
                    val existingMemory = memoryRepository.findMemoryByTitle(mainProblem.title)
                    if (existingMemory != null) {
                        AppLogger.d(TAG, "1. 发现同名核心记忆，更新内�?${mainProblem.title}'")
                        existingMemory.content = mainProblem.content
                        memoryRepository.saveMemory(existingMemory)
                        existingMemory
                    } else {
                        AppLogger.d(TAG, "1. 创建主要问题记忆节点: '${mainProblem.title}'")
                        val memory = Memory(
                            title = mainProblem.title,
                            content = mainProblem.content,
                            importance = 0.8f, // Main problems are highly important
                            credibility = 1.0f,
                            folderPath = mainProblem.folderPath ?: ""
                        )
                        memoryRepository.saveMemory(memory)
                        mainProblem.tags.forEach { tagName ->
                            memoryRepository.addTagToMemory(memory, tagName)
                        }
                        memory
                    }
                }
                mainProblemMemory?.let {
                    createdMemories[it.title] = it
                }

                // 2. Process entities with new LLM-driven deduplication logic
                analysis.extractedEntities.forEach { entity ->
                    AppLogger.d(TAG, "2. 处理实体: '${entity.title}'")
                    var memory: Memory? = null

                    if (!entity.aliasFor.isNullOrBlank()) {
                        // This entity is an alias for an existing one, as determined by the LLM.AppLogger.d(TAG, "   -> LLM 识别此实体为 '${entity.aliasFor}' 的别名，)
                        // Try to find the canonical memory, first in the ones we just created, then in the DB.
                        memory = createdMemories[entity.aliasFor] ?: memoryRepository.findMemoryByTitle(entity.aliasFor)

                        if (memory != null) {
                            AppLogger.d(TAG, "   -> 复用已存在的记忆节点 (ID: ${memory.id}).")
                        } else {
                            // This is an edge case: LLM said it's an alias, but we can't find the original.
                            // We will treat it as a new entity.AppLogger.w(TAG, "   -> 无法找到别名 '${entity.aliasFor}' 的原始记忆。将其作为新实体处理的）
                        }
                    }

                    // If it's not an alias, or if the original for the alias wasn't found, create a new memory.
                    if (memory == null) {
                        AppLogger.d(TAG, "   -> 创建新的记忆节点的）
                        memory = Memory(
                            title = entity.title,
                            content = entity.content,
                            source = "problem_library_analysis",
                            folderPath = entity.folderPath ?: analysis.mainProblem.folderPath ?: ""
                        )
                        memoryRepository.saveMemory(memory)
                        entity.tags.forEach { tagName ->
                            memoryRepository.addTagToMemory(memory, tagName)
                        }
                    }

                    // Map the title of the entity (whether it's an alias or new) to the resolved memory object.
                    // This ensures that links pointing to the alias title will resolve to the correct canonical memory.
                    createdMemories[entity.title] = memory
                }

                // 3. Create links between the memories
                AppLogger.d(TAG, "3. 开始创建记忆链�?.")
                analysis.links.forEach { link ->
                    // Try to find source: first in newly created/updated memories, then in existing DB
                    val source = createdMemories[link.sourceTitle] 
                        ?: memoryRepository.findMemoryByTitle(link.sourceTitle)
                    
                    // Try to find target: first in newly created/updated memories, then in existing DB
                    val target = createdMemories[link.targetTitle] 
                        ?: memoryRepository.findMemoryByTitle(link.targetTitle)
                    
                    if (source != null && target != null) {
                        AppLogger.d(TAG, "   -> 正在链接: '${link.sourceTitle}' --(${link.type}, weight=${link.weight})--> '${link.targetTitle}'")
                        memoryRepository.linkMemories(source, target, link.type, weight = link.weight, description = link.description)
                    } else {
                        AppLogger.w(TAG, "   -> 无法创建链接，源或目标实体未找到: ${link.sourceTitle} -> ${link.targetTitle}")
                        if (source == null) AppLogger.w(TAG, "      源节�?${link.sourceTitle}' 未找的）
                        if (target == null) AppLogger.w(TAG, "      目标节点 '${link.targetTitle}' 未找的）
                    }
                }

                AppLogger.d(TAG, "成功从对话中提取并保存了记忆图谱")

            } catch (e: Exception) {
                AppLogger.e(TAG, "保存记忆图谱失败", e)
            }
        }
    }

    /**
     * Generates a structured analysis of the conversation for graph creation.
     */
    private suspend fun generateAnalysis(
        context: Context,
        aiService: AIService,
        query: String,
        solution: String,
        conversationHistory: List<Pair<String, String>>,
        memoryRepository: MemoryRepository,
        profileId: String
    ): ParsedAnalysis {
        try {
            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
            val currentPreferences = withContext(Dispatchers.IO) {
                var preferences = ""
                preferencesManager.getUserPreferencesFlow().take(1).collect { profile ->
                    preferences = buildPreferencesText(context, profile)
                }
                preferences
            }

            // --- Hybrid Strategy: Local rough search + LLM final decision ---
            // 1. Use a compact search query (question-focused) for rough candidate selection.
            val contextQuery = buildCandidateSearchQuery(query, solution)
            val searchConfig = MemorySearchSettingsPreferences(context, profileId).load()
            val candidateMemories = memoryRepository.searchMemories(
                query = contextQuery,
                scoreMode = searchConfig.scoreMode,
                keywordWeight = searchConfig.keywordWeight,
                tagWeight = searchConfig.tagWeight,
                semanticWeight = searchConfig.vectorWeight,
                edgeWeight = searchConfig.edgeWeight
            ).take(15)

            AppLogger.d(
                TAG,
                "候选记忆检索完的count=${candidateMemories.size}, " +
                    "mode=${searchConfig.scoreMode}, " +
                    "keywordWeight=${searchConfig.keywordWeight}, tagWeight=${searchConfig.tagWeight}, vectorWeight=${searchConfig.vectorWeight}, edgeWeight=${searchConfig.edgeWeight}, " +
                    "searchQueryLen=${contextQuery.length}"
            )
            AppLogger.d(TAG, "候选检索查询（截断�?${contextQuery.take(220)}")
            if (candidateMemories.isEmpty()) {
                AppLogger.d(TAG, "候选记忆列表为空（通过阈值过滤后无结果）的）
            } else {
                candidateMemories.forEachIndexed { index, memory ->
                    val preview = memory.content
                        .replace("\r\n", " ")
                        .replace("\n", " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(120)
                    AppLogger.d(
                        TAG,
                        "候选记忆[${index}] id=${memory.id}, title='${memory.title}', " +
                            "folder='${memory.folderPath ?: ""}', importance=${String.format("%.2f", memory.importance)}, " +
                            "credibility=${String.format("%.2f", memory.credibility)}, preview='${preview}'"
                    )
                }
            }

            // 2. Proactively find duplicates among candidates and instruct LLM to merge them
            val duplicatesPromptPart = findAndDescribeDuplicates(candidateMemories, memoryRepository, useEnglish)

            val existingMemoriesPrompt = if (candidateMemories.isNotEmpty()) {
                FunctionalPrompts.knowledgeGraphExistingMemoriesPrefix(useEnglish) +
                    candidateMemories.joinToString("\n") { "- \"${it.title}\": ${it.content.take(150).replace("\n", " ")}..." }
            } else {
                FunctionalPrompts.knowledgeGraphNoExistingMemoriesMessage(useEnglish)
            }

            // 获取现有文件夹列�?          val existingFolders = memoryRepository.getAllFolderPaths()
            val existingFoldersPrompt = FunctionalPrompts.knowledgeGraphExistingFoldersPrompt(
                existingFolders = existingFolders,
                useEnglish = useEnglish
            )

            val systemPrompt = FunctionalPrompts.buildKnowledgeGraphExtractionPrompt(
                duplicatesPromptPart = duplicatesPromptPart,
                existingMemoriesPrompt = existingMemoriesPrompt,
                existingFoldersPrompt = existingFoldersPrompt,
                currentPreferences = currentPreferences,
                useEnglish = useEnglish
            )

            val analysisMessage = buildAnalysisMessage(context, query, solution, conversationHistory, useEnglish)
            val messages = listOf(Pair("system", systemPrompt), Pair("user", analysisMessage)).toPromptTurns()
            val result = StringBuilder()

            withContext(Dispatchers.IO) {
                val stream =
                    aiService.sendMessage(
                        context = context,
                        chatHistory = messages
                    )
                stream.collect { content -> result.append(content) }
            }

            apiPreferences?.updateTokensForProviderModel(
                    aiService.providerModel,
                    aiService.inputTokenCount,
                    aiService.outputTokenCount,
                    aiService.cachedInputTokenCount
            )
            
            // Update request count
            apiPreferences?.incrementRequestCountForProviderModel(aiService.providerModel)

            return parseAnalysisResult(context, ChatUtils.removeThinkingContent(result.toString()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成分析失败", e)
            return ParsedAnalysis(null)
        }
    }

    private fun buildCandidateSearchQuery(query: String, solution: String): String {
        val coreQuestion = extractCoreQuestionText(query)
        val fallbackQuestion = normalizeCandidateSearchText(query, maxLen = 800)

        val selectedQuestion = if (coreQuestion.isNotBlank()) coreQuestion else fallbackQuestion
        if (selectedQuestion.isBlank()) return normalizeCandidateSearchText(solution, maxLen = 300)

        val conciseSolution = normalizeCandidateSearchText(solution, maxLen = 180)

        // 优先问题文本，附带少量解答上下文（避免历史记录噪声）�?       return if (conciseSolution.isNotBlank()) {
            "${selectedQuestion}\n${conciseSolution}"
        } else {
            selectedQuestion
        }
    }

    private fun extractCoreQuestionText(rawQuery: String): String {
        val compact = rawQuery.replace("\r\n", "\n")

        val cn = Regex("(?s)问题\\s*[的]\\s*(.+)(?:\\n\\s*解决方案\\s*[的]|\\z)")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val en = Regex("(?s)Question\\s*:\\s*(.+)(?:\\n\\s*Solution\\s*:|\\z)")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val selected = when {
            !cn.isNullOrBlank() -> cn
            !en.isNullOrBlank() -> en
            else -> compact
        }

        val filtered = selected
            .lineSequence()
            .filterNot { it.trimStart().startsWith("历史记录:") }
            .filterNot { it.trimStart().startsWith("History:") }
            .joinToString("\n")

        return normalizeCandidateSearchText(filtered, maxLen = 500)
    }

    private fun normalizeCandidateSearchText(raw: String, maxLen: Int): String {
        return raw
            .replace(ChatMarkupRegex.toolTag, " ")
            .replace(ChatMarkupRegex.toolSelfClosingTag, " ")
            .replace(ChatMarkupRegex.toolResultTag, " ")
            .replace(ChatMarkupRegex.toolResultSelfClosingTag, " ")
            .replace(ChatMarkupRegex.statusTag, " ")
            .replace(ChatMarkupRegex.statusSelfClosingTag, " ")
            .replace(ChatMarkupRegex.thinkTag, " ")
            .replace(ChatMarkupRegex.thinkSelfClosingTag, " ")
            .replace(ChatMarkupRegex.searchTag, " ")
            .replace(ChatMarkupRegex.searchSelfClosingTag, " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[`*_#>]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLen)
    }

    /**
     * Finds duplicates within a list of candidate memories and creates a prompt instruction for the LLM.
     */
    private suspend fun findAndDescribeDuplicates(candidateMemories: List<Memory>, memoryRepository: MemoryRepository, useEnglish: Boolean): String {
        val titles = candidateMemories.map { it.title }.distinct()
        val duplicatesFound = mutableListOf<String>()

        for (title in titles) {
            val memoriesWithSameTitle = memoryRepository.findMemoriesByTitle(title)
            if (memoriesWithSameTitle.size > 1) {
                duplicatesFound.add(
                    FunctionalPrompts.knowledgeGraphDuplicateTitleInstruction(
                        title = title,
                        count = memoriesWithSameTitle.size,
                        useEnglish = useEnglish
                    )
                )
            }
        }

        return if (duplicatesFound.isNotEmpty()) {
            FunctionalPrompts.knowledgeGraphDuplicateHeader(useEnglish) + duplicatesFound.joinToString("\n") + "\n"
        } else {
            ""
        }
    }

    private fun buildAnalysisMessage(
            context: Context,
            query: String,
            solution: String,
            conversationHistory: List<Pair<String, String>>,
            useEnglish: Boolean
    ): String {
        val messageBuilder = StringBuilder()
        if (useEnglish) {
            messageBuilder.appendLine("Question:")
            messageBuilder.appendLine(query)
            messageBuilder.appendLine()
            messageBuilder.appendLine("Solution:")
            messageBuilder.appendLine(solution.take(3000))
            messageBuilder.appendLine()
        } else {
            messageBuilder.appendLine(context.getString(R.string.problem_library_question))
            messageBuilder.appendLine(query)
            messageBuilder.appendLine()
            messageBuilder.appendLine(context.getString(R.string.problem_library_solution))
            messageBuilder.appendLine(solution.take(3000))
            messageBuilder.appendLine()
        }
        val recentHistory = conversationHistory.takeLast(10)
        if (recentHistory.isNotEmpty()) {
            messageBuilder.appendLine(if (useEnglish) "History:" else context.getString(R.string.problem_library_history))
            recentHistory.forEachIndexed { index, (role, content) ->
                messageBuilder.appendLine("#${index + 1} ${role}: ${content.take(4000)}")
            }
        }
        return messageBuilder.toString()
    }

    /**
     * Parses the JSON response from the AI into a ParsedAnalysis object.
     */
    private fun parseAnalysisResult(context: Context, jsonString: String): ParsedAnalysis {
        return try {
            val cleanJson = ChatUtils.extractJson(jsonString)
            if (cleanJson.isEmpty() || !cleanJson.startsWith("{")) return ParsedAnalysis(null)

            // Handle the case where AI decides not to extract any knowledge
            if (cleanJson == "{}") {
                return ParsedAnalysis(null)
            }

            val json = JSONObject(cleanJson)
            
            // 【新增】输的AI 返回的完的JSON 指令
            AppLogger.d(TAG, "AI 返回的完的JSON 指令:\n${json.toString(2)}")

            // Parse main_problem from "main" array
            val mainProblem = json.optJSONArray("main")?.let {
                val tags = it.optJSONArray(2)?.let { tagsArray -> List(tagsArray.length()) { i -> tagsArray.getString(i) } } ?: emptyList()
                ParsedEntity(
                    title = it.getString(0),
                    content = it.getString(1),
                    tags = tags,
                    aliasFor = null,
                    folderPath = it.optString(3, "")
                )
            }

            // Parse extracted_entities from "new" array
            val extractedEntities = json.optJSONArray("new")?.let { entitiesArray ->
                List(entitiesArray.length()) { i ->
                    val entityArr = entitiesArray.getJSONArray(i)
                    val tags = entityArr.optJSONArray(2)?.let { tagsArray -> List(tagsArray.length()) { j -> tagsArray.getString(j) } } ?: emptyList()
                    val aliasFor = if (!entityArr.isNull(4)) entityArr.getString(4) else null
                    ParsedEntity(
                        title = entityArr.getString(0),
                        content = entityArr.getString(1),
                        tags = tags,
                        aliasFor = aliasFor,
                        folderPath = entityArr.optString(3, "")
                    )
                }
            } ?: emptyList()

            // Parse links from "links" array
            val links = json.optJSONArray("links")?.let { linksArray ->
                List(linksArray.length()) { i ->
                    val linkArr = linksArray.getJSONArray(i)
                    ParsedLink(
                        sourceTitle = linkArr.getString(0),
                        targetTitle = linkArr.getString(1),
                        type = linkArr.getString(2),
                        description = linkArr.optString(3, ""),
                        weight = linkArr.optDouble(4, 1.0).toFloat()
                    )
                }
            } ?: emptyList()

            // Parse updated_entities from "update" array
            val updatedEntities = json.optJSONArray("update")?.let { updatesArray ->
                List(updatesArray.length()) { i ->
                    val updateArr = updatesArray.getJSONArray(i)
                    val credibility = if (!updateArr.isNull(3)) updateArr.getDouble(3).toFloat() else null
                    val importance = if (!updateArr.isNull(4)) updateArr.getDouble(4).toFloat() else null
                    ParsedUpdate(
                        titleToUpdate = updateArr.getString(0),
                        newContent = updateArr.getString(1),
                        reason = updateArr.getString(2),
                        newCredibility = credibility,
                        newImportance = importance
                    )
                }
            } ?: emptyList()

            // Parse merge_entities from "merge" array
            val mergedEntities = json.optJSONArray("merge")?.let { mergeArray ->
                List(mergeArray.length()) { i ->
                    val mergeObj = mergeArray.getJSONObject(i)
                    val sourceTitles = mergeObj.getJSONArray("source_titles").let { titles ->
                        List(titles.length()) { j -> titles.getString(j) }
                    }
                    ParsedMerge(
                        sourceTitles = sourceTitles,
                        newTitle = mergeObj.getString("new_title"),
                        newContent = mergeObj.getString("new_content"),
                        newTags = mergeObj.optJSONArray("new_tags")?.let { tags ->
                            List(tags.length()) { k -> tags.getString(k) }
                        } ?: emptyList(),
                        folderPath = mergeObj.optString("folder_path"),
                        reason = mergeObj.optString("reason")
                    )
                }
            } ?: emptyList()

            val userPreferences = json.optJSONObject("user")?.let {
                parseUserPreferences(context, it)
            } ?: ""

            ParsedAnalysis(
                mainProblem = mainProblem,
                extractedEntities = extractedEntities,
                links = links,
                updatedEntities = updatedEntities,
                mergedEntities = mergedEntities,
                userPreferences = userPreferences
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析分析结果失败: ${jsonString}", e)
            ParsedAnalysis(null)
        }
    }

    private fun parseUserPreferences(context: Context, preferencesObj: JSONObject): String {
        val preferenceParts = mutableListOf<String>()
        // Helper to add preference if it exists and is not "<UNCHANGED>"
        fun addPref(key: String, prefix: String) {
            if (preferencesObj.has(key) && preferencesObj.get(key) != "<UNCHANGED>") {
                val value = preferencesObj.get(key).toString()
                if (value.isNotEmpty()) preferenceParts.add("${prefix}: ${value}")
            }
        }
        addPref("age", context.getString(R.string.profile_birth_year))
        addPref("gender", context.getString(R.string.profile_gender))
        addPref("personality", context.getString(R.string.profile_personality))
        addPref("identity", context.getString(R.string.profile_identity))
        addPref("occupation", context.getString(R.string.profile_occupation))
        addPref("aiStyle", context.getString(R.string.profile_ai_style))
        return preferenceParts.joinToString("; ")
    }


    private fun buildPreferencesText(context: Context, profile: com.apex.data.model.PreferenceProfile): String {
        val parts = mutableListOf<String>()
        if (profile.gender.isNotEmpty()) parts.add(context.getString(R.string.profile_gender_value, profile.gender))
        if (profile.birthDate > 0) {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add(context.getString(R.string.profile_birth_date, dateFormat.format(java.util.Date(profile.birthDate))))
            val today = java.util.Calendar.getInstance()
            val birthCal = java.util.Calendar.getInstance().apply { timeInMillis = profile.birthDate }
            var age = today.get(java.util.Calendar.YEAR) - birthCal.get(java.util.Calendar.YEAR)
            if (today.get(java.util.Calendar.DAY_OF_YEAR) < birthCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                age--
            }
            parts.add(context.getString(R.string.profile_age, age))
        }
        if (profile.personality.isNotEmpty()) parts.add(context.getString(R.string.profile_personality_value, profile.personality))
        if (profile.identity.isNotEmpty()) parts.add(context.getString(R.string.profile_identity_value, profile.identity))
        if (profile.occupation.isNotEmpty()) parts.add(context.getString(R.string.profile_occupation_value, profile.occupation))
        if (profile.aiStyle.isNotEmpty()) parts.add(context.getString(R.string.profile_ai_style_value, profile.aiStyle))
        return parts.joinToString("; ")
    }

    private suspend fun updateUserPreferencesFromAnalysis(context: Context, preferencesText: String) {
        if (preferencesText.isEmpty()) return

        fun extractValue(match: MatchResult): String? {
            if (match == null) return null
            return if (match.groupValues.size > 1) match.groupValues.last().trim() else null
        }

        val birthDateMatch = "(出生日期|出生年月日|Birth Date|Date of Birth)[:：\\s]+([\\d-]+)".toRegex().find(preferencesText)
        val birthYearMatch = "(出生年份|年龄|Birth year|Age)[:：\\s]+(\\d+)".toRegex().find(preferencesText)
        val genderMatch = "(性别|Gender)[:：\\s]+([^;]+)".toRegex().find(preferencesText)
        val personalityMatch = "(性格(特点�?|Personality( traits))[:：\\s]+([^;]+)".toRegex().find(preferencesText)
        val identityMatch = "(身份(认同�?|Identity( recognition))[:：\\s]+([^;]+)".toRegex().find(preferencesText)
        val occupationMatch = "(职业|Occupation)[:：\\s]+([^;]+)".toRegex().find(preferencesText)
        val aiStyleMatch = "(AI风格|期待的AI风格|偏好的AI风格|AI Style|Expected AI Style|Preferred AI Style)[:：\\s]+([^;]+)".toRegex().find(preferencesText)

        var birthDateTimestamp: Long? = null
        if (birthDateMatch != null) {
            try {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val date = extractValue(birthDateMatch)?.let { dateFormat.parse(it) }
                if (date != null) birthDateTimestamp = date.time
            } catch (e: Exception) {
                AppLogger.e(TAG, "解析出生日期失败: ${e.message}")
            }
        } else if (birthYearMatch != null) {
            try {
                val year = extractValue(birthYearMatch)?.toInt()
                if (year == null) return
                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, java.util.Calendar.JANUARY, 1, 0, 0, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                birthDateTimestamp = calendar.timeInMillis
            } catch (e: Exception) {
                AppLogger.e(TAG, "解析出生年份失败: ${e.message}")
            }
        }

        preferencesManager.updateProfileCategory(
                birthDate = birthDateTimestamp,
                gender = extractValue(genderMatch),
                personality = extractValue(personalityMatch),
                identity = extractValue(identityMatch),
                occupation = extractValue(occupationMatch),
                aiStyle = extractValue(aiStyleMatch)
        )
    }

    /**
     * Replaces the content of <tool_result> tags with a placeholder to reduce token count.
     */
    private fun pruneToolResultContent(context: Context, message: String): String {
        return ChatMarkupRegex.pruneToolResultContentPattern.replace(message) { matchResult ->
            val attributes = matchResult.groupValues[1]
            context.getString(R.string.problem_library_tool_result_pruned, attributes)
        }
    }

}
