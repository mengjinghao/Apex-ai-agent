package com.apex.agent.core.streaming

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StreamingDataManager(private val context: Context) {

    private val TAG = "StreamingDataManager"

    enum class StreamSourceType {
        API,
        RSS,
        WEBSOCKET,
        LOCAL_FILE,
        DATABASE
    }

    enum class AlertLevel {
        INFO,
        WARNING,
        CRITICAL,
        EMERGENCY
    }

    data class StreamDataPoint(
        val id: String,
        val timestamp: Long,
        val sourceId: String,
        val sourceType: StreamSourceType,
        val content: String,
        val metadata: Map<String, Any>,
        val processed: Boolean = false,
        val alertLevel: AlertLevel = AlertLevel.INFO
    )

    data class StreamConfig(
        val id: String,
        val name: String,
        val sourceType: StreamSourceType,
        val sourceUrl: String,
        val pollingInterval: Long = 60000,
        val enabled: Boolean = true,
        val alertRules: List<AlertRule> = emptyList(),
        val processingPipeline: List<String> = emptyList()
    )

    data class AlertRule(
        val id: String,
        val triggerPattern: String,
        val triggerThreshold: Float,
        val alertLevel: AlertLevel,
        val action: String
    )

    data class ProcessingPipeline(
        val id: String,
        val name: String,
        val steps: List<ProcessingStep>
    )

    data class ProcessingStep(
        val name: String,
        val type: StepType,
        val config: Map<String, Any>
    )

    enum class StepType {
        FILTER,
        TRANSFORM,
        ANALYZE,
        ALERT,
        PERSIST,
        NOTIFY
    }

    private val dataDir: File
        get() = File(context.filesDir, "streaming_data").also {
            if (!it.exists()) it.mkdirs()
        }

    private val configDir: File
        get() = File(context.filesDir, "streaming_config").also {
            if (!it.exists()) it.mkdirs()
        }

    private val activeStreams = mutableMapOf<String, StreamJob>()
    private val dataHistory = mutableMapOf<String, MutableList<StreamDataPoint>>()

    suspend fun registerStream(config: StreamConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val configFile = File(configDir, "${config.id}.json")
            val json = JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("sourceType", config.sourceType.name)
                put("sourceUrl", config.sourceUrl)
                put("pollingInterval", config.pollingInterval)
                put("enabled", config.enabled)

                val rulesJson = JSONArray()
                config.alertRules.forEach { rule ->
                    rulesJson.put(JSONObject().apply {
                        put("id", rule.id)
                        put("triggerPattern", rule.triggerPattern)
                        put("triggerThreshold", rule.triggerThreshold.toDouble())
                        put("alertLevel", rule.alertLevel.name)
                        put("action", rule.action)
                    })
                }
                put("alertRules", rulesJson)

                put("processingPipeline", JSONArray(config.processingPipeline))
            }

            configFile.writeText(json.toString(2))
            AppLogger.d(TAG, "数据流配置已注册: ${config.id}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册数据流失�?, e)
            false
        }
    }

    suspend fun getStreamConfigs(): List<StreamConfig> = withContext(Dispatchers.IO) {
        try {
            configDir.listFiles { _, name -> name.endsWith(".json") }
                ?.mapNotNull { parseConfig(it) }
                ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载数据流配置失�?, e)
            emptyList()
        }
    }

    private fun parseConfig(file: File): StreamConfig? {
        return try {
            val json = JSONObject(file.readText())
            val rulesJson = json.getJSONArray("alertRules")
            val rules = mutableListOf<AlertRule>()

            for (i in 0 until rulesJson.length()) {
                val ruleJson = rulesJson.getJSONObject(i)
                rules.add(
                    AlertRule(
                        id = ruleJson.getString("id"),
                        triggerPattern = ruleJson.getString("triggerPattern"),
                        triggerThreshold = ruleJson.getDouble("triggerThreshold").toFloat(),
                        alertLevel = AlertLevel.valueOf(ruleJson.getString("alertLevel")),
                        action = ruleJson.getString("action")
                    )
                )
            }

            val pipeline = mutableListOf<String>()
            val pipelineJson = json.getJSONArray("processingPipeline")
            for (i in 0 until pipelineJson.length()) {
                pipeline.add(pipelineJson.getString(i))
            }

            StreamConfig(
                id = json.getString("id"),
                name = json.getString("name"),
                sourceType = StreamSourceType.valueOf(json.getString("sourceType")),
                sourceUrl = json.getString("sourceUrl"),
                pollingInterval = json.getLong("pollingInterval"),
                enabled = json.getBoolean("enabled"),
                alertRules = rules,
                processingPipeline = pipeline
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析数据流配置失�? ${file.name}", e)
            null
        }
    }

    fun startStream(config: StreamConfig, callback: (StreamDataPoint) -> Unit) {
        if (activeStreams.containsKey(config.id)) {
            AppLogger.w(TAG, "数据流已在运�? ${config.id}")
            return
        }

        val job = StreamJob(config, callback)
        activeStreams[config.id] = job
        job.start()

        AppLogger.d(TAG, "数据流已启动: ${config.id}")
    }

    fun stopStream(streamId: String) {
        activeStreams.remove(streamId)?.stop()
        AppLogger.d(TAG, "数据流已停止: ${streamId}")
    }

    fun getStreamData(streamId: String, limit: Int = 100): List<StreamDataPoint> {
        return dataHistory[streamId]?.takeLast(limit) ?: emptyList()
    }

    fun getRecentDataPoints(limit: Int = 50): List<StreamDataPoint> {
        val allPoints = dataHistory.values.flatten()
        return allPoints.sortedByDescending { it.timestamp }.take(limit)
    }

    suspend fun persistDataPoint(dataPoint: StreamDataPoint) = withContext(Dispatchers.IO) {
        try {
            val sourceDir = File(dataDir, dataPoint.sourceId)
            if (!sourceDir.exists()) sourceDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = dateFormat.format(Date(dataPoint.timestamp))
            val dataFile = File(sourceDir, "${dateStr}.json")

            val existingData = if (dataFile.exists()) {
                JSONArray(dataFile.readText())
            } else {
                JSONArray()
            }

            val pointJson = JSONObject().apply {
                put("id", dataPoint.id)
                put("timestamp", dataPoint.timestamp)
                put("sourceType", dataPoint.sourceType.name)
                put("content", dataPoint.content)
                put("processed", dataPoint.processed)
                put("alertLevel", dataPoint.alertLevel.name)

                val metadataJson = JSONObject()
                dataPoint.metadata.forEach { (key, value) ->
                    metadataJson.put(key, value)
                }
                put("metadata", metadataJson)
            }

            existingData.put(pointJson)
            dataFile.writeText(existingData.toString(2))

            dataHistory.getOrPut(dataPoint.sourceId) { mutableListOf() }
                .add(dataPoint)

            AppLogger.d(TAG, "数据点已持久�? ${dataPoint.id}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "持久化数据点失败", e)
        }
    }

    fun analyzeTrends(streamId: String, windowMs: Long = 3600000): TrendAnalysis {
        val history = dataHistory[streamId] ?: emptyList()
        val cutoffTime = System.currentTimeMillis() - windowMs
        val windowData = history.filter { it.timestamp >= cutoffTime }

        if (windowData.isEmpty()) {
            return TrendAnalysis(
                streamId = streamId,
                windowSize = windowMs,
                dataPoints = 0,
                alertCounts = emptyMap(),
                trends = emptyMap()
            )
        }

        val alertCounts = windowData.groupingBy { it.alertLevel }
            .eachCount()
            .mapKeys { it.key.name }

        return TrendAnalysis(
            streamId = streamId,
            windowSize = windowMs,
            dataPoints = windowData.size,
            alertCounts = alertCounts,
            trends = mapOf(
                "averageFrequency" to (windowData.size / (windowMs / 60000.0))
            )
        )
    }

    suspend fun generateReport(streamId: String, daysBack: Int = 7): String = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysBack * 24 * 60 * 60 * 1000L)
        val history = dataHistory[streamId]?.filter { it.timestamp >= cutoffTime } ?: emptyList()

        buildString {
            appendLine("=== 数据流分析报�?===")
            appendLine("时间范围: ${daysBack}�?)
            appendLine("数据流ID: ${streamId}")
            appendLine()

            if (history.isEmpty()) {
                appendLine("暂无数据")
                return@buildString
            }

            appendLine("【数据统计�?)
            appendLine("总数据点: ${history.size}")
            appendLine()

            appendLine("【告警分布�?)
            val alertCounts = history.groupingBy { it.alertLevel }.eachCount()
            AlertLevel.values().forEach { level ->
                val count = alertCounts.getOrDefault(level, 0)
                appendLine("  ${level.name}: ${count}")
            }
            appendLine()

            appendLine("【最新数据点�?)
            history.takeLast(5).reversed().forEach { point ->
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val time = timeFormat.format(Date(point.timestamp))
                appendLine("  [${time}] ${point.alertLevel.name}: ${point.content.take(50)}...")
            }
        }
    }

    suspend fun cleanupOldData(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        dataDir.listFiles()?.forEach { sourceDir ->
            if (sourceDir.isDirectory) {
                sourceDir.listFiles()?.forEach { file ->
                    val fileDateStr = file.nameWithoutExtension
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val fileDate = format.parse(fileDateStr)?.time ?: 0L

                        if (fileDate < cutoffTime) {
                            file.delete()
                            AppLogger.d(TAG, "清理旧数据文�? ${file.name}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "跳过清理文件: ${file.name}", e)
                    }
                }
            }
        }

        dataHistory.values.forEach { list ->
            list.removeIf { it.timestamp < cutoffTime }
        }
    }

    private inner class StreamJob(
        private val config: StreamConfig,
        private val callback: (StreamDataPoint) -> Unit
    ) {
        private var running = false
        private var jobThread: Thread? = null

        fun start() {
            running = true
            jobThread = Thread {
                while (running) {
                    try {
                        pollForData()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "数据轮询出错", e)
                    }

                    try {
                        Thread.sleep(config.pollingInterval)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply {
                name = "Stream-${config.id}"
                start()
            }
        }

        fun stop() {
            running = false
            jobThread?.interrupt()
            jobThread = null
        }

        private fun pollForData() {
            val dataPoint = StreamDataPoint(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                sourceId = config.id,
                sourceType = config.sourceType,
                content = "Sample data from ${config.name}",
                metadata = mapOf("sourceUrl" to config.sourceUrl),
                alertLevel = AlertLevel.INFO
            )

            val finalDataPoint = applyProcessingPipeline(dataPoint, config)
            callback(finalDataPoint)

            for (rule in config.alertRules) {
                if (checkAlertRule(finalDataPoint, rule)) {
                    triggerAlert(finalDataPoint, rule)
                }
            }
        }

        private fun applyProcessingPipeline(
            dataPoint: StreamDataPoint,
            config: StreamConfig
        ): StreamDataPoint {
            var result = dataPoint

            for (stepName in config.processingPipeline) {
                result = applyStep(result, stepName)
            }

            return result
        }

        private fun applyStep(dataPoint: StreamDataPoint, stepName: String): StreamDataPoint {
            return dataPoint
        }

        private fun checkAlertRule(dataPoint: StreamDataPoint, rule: AlertRule): Boolean {
            return dataPoint.content.contains(rule.triggerPattern, ignoreCase = true)
        }

        private fun triggerAlert(dataPoint: StreamDataPoint, rule: AlertRule) {
            val alertPoint = dataPoint.copy(
                alertLevel = rule.alertLevel
            )
            callback(alertPoint)
        }
    }
}

data class TrendAnalysis(
    val streamId: String,
    val windowSize: Long,
    val dataPoints: Int,
    val alertCounts: Map<String, Int>,
    val trends: Map<String, Double>
)