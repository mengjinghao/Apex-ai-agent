package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.agent.core.memory.unified.AgentMode
import com.apex.agent.core.memory.unified.UnifiedMemoryManager
import com.apex.util.AppLogger
import com.apex.agent.core.tools.MemoryQueryResultData
import com.apex.agent.core.tools.MemoryLinkResultData
import com.apex.agent.core.tools.MemoryLinkQueryResultData
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.ToolExecutor
import com.apex.data.model.AITool
import com.apex.data.model.Memory
import com.apex.data.model.ToolResult
import com.apex.data.model.ToolValidationResult
import com.apex.data.preferences.MemorySearchSettingsPreferences
import com.apex.agent.data.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.*
import com.apex.data.preferences.preferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import com.apex.agent.core.tools.defaultTool.standard.MemoryQueryUtils
import com.apex.agent.core.tools.defaultTool.standard.name

/**
 * Executes queries against the AI's memory graph and manages user preferences.
 */
class MemoryQueryToolExecutor(private val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "MemoryQueryToolExecutor"
        private val MAX_QUERY_SNAPSHOTS_PER_PROFILE = MemoryQueryConfig.MAX_QUERY_SNAPSHOTS_PER_PROFILE
        private val DEFAULT_RELEVANCE_THRESHOLD = MemoryQueryConfig.DEFAULT_RELEVANCE_THRESHOLD
        private val DEFAULT_QUERY_LIMIT = MemoryQueryConfig.DEFAULT_QUERY_LIMIT
        private val MAX_WILDCARD_QUERY_LIMIT = MemoryQueryConfig.MAX_WILDCARD_QUERY_LIMIT

        private data class QuerySnapshotState(
            val id: String,
            val seenMemoryIds: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>(),
            val lock: Any = Any(),
            @Volatile var lastAccessAtMs: Long = System.currentTimeMillis()
        )

        private val querySnapshotsByProfile =
            ConcurrentHashMap<String, ConcurrentHashMap<String, QuerySnapshotState>>()
    }

    private val memoryRepositories = ConcurrentHashMap<String, MemoryRepository>()
    private val settingsRepositories = ConcurrentHashMap<String, MemorySearchSettingsPreferences>()
    private val unifiedMemoryManager by lazy {
        UnifiedMemoryManager.getInstance()
    }

    private val skillEvolutionManager by lazy { 
        com.apex.agent.core.skills.SkillEvolutionManager(context)
    }
    private val evolutionEngine by lazy { 
        com.apex.agent.core.evolution.LogistraAgentEvolutionEngine(
            context,
            memoryRepository,
            skillEvolutionManager
        )
    }

    private fun resolveActiveMode(): AgentMode {
        return unifiedMemoryManager.currentMode.value
    }

    private fun resolveActiveProfileId(): String {
        return runBlocking { preferencesManager.activeProfileIdFlow.first() }
    }

    private val memoryRepository: MemoryRepository
        get() {
            val profileId = resolveActiveProfileId()
            return memoryRepositories.computeIfAbsent(profileId) { MemoryRepository(context, it) }
        }

    private val memorySearchSettingsPreferences: MemorySearchSettingsPreferences
        get() {
            val profileId = resolveActiveProfileId()
            return settingsRepositories.computeIfAbsent(profileId) { MemorySearchSettingsPreferences(context, it) }
        }

    private fun getQuerySnapshotStore(profileId: String): ConcurrentHashMap<String, QuerySnapshotState> {
        return querySnapshotsByProfile.computeIfAbsent(profileId) { ConcurrentHashMap() }
    }

    private fun getOrCreateQuerySnapshot(profileId: String, requestedSnapshotId: String): Pair<QuerySnapshotState, Boolean> {
        val store = getQuerySnapshotStore(profileId)
        val now = System.currentTimeMillis()

        if (requestedSnapshotId == null) {
            while (true) {
                val generatedSnapshot = QuerySnapshotState(
                    id = UUID.randomUUID().toString(),
                    lastAccessAtMs = now
                )
                if (store.putIfAbsent(generatedSnapshot.id, generatedSnapshot) == null) {
                    trimOldSnapshots(store)
                    return generatedSnapshot to true
                }
            }
        }

        store[requestedSnapshotId]?.let { existingSnapshot ->
            existingSnapshot.lastAccessAtMs = now
            return existingSnapshot to false
        }

        val requestedSnapshot = QuerySnapshotState(
            id = requestedSnapshotId,
            lastAccessAtMs = now
        )
        val existingSnapshot = store.putIfAbsent(requestedSnapshotId, requestedSnapshot)
        val resolvedSnapshot = existingSnapshot ?: requestedSnapshot
        val snapshotCreated = existingSnapshot == null
        resolvedSnapshot.lastAccessAtMs = now
        if (snapshotCreated) {
            trimOldSnapshots(store)
        }
        return resolvedSnapshot to snapshotCreated
    }

    private fun trimOldSnapshots(store: ConcurrentHashMap<String, QuerySnapshotState>) {
        if (store.size <= MAX_QUERY_SNAPSHOTS_PER_PROFILE) {
            return
        }
        val overflow = store.size - MAX_QUERY_SNAPSHOTS_PER_PROFILE
        store.values
            .sortedBy { it.lastAccessAtMs }
            .take(overflow)
            .forEach { stale ->
                store.remove(stale.id, stale)
            }
    }

    private fun parseTimeBoundary(value: String?, isEnd: Boolean): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val timezone = TimeZone.getDefault()

        fun parseExact(pattern: String): Date? {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = timezone
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(trimmed, position)
            return if (parsed != null && position.index == trimmed.length) parsed else null
        }

        parseExact("yyyy-MM-dd HH:mm")?.let { parsed ->
            return Calendar.getInstance(timezone).apply {
                time = parsed
                set(Calendar.SECOND, if (isEnd) 59 else 0)
                set(Calendar.MILLISECOND, if (isEnd) 999 else 0)
            }.timeInMillis
        }

        parseExact("yyyy-MM-dd")?.let { parsed ->
            return Calendar.getInstance(timezone).apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, if (isEnd) 23 else 0)
                set(Calendar.MINUTE, if (isEnd) 59 else 0)
                set(Calendar.SECOND, if (isEnd) 59 else 0)
                set(Calendar.MILLISECOND, if (isEnd) 999 else 0)
            }.timeInMillis
        }

        return null
    }

    private var lastLoggedMode: AgentMode? = null

    override fun invoke(tool: AITool): ToolResult = runBlocking {
        val currentMode = resolveActiveMode()
        if (lastLoggedMode != currentMode) {
            lastLoggedMode = currentMode
            AppLogger.d(TAG, "MemoryQueryToolExecutor running in mode: $currentMode")
        }
        return@runBlocking when (tool.name) {
            "query_memory" -> executeQueryMemory(tool)
            "get_memory_by_title" -> executeGetMemoryByTitle(tool)
            "create_memory" -> executeCreateMemory(tool)
            "update_memory" -> executeUpdateMemory(tool)
            "delete_memory" -> executeDeleteMemory(tool)
            "move_memory" -> executeMoveMemory(tool)
            "update_user_preferences" -> executeUpdateUserPreferences(tool)
            "link_memories" -> executeLinkMemories(tool)
            "query_memory_links" -> executeQueryMemoryLinks(tool)
            "update_memory_link" -> executeUpdateMemoryLink(tool)
            "delete_memory_link" -> executeDeleteMemoryLink(tool)
            "find_duplicate_memories" -> executeFindDuplicateMemories(tool)
            "merge_duplicate_memories" -> executeMergeDuplicateMemories(tool)
            "recover_deleted_memory" -> executeRecoverDeletedMemory(tool)
            "rollback_memory_to_time" -> executeRollbackMemoryToTime(tool)
            "query_operation_logs" -> executeQueryOperationLogs(tool)
            "find_path_between_memories" -> executeFindPathBetweenMemories(tool)
            "find_graph_related_memories" -> executeFindGraphRelatedMemories(tool)
            "export_memories_to_markdown" -> executeExportMemoriesToMarkdown(tool)
            "export_graph_to_opml" -> executeExportGraphToOPML(tool)
            "chain_of_thought_search" -> executeChainOfThoughtSearch(tool)
            "solidify_high_value_memories" -> executeSolidifyHighValueMemories(tool)
            "export_environment_memory_to_markdown" -> executeExportEnvironmentMemoryToMarkdown(tool),
            "export_user_profile_to_markdown" -> executeExportUserProfileToMarkdown(tool),
            "honzon_update_profile" -> executeHonzonUpdateProfile(tool),
            "honzon_get_profile" -> executeHonzonGetProfile(tool),
            "honzon_get_dimensions" -> executeHonzonGetDimensions(tool),
            "honzon_generate_strategy" -> executeHonzonGenerateStrategy(tool),
            "skill_extract" -> executeSkillExtract(tool),
            "skill_evolve" -> executeSkillEvolve(tool),
            "skill_get_all" -> executeSkillGetAll(tool),
            "skill_get" -> executeSkillGet(tool),
            "skill_delete" -> executeSkillDelete(tool),
            "apex-agent_evolution_loop" -> executeApexAgentEvolutionLoop(tool),
            "apex-agent_evaluate_effect" -> executeApexAgentEvaluateEffect(tool),
            "apex-agent_optimize_strategy" -> executeApexAgentOptimizeStrategy(tool),
            "apex-agent_get_iteration_count" -> executeApexAgentGetIterationCount(tool),
            "apex-agent_reset_iteration_count" -> executeApexAgentResetIterationCount(tool),
            "memory_create_normal" -> executeMemoryCreateNormal(tool),
            "memory_create_agent" -> executeMemoryCreateAgent(tool),
            "memory_create_public" -> executeMemoryCreatePublic(tool),
            "memory_get_normal" -> executeMemoryGetNormal(tool),
            "memory_get_agent" -> executeMemoryGetAgent(tool),
            "memory_get_public" -> executeMemoryGetPublic(tool),
            "memory_search_by_type" -> executeMemorySearchByType(tool),
            "memory_delete_agent" -> executeMemoryDeleteAgent(tool),
            else -> ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Unknown tool: ${tool.name}"
            )
        }
    }

    private suspend fun executeQueryMemory(tool: AITool): ToolResult {
        val mode = resolveActiveMode()
        AppLogger.d(TAG, "query_memory executed in mode: $mode")
        val profileId = resolveActiveProfileId()
        val query = MemoryQueryUtils.getStringParameter(tool, "query") ?: ""
        val folderPath = MemoryQueryUtils.getStringParameter(tool, "folder_path")
        val settings = memorySearchSettingsPreferences.load()
        val limit = MemoryQueryUtils.getIntParameter(tool, "limit", MemoryQueryConfig.DEFAULT_QUERY_LIMIT)
        val startTimeParam = MemoryQueryUtils.getStringParameter(tool, "start_time")
        val endTimeParam = MemoryQueryUtils.getStringParameter(tool, "end_time")
        val snapshotIdParam = MemoryQueryUtils.getStringParameter(tool, "snapshot_id")
        val thresholdParam = MemoryQueryUtils.getStringParameter(tool, "threshold")
        val normalizedSnapshotId = snapshotIdParam?.trim()?.takeIf { it.isNotEmpty() }
        val threshold = thresholdParam?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        if (!thresholdParam.isNullOrBlank() && threshold == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid threshold. Expected a non-negative number.")
        }

        if (threshold != null && threshold < 0.0) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid threshold. Expected a non-negative number.")
        }

        val startTimeMs = parseTimeBoundary(startTimeParam, isEnd = false)
        if (!startTimeParam.isNullOrBlank() && startTimeMs == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid start_time. Expected format YYYY-MM-DD or YYYY-MM-DD HH:mm.")
        }

        val endTimeMs = parseTimeBoundary(endTimeParam, isEnd = true)
        if (!endTimeParam.isNullOrBlank() && endTimeMs == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid end_time. Expected format YYYY-MM-DD or YYYY-MM-DD HH:mm.")
        }

        if (startTimeMs != null && endTimeMs != null && startTimeMs > endTimeMs) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid time range: start_time must be <= end_time.")
        }
        
        // 如果查询"*" 且用户没有显式指？limit，则返回合理数量的结�?
    val isWildcardQuery = query.trim() == "*"
        val defaultLimit = if (isWildcardQuery) {
            MAX_WILDCARD_QUERY_LIMIT
        } else {
            DEFAULT_QUERY_LIMIT
        }
        val finalLimit = (limit ?: defaultLimit).coerceAtMost(MAX_WILDCARD_QUERY_LIMIT)

        if (query.isBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Query parameter cannot be empty.")
        }

        // limit 无上限，但至少为 1
    val validLimit = if (finalLimit < 1) 1 else finalLimit
        val (snapshotState, snapshotCreated) = getOrCreateQuerySnapshot(profileId, normalizedSnapshotId)

        AppLogger.d(
            TAG,
            "Executing memory query: '${query}' in folder: '${folderPath ?: "All"}', snapshot_id=${snapshotState.id}, snapshot_created=${snapshotCreated}, start_time: ${startTimeMs ?: "null"}, end_time: ${endTimeMs ?: "null"}, limit: ${validLimit}, threshold=${threshold ?: DEFAULT_RELEVANCE_THRESHOLD}, mode=${settings.scoreMode}, keywordWeight=${settings.keywordWeight}, tagWeight=${settings.tagWeight}, vectorWeight=${settings.vectorWeight}, edgeWeight=${settings.edgeWeight}"
        )

        return try {
            val results = memoryRepository.searchMemories(
                query = query,
                limit = validLimit,
                folderPath = folderPath,
                scoreMode = settings.scoreMode,
                keywordWeight = settings.keywordWeight,
                tagWeight = settings.tagWeight,
                semanticWeight = settings.vectorWeight,
                edgeWeight = settings.edgeWeight,
                relevanceThreshold = threshold ?: DEFAULT_RELEVANCE_THRESHOLD,
                createdAtStartMs = startTimeMs,
                createdAtEndMs = endTimeMs
            )

            // Keep de-duplication stable even when multiple calls share the same snapshot in parallel.
    val (excludedBySnapshotCount, returnedMemories) = synchronized(snapshotState.lock) {
                val excludedCount = results.count { it.id in snapshotState.seenMemoryIds }
                val unseenResults = results.filterNot { it.id in snapshotState.seenMemoryIds }
                val selectedResults = unseenResults.take(validLimit)
                if (selectedResults.isNotEmpty()) {
                    snapshotState.seenMemoryIds.addAll(selectedResults.map { it.id })
                }
                snapshotState.lastAccessAtMs = System.currentTimeMillis()
                excludedCount to selectedResults
            }

            val formattedResult = buildResultData(
                memories = returnedMemories,
                query = query,
                limit = validLimit,
                snapshotId = snapshotState.id,
                snapshotCreated = snapshotCreated,
                excludedBySnapshotCount = excludedBySnapshotCount
            )
            AppLogger.d(TAG, "Memory query result for '${query}':\n${formattedResult}")
            ToolResult(toolName = tool.name, success = true, result = formattedResult)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Memory query failed", e)
            ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Failed to execute memory query: ${e.message}")
        }
    }

    private suspend fun executeGetMemoryByTitle(tool: AITool): ToolResult {
        val title = MemoryQueryUtils.getStringParameter(tool, "title")
        if (title.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "title parameter is required")
        }

        // 提取可选的分块相关参数
    val chunkIndexParam = MemoryQueryUtils.getStringParameter(tool, "chunk_index")
        val chunkRangeParam = MemoryQueryUtils.getStringParameter(tool, "chunk_range")
        val queryParam = MemoryQueryUtils.getStringParameter(tool, "query")
        val chunkLimitParam = MemoryQueryUtils.getStringParameter(tool, "limit")

        AppLogger.d(TAG, "Getting memory by title: ${title}, chunk_index: ${chunkIndexParam}, chunk_range: ${chunkRangeParam}, query: ${queryParam}, limit: ${chunkLimitParam}")

        return try {
            val memory = memoryRepository.findMemoryByTitle(title)
            if (memory == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Memory not found with title: ${title}"
                )
            }

            // 如果是文档节点且提供了分块参数，则进行特殊处�?
    if (memory.isDocumentNode && (chunkIndexParam != null || chunkRangeParam != null || queryParam != null)) {
                return handleDocumentChunkRetrieval(tool.name, memory, chunkIndexParam, chunkRangeParam, queryParam, chunkLimitParam)
            }

            // 默认行为：返回完整记�?
    val formattedResult = buildResultData(listOf(memory), title, 1)
            AppLogger.d(TAG, "Found memory by title '${title}':\n${formattedResult}")
            ToolResult(
                toolName = tool.name,
                success = true,
                result = formattedResult
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get memory by title", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to get memory by title: ${e.message}"
            )
        }
    }

    private suspend fun handleDocumentChunkRetrieval(
        toolName: String,
        memory: Memory,
        chunkIndexParam: String?,
        chunkRangeParam: String?,
        queryParam: String?,
        limitParam: String?
    ): ToolResult = withContext(Dispatchers.IO) {
        val totalChunks = memoryRepository.getTotalChunkCount(memory.id)
        val validLimit = (limitParam?.toIntOrNull() ?: MemoryQueryConfig.DEFAULT_QUERY_LIMIT).coerceAtLeast(1)
        
        try {
            // 优先级：query > chunk_range > chunk_index
    val chunks = when {
                // 模糊搜索分块
                !queryParam.isNullOrBlank() -> {
                    AppLogger.d(TAG, "Searching chunks in document '${memory.title}' with query: '${queryParam}', limit: ${validLimit}")
                    memoryRepository.searchChunksInDocument(memory.id, queryParam, validLimit)
                }
                // 范围查询
                !chunkRangeParam.isNullOrBlank() -> {
                    val rangeParts = chunkRangeParam.split("-")
                    if (rangeParts.size != 2) {
                        return@withContext ToolResult(
                            toolName = toolName,
                            success = false,
                            result = StringResultData(""),
                            error = "Invalid chunk_range format. Expected 'start-end' (e.g., '3-7')"
                        )
                    }
                    // 解析-based索引，转换为0-based
    val startIndex = (rangeParts[0].toIntOrNull() ?: 1) - 1
                    val endIndex = (rangeParts[1].toIntOrNull() ?: totalChunks) - 1
                    
                    if (startIndex < 0 || endIndex >= totalChunks || startIndex > endIndex) {
                        return@withContext ToolResult(
                            toolName = toolName,
                            success = false,
                            result = StringResultData(""),
                            error = "Chunk range out of bounds. Document has ${totalChunks} chunks. Valid range: 1-${totalChunks}"
                        )
                    }
                    AppLogger.d(TAG, "Retrieving chunk range ${startIndex + 1}-${endIndex + 1} from document '${memory.title}'")
                    memoryRepository.getChunksByRange(memory.id, startIndex, endIndex)
                }
                // 单个分块
                !chunkIndexParam.isNullOrBlank() -> {
                    // 解析-based索引，转换为0-based
    val chunkIndex = (chunkIndexParam.toIntOrNull() ?: 1) - 1
                    if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                        return@withContext ToolResult(
                            toolName = toolName,
                            success = false,
                            result = StringResultData(""),
                            error = "Chunk index out of bounds. Document has ${totalChunks} chunks. Valid range: 1-${totalChunks}"
                        )
                    }
                    AppLogger.d(TAG, "Retrieving chunk ${chunkIndex + 1} from document '${memory.title}'")
                    val chunk = memoryRepository.getChunkByIndex(memory.id, chunkIndex)
                    listOfNotNull(chunk)
                }
                else -> emptyList()
            }

            if (chunks.isEmpty()) {
                return@withContext ToolResult(
                    toolName = toolName,
                    success = false,
                    result = StringResultData(""),
                    error = "No matching chunks found"
                )
            }

            // 格式化返回结�?
    val content = "Document: ${memory.title}\n" +
                chunks.joinToString("\n---\n") { chunk ->
                    "Chunk ${chunk.chunkIndex + 1}/${totalChunks}:\n${chunk.content}"
                }

            val chunkIndices = chunks.map { it.chunkIndex }
            val chunkInfo = if (chunks.size == 1) {
                "Chunk ${chunks[0].chunkIndex + 1}/${totalChunks}"
            } else {
                "Chunks ${chunks.map { it.chunkIndex + 1 }.joinToString(", ")}/${totalChunks}"
            }

            AppLogger.d(TAG, "Retrieved ${chunks.size} chunks from document '${memory.title}': ${chunkInfo}")
            
            ToolResult(
                toolName = toolName,
                success = true,
                result = StringResultData(content)
            )
        } catch (e: NumberFormatException) {
            ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Invalid number format in chunk parameters: ${e.message}"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to retrieve document chunks", e)
            ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Failed to retrieve document chunks: ${e.message}"
            )
        }
    }

    private suspend fun executeCreateMemory(tool: AITool): ToolResult {
        val title = MemoryQueryUtils.getStringParameter(tool, "title") ?: ""
        val content = MemoryQueryUtils.getStringParameter(tool, "content") ?: ""
        
        if (title.isBlank() || content.isBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Both title and content parameters are required")
        }

        AppLogger.d(TAG, "Creating memory: ${title}")

        return try {
            val contentType = MemoryQueryUtils.getStringParameter(tool, "content_type") ?: "text/plain"
            val source = MemoryQueryUtils.getStringParameter(tool, "source") ?: "ai_created"
            val folderPath = MemoryQueryUtils.getStringParameter(tool, "folder_path") ?: ""
            val tagsParam = MemoryQueryUtils.getStringParameter(tool, "tags")
            val tags = tagsParam
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
             
            val mode = resolveActiveMode()
            val enhancedTags = (tags ?: emptyList()) + "mode_${mode.name}"

            val memory = memoryRepository.createMemory(
                title = title,
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath,
                tags = enhancedTags.distinct()
            )
            
            if (memory != null) {
                val message = "Successfully created memory: '${title}' (UUID: ${memory.uuid})"
                AppLogger.d(TAG, message)
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(message)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to create memory"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to create memory: ${e.message}"
            )
        }
    }

    private suspend fun executeUpdateMemory(tool: AITool): ToolResult {
        val oldTitle = MemoryQueryUtils.getStringParameter(tool, "old_title")
        
        if (oldTitle.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "old_title parameter is required to identify the memory")
        }

        AppLogger.d(TAG, "Updating memory with title: ${oldTitle}")

        return try {
            val memory = memoryRepository.findMemoryByTitle(oldTitle)
            if (memory == null) {
                return MemoryQueryUtils.createErrorResult(tool.name, "Memory not found with title: ${oldTitle}")
            }

            // 获取要更新的字段，如果没有提供则使用户？
    val newTitle = MemoryQueryUtils.getStringParameter(tool, "new_title") ?: memory.title
            val newContent = MemoryQueryUtils.getStringParameter(tool, "content") ?: memory.content
            val newContentType = MemoryQueryUtils.getStringParameter(tool, "content_type") ?: memory.contentType
            val newSource = MemoryQueryUtils.getStringParameter(tool, "source") ?: memory.source
            val newCredibility = MemoryQueryUtils.getFloatParameter(tool, "credibility", memory.credibility)
            val newImportance = MemoryQueryUtils.getFloatParameter(tool, "importance", memory.importance)
            val newFolderPath = MemoryQueryUtils.getStringParameter(tool, "folder_path") ?: memory.folderPath
            val tagsParam = MemoryQueryUtils.getStringParameter(tool, "tags")
            val newTags = tagsParam?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            
            val updatedMemory = memoryRepository.updateMemory(
                memory = memory,
                newTitle = newTitle,
                newContent = newContent,
                newContentType = newContentType,
                newSource = newSource,
                newCredibility = newCredibility,
                newImportance = newImportance,
                newFolderPath = newFolderPath,
                newTags = newTags
            )
            
            if (updatedMemory != null) {
                val message = "Successfully updated memory from '${oldTitle}' to '${newTitle}'"
                AppLogger.d(TAG, message)
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(message)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to update memory"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to update memory: ${e.message}"
            )
        }
    }

    private suspend fun executeDeleteMemory(tool: AITool): ToolResult {
        val title = MemoryQueryUtils.getStringParameter(tool, "title")
        
        if (title.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "title parameter is required to identify the memory")
        }

        AppLogger.d(TAG, "Deleting memory with title: ${title}")

        return try {
            val memory = memoryRepository.findMemoryByTitle(title)
            if (memory == null) {
                return MemoryQueryUtils.createErrorResult(tool.name, "Memory not found with title: ${title}")
            }

            val deleted = memoryRepository.deleteMemoryById(memory.id)
            
            if (deleted) {
                val message = "Successfully deleted memory: '${title}'"
                AppLogger.d(TAG, message)
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(message)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to delete memory"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to delete memory: ${e.message}"
            )
        }
    }

    private suspend fun executeUpdateUserPreferences(tool: AITool): ToolResult {
        AppLogger.d(TAG, "Executing update user preferences")

        return try {
            // 从参数中提取各项偏好设置
    val birthDate = MemoryQueryUtils.getStringParameter(tool, "birth_date")?.toLongOrNull()
            val gender = MemoryQueryUtils.getStringParameter(tool, "gender")
            val personality = MemoryQueryUtils.getStringParameter(tool, "personality")
            val identity = MemoryQueryUtils.getStringParameter(tool, "identity")
            val occupation = MemoryQueryUtils.getStringParameter(tool, "occupation")
            val aiStyle = MemoryQueryUtils.getStringParameter(tool, "ai_style")

            // 检查是否至少有一个参�?
    if (birthDate == null && gender == null && personality == null && 
                identity == null && occupation == null && aiStyle == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "At least one preference parameter must be provided"
                )
            }

            // 更新用户偏好
            withContext(Dispatchers.IO) {
                preferencesManager.updateProfileCategory(
                    birthDate = birthDate,
                    gender = gender,
                    personality = personality,
                    identity = identity,
                    occupation = occupation,
                    aiStyle = aiStyle
                )
            }

            val updatedFields = mutableListOf<String>()
            birthDate?.let { updatedFields.add("birth_date") }
            gender?.let { updatedFields.add("gender") }
            personality?.let { updatedFields.add("personality") }
            identity?.let { updatedFields.add("identity") }
            occupation?.let { updatedFields.add("occupation") }
            aiStyle?.let { updatedFields.add("ai_style") }

            val message = "Successfully updated user preferences: ${updatedFields.joinToString(", ")}"
            AppLogger.d(TAG, message)
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update user preferences", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to update user preferences: ${e.message}"
            )
        }
    }

    private suspend fun executeLinkMemories(tool: AITool): ToolResult {
        val sourceTitle = MemoryQueryUtils.getStringParameter(tool, "source_title")
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")
        
        if (sourceTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Both source_title and target_title parameters are required")
        }

        AppLogger.d(TAG, "Linking memories: '${sourceTitle}' -> '${targetTitle}'")

        return try {
            // 提取可选参�?
    val linkType = MemoryQueryUtils.getStringParameter(tool, "link_type") ?: "related"
            val weight = MemoryQueryUtils.getFloatParameter(tool, "weight", MemoryQueryConfig.DEFAULT_LINK_WEIGHT)
            val description = MemoryQueryUtils.getStringParameter(tool, "description") ?: ""
            
            // 限制 weight 在有效范围内
    val validWeight = weight.coerceIn(0.0f, 1.0f)
            
            // 查找源记忆和目标记忆
    val sourceMemory = memoryRepository.findMemoryByTitle(sourceTitle)
            if (sourceMemory == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Source memory not found with title: ${sourceTitle}"
                )
            }
            
            val targetMemory = memoryRepository.findMemoryByTitle(targetTitle)
            if (targetMemory == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Target memory not found with title: ${targetTitle}"
                )
            }
            
            // 创建链接
            memoryRepository.linkMemories(
                source = sourceMemory,
                target = targetMemory,
                type = linkType,
                weight = validWeight,
                description = description
            )
            
            val resultData = MemoryLinkResultData(
                sourceTitle = sourceTitle,
                targetTitle = targetTitle,
                linkType = linkType,
                weight = validWeight,
                description = description
            )
            
            AppLogger.d(TAG, "Successfully linked memories: '${sourceTitle}' -> '${targetTitle}' (type: ${linkType}, weight: ${validWeight})")
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = resultData
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to link memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to link memories: ${e.message}"
            )
        }
    }

    private suspend fun executeQueryMemoryLinks(tool: AITool): ToolResult {
        val linkIdRaw = MemoryQueryUtils.getStringParameter(tool, "link_id")
        val linkId = linkIdRaw?.toLongOrNull()
        if (!linkIdRaw.isNullOrBlank() && linkId == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid link_id. Expected integer.")
        }

        val sourceTitle = MemoryQueryUtils.getStringParameter(tool, "source_title")?.trim()
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")?.trim()
        val linkType = MemoryQueryUtils.getStringParameter(tool, "link_type")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = MemoryQueryUtils.getIntParameter(tool, "limit", 20).coerceIn(1, 200)

        return try {
            val sourceMemoryId = if (!sourceTitle.isNullOrBlank()) {
                memoryRepository.findMemoryByTitle(sourceTitle)?.id ?: return MemoryQueryUtils.createErrorResult(tool.name, "Source memory not found with title: ${sourceTitle}")
            } else {
                null
            }

            val targetMemoryId = if (!targetTitle.isNullOrBlank()) {
                memoryRepository.findMemoryByTitle(targetTitle)?.id ?: return MemoryQueryUtils.createErrorResult(tool.name, "Target memory not found with title: ${targetTitle}")
            } else {
                null
            }

            val links = memoryRepository.queryMemoryLinks(
                linkId = linkId,
                sourceMemoryId = sourceMemoryId,
                targetMemoryId = targetMemoryId,
                linkType = linkType,
                limit = limit
            )

            val linkInfos = links.mapNotNull { link ->
                val source = link.source.target
                val target = link.target.target
                if (source == null || target == null) {
                    null
                } else {
                    MemoryLinkQueryResultData.LinkInfo(
                        linkId = link.id,
                        sourceTitle = source.title,
                        targetTitle = target.title,
                        linkType = link.type,
                        weight = link.weight,
                        description = link.description
                    )
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = MemoryLinkQueryResultData(
                    totalCount = linkInfos.size,
                    links = linkInfos
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to query memory links", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to query memory links: ${e.message}"
            )
        }
    }

    private suspend fun executeMoveMemory(tool: AITool): ToolResult {
        val targetFolderPath = MemoryQueryUtils.getStringParameter(tool, "target_folder_path")
        val sourceFolderPath = MemoryQueryUtils.getStringParameter(tool, "source_folder_path")
        val hasSourceFolderParam = sourceFolderPath != null
        val titlesRaw = MemoryQueryUtils.getStringParameter(tool, "titles")
        val titles = MemoryQueryUtils.parseTitlesParam(titlesRaw)

        if (targetFolderPath == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "target_folder_path parameter is required")
        }

        if (titles.isEmpty() && !hasSourceFolderParam) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Provide titles and/or source_folder_path to select memories to move")
        }

        AppLogger.d(
            TAG,
            "Moving memories. target_folder_path='${targetFolderPath}', source_folder_path='${sourceFolderPath ?: ""}', has_source_folder_param=${hasSourceFolderParam}, titles_count=${titles.size}"
        )

        return try {
            val selectedByTitle = if (titles.isNotEmpty()) {
                titles.flatMap { title -> memoryRepository.findMemoriesByTitle(title) }
            } else {
                emptyList()
            }
            val selectedByFolder = if (hasSourceFolderParam) {
                memoryRepository.getMemoriesByFolderPath(sourceFolderPath ?: "")
            } else {
                emptyList()
            }

            val selected = when {
                titles.isNotEmpty() && hasSourceFolderParam -> {
                    val folderIds = selectedByFolder.map { it.id }.toHashSet()
                    selectedByTitle.filter { folderIds.contains(it.id) }
                }
                titles.isNotEmpty() -> selectedByTitle
                else -> selectedByFolder
            }

            val uniqueMemories = LinkedHashMap<Long, Memory>()
            selected.forEach { uniqueMemories[it.id] = it }
            val memoryIds = uniqueMemories.keys.toList()

            if (memoryIds.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "No matching memories found to move"
                )
            }

            val moved = memoryRepository.moveMemoriesToFolder(memoryIds, targetFolderPath)
            if (!moved) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to move selected memories"
                )
            }

            val destination = if (targetFolderPath.isBlank()) "uncategorized" else targetFolderPath
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData("Successfully moved ${memoryIds.size} memories to '${destination}'")
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to move memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to move memories: ${e.message}"
            )
        }
    }



    private suspend fun executeUpdateMemoryLink(tool: AITool): ToolResult {
        val linkId = MemoryQueryUtils.getStringParameter(tool, "link_id")?.toLongOrNull()
        val sourceTitle = MemoryQueryUtils.getStringParameter(tool, "source_title")
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")
        val locatorLinkType = MemoryQueryUtils.getStringParameter(tool, "link_type")

        val newLinkType = MemoryQueryUtils.getStringParameter(tool, "new_link_type")
        val newWeight = MemoryQueryUtils.getFloatParameter(tool, "weight", -1f).takeIf { it >= 0 }
        val newDescription = MemoryQueryUtils.getStringParameter(tool, "description")

        if (newLinkType.isNullOrBlank() && newWeight == null && newDescription == null) {
            return MemoryQueryUtils.createErrorResult(tool.name, "At least one of new_link_type, weight, description must be provided")
        }

        return try {
            val link = if (linkId != null) {
                memoryRepository.findLinkById(linkId)
            } else {
                if (sourceTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
                    return MemoryQueryUtils.createErrorResult(tool.name, "Provide link_id, or provide both source_title and target_title")
                }

                val sourceMemory = memoryRepository.findMemoryByTitle(sourceTitle)
                    ?: return MemoryQueryUtils.createErrorResult(tool.name, "Source memory not found with title: ${sourceTitle}")
                val targetMemory = memoryRepository.findMemoryByTitle(targetTitle)
                    ?: return MemoryQueryUtils.createErrorResult(tool.name, "Target memory not found with title: ${targetTitle}")

                val candidates = sourceMemory.links.filter {
                    it.target.target?.id == targetMemory.id &&
                        (locatorLinkType.isNullOrBlank() || it.type == locatorLinkType)
                }
                when {
                    candidates.isEmpty() -> null
                    candidates.size > 1 -> {
                        return MemoryQueryUtils.createErrorResult(tool.name, "Multiple links matched. Provide link_id or a more specific link_type.")
                    }
                    else -> candidates.first()
                }
            }

            if (link == null) {
                return MemoryQueryUtils.createErrorResult(tool.name, if (linkId != null) "Link not found with id: ${linkId}" else "No matching link found")
            }

            val updated = memoryRepository.updateLink(
                linkId = link.id,
                type = newLinkType ?: link.type,
                weight = (newWeight ?: link.weight).coerceIn(0.0f, 1.0f),
                description = newDescription ?: link.description
            )

            if (updated == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to update memory link"
                )
            }

            val source = updated.source.target?.title ?: sourceTitle ?: ""
            val target = updated.target.target?.title ?: targetTitle ?: ""

            ToolResult(
                toolName = tool.name,
                success = true,
                result = MemoryLinkResultData(
                    sourceTitle = source,
                    targetTitle = target,
                    linkType = updated.type,
                    weight = updated.weight,
                    description = updated.description
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update memory link", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to update memory link: ${e.message}"
            )
        }
    }

    private suspend fun executeDeleteMemoryLink(tool: AITool): ToolResult {
        val linkId = MemoryQueryUtils.getStringParameter(tool, "link_id")?.toLongOrNull()
        val sourceTitle = MemoryQueryUtils.getStringParameter(tool, "source_title")
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")
        val locatorLinkType = MemoryQueryUtils.getStringParameter(tool, "link_type")

        return try {
            val resolvedLinkId = if (linkId != null) {
                linkId
            } else {
                if (sourceTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
                    return MemoryQueryUtils.createErrorResult(tool.name, "Provide link_id, or provide both source_title and target_title")
                }

                val sourceMemory = memoryRepository.findMemoryByTitle(sourceTitle)
                    ?: return MemoryQueryUtils.createErrorResult(tool.name, "Source memory not found with title: ${sourceTitle}")
                val targetMemory = memoryRepository.findMemoryByTitle(targetTitle)
                    ?: return MemoryQueryUtils.createErrorResult(tool.name, "Target memory not found with title: ${targetTitle}")

                val candidates = sourceMemory.links.filter {
                    it.target.target?.id == targetMemory.id &&
                        (locatorLinkType.isNullOrBlank() || it.type == locatorLinkType)
                }

                when {
                    candidates.isEmpty() -> {
                        return MemoryQueryUtils.createErrorResult(tool.name, "No matching link found")
                    }
                    candidates.size > 1 -> {
                        return MemoryQueryUtils.createErrorResult(tool.name, "Multiple links matched. Provide link_id or a more specific link_type.")
                    }
                    else -> candidates.first().id
                }
            }

            val deleted = memoryRepository.deleteLink(resolvedLinkId)
            if (!deleted) {
                return MemoryQueryUtils.createErrorResult(tool.name, "Failed to delete memory link with id: ${resolvedLinkId}")
            }

            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("Successfully deleted memory link: ${resolvedLinkId}"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete memory link", e)
            MemoryQueryUtils.createErrorResult(tool.name, "Failed to delete memory link: ${e.message}")
        }
    }

    private suspend fun buildResultData(
        memories: List<Memory>,
        query: String,
        limit: Int,
        snapshotId: String? = null,
        snapshotCreated: Boolean = false,
        excludedBySnapshotCount: Int = 0
    ): MemoryQueryResultData = withContext(Dispatchers.IO) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        // ??limit > 20 时，只返回标题和截断内容
    val isTruncatedMode = limit > MemoryQueryConfig.DEFAULT_QUERY_LIMIT
        val maxContentLength = MemoryQueryConfig.MAX_CONTENT_LENGTH_TRUNCATED // 截断后的最大内容长度（更严格）
    val memoryInfos = memories.map { memory ->
            val content: String
            val chunkInfo: String?
            val chunkIndices: List<Int>?
            
            if (memory.isDocumentNode) {
                // 对于文档节点，执？二次探�?，获取匹配的区块内容
                AppLogger.d(TAG, "Memory result is a document ('${memory.title}'). Fetching specific matching chunks for query: '${query}'")
                val matchingChunks = memoryRepository.searchChunksInDocument(memory.id, query, limit)
                val totalChunks = memoryRepository.getTotalChunkCount(memory.id)

                if (matchingChunks.isNotEmpty()) {
                    // 收集分块索引（使�?based显示�?
                    chunkIndices = matchingChunks.map { it.chunkIndex }
                    
                    // 生成分块信息摘要
                    chunkInfo = if (matchingChunks.size == 1) {
                        "Chunk ${matchingChunks[0].chunkIndex + 1}/${totalChunks}"
                    } else {
                        "Chunks ${matchingChunks.map { it.chunkIndex + 1 }.take(MemoryQueryConfig.MAX_CHUNKS_DISPLAYED).joinToString(", ")}/${totalChunks}"
                    }
                    
                    if (isTruncatedMode) {
                        // 截断模式：只显示文档标题和分块信�?
                        content = "Document: ${memory.title} (${totalChunks} chunks)"
                    } else {
                        // 将匹配的区块内容拼接起来，每个区块显示编�?
                        content = "Document: ${memory.title}\n" +
                            matchingChunks.take(MemoryQueryConfig.MAX_CHUNKS_DISPLAYED) // 最多取5个最相关的区�?                               .joinToString("\n---\n") { chunk -> 
                                    "Chunk ${chunk.chunkIndex + 1}/${totalChunks}:\n${chunk.content}"
                                }
                    }
                } else {
                    // 如果二次探查未找到（理论上很少见，因为全局搜索已经认为它相关），提供一个回退信息
                    chunkInfo = null
                    chunkIndices = null
                    if (isTruncatedMode) {
                        content = "Document: ${memory.title}"
                    } else {
                        content = "Document '${memory.title}' was found, but no specific chunks matched the query '${query}'. The document's general content is: ${memory.content}"
                    }
                }
            } else {
                // 对于普通记�?
                chunkInfo = null
                chunkIndices = null
                if (isTruncatedMode) {
                    // 截断模式：只返回标题和部分内�?
                    content = if (memory.content.length > maxContentLength) {
                        memory.content.take(maxContentLength) + "..."
                    } else {
                        memory.content
                    }
                } else {
                    // 完整模式：返回完整内�?
                    content = memory.content
                }
            }

            MemoryQueryResultData.MemoryInfo(
                title = memory.title,
                content = content,
                source = memory.source,
                tags = memory.tags.map { it.name },
                createdAt = sdf.format(memory.createdAt),
                chunkInfo = chunkInfo,
                chunkIndices = chunkIndices
        )
    }

    private suspend fun executeFindDuplicateMemories(tool: AITool): ToolResult {
        val threshold = MemoryQueryUtils.getFloatParameter(tool, "similarity_threshold", MemoryQueryConfig.DEFAULT_SIMILARITY_THRESHOLD)

        if (threshold < 0.0f || threshold > 1.0f) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid similarity_threshold. Must be between 0.0 and 1.0.")
        }

        return try {
            val duplicateGroups = memoryRepository.findDuplicateMemories(threshold)
            if (duplicateGroups.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("No duplicate memories found.")
                )
            }

            val resultContent = buildString {
                appendLine("Found ${duplicateGroups.size} duplicate memory groups:")
                duplicateGroups.forEachIndexed { index, group ->
                    appendLine("\nGroup ${index + 1} (${group.size} memories):")
                    group.forEach { memory ->
                        appendLine("- ${memory.title} (UUID: ${memory.uuid}, created: ${memory.createdAt})")
                    }
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultContent)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to find duplicate memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to find duplicate memories: ${e.message}"
            )
        }
    }

    private suspend fun executeMergeDuplicateMemories(tool: AITool): ToolResult {
        val sourceTitlesParam = MemoryQueryUtils.getStringParameter(tool, "source_titles")
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")
        val targetContent = MemoryQueryUtils.getStringParameter(tool, "target_content")
        val keepTags = MemoryQueryUtils.getBooleanParameter(tool, "keep_tags", true)
        val keepLinks = MemoryQueryUtils.getBooleanParameter(tool, "keep_links", true)

        if (sourceTitlesParam.isNullOrBlank() || targetTitle.isNullOrBlank() || targetContent.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "source_titles, target_title, target_content are required parameters.")
        }

        val sourceTitles = sourceTitlesParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return try {
            val mergedMemory = memoryRepository.mergeDuplicateMemories(
                sourceTitles = sourceTitles,
                targetTitle = targetTitle,
                targetContent = targetContent,
                keepTags = keepTags,
                keepLinks = keepLinks
            )

            if (mergedMemory == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to merge memories. Please check if source titles are valid."
                )
            }

            val message = "Successfully merged ${sourceTitles.size} memories into new memory: '${targetTitle}' (UUID: ${mergedMemory.uuid})"
            AppLogger.d(TAG, message)
            return ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to merge memories", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to merge memories: ${e.message}"
            )
        }
    }

    private suspend fun executeRecoverDeletedMemory(tool: AITool): ToolResult {
        val memoryUuid = MemoryQueryUtils.getStringParameter(tool, "memory_uuid")

        if (memoryUuid.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Missing or empty required parameter: memory_uuid")
        }

        return try {
            val profileId = resolveActiveProfileId()
            val repository = MemoryRepository(context, profileId)
            val recoveredMemory = repository.recoverDeletedMemory(memoryUuid)

            if (recoveredMemory == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "No deleted memory found with UUID: ${memoryUuid}"
                )
            }

            val message = "Successfully recovered deleted memory: '${recoveredMemory.title}' (UUID: ${recoveredMemory.uuid})"
            AppLogger.d(TAG, message)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to recover deleted memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to recover deleted memory: ${e.message}"
            )
        }
    }

    private suspend fun executeRollbackMemoryToTime(tool: AITool): ToolResult {
        val targetTimeParam = MemoryQueryUtils.getStringParameter(tool, "target_time")

        if (targetTimeParam.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Missing or empty required parameter: target_time")
        }

        return try {
            val targetTime = parseDateTime(targetTimeParam)
                ?: return MemoryQueryUtils.createErrorResult(tool.name, "Invalid time format. Expected: YYYY-MM-DD HH:mm")

            val profileId = resolveActiveProfileId()
            val repository = MemoryRepository(context, profileId)
            val success = repository.rollbackToTimepoint(targetTime)

            if (!success) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to rollback. No WAL logs found before target time."
                )
            }

            val message = "Successfully rolled back memory library to: ${targetTimeParam}"
            AppLogger.d(TAG, message)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to rollback memory library", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to rollback memory library: ${e.message}"
            )
        }
    }

    private suspend fun executeQueryOperationLogs(tool: AITool): ToolResult {
        val operationType = MemoryQueryUtils.getStringParameter(tool, "operation_type")
        val startTimeParam = MemoryQueryUtils.getStringParameter(tool, "start_time")
        val endTimeParam = MemoryQueryUtils.getStringParameter(tool, "end_time")
        val limit = MemoryQueryUtils.getIntParameter(tool, "limit", 100)

        return try {
            val startTime = startTimeParam?.let { parseDateTime(it) }
            val endTime = endTimeParam?.let { parseDateTime(it) }

            val profileId = resolveActiveProfileId()
            val repository = MemoryRepository(context, profileId)
            val logs = repository.queryWALLogs(
                operationType = operationType?.takeIf { it.isNotBlank() },
                startTime = startTime,
                endTime = endTime,
                limit = limit
            )

            if (logs.isEmpty()) {
                return MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("No operation logs found matching the criteria."))
            }

            val resultContent = buildString {
                appendLine("Found ${logs.size} operation logs:")
                logs.take(20).forEach { wal ->
                    appendLine("\n- ${wal.operationType}: ${wal.memoryUuid} at ${wal.operatedAt}")
                    appendLine("  Remark: ${wal.remark}")
                }
                if (logs.size > 20) {
                    appendLine("\n... and ${logs.size - 20} more logs")
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultContent)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to query operation logs", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to query operation logs: ${e.message}"
            )
        }
    }

    private fun parseDateTime(dateTimeStr: String): Date? {
        return try {
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            )
            formats.firstNotNullOfOrNull { format ->
                try {
                    format.isLenient = false
                    format.parse(dateTimeStr)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeFindPathBetweenMemories(tool: AITool): ToolResult {
        val sourceTitle = MemoryQueryUtils.getStringParameter(tool, "source_title")
        val targetTitle = MemoryQueryUtils.getStringParameter(tool, "target_title")
        val maxHops = MemoryQueryUtils.getIntParameter(tool, "max_hops", 3)
        val maxPaths = MemoryQueryUtils.getIntParameter(tool, "max_paths", MemoryQueryConfig.MAX_PATHS_FOR_PATH_FINDING)

        if (sourceTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "source_title and target_title are required parameters.")
        }

        if (maxHops < 1 || maxHops > MemoryQueryConfig.MAX_HOPS_FOR_PATH_FINDING) {
            return MemoryQueryUtils.createErrorResult(tool.name, "max_hops must be between 1 and ${MemoryQueryConfig.MAX_HOPS_FOR_PATH_FINDING}.")
        }

        return try {
            val paths = memoryRepository.findPathsBetweenMemories(
                sourceTitle = sourceTitle,
                targetTitle = targetTitle,
                maxHops = maxHops,
                maxPaths = maxPaths
            )

            if (paths.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("No paths found between '${sourceTitle}' and '${targetTitle}' within ${maxHops} hops.")
                )
            }

            val resultContent = buildString {
                appendLine("Found ${paths.size} path(s) between '${sourceTitle}' and '${targetTitle}':")
                paths.forEachIndexed { pathIndex, path ->
                    appendLine("\nPath ${pathIndex + 1} (${path.size - 1} hops):")
                    path.forEachIndexed { index, memory ->
                        if (index > 0) append(" ??")
                        append("'${memory.title}'")
                    }
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultContent)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to find paths between memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to find paths: ${e.message}"
            )
        }
    }

    private suspend fun executeFindGraphRelatedMemories(tool: AITool): ToolResult {
        val queryTitle = MemoryQueryUtils.getStringParameter(tool, "query_title")
        val maxHops = MemoryQueryUtils.getIntParameter(tool, "max_hops", 2)
        val minWeight = MemoryQueryUtils.getFloatParameter(tool, "min_weight", 0.5f)

        if (queryTitle.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "query_title is a required parameter.")
        }

        if (maxHops < 1 || maxHops > MemoryQueryConfig.MAX_HOPS_FOR_GRAPH_RELATED) {
            return MemoryQueryUtils.createErrorResult(tool.name, "max_hops must be between 1 and ${MemoryQueryConfig.MAX_HOPS_FOR_GRAPH_RELATED}.")
        }
        if (minWeight < 0.0f || minWeight > 1.0f) {
            return MemoryQueryUtils.createErrorResult(tool.name, "min_weight must be between 0.0 and 1.0.")
        }

        return try {
            val relatedMemories = memoryRepository.findGraphRelatedMemories(
                queryTitle = queryTitle,
                maxHops = maxHops,
                minWeight = minWeight
            )

            if (relatedMemories.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("No graph-related memories found for '${queryTitle}'.")
                )
            }

            val resultContent = buildString {
                appendLine("Found ${relatedMemories.size} graph-related memories for '${queryTitle}':")
                relatedMemories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title}")
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultContent)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to find graph-related memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to find graph-related memories: ${e.message}"
            )
        }
    }

    private suspend fun executeExportMemoriesToMarkdown(tool: AITool): ToolResult {
        val outputDirPath = MemoryQueryUtils.getStringParameter(tool, "output_directory")
        val includeMetadata = MemoryQueryUtils.getBooleanParameter(tool, "include_metadata", true)

        if (outputDirPath.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "output_directory is a required parameter.")
        }
        val outputDir = File(outputDirPath)

        return try {
            val exportedCount = memoryRepository.exportMemoriesToMarkdown(
                outputDir = outputDir,
                includeMetadata = includeMetadata
            )

            val message = "Successfully exported ${exportedCount} memories to Markdown in: ${outputDir.absolutePath}"
            AppLogger.d(TAG, message)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export memories to Markdown", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to export memories: ${e.message}"
            )
        }
    }

    private suspend fun executeExportGraphToOPML(tool: AITool): ToolResult {
        val outputFilePath = MemoryQueryUtils.getStringParameter(tool, "output_file")

        if (outputFilePath.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "output_file is a required parameter.")
        }

        val outputFile = File(outputFilePath)

        return try {
            val success = memoryRepository.exportMemoriesToOPML(outputFile)

            if (success) {
                val message = "Successfully exported knowledge graph to OPML: ${outputFile.absolutePath}"
                AppLogger.d(TAG, message)
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(message)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to export knowledge graph to OPML."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export graph to OPML", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to export graph: ${e.message}"
            )
        }
    }

    private suspend fun executeChainOfThoughtSearch(tool: AITool): ToolResult {
        val query = MemoryQueryUtils.getStringParameter(tool, "query")
        val maxSteps = MemoryQueryUtils.getIntParameter(tool, "max_steps", 3)

        if (query.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "query is a required parameter.")
        }

        if (maxSteps < 1 || maxSteps > MemoryQueryConfig.MAX_STEPS_FOR_CHAIN_OF_THOUGHT) {
            return MemoryQueryUtils.createErrorResult(tool.name, "max_steps must be between 1 and ${MemoryQueryConfig.MAX_STEPS_FOR_CHAIN_OF_THOUGHT}.")
        }

        return try {
            val cotResult = memoryRepository.chainOfThoughtSearch(
                originalQuery = query,
                maxSteps = maxSteps
            )

            if (cotResult.allRelevantMemories.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("No relevant memories found via Chain-of-Thought search.")
                )
            }

            val resultContent = buildString {
                appendLine("=== Chain-of-Thought Search Result ===")
                appendLine("Original Query: '${query}'")
                appendLine("Total Steps: ${cotResult.steps.size}")
                appendLine("Total Relevant Memories: ${cotResult.allRelevantMemories.size}")
                appendLine()

                cotResult.steps.forEach { step ->
                    appendLine("--- Step ${step.stepNumber} ---")
                    appendLine("Query: '${step.query}'")
                    appendLine("Reasoning: ${step.reasoning}")
                    if (step.foundMemories.isNotEmpty()) {
                        appendLine("Found Memories:")
                        step.foundMemories.take(3).forEach { memory ->
                            appendLine("- ${memory.title}")
                        }
                        if (step.foundMemories.size > 3) {
                            appendLine("  ... and ${step.foundMemories.size - 3} more")
                        }
                    }
                    appendLine()
                }

                appendLine("=== Final Relevant Memories ===")
                cotResult.allRelevantMemories.take(10).forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title}")
                }
                if (cotResult.allRelevantMemories.size > 10) {
                    appendLine("... and ${cotResult.allRelevantMemories.size - 10} more")
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultContent)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to perform Chain-of-Thought search", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to perform Chain-of-Thought search: ${e.message}"
            )
        }
    }


    private suspend fun executeSolidifyHighValueMemories(tool: AITool): ToolResult {
        val importanceThreshold = MemoryQueryUtils.getFloatParameter(tool, "importance_threshold", MemoryQueryConfig.DEFAULT_IMPORTANCE_THRESHOLD)

        if (importanceThreshold < 0.0f || importanceThreshold > 1.0f) {
            return MemoryQueryUtils.createErrorResult(tool.name, "Invalid importance_threshold. Must be between 0.0 and 1.0.")
        }

        return try {
            val solidifiedCount = memoryRepository.solidifyHighValueMemories(
                importanceThreshold = importanceThreshold
            )

            if (solidifiedCount == 0) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("No high-value memories found for solidification.")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("Successfully solidified ${solidifiedCount} high-value memories.")
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to solidify high-value memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to solidify high-value memories: ${e.message}"
            )
        }
    }

    private suspend fun executeExportEnvironmentMemoryToMarkdown(tool: AITool): ToolResult {
        val outputPath = MemoryQueryUtils.getStringParameter(tool, "output_path")

        if (outputPath.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "output_path is a required parameter.")
        }

        return try {
            val outputFile = java.io.File(outputPath)
            val success = memoryRepository.exportEnvironmentMemoryToMarkdown(outputFile)

            if (success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("Successfully exported environment memory to: ${outputPath}")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to export environment memory to markdown."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export environment memory to markdown", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to export environment memory: ${e.message}"
            )
        }
    }

    private suspend fun executeExportUserProfileToMarkdown(tool: AITool): ToolResult {
        val outputPath = MemoryQueryUtils.getStringParameter(tool, "output_path")

        if (outputPath.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "output_path is a required parameter.")
        }

        return try {
            val outputFile = java.io.File(outputPath)
            val success = memoryRepository.exportUserProfileToMarkdown(outputFile)

            if (success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("Successfully exported user profile to: ${outputPath}")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to export user profile to markdown."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export user profile to markdown", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to export user profile: ${e.message}"
            )
        }
    }

    private suspend fun executeHonzonUpdateProfile(tool: AITool): ToolResult {
        val userId = MemoryQueryUtils.getStringParameter(tool, "user_id")
        val dimension = MemoryQueryUtils.getStringParameter(tool, "dimension")
        val content = MemoryQueryUtils.getStringParameter(tool, "content")
        
        if (userId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "user_id是必需参数")
        }
        if (dimension.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "dimension是必需参数")
        }
        if (content.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "content是必需参数")
        }
        
        return try {
            val success = memoryRepository.updateHonzonProfile(userId, dimension, content)
            if (success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("成功更新用户画像？userId@${dimension}")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "更新用户画像失败"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update Honzon profile", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "更新用户画像失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeHonzonGetProfile(tool: AITool): ToolResult {
        val userId = MemoryQueryUtils.getStringParameter(tool, "user_id")
        
        if (userId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "user_id是必需参数")
        }
        
        return try {
            val profile = memoryRepository.getHonzonProfile(userId)
            val nonEmptyDimensions = profile.getNonEmptyDimensions()
            
            if (nonEmptyDimensions.isEmpty()) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("用户�?{userId尚无画像数据}")
                )
            } else {
                val profileStr = nonEmptyDimensions.entries.joinToString("\n") { 
                    "- ${it.key}??{it.value}"
                }
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("用户�?{userId画像}：\n${profileStr}")
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get Honzon profile", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取用户画像失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeHonzonGetDimensions(tool: AITool): ToolResult {
        return try {
            val dimensions = memoryRepository.getHonzonDimensions()
            val dimensionsStr = dimensions.joinToString("\n") { 
                "- ${it}"
            }
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("Honzon用户画像维度（共${dimensions.size}个）：\n${dimensionsStr}"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get Honzon dimensions", e)
            MemoryQueryUtils.createErrorResult(tool.name, "获取维度列表失败�?{e.message}")
        }
    }
    
    private suspend fun executeHonzonGenerateStrategy(tool: AITool): ToolResult {
        val userId = MemoryQueryUtils.getStringParameter(tool, "user_id")
        val taskType = MemoryQueryUtils.getStringParameter(tool, "task_type")
        
        if (userId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "user_id是必需参数")
        }
        if (taskType.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_type是必需参数")
        }
        
        return try {
            val profile = memoryRepository.getHonzonProfile(userId)
            val prompt = memoryRepository.generatePersonalizedStrategyPrompt(profile, taskType)
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData(prompt))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to generate personalized strategy", e)
            MemoryQueryUtils.createErrorResult(tool.name, "生成个性化策略失败�?{e.message}")
        }
    }
    
    private suspend fun executeSkillExtract(tool: AITool): ToolResult {
        val agentBehavior = MemoryQueryUtils.getStringParameter(tool, "agent_behavior")
        val taskType = MemoryQueryUtils.getStringParameter(tool, "task_type")
        val errorCases = MemoryQueryUtils.getStringParameter(tool, "error_cases")
        
        if (agentBehavior.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "agent_behavior是必需参数")
        }
        if (taskType.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_type是必需参数")
        }
        
        return try {
            val behaviorList = agentBehavior.split("\n").filter { it.isNotBlank() }
            val errorCasesList = errorCases?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            
            val skillPath = skillEvolutionManager.extractSkill(
                agentBehavior = behaviorList,
                taskType = taskType,
                errorCases = errorCasesList
            )
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("技能萃取成功，保存路径？skillPath"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract skill", e)
            MemoryQueryUtils.createErrorResult(tool.name, "技能萃取失败：${e.message}")
        }
    }
    
    private suspend fun executeSkillEvolve(tool: AITool): ToolResult {
        val skillId = MemoryQueryUtils.getStringParameter(tool, "skill_id")
        val newBehavior = MemoryQueryUtils.getStringParameter(tool, "new_behavior")
        val newErrorCases = MemoryQueryUtils.getStringParameter(tool, "new_error_cases")
        
        if (skillId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "skill_id是必需参数")
        }
        if (newBehavior.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "new_behavior是必需参数")
        }
        if (newErrorCases.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "new_error_cases是必需参数")
        }
        
        return try {
            val behaviorList = newBehavior.split("\n").filter { it.isNotBlank() }
            val errorCasesList = newErrorCases.split("\n").filter { it.isNotBlank() }
            
            val skillPath = skillEvolutionManager.evolveSkill(
                skillId = skillId,
                newBehavior = behaviorList,
                newErrorCases = errorCasesList
            )
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("技能迭代成功，保存路径？skillPath"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to evolve skill", e)
            MemoryQueryUtils.createErrorResult(tool.name, "技能迭代失败：${e.message}")
        }
    }
    
    private suspend fun executeSkillGetAll(tool: AITool): ToolResult {
        return try {
            val skills = skillEvolutionManager.getAllSkills()
            
            if (skills.isEmpty()) {
                return MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("暂无技能数据）)
            }
            
            val skillsStr = skills.joinToString("\n\n") { skill ->
                "技能ID??{skill.skillId}\n" +
                "任务类型�?{skill.taskType}\n" +
                "版本�?{skill.version}\n" +
                "步骤数：${skill.operationSteps.size}\n" +
                "适用场景数：${skill.applicableScenarios.size}\n" +
                "踩坑案例数：${skill.errorCases.size}"
            }
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("技能列表（??{skills.size}个）：\n${skillsStr}"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get all skills", e)
            MemoryQueryUtils.createErrorResult(tool.name, "获取技能列表失败：${e.message}")
        }
    }
    
    private suspend fun executeSkillGet(tool: AITool): ToolResult {
        val skillId = MemoryQueryUtils.getStringParameter(tool, "skill_id")
        
        if (skillId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "skill_id是必需参数")
        }
        
        return try {
            val skill = skillEvolutionManager.getSkill(skillId)
            
            if (skill == null) {
                return MemoryQueryUtils.createErrorResult(tool.name, "技能不存在")
            }
            
            val skillStr = """
技能ID??{skill.skillId}
任务类型�?{skill.taskType}
版本�?{skill.version}
更新时间�?{skill.updateTimestamp}

操作步骤�?{skill.operationSteps.joinToString("\n")}

适用场景�?{skill.applicableScenarios.joinToString("\n")}

踩坑案例�"{if (skill.errorCases.isNotEmpty()) skill.errorCases.joinToString("\n") else "??}
            """.trimIndent()
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData(skillStr))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get skill", e)
            MemoryQueryUtils.createErrorResult(tool.name, "获取技能失败：${e.message}")
        }
    }
    
    private suspend fun executeSkillDelete(tool: AITool): ToolResult {
        val skillId = MemoryQueryUtils.getStringParameter(tool, "skill_id")
        
        if (skillId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "skill_id是必需参数")
        }
        
        return try {
            val success = skillEvolutionManager.deleteSkill(skillId)
            
            if (success) {
                MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("技能删除成功）)
            } else {
                MemoryQueryUtils.createErrorResult(tool.name, "技能不存在")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete skill", e)
            MemoryQueryUtils.createErrorResult(tool.name, "删除技能失败：${e.message}")
        }
    }
    
    private suspend fun executeApexAgentEvolutionLoop(tool: AITool): ToolResult {
        val agentBehavior = MemoryQueryUtils.getStringParameter(tool, "agent_behavior")
        val taskType = MemoryQueryUtils.getStringParameter(tool, "task_type")
        val taskGoal = MemoryQueryUtils.getStringParameter(tool, "task_goal")
        val userId = MemoryQueryUtils.getStringParameter(tool, "user_id")
        val errorCases = MemoryQueryUtils.getStringParameter(tool, "error_cases")
        
        if (agentBehavior.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "agent_behavior是必需参数")
        }
        if (taskType.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_type是必需参数")
        }
        if (taskGoal.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_goal是必需参数")
        }
        if (userId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "user_id是必需参数")
        }
        
        return try {
            val behaviorList = agentBehavior.split("\n").filter { it.isNotBlank() }
            val errorCasesList = errorCases?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            
            val result = evolutionEngine.completeEvolutionLoop(
                agentBehavior = behaviorList,
                taskType = taskType,
                taskGoal = taskGoal,
                userId = userId,
                errorCases = errorCasesList
            )
            
            val resultStr = """
=== logistra 多智能体自优化结�?==
优化后策略：${result.optimizedStrategy.take(200)}...
沉淀技能路径：${result.skillPath}
效果评分�?{result.effectScore}
迭代次数�?{result.iterationCount}
是否收敛�?{result.convergence}
            """.trimIndent()
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData(resultStr))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to execute evolution loop", e)
            MemoryQueryUtils.createErrorResult(tool.name, "执行进化闭环失败�?{e.message}")
        }
    }
    
    private suspend fun executeApexAgentEvaluateEffect(tool: AITool): ToolResult {
        val agentBehavior = MemoryQueryUtils.getStringParameter(tool, "agent_behavior")
        val taskGoal = MemoryQueryUtils.getStringParameter(tool, "task_goal")
        
        if (agentBehavior.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "agent_behavior是必需参数")
        }
        if (taskGoal.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_goal是必需参数")
        }
        
        return try {
            val behaviorList = agentBehavior.split("\n").filter { it.isNotBlank() }
            val score = evolutionEngine.evaluateEffect(behaviorList, taskGoal)
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("执行效果评分？score（满�?分）"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to evaluate effect", e)
            MemoryQueryUtils.createErrorResult(tool.name, "评估执行效果失败�?{e.message}")
        }
    }
    
    private suspend fun executeApexAgentOptimizeStrategy(tool: AITool): ToolResult {
        val taskType = MemoryQueryUtils.getStringParameter(tool, "task_type")
        val userId = MemoryQueryUtils.getStringParameter(tool, "user_id")
        val currentStrategy = MemoryQueryUtils.getStringParameter(tool, "current_strategy")
        val effectScoreStr = MemoryQueryUtils.getStringParameter(tool, "effect_score")
        
        if (taskType.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "task_type是必需参数")
        }
        if (userId.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "user_id是必需参数")
        }
        if (currentStrategy.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "current_strategy是必需参数")
        }
        if (effectScoreStr.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "effect_score是必需参数")
        }
        
        return try {
            val effectScore = effectScoreStr.toFloat()
            val optimizedStrategy = evolutionEngine.optimizeStrategy(
                taskType = taskType,
                userId = userId,
                currentStrategy = currentStrategy,
                effectScore = effectScore
            )
            
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("优化后策略：\n${optimizedStrategy}"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to optimize strategy", e)
            MemoryQueryUtils.createErrorResult(tool.name, "优化策略失败�?{e.message}")
        }
    }
    
    private suspend fun executeApexAgentGetIterationCount(tool: AITool): ToolResult {
        return try {
            val count = evolutionEngine.getIterationCount()
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("当前迭代次数？count"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get iteration count", e)
            MemoryQueryUtils.createErrorResult(tool.name, "获取迭代次数失败�?{e.message}")
        }
    }
    
    private suspend fun executeApexAgentResetIterationCount(tool: AITool): ToolResult {
        return try {
            evolutionEngine.resetIterationCount()
            MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("迭代计数器已重置"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to reset iteration count", e)
            MemoryQueryUtils.createErrorResult(tool.name, "重置迭代计数器失败：${e.message}")
        }
    }
    
    private suspend fun executeMemoryCreateNormal(tool: AITool): ToolResult {
        val title = MemoryQueryUtils.getStringParameter(tool, "title")
        val content = MemoryQueryUtils.getStringParameter(tool, "content")
        val contentType = MemoryQueryUtils.getStringParameter(tool, "content_type") ?: "text/plain"
        val source = MemoryQueryUtils.getStringParameter(tool, "source") ?: "user_input"
        val folderPath = MemoryQueryUtils.getStringParameter(tool, "folder_path") ?: ""
        
        if (title.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "title是必需参数")
        }
        if (content.isNullOrBlank()) {
            return MemoryQueryUtils.createErrorResult(tool.name, "content是必需参数")
        }
        
        return try {
            val memory = memoryRepository.createNormalMemory(
                title = title,
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath
            )
            
            if (memory != null) {
                MemoryQueryUtils.createSuccessResult(tool.name, StringResultData("普通对话记忆创建成功，ID??{memory.id}"))
            } else {
                MemoryQueryUtils.createErrorResult(tool.name, "创建普通对话记忆失败）
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create normal memory", e)
            MemoryQueryUtils.createErrorResult(tool.name, "创建普通对话记忆失败：${e.message}")
        }
    }
    
    private suspend fun executeMemoryCreateAgent(tool: AITool): ToolResult {
        val agentId = tool.parameters.find { it.name == "agent_id" }?.value
        val title = tool.parameters.find { it.name == "title" }?.value
        val content = tool.parameters.find { it.name == "content" }?.value
        val contentType = tool.parameters.find { it.name == "content_type" }?.value ?: "text/plain"
        val source = tool.parameters.find { it.name == "source" }?.value ?: "agent_input"
        val folderPath = tool.parameters.find { it.name == "folder_path" }?.value ?: ""
        
        if (agentId.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "agent_id是必需参数"
            )
        }
        if (title.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "title是必需参数"
            )
        }
        if (content.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "content是必需参数"
            )
        }
        
        return try {
            val memory = memoryRepository.createAgentMemory(
                agentId = agentId,
                title = title,
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath
            )
            
            if (memory != null) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("多Agent专属记忆创建成功，ID??{memory.id}")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "创建多Agent专属记忆失败"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create agent memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "创建多Agent专属记忆失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeMemoryCreatePublic(tool: AITool): ToolResult {
        val title = tool.parameters.find { it.name == "title" }?.value
        val content = tool.parameters.find { it.name == "content" }?.value
        val contentType = tool.parameters.find { it.name == "content_type" }?.value ?: "text/plain"
        val source = tool.parameters.find { it.name == "source" }?.value ?: "system"
        val folderPath = tool.parameters.find { it.name == "folder_path" }?.value ?: ""
        
        if (title.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "title是必需参数"
            )
        }
        if (content.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "content是必需参数"
            )
        }
        
        return try {
            val memory = memoryRepository.createPublicMemory(
                title = title,
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath
            )
            
            if (memory != null) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("公共记忆创建成功，ID??{memory.id}")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "创建公共记忆失败"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create public memory", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "创建公共记忆失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeMemoryGetNormal(tool: AITool): ToolResult {
        val limitStr = tool.parameters.find { it.name == "limit" }?.value ?: "20"
        val limit = limitStr.toIntOrNull() ?: 20
        
        return try {
            val memories = memoryRepository.getNormalMemories(limit)
            val resultStr = buildString {
                appendLine("普通对话记忆列表：")
                memories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title} (${memory.createdAt})")
                    appendLine("   内容�?{memory.content.take(100)}...")
                }
                appendLine("总计�?{memories.size}条记�"
            }
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultStr)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get normal memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取普通对话记忆失败：${e.message}"
            )
        }
    }
    
    private suspend fun executeMemoryGetAgent(tool: AITool): ToolResult {
        val agentId = tool.parameters.find { it.name == "agent_id" }?.value
        val limitStr = tool.parameters.find { it.name == "limit" }?.value ?: "20"
        val limit = limitStr.toIntOrNull() ?: 20
        
        if (agentId.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "agent_id是必需参数"
            )
        }
        
        return try {
            val memories = memoryRepository.getAgentMemories(agentId, limit)
            val resultStr = buildString {
                appendLine("Agent ${agentId} 专属记忆列表�"
                memories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title} (${memory.createdAt})")
                    appendLine("   内容�?{memory.content.take(100)}...")
                }
                appendLine("总计�?{memories.size}条记�"
            }
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultStr)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get agent memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取多Agent专属记忆失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeMemoryGetPublic(tool: AITool): ToolResult {
        val limitStr = tool.parameters.find { it.name == "limit" }?.value ?: "20"
        val limit = limitStr.toIntOrNull() ?: 20
        
        return try {
            val memories = memoryRepository.getPublicMemories(limit)
            val resultStr = buildString {
                appendLine("公共记忆列表�"
                memories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title} (${memory.createdAt})")
                    appendLine("   内容�?{memory.content.take(100)}...")
                }
                appendLine("总计�?{memories.size}条记�"
            }
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultStr)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get public memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取公共记忆失败�?{e.message}"
            )
        }
    }
    
    private suspend fun executeMemorySearchByType(tool: AITool): ToolResult {
        val query = tool.parameters.find { it.name == "query" }?.value
        val memoryType = tool.parameters.find { it.name == "memory_type" }?.value
        val agentId = tool.parameters.find { it.name == "agent_id" }?.value
        val limitStr = tool.parameters.find { it.name == "limit" }?.value ?: "20"
        val limit = limitStr.toIntOrNull() ?: 20
        
        if (query.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "query是必需参数"
            )
        }
        if (memoryType.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "memory_type是必需参数"
            )
        }
        
        return try {
            val memories = memoryRepository.searchMemoriesByType(
                query = query,
                memoryType = memoryType,
                agentId = agentId,
                limit = limit
            )
            val resultStr = buildString {
                appendLine("${memoryType}）：")
                memories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.title} (${memory.createdAt})")
                    appendLine("   内容�?{memory.content.take(100)}...")
                }
                appendLine("总计�?{memories.size}条记�"
            }
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(resultStr)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to search memories by type", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "按类型搜索记忆失败：${e.message}"
            )
        }
    }
    
    private suspend fun executeMemoryDeleteAgent(tool: AITool): ToolResult {
        val agentId = tool.parameters.find { it.name == "agent_id" }?.value
        
        if (agentId.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "agent_id是必需参数"
            )
        }
        
        return try {
            val deletedCount = memoryRepository.deleteAgentMemories(agentId)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData("成功删除Agent ${agentId} ??${deletedCount} 条专属记忆）
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete agent memories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "删除多Agent专属记忆失败�?{e.message}"
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        if (tool.name == "query_memory") {
            val query = tool.parameters.find { it.name == "query" }?.value
            if (query.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: query")
            }
        }
        if (tool.name == "merge_duplicate_memories") {
            val sourceTitles = tool.parameters.find { it.name == "source_titles" }?.value
            if (sourceTitles.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: source_titles")
            }
            val targetTitle = tool.parameters.find { it.name == "target_title" }?.value
            if (targetTitle.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: target_title")
            }
            val targetContent = tool.parameters.find { it.name == "target_content" }?.value
            if (targetContent.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: target_content")
            }
        }
        if (tool.name == "honzon_update_profile") {
            val userId = tool.parameters.find { it.name == "user_id" }?.value
            if (userId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: user_id")
            }
            val dimension = tool.parameters.find { it.name == "dimension" }?.value
            if (dimension.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: dimension")
            }
            val content = tool.parameters.find { it.name == "content" }?.value
            if (content.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: content")
            }
        }
        if (tool.name == "honzon_get_profile") {
            val userId = tool.parameters.find { it.name == "user_id" }?.value
            if (userId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: user_id")
            }
        }
        if (tool.name == "honzon_generate_strategy") {
            val userId = tool.parameters.find { it.name == "user_id" }?.value
            if (userId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: user_id")
            }
            val taskType = tool.parameters.find { it.name == "task_type" }?.value
            if (taskType.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_type")
            }
        }
        if (tool.name == "skill_extract") {
            val agentBehavior = tool.parameters.find { it.name == "agent_behavior" }?.value
            if (agentBehavior.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: agent_behavior")
            }
            val taskType = tool.parameters.find { it.name == "task_type" }?.value
            if (taskType.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_type")
            }
        }
        if (tool.name == "skill_evolve") {
            val skillId = tool.parameters.find { it.name == "skill_id" }?.value
            if (skillId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: skill_id")
            }
            val newBehavior = tool.parameters.find { it.name == "new_behavior" }?.value
            if (newBehavior.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: new_behavior")
            }
            val newErrorCases = tool.parameters.find { it.name == "new_error_cases" }?.value
            if (newErrorCases.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: new_error_cases")
            }
        }
        if (tool.name == "skill_get") {
            val skillId = tool.parameters.find { it.name == "skill_id" }?.value
            if (skillId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: skill_id")
            }
        }
        if (tool.name == "skill_delete") {
            val skillId = tool.parameters.find { it.name == "skill_id" }?.value
            if (skillId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: skill_id")
            }
        }
        if (tool.name == "apex-agent_evolution_loop") {
            val agentBehavior = tool.parameters.find { it.name == "agent_behavior" }?.value
            if (agentBehavior.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: agent_behavior")
            }
            val taskType = tool.parameters.find { it.name == "task_type" }?.value
            if (taskType.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_type")
            }
            val taskGoal = tool.parameters.find { it.name == "task_goal" }?.value
            if (taskGoal.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_goal")
            }
            val userId = tool.parameters.find { it.name == "user_id" }?.value
            if (userId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: user_id")
            }
        }
        if (tool.name == "apex-agent_evaluate_effect") {
            val agentBehavior = tool.parameters.find { it.name == "agent_behavior" }?.value
            if (agentBehavior.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: agent_behavior")
            }
            val taskGoal = tool.parameters.find { it.name == "task_goal" }?.value
            if (taskGoal.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_goal")
            }
        }
        if (tool.name == "apex-agent_optimize_strategy") {
            val taskType = tool.parameters.find { it.name == "task_type" }?.value
            if (taskType.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: task_type")
            }
            val userId = tool.parameters.find { it.name == "user_id" }?.value
            if (userId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: user_id")
            }
            val currentStrategy = tool.parameters.find { it.name == "current_strategy" }?.value
            if (currentStrategy.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: current_strategy")
            }
            val effectScore = tool.parameters.find { it.name == "effect_score" }?.value
            if (effectScore.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: effect_score")
            }
        }
        if (tool.name == "memory_create_normal" || tool.name == "memory_create_public") {
            val title = tool.parameters.find { it.name == "title" }?.value
            if (title.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: title")
            }
            val content = tool.parameters.find { it.name == "content" }?.value
            if (content.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: content")
            }
        }
        if (tool.name == "memory_create_agent") {
            val agentId = tool.parameters.find { it.name == "agent_id" }?.value
            if (agentId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: agent_id")
            }
            val title = tool.parameters.find { it.name == "title" }?.value
            if (title.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: title")
            }
            val content = tool.parameters.find { it.name == "content" }?.value
            if (content.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: content")
            }
        }
        if (tool.name == "memory_get_agent" || tool.name == "memory_delete_agent") {
            val agentId = tool.parameters.find { it.name == "agent_id" }?.value
            if (agentId.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: agent_id")
            }
        }
        if (tool.name == "memory_search_by_type") {
            val query = tool.parameters.find { it.name == "query" }?.value
            if (query.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: query")
            }
            val memoryType = tool.parameters.find { it.name == "memory_type" }?.value
            if (memoryType.isNullOrBlank()) {
                return ToolValidationResult(valid = false, errorMessage = "Missing or empty required parameter: memory_type")
            }
        }
        return ToolValidationResult(valid = true)
    }
}
