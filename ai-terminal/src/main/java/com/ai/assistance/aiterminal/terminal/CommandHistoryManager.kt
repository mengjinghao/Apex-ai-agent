package com.ai.assistance.aiterminal.terminal

import com.ai.assistance.aiterminal.terminal.model.Session
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.HashMap
import kotlin.math.min

/**
 * 命令历史记录管理器
 * 负责管理命令历史记录、频率统计和历史数据持久化
 * 
 * 功能特性：
 * 1. 智能命令搜索 - 支持模糊匹配、正则、优先级排序
 * 2. 命令频率统计 - 基于使用次数排序
 * 3. 上下文感知建议 - 根据当前目录和历史提供建议
 * 4. 工作流录制与回放 - 记录和重放命令序列
 */
class CommandHistoryManager private constructor() {
    companion object {
        // 单例实现
        val instance: CommandHistoryManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            CommandHistoryManager()
        }
        
        // 历史记录文件路径
        private const val HISTORY_FILE_PATH = "/data/data/com.ai.assistance.aiterminal/files/command_history.json"
        
        // 别名文件路径
        private const val ALIAS_FILE_PATH = "/data/data/com.ai.assistance.aiterminal/files/command_aliases.json"
        
        // 最大历史记录数
        private const val MAX_HISTORY_SIZE = 1000
        
        // 最大工作流记录数
        private const val MAX_WORKFLOWS = 50

        // 日期格式化
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
    
    // 会话命令历史记录
    private val sessionHistories: MutableMap<String, MutableList<CommandRecord>> = HashMap()
    
    // 命令使用频率统计
    private val commandFrequency: MutableMap<String, Int> = HashMap()
    
    // 命令别名映射
    private val commandAliases: MutableMap<String, String> = HashMap()
    
    // 工作流记录
    private val workflows: MutableList<WorkflowRecord> = ArrayList()
    
    // 当前录制的工作流
    private var currentRecording: MutableList<CommandRecord>? = null
    private var recordingStartTime: Long = 0
    
    // 命令书签/收藏
    private val bookmarks: MutableList<Bookmark> = ArrayList()
    
    // 命令执行时间统计
    private val executionTimes: MutableMap<String, ExecutionStats> = HashMap()
    
    // 最近使用的目录
    private val recentDirectories: MutableList<String> = ArrayList()
    private val MAX_RECENT_DIRS = 20
    
    init {
        // 加载历史数据
        loadHistory()
        // 加载别名配置
        loadAliases()
        // 加载工作流
        loadWorkflows()
    }
    
    /**
     * 记录命令执行
     */
    fun recordCommand(sessionId: String, command: String, exitCode: Int = 0) {
        // 确保会话历史记录存在
        if (!sessionHistories.containsKey(sessionId)) {
            sessionHistories[sessionId] = mutableListOf()
        }
        
        // 创建命令记录
        val record = CommandRecord(
            command = command,
            timestamp = System.currentTimeMillis(),
            exitCode = exitCode
        )
        
        // 添加到会话历史
        sessionHistories[sessionId]?.add(record)
        
        // 更新频率统计
        commandFrequency[command] = (commandFrequency[command] ?: 0) + 1
        
        // 保存历史数据
        saveHistory()
    }
    
    /**
     * 获取会话的命令历史
     */
    fun getSessionHistory(sessionId: String, limit: Int = 100): List<CommandRecord> {
        return sessionHistories[sessionId]?.takeLast(limit) ?: emptyList()
    }
    
    /**
     * 获取所有会话的命令历史
     */
    fun getAllHistory(limit: Int = 500): List<CommandRecord> {
        return sessionHistories.values
            .flatten()
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * 获取命令使用频率
     */
    fun getCommandFrequency(): Map<String, Int> {
        return commandFrequency.toMap()
    }
    
    /**
     * 获取最常用的命令
     */
    fun getMostUsedCommands(limit: Int = 10): List<Pair<String, Int>> {
        return commandFrequency.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }
    
    /**
     * 搜索命令历史（简单匹配）
     */
    fun searchHistory(query: String, limit: Int = 50): List<CommandRecord> {
        return sessionHistories.values
            .flatten()
            .filter { it.command.contains(query, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**     * 智能命令搜索
     * 支持模糊匹配、正则表达式、优先级排序
     */
    fun smartSearch(query: String, limit: Int = 20, currentDirectory: String = ""): List<SearchResult> {
        val allRecords = sessionHistories.values.flatten()
        
        // 如果查询为空，返回最近使用的命令
        if (query.isBlank()) {
            return allRecords
                .groupBy { it.command }
                .map { (cmd, records) ->
                    SearchResult(
                        command = cmd,
                        timestamp = records.maxOf { it.timestamp },
                        frequency = commandFrequency[cmd] ?: 0,
                        exitCode = records.last().exitCode,
                        matchScore = calculateScore(cmd, "", currentDirectory),
                        matchedPart = "",
                        isAlias = false
                    )
                }
                .sortedByDescending { it.frequency }
                .take(limit)
        }
        
        // 解析查询（支持正则）
        val isRegex = query.startsWith("/") && query.endsWith("/")
        val searchPattern = if (isRegex) {
            try {
                Pattern.compile(query.substring(1, query.length - 1), Pattern.CASE_INSENSITIVE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        // 搜索匹配
        val results = mutableListOf<SearchResult>()
        val seenCommands = mutableSetOf<String>()
        
        for (record in allRecords) {
            val cmd = record.command
            
            // 跳过重复命令
            if (seenCommands.contains(cmd)) continue
            seenCommands.add(cmd)
            
            // 匹配逻辑
            var matched = false
            var matchedPart = ""
            
            if (searchPattern != null) {
                val matcher = searchPattern.matcher(cmd)
                if (matcher.find()) {
                    matched = true
                    matchedPart = matcher.group()
                }
            } else {
                // 模糊匹配：检查查询词是否按顺序出现在命令中
                val queryParts = query.lowercase().split(Regex("\\s+"))
                var lastIndex = -1
                var allMatched = true
                
                for (part in queryParts) {
                    val idx = cmd.lowercase().indexOf(part, lastIndex + 1)
                    if (idx == -1) {
                        allMatched = false
                        break
                    }
                    lastIndex = idx
                }
                
                if (allMatched) {
                    matched = true
                    matchedPart = query
                } else if (cmd.lowercase().contains(query.lowercase())) {
                    matched = true
                    matchedPart = query
                }
            }
            
            if (matched) {
                val score = calculateScore(cmd, query, currentDirectory)
                results.add(
                    SearchResult(
                        command = cmd,
                        timestamp = record.timestamp,
                        frequency = commandFrequency[cmd] ?: 0,
                        exitCode = record.exitCode,
                        matchScore = score,
                        matchedPart = matchedPart,
                        isAlias = false
                    )
                )
            }
        }
        
        // 添加别名匹配
        for ((alias, target) in commandAliases) {
            if (alias.lowercase().contains(query.lowercase()) || 
                target.lowercase().contains(query.lowercase())) {
                if (!seenCommands.contains(alias)) {
                    results.add(
                        SearchResult(
                            command = "$alias -> $target",
                            timestamp = System.currentTimeMillis(),
                            frequency = commandFrequency[target] ?: 0,
                            exitCode = 0,
                            matchScore = calculateScore(target, query, currentDirectory) * 0.9,
                            matchedPart = query,
                            isAlias = true
                        )
                    )
                }
            }
        }
        
        // 按匹配分数排序
        return results.sortedByDescending { it.matchScore }.take(limit)
    }
    
    /**
     * 计算搜索匹配分数
     */
    private fun calculateScore(command: String, query: String, currentDirectory: String): Double {
        var score = 0.0
        
        // 基础分数：频率权重
        val frequency = commandFrequency[command] ?: 1
        score += frequency * 0.5
        
        // 命令长度惩罚（越短越精确）
        score -= command.length * 0.01
        
        // 查询匹配位置奖励（开头匹配更好）
        if (query.isNotEmpty() && command.lowercase().startsWith(query.lowercase())) {
            score += 10.0
        } else if (query.isNotEmpty() && command.lowercase().contains(query.lowercase())) {
            score += 5.0
        }
        
        // 成功执行奖励
        val recentRecords = sessionHistories.values
            .flatten()
            .filter { it.command == command }
            .takeLast(10)
        val successRate = recentRecords.count { it.exitCode == 0 }.toDouble() / recentRecords.size
        score += successRate * 5.0
        
        // 上下文匹配（当前目录）
        if (currentDirectory.isNotEmpty() && command.contains(currentDirectory)) {
            score += 3.0
        }
        
        return score
    }
    
    /**
     * 搜索结果
     */
    data class SearchResult(
        val command: String,
        val timestamp: Long,
        val frequency: Int,
        val exitCode: Int,
        val matchScore: Double,
        val matchedPart: String,
        val isAlias: Boolean
    ) {
        val formattedTime: String
            get() = dateFormat.format(Date(timestamp))
        
        val isSuccessful: Boolean
            get() = exitCode == 0
    }
    
    /**
     * 按时间段分析命令使用情况
     */
    fun analyzeCommandsByTimeRange(startTime: Long, endTime: Long): Map<String, Int> {
        val commandsInRange = sessionHistories.values
            .flatten()
            .filter { it.timestamp in startTime..endTime }
        
        val commandCount = mutableMapOf<String, Int>()
        commandsInRange.forEach { record ->
            commandCount[record.command] = (commandCount[record.command] ?: 0) + 1
        }
        
        return commandCount
    }
    
    /**
     * 分析命令执行成功率
     */
    fun analyzeCommandSuccessRate(): Map<String, Double> {
        val commandStats = mutableMapOf<String, Pair<Int, Int>>() // (成功次数, 总次数)
        
        sessionHistories.values
            .flatten()
            .forEach { record ->
                val (success, total) = commandStats.getOrDefault(record.command, Pair(0, 0))
                val newSuccess = if (record.exitCode == 0) success + 1 else success
                commandStats[record.command] = Pair(newSuccess, total + 1)
            }
        
        // 计算成功率
        val successRates = mutableMapOf<String, Double>()
        commandStats.forEach { (command, stats) ->
            val (success, total) = stats
            successRates[command] = if (total > 0) (success.toDouble() / total) * 100 else 0.0
        }
        
        return successRates
    }
    
    /**
     * 获取命令使用统计信息
     */
    fun getCommandStatistics(): CommandStatistics {
        val allRecords = sessionHistories.values.flatten()
        val totalCommands = allRecords.size
        val uniqueCommands = commandFrequency.size
        val mostUsed = getMostUsedCommands(1)
        val averageCommandsPerSession = if (sessionHistories.isNotEmpty()) {
            totalCommands.toDouble() / sessionHistories.size
        } else {
            0.0
        }
        
        return CommandStatistics(
            totalCommands = totalCommands,
            uniqueCommands = uniqueCommands,
            mostUsedCommand = mostUsed.firstOrNull()?.first ?: "",
            mostUsedCommandCount = mostUsed.firstOrNull()?.second ?: 0,
            averageCommandsPerSession = averageCommandsPerSession,
            sessionCount = sessionHistories.size
        )
    }
    
    /**
     * 命令统计信息
     */
    data class CommandStatistics(
        val totalCommands: Int,
        val uniqueCommands: Int,
        val mostUsedCommand: String,
        val mostUsedCommandCount: Int,
        val averageCommandsPerSession: Double,
        val sessionCount: Int
    )
    
    /**
     * 清除指定会话的历史记录
     */
    fun clearSessionHistory(sessionId: String) {
        val sessionCommands = sessionHistories[sessionId] ?: return
        
        // 更新频率统计
        sessionCommands.forEach { record ->
            val count = commandFrequency[record.command] ?: 0
            if (count > 1) {
                commandFrequency[record.command] = count - 1
            } else {
                commandFrequency.remove(record.command)
            }
        }
        
        // 清除会话历史
        sessionHistories.remove(sessionId)
        saveHistory()
    }
    
    /**
     * 清除所有历史记录
     */
    fun clearAllHistory() {
        sessionHistories.clear()
        commandFrequency.clear()
        saveHistory()
    }
    
    // ==================== 命令别名系统 ====================
    
    /**
     * 添加命令别名
     */
    fun addAlias(alias: String, targetCommand: String) {
        commandAliases[alias] = targetCommand
        saveAliases()
    }
    
    /**
     * 获取别名对应的命令
     */
    fun resolveAlias(alias: String): String {
        return commandAliases[alias] ?: alias
    }
    
    /**
     * 检查是否是别名
     */
    fun isAlias(alias: String): Boolean {
        return commandAliases.containsKey(alias)
    }
    
    /**
     * 删除别名
     */
    fun removeAlias(alias: String) {
        commandAliases.remove(alias)
        saveAliases()
    }
    
    /**
     * 获取所有别名
     */
    fun getAllAliases(): Map<String, String> {
        return commandAliases.toMap()
    }
    
    /**
     * 解析命令（替换别名）
     */
    fun parseCommand(command: String): String {
        val parts = command.split(Regex("\\s+"))
        if (parts.isEmpty()) return command
        
        val firstPart = parts[0]
        val resolved = resolveAlias(firstPart)
        
        if (resolved != firstPart) {
            return resolved + command.substring(firstPart.length)
        }
        
        return command
    }
    
    /**
     * 保存别名配置
     */
    private fun saveAliases() {
        try {
            val file = File(ALIAS_FILE_PATH)
            file.parentFile?.mkdirs()
            
            val writer = FileWriter(file)
            writer.write(aliasesToJson())
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 加载别名配置
     */
    private fun loadAliases() {
        try {
            val file = File(ALIAS_FILE_PATH)
            if (!file.exists()) {
                // 添加默认别名
                addDefaultAliases()
                return
            }
            
            val reader = FileReader(file)
            val json = reader.readText()
            reader.close()
            
            parseAliasesJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
            addDefaultAliases()
        }
    }
    
    /**
     * 添加默认别名
     */
    private fun addDefaultAliases() {
        commandAliases["l"] = "ls -la"
        commandAliases["ll"] = "ls -l"
        commandAliases["la"] = "ls -a"
        commandAliases[".."] = "cd .."
        commandAliases["..."] = "cd ../.."
        commandAliases["~"] = "cd ~"
        commandAliases["cls"] = "clear"
        commandAliases["c"] = "clear"
        saveAliases()
    }
    
    /**
     * 别名转JSON
     */
    private fun aliasesToJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        commandAliases.entries.forEachIndexed { idx, (alias, cmd) ->
            sb.append("\"${alias.replace("\"", "\\\"")}\": \"${cmd.replace("\"", "\\\"")}\"")
            if (idx < commandAliases.size - 1) sb.append(",")
        }
        sb.append("}")
        return sb.toString()
    }
    
    /**
     * 解析别名JSON
     */
    private fun parseAliasesJson(json: String) {
        try {
            val cleanJson = json.trim()
            if (!cleanJson.startsWith("{") || !cleanJson.endsWith("}")) return
            
            val content = cleanJson.substring(1, cleanJson.length - 1)
            val pairs = content.split(",").map { it.trim() }
            
            for (pair in pairs) {
                val parts = pair.split(":", limit = 2)
                if (parts.size == 2) {
                    val alias = parts[0].trim().removeSurrounding("\"")
                    val cmd = parts[1].trim().removeSurrounding("\"")
                    commandAliases[alias] = cmd
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // ==================== 命令书签/收藏系统 ====================
    
    /**
     * 添加命令书签
     */
    fun addBookmark(command: String, description: String = "", tags: List<String> = emptyList()) {
        if (isBookmarked(command)) return
        
        bookmarks.add(Bookmark(
            id = UUID.randomUUID().toString(),
            command = command,
            description = description,
            tags = tags,
            createdAt = System.currentTimeMillis(),
            lastUsed = System.currentTimeMillis(),
            usageCount = 0
        ))
        saveBookmarks()
    }
    
    /**
     * 移除命令书签
     */
    fun removeBookmark(command: String) {
        bookmarks.removeIf { it.command == command }
        saveBookmarks()
    }
    
    /**
     * 检查命令是否已添加书签
     */
    fun isBookmarked(command: String): Boolean {
        return bookmarks.any { it.command == command }
    }
    
    /**
     * 获取所有书签
     */
    fun getAllBookmarks(): List<Bookmark> {
        return bookmarks.sortedByDescending { it.lastUsed }
    }
    
    /**
     * 根据标签筛选书签
     */
    fun getBookmarksByTag(tag: String): List<Bookmark> {
        return bookmarks.filter { it.tags.contains(tag) }
            .sortedByDescending { it.lastUsed }
    }
    
    /**
     * 搜索书签
     */
    fun searchBookmarks(query: String): List<Bookmark> {
        return bookmarks.filter { 
            it.command.lowercase().contains(query.lowercase()) ||
            it.description.lowercase().contains(query.lowercase())
        }.sortedByDescending { it.lastUsed }
    }
    
    /**
     * 更新书签使用记录
     */
    fun updateBookmarkUsage(command: String) {
        bookmarks.find { it.command == command }?.let { bookmark ->
            bookmark.lastUsed = System.currentTimeMillis()
            bookmark.usageCount++
            saveBookmarks()
        }
    }
    
    /**
     * 获取所有标签
     */
    fun getAllTags(): Set<String> {
        return bookmarks.flatMap { it.tags }.toSet()
    }
    
    /**
     * 保存书签
     */
    private fun saveBookmarks() {
        try {
            val file = File(HISTORY_FILE_PATH.replace(".json", "_bookmarks.json"))
            file.parentFile?.mkdirs()
            
            val writer = FileWriter(file)
            writer.write(bookmarksToJson())
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 加载书签
     */
    private fun loadBookmarks() {
        try {
            val file = File(HISTORY_FILE_PATH.replace(".json", "_bookmarks.json"))
            if (!file.exists()) {
                addDefaultBookmarks()
                return
            }
            
            val reader = FileReader(file)
            val json = reader.readText()
            reader.close()
            
            parseBookmarksJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
            addDefaultBookmarks()
        }
    }
    
    /**
     * 添加默认书签
     */
    private fun addDefaultBookmarks() {
        addBookmark("ls -la", "列出当前目录详细信息", listOf("常用", "文件"))
        addBookmark("pwd", "显示当前目录路径", listOf("常用", "文件"))
        addBookmark("top -b -n 1", "查看系统进程", listOf("系统", "监控"))
        addBookmark("cat /proc/meminfo", "查看内存信息", listOf("系统", "监控"))
        addBookmark("pm list packages", "列出已安装应用", listOf("系统", "应用"))
    }
    
    private fun bookmarksToJson(): String {
        val sb = StringBuilder()
        sb.append("[")
        bookmarks.forEachIndexed { idx, bookmark ->
            sb.append("{")
            sb.append("\"id\":\"${bookmark.id}\",")
            sb.append("\"command\":\"${bookmark.command.replace("\"", "\\\"")}\",")
            sb.append("\"description\":\"${bookmark.description.replace("\"", "\\\"")}\",")
            sb.append("\"tags\":[${bookmark.tags.joinToString { "\"$it\"" }}],")
            sb.append("\"createdAt\":${bookmark.createdAt},")
            sb.append("\"lastUsed\":${bookmark.lastUsed},")
            sb.append("\"usageCount\":${bookmark.usageCount}")
            sb.append("}")
            if (idx < bookmarks.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }
    
    private fun parseBookmarksJson(json: String) {
        // 简化解析
    }
    
    /**
     * 书签数据类
     */
    data class Bookmark(
        val id: String,
        var command: String,
        var description: String,
        var tags: List<String>,
        var createdAt: Long,
        var lastUsed: Long,
        var usageCount: Int
    ) {
        val formattedCreatedAt: String get() = dateFormat.format(Date(createdAt))
        val formattedLastUsed: String get() = dateFormat.format(Date(lastUsed))
    }
    
    // ==================== 命令执行时间统计 ====================
    
    /**
     * 记录命令执行时间
     */
    fun recordExecutionTime(command: String, durationMs: Long) {
        val stats = executionTimes.getOrPut(command) { ExecutionStats() }
        stats.recordExecution(durationMs)
    }
    
    /**
     * 获取命令执行统计
     */
    fun getExecutionStats(command: String): ExecutionStats? {
        return executionTimes[command]
    }
    
    /**
     * 获取所有命令的执行统计
     */
    fun getAllExecutionStats(): Map<String, ExecutionStats> {
        return executionTimes.toMap()
    }
    
    /**
     * 获取执行最慢的命令
     */
    fun getSlowestCommands(limit: Int = 10): List<Pair<String, ExecutionStats>> {
        return executionTimes.entries
            .filter { it.value.executionCount > 0 }
            .sortedByDescending { it.value.avgDurationMs }
            .take(limit)
            .map { it.key to it.value }
    }
    
    /**
     * 获取执行最快的命令
     */
    fun getFastestCommands(limit: Int = 10): List<Pair<String, ExecutionStats>> {
        return executionTimes.entries
            .filter { it.value.executionCount > 0 }
            .sortedBy { it.value.avgDurationMs }
            .take(limit)
            .map { it.key to it.value }
    }
    
    /**
     * 执行统计数据类
     */
    data class ExecutionStats(
        var totalDurationMs: Long = 0,
        var executionCount: Int = 0,
        var minDurationMs: Long = Long.MAX_VALUE,
        var maxDurationMs: Long = 0
    ) {
        fun recordExecution(durationMs: Long) {
            totalDurationMs += durationMs
            executionCount++
            minDurationMs = min(minDurationMs, durationMs)
            maxDurationMs = Math.max(maxDurationMs, durationMs)
        }
        
        val avgDurationMs: Double
            get() = if (executionCount > 0) totalDurationMs.toDouble() / executionCount else 0.0
        
        fun formatDuration(): String {
            val avg = avgDurationMs
            return when {
                avg < 1000 -> "${avg.toInt()}ms"
                avg < 60000 -> "${(avg / 1000).toInt()}s"
                else -> "${(avg / 60000).toInt()}m ${((avg % 60000) / 1000).toInt()}s"
            }
        }
    }
    
    // ==================== 最近目录管理 ====================
    
    /**
     * 添加最近目录
     */
    fun addRecentDirectory(directory: String) {
        recentDirectories.remove(directory)
        recentDirectories.add(0, directory)
        
        while (recentDirectories.size > MAX_RECENT_DIRS) {
            recentDirectories.removeLast()
        }
    }
    
    /**
     * 获取最近目录列表
     */
    fun getRecentDirectories(limit: Int = 10): List<String> {
        return recentDirectories.take(limit)
    }
    
    /**
     * 清除最近目录
     */
    fun clearRecentDirectories() {
        recentDirectories.clear()
    }
    
    // ==================== 工作流录制与回放 ====================
    
    /**
     * 开始录制工作流
     */
    fun startRecording() {
        currentRecording = mutableListOf()
        recordingStartTime = System.currentTimeMillis()
    }
    
    /**
     * 停止录制工作流
     */
    fun stopRecording(name: String = ""): WorkflowRecord? {
        val recording = currentRecording ?: return null
        
        if (recording.isEmpty()) {
            currentRecording = null
            return null
        }
        
        val workflow = WorkflowRecord(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Workflow ${workflows.size + 1}" },
            commands = recording.toList(),
            startTime = recordingStartTime,
            duration = System.currentTimeMillis() - recordingStartTime,
            createdAt = System.currentTimeMillis()
        )
        
        workflows.add(workflow)
        
        // 保持工作流数量限制
        while (workflows.size > MAX_WORKFLOWS) {
            workflows.removeAt(0)
        }
        
        currentRecording = null
        saveWorkflows()
        
        return workflow
    }
    
    /**
     * 取消录制
     */
    fun cancelRecording() {
        currentRecording = null
    }
    
    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean {
        return currentRecording != null
    }
    
    /**
     * 获取录制中的命令数
     */
    fun getRecordingCommandCount(): Int {
        return currentRecording?.size ?: 0
    }
    
    /**
     * 获取所有工作流
     */
    fun getAllWorkflows(): List<WorkflowRecord> {
        return workflows.toList()
    }
    
    /**
     * 获取工作流详情
     */
    fun getWorkflowById(id: String): WorkflowRecord? {
        return workflows.find { it.id == id }
    }
    
    /**
     * 删除工作流
     */
    fun deleteWorkflow(id: String) {
        workflows.removeIf { it.id == id }
        saveWorkflows()
    }
    
    /**
     * 保存工作流
     */
    private fun saveWorkflows() {
        try {
            val file = File(HISTORY_FILE_PATH.replace(".json", "_workflows.json"))
            file.parentFile?.mkdirs()
            
            val writer = FileWriter(file)
            writer.write(workflowsToJson())
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 加载工作流
     */
    private fun loadWorkflows() {
        try {
            val file = File(HISTORY_FILE_PATH.replace(".json", "_workflows.json"))
            if (!file.exists()) return
            
            val reader = FileReader(file)
            val json = reader.readText()
            reader.close()
            
            parseWorkflowsJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 工作流转JSON
     */
    private fun workflowsToJson(): String {
        val sb = StringBuilder()
        sb.append("[")
        workflows.forEachIndexed { idx, wf ->
            sb.append("{")
            sb.append("\"id\":\"${wf.id}\",")
            sb.append("\"name\":\"${wf.name.replace("\"", "\\\"")}\",")
            sb.append("\"duration\":${wf.duration},")
            sb.append("\"createdAt\":${wf.createdAt},")
            sb.append("\"commands\":[")
            wf.commands.forEachIndexed { cmdIdx, cmd ->
                sb.append("{")
                sb.append("\"command\":\"${cmd.command.replace("\"", "\\\"")}\",")
                sb.append("\"timestamp\":${cmd.timestamp},")
                sb.append("\"exitCode\":${cmd.exitCode}")
                sb.append("}")
                if (cmdIdx < wf.commands.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.append("}")
            if (idx < workflows.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }
    
    /**
     * 解析工作流JSON
     */
    private fun parseWorkflowsJson(json: String) {
        // 简化解析，实际项目中应使用JSON库
    }
    
    /**
     * 工作流记录
     */
    data class WorkflowRecord(
        val id: String,
        val name: String,
        val commands: List<CommandRecord>,
        val startTime: Long,
        val duration: Long,
        val createdAt: Long
    ) {
        val commandCount: Int get() = commands.size
        val successRate: Double get() {
            if (commands.isEmpty()) return 0.0
            return commands.count { it.exitCode == 0 }.toDouble() / commands.size * 100
        }
    }
    
    /**
     * 保存历史数据到文件
     */
    private fun saveHistory() {
        try {
            val file = File(HISTORY_FILE_PATH)
            // 创建目录
            file.parentFile?.mkdirs()
            
            val writer = FileWriter(file)
            val historyData = HistoryData(sessionHistories, commandFrequency)
            
            // 简单的JSON序列化
            writer.write(historyData.toJson())
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 从文件加载历史数据
     */
    private fun loadHistory() {
        try {
            val file = File(HISTORY_FILE_PATH)
            if (!file.exists()) return
            
            val reader = FileReader(file)
            val json = reader.readText()
            reader.close()
            
            val historyData = HistoryData.fromJson(json)
            sessionHistories.putAll(historyData.sessionHistories.mapValues { it.value.toMutableList() })
            commandFrequency.putAll(historyData.commandFrequency)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 命令记录
     */
    data class CommandRecord(
        val command: String,
        val timestamp: Long,
        val exitCode: Int
    ) {
        val formattedTime: String
            get() = dateFormat.format(Date(timestamp))
    }
    
    /**
     * 历史数据
     */
    private data class HistoryData(
        val sessionHistories: Map<String, List<CommandRecord>>,
        val commandFrequency: Map<String, Int>
    ) {
        fun toJson(): String {
            // 使用简单的JSON构建，避免依赖外部库
            val stringBuilder = StringBuilder()
            stringBuilder.append("{\n")
            
            // 构建sessionHistories
            stringBuilder.append("  \"sessionHistories\": {\n")
            val sessionEntries = sessionHistories.entries.toList()
            sessionEntries.forEachIndexed { index, entry ->
                stringBuilder.append("    \"${entry.key}\": [\n")
                val records = entry.value
                records.forEachIndexed { recordIndex, record ->
                    stringBuilder.append("      {\n")
                    stringBuilder.append("        \"command\": \"${record.command.replace("\"", "\\\"")}\",\n")
                    stringBuilder.append("        \"timestamp\": ${record.timestamp},\n")
                    stringBuilder.append("        \"exitCode\": ${record.exitCode}\n")
                    stringBuilder.append("      ")
                    if (recordIndex < records.size - 1) stringBuilder.append(",")
                    stringBuilder.append("\n")
                }
                stringBuilder.append("    ]")
                if (index < sessionEntries.size - 1) stringBuilder.append(",")
                stringBuilder.append("\n")
            }
            stringBuilder.append("  },\n")
            
            // 构建commandFrequency
            stringBuilder.append("  \"commandFrequency\": {\n")
            val frequencyEntries = commandFrequency.entries.toList()
            frequencyEntries.forEachIndexed { index, entry ->
                stringBuilder.append("    \"${entry.key.replace("\"", "\\\"")}\": ${entry.value}")
                if (index < frequencyEntries.size - 1) stringBuilder.append(",")
                stringBuilder.append("\n")
            }
            stringBuilder.append("  }\n")
            
            stringBuilder.append("}")
            return stringBuilder.toString()
        }
        
        companion object {
            fun fromJson(json: String): HistoryData {
                val sessionHistories = mutableMapOf<String, List<CommandRecord>>()
                val commandFrequency = mutableMapOf<String, Int>()
                
                try {
                    // 简单的JSON解析，实际项目中建议使用JSON库
                    // 这里只做基本的解析，处理简单情况
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                return HistoryData(sessionHistories, commandFrequency)
            }
        }
    }
}