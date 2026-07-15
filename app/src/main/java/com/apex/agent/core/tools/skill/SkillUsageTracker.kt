package com.apex.agent.core.tools.skill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apex.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Context.skillUsageDataStore: DataStore<Preferences> by preferencesDataStore(name = "skill_usage_stats")

class SkillUsageTracker private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillUsageTracker"

        @Volatile private var INSTANCE: SkillUsageTracker? = null

        fun getInstance(context: Context): SkillUsageTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillUsageTracker(context.applicationContext).also { INSTANCE = it }
            }
        }
        private val KEY_USAGE_DATA = stringPreferencesKey("usage_data")
        private val KEY_TOOL_CALLS = stringPreferencesKey("tool_calls")
        private val KEY_EXECUTION_TIMES = stringPreferencesKey("execution_times")
        private val KEY_SUCCESS_FAILURES = stringPreferencesKey("success_failures")
        private val KEY_LAST_RESET = longPreferencesKey("last_reset")
        private val KEY_DAILY_STATS = stringPreferencesKey("daily_stats")
    }
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SkillUsageData(
        val skillName: String,
        val totalInvocations: Long = 0,
        val totalToolCalls: Long = 0,
        val totalExecutionTimeMs: Long = 0,
        val successCount: Long = 0,
        val failureCount: Long = 0,
        val lastUsed: Long = 0,
        val lastUsedDate: String = ""
    )

    @Serializable
    data class ToolCallRecord(
        val skillName: String,
        val toolName: String,
        val count: Long = 0,
        val totalTimeMs: Long = 0,
        val lastCalled: Long = 0
    )

    @Serializable
    data class DailyStats(
        val date: String,
        val totalInvocations: Long = 0,
        val totalToolCalls: Long = 0,
        val totalExecutionTimeMs: Long = 0,
        val skillCounts: Map<String, Long> = emptyMap(),
        val toolCounts: Map<String, Long> = emptyMap()
    )

    @Serializable
    data class SuccessFailureRecord(
        val skillName: String,
        val successCount: Long = 0,
        val failureCount: Long = 0,
        val lastSuccess: Long = 0,
        val lastFailure: Long = 0
    )

    data class ExecutionTimeRecord(
        val skillName: String,
        val date: String,
        val executionTimeMs: Long,
        val timestamp: Long
    )
        private var cachedUsageData: MutableMap<String, SkillUsageData> = mutableMapOf()
        private var cachedToolCalls: MutableMap<String, ToolCallRecord> = mutableMapOf()
        private var cachedExecutionTimes: MutableList<ExecutionTimeRecord> = mutableListOf()
        private var cachedSuccessFailures: MutableMap<String, SuccessFailureRecord> = mutableMapOf()
        private var cachedDailyStats: MutableMap<String, DailyStats> = mutableMapOf()
        private var isInitialized = false

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val usageDataFlow: Flow<Map<String, SkillUsageData>> = context.skillUsageDataStore.data.map { preferences ->
        val dataJson = preferences[KEY_USAGE_DATA] ?: "{}"
        try {
            json.decodeFromString<Map<String, SkillUsageData>>(dataJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }
        val toolCallsFlow: Flow<Map<String, ToolCallRecord>> = context.skillUsageDataStore.data.map { preferences ->
        val dataJson = preferences[KEY_TOOL_CALLS] ?: "{}"
        try {
            json.decodeFromString<Map<String, ToolCallRecord>>(dataJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }
        val dailyStatsFlow: Flow<Map<String, DailyStats>> = context.skillUsageDataStore.data.map { preferences ->
        val dataJson = preferences[KEY_DAILY_STATS] ?: "{}"
        try {
            json.decodeFromString<Map<String, DailyStats>>(dataJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun initialize() {
        if (isInitialized) return
        loadFromDataStore()
        isInitialized = true
    }
        private suspend fun loadFromDataStore() {
        context.skillUsageDataStore.data.first().let { preferences ->
            val usageJson = preferences[KEY_USAGE_DATA] ?: "{}"
        val toolCallsJson = preferences[KEY_TOOL_CALLS] ?: "{}"
        val execTimesJson = preferences[KEY_EXECUTION_TIMES] ?: "[]"
        val successFailJson = preferences[KEY_SUCCESS_FAILURES] ?: "{}"
        val dailyStatsJson = preferences[KEY_DAILY_STATS] ?: "{}"

            cachedUsageData = try {
                json.decodeFromString<MutableMap<String, SkillUsageData>>(usageJson)
            } catch (e: Exception) {
                mutableMapOf()
            }

            cachedToolCalls = try {
                json.decodeFromString<MutableMap<String, ToolCallRecord>>(toolCallsJson)
            } catch (e: Exception) {
                mutableMapOf()
            }

            cachedExecutionTimes = try {
                json.decodeFromString<MutableList<ExecutionTimeRecord>>(execTimesJson)
            } catch (e: Exception) {
                mutableListOf()
            }

            cachedSuccessFailures = try {
                json.decodeFromString<MutableMap<String, SuccessFailureRecord>>(successFailJson)
            } catch (e: Exception) {
                mutableMapOf()
            }

            cachedDailyStats = try {
                json.decodeFromString<MutableMap<String, DailyStats>>(dailyStatsJson)
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
    }
        private suspend fun saveToDataStore() {
        context.skillUsageDataStore.edit { preferences ->
            preferences[KEY_USAGE_DATA] = json.encodeToString(cachedUsageData)
            preferences[KEY_TOOL_CALLS] = json.encodeToString(cachedToolCalls)
            preferences[KEY_EXECUTION_TIMES] = json.encodeToString(cachedExecutionTimes)
            preferences[KEY_SUCCESS_FAILURES] = json.encodeToString(cachedSuccessFailures)
            preferences[KEY_DAILY_STATS] = json.encodeToString(cachedDailyStats)
            preferences[KEY_LAST_RESET] = System.currentTimeMillis()
        }
    }
        fun trackSkillInvoked(skillName: String) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().format(dateFormatter)

        cachedUsageData.getOrPut(skillName) {
            SkillUsageData(skillName = skillName)
        }.let { data ->
            cachedUsageData[skillName] = data.copy(
                totalInvocations = data.totalInvocations + 1,
                lastUsed = now,
                lastUsedDate = today
            )
        }

        cachedDailyStats.getOrPut(today) {
            DailyStats(date = today)
        }.let { stats ->
            cachedDailyStats[today] = stats.copy(
                totalInvocations = stats.totalInvocations + 1,
                skillCounts = stats.skillCounts.toMutableMap().apply {
                    put(skillName, (get(skillName) ?: 0) + 1)
                }
            )
        }

        AppLogger.d(TAG, "Tracked skill invocation: ${skillName}")
    }
        fun trackToolCall(skillName: String, toolName: String, executionTimeMs: Long = 0) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().format(dateFormatter)
        val key = "${skillName}:${toolName}"

        cachedToolCalls.getOrPut(key) {
            ToolCallRecord(skillName = skillName, toolName = toolName)
        }.let { record ->
            cachedToolCalls[key] = record.copy(
                count = record.count + 1,
                totalTimeMs = record.totalTimeMs + executionTimeMs,
                lastCalled = now
            )
        }

        cachedUsageData[skillName]?.let { data ->
            cachedUsageData[skillName] = data.copy(
                totalToolCalls = data.totalToolCalls + 1
            )
        }

        cachedDailyStats.getOrPut(today) {
            DailyStats(date = today)
        }.let { stats ->
            cachedDailyStats[today] = stats.copy(
                totalToolCalls = stats.totalToolCalls + 1,
                toolCounts = stats.toolCounts.toMutableMap().apply {
                    put(toolName, (get(toolName) ?: 0) + 1)
                }
            )
        }

        AppLogger.d(TAG, "Tracked tool call: ${skillName} -> ${toolName}")
    }
        fun trackExecutionTime(skillName: String, executionTimeMs: Long) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().format(dateFormatter)

        cachedUsageData[skillName]?.let { data ->
            cachedUsageData[skillName] = data.copy(
                totalExecutionTimeMs = data.totalExecutionTimeMs + executionTimeMs
            )
        }

        cachedExecutionTimes.add(
            ExecutionTimeRecord(
                skillName = skillName,
                date = today,
                executionTimeMs = executionTimeMs,
                timestamp = now
            )
        )
        if (cachedExecutionTimes.size > 10000) {
            cachedExecutionTimes = cachedExecutionTimes.takeLast(5000).toMutableList()
        }

        cachedDailyStats.getOrPut(today) {
            DailyStats(date = today)
        }.let { stats ->
            cachedDailyStats[today] = stats.copy(
                totalExecutionTimeMs = stats.totalExecutionTimeMs + executionTimeMs
            )
        }

        AppLogger.d(TAG, "Tracked execution time: ${skillName} = ${executionTimeMs}ms")
    }
        fun trackSuccess(skillName: String) {
        val now = System.currentTimeMillis()

        cachedSuccessFailures.getOrPut(skillName) {
            SuccessFailureRecord(skillName = skillName)
        }.let { record ->
            cachedSuccessFailures[skillName] = record.copy(
                successCount = record.successCount + 1,
                lastSuccess = now
            )
        }

        cachedUsageData[skillName]?.let { data ->
            cachedUsageData[skillName] = data.copy(
                successCount = data.successCount + 1
            )
        }

        AppLogger.d(TAG, "Tracked success: ${skillName}")
    }
        fun trackFailure(skillName: String) {
        val now = System.currentTimeMillis()

        cachedSuccessFailures.getOrPut(skillName) {
            SuccessFailureRecord(skillName = skillName)
        }.let { record ->
            cachedSuccessFailures[skillName] = record.copy(
                failureCount = record.failureCount + 1,
                lastFailure = now
            )
        }

        cachedUsageData[skillName]?.let { data ->
            cachedUsageData[skillName] = data.copy(
                failureCount = data.failureCount + 1
            )
        }

        AppLogger.d(TAG, "Tracked failure: ${skillName}")
    }

    suspend fun persist() {
        saveToDataStore()
    }
        fun getUsageData(): Map<String, SkillUsageData> = cachedUsageData.toMap()
        fun getToolCalls(): Map<String, ToolCallRecord> = cachedToolCalls.toMap()
        fun getExecutionTimes(): List<ExecutionTimeRecord> = cachedExecutionTimes.toList()
        fun getSuccessFailures(): Map<String, SuccessFailureRecord> = cachedSuccessFailures.toMap()
        fun getDailyStats(): Map<String, DailyStats> = cachedDailyStats.toMap()
        fun getSkillUsageData(skillName: String): SkillUsageData? = cachedUsageData[skillName]

    fun getToolCallsForSkill(skillName: String): List<ToolCallRecord> {
        return cachedToolCalls.values.filter { it.skillName == skillName }
    }
        fun getExecutionTimesForSkill(skillName: String): List<ExecutionTimeRecord> {
        return cachedExecutionTimes.filter { it.skillName == skillName }
    }
        fun getSuccessRate(skillName: String): Double {
        val record = cachedSuccessFailures[skillName] ?: return 0.0
        val total = record.successCount + record.failureCount
        return if (total > 0) record.successCount.toDouble() / total else 0.0
    }
        fun getAverageExecutionTime(skillName: String): Long {
        val times = cachedExecutionTimes.filter { it.skillName == skillName }
        if (times.isEmpty()) return 0
        return times.map { it.executionTimeMs }.average().toLong()
    }
        fun getTopUsedSkills(limit: Int = 10): List<Pair<String, Long>> {
        return cachedUsageData
            .map { it.key to it.value.totalInvocations }
            .sortedByDescending { it.second }
            .take(limit)
    }
        fun getTopUsedTools(limit: Int = 10): List<Pair<String, Long>> {
        return cachedToolCalls
            .map { it.key to it.value.count }
            .sortedByDescending { it.second }
            .take(limit)
    }
        fun getDailyStatsForPeriod(startDate: LocalDate, endDate: LocalDate): List<DailyStats> {
        return cachedDailyStats
            .filterKeys { dateStr ->
                try {
                    val date = LocalDate.parse(dateStr, dateFormatter)
                    !date.isBefore(startDate) && !date.isAfter(endDate)
                } catch (e: Exception) {
                    false
                }
            }
            .values
            .sortedBy { it.date }
    }
        fun getSkillUsageForPeriod(skillName: String, startDate: LocalDate, endDate: LocalDate): Long {
        val dateSet = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .map { it.format(dateFormatter) }
            .toSet()
        return cachedDailyStats
            .filter { it.key in dateSet }
            .mapNotNull { it.value.skillCounts[skillName] }
            .sum()
    }

    suspend fun resetStats() {
        cachedUsageData.clear()
        cachedToolCalls.clear()
        cachedExecutionTimes.clear()
        cachedSuccessFailures.clear()
        cachedDailyStats.clear()
        saveToDataStore()
        AppLogger.i(TAG, "All usage stats have been reset")
    }

    suspend fun resetStatsForSkill(skillName: String) {
        cachedUsageData.remove(skillName)
        cachedSuccessFailures.remove(skillName)
        cachedExecutionTimes.removeAll { it.skillName == skillName }
        cachedToolCalls.keys.removeAll { it.startsWith("${skillName}:") }

        cachedDailyStats.values.forEach { stats ->
            stats.skillCounts.remove(skillName)
        }

        saveToDataStore()
        AppLogger.i(TAG, "Usage stats for skill '${skillName}' have been reset")
    }
        class UsageStats(
        val totalInvocations: Long,
        val totalToolCalls: Long,
        val totalExecutionTimeMs: Long,
        val averageExecutionTimeMs: Long,
        val successRate: Double,
        val topSkills: List<Pair<String, Long>>,
        val topTools: List<Pair<String, Long>>
    )
        fun getOverallStats(): UsageStats {
        val totalInvocations = cachedUsageData.values.sumOf { it.totalInvocations }
        val totalToolCalls = cachedUsageData.values.sumOf { it.totalToolCalls }
        val totalExecutionTimeMs = cachedUsageData.values.sumOf { it.totalExecutionTimeMs }
        val avgExecTime = if (cachedExecutionTimes.isNotEmpty()) {
            cachedExecutionTimes.map { it.executionTimeMs }.average().toLong()
        } else 0L

        val totalSuccess = cachedSuccessFailures.values.sumOf { it.successCount }
        val totalFailure = cachedSuccessFailures.values.sumOf { it.failureCount }
        val totalAttempts = totalSuccess + totalFailure
        val successRate = if (totalAttempts > 0) totalSuccess.toDouble() / totalAttempts else 0.0

        return UsageStats(
            totalInvocations = totalInvocations,
            totalToolCalls = totalToolCalls,
            totalExecutionTimeMs = totalExecutionTimeMs,
            averageExecutionTimeMs = avgExecTime,
            successRate = successRate,
            topSkills = getTopUsedSkills(5),
            topTools = getTopUsedTools(5)
        )
    }
}