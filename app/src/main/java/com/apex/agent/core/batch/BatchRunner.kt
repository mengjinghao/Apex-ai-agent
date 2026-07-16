package com.apex.agent.core.batch

import com.apex.agent.core.storage.BatchRunStorage
import com.apex.agent.core.trajectory.TrajectoryData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import android.content.Context
import com.apex.agent.R
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DatasetLoader {

    companion object {
        fun initialize() {
            LoggerFactory.getLogger(DatasetLoader::class.java).info("DatasetLoader initialized")
        }
    }

    suspend fun loadJsonlFile(filePath: String): List<DatasetItem> {
        return withContext(Dispatchers.IO) {
            val items = mutableListOf<DatasetItem>()
            val logger = LoggerFactory.getLogger(DatasetLoader::class.java)
            val file = File(filePath)

            if (!file.exists()) {
                logger.warn("loadJsonlFile: File not found: ${filePath}")
                return@withContext items
            }

            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val json = JSONObject(line)
                    items.add(parseDatasetItem(json))
                } catch (e: Exception) {
                    logger.warn("loadJsonlFile: Failed to parse line: ${line}, error: ${e.message}")
                }
            }

            logger.info("loadJsonlFile: Successfully loaded ${items.size} items from ${filePath}")
            items
        }
    }

    suspend fun loadFromAssets(context: Any, assetPath: String): List<DatasetItem> {
        return withContext(Dispatchers.IO) {
            val items = mutableListOf<DatasetItem>()
            val logger = LoggerFactory.getLogger(DatasetLoader::class.java)

            val androidContext = context as? android.content.Context
            if (androidContext == null) {
                logger.warn("loadFromAssets: context is not an Android Context")
                return@withContext items
            }

            try {
                androidContext.assets.open(assetPath).use { inputStream ->
                    inputStream.bufferedReader().forEachLine { line ->
                        if (line.isBlank()) return@forEachLine

                        try {
                            val json = JSONObject(line)
                            items.add(parseDatasetItem(json))
                        } catch (e: Exception) {
                            logger.warn("loadFromAssets: Failed to parse line: ${line}, error: ${e.message}")
                        }
                    }

                    logger.info("loadFromAssets: Successfully loaded ${items.size} items from ${assetPath}")
                }
            } catch (e: Exception) {
                logger.error("loadFromAssets: Failed to load assets from ${assetPath}", e)
            }

            items
        }
    }

    suspend fun validateDataset(items: List<DatasetItem>, context: Context): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        items.forEachIndexed { index, item ->
            if (item.prompt.isBlank()) {
                errors.add(context.getString(R.string.error_dataset_empty_prompt, index))
            }
            if (item.id.isBlank()) {
                warnings.add(context.getString(R.string.error_dataset_no_id, index))
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            validCount = items.size - errors.size,
            totalCount = items.size
        )
    }

    data class DatasetItem(
        val id: String,
        val prompt: String,
        val expectedOutput: String? = null,
        val metadata: Map<String, Any> = emptyMap()
    )

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val validCount: Int,
        val totalCount: Int
    )

    private fun parseDatasetItem(json: JSONObject): DatasetItem {
        val id = json.optString("id", "").ifBlank { UUID.randomUUID().toString() }
        val prompt = json.optString("prompt", "")
        val expectedOutput = if (json.has("expected_output") && !json.isNull("expected_output")) {
            json.optString("expected_output")
        } else {
            null
        }

        val metadata = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "id" && key != "prompt" && key != "expected_output") {
                val value = json.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    metadata[key] = value
                }
            }
        }

        return DatasetItem(
            id = id,
            prompt = prompt,
            expectedOutput = expectedOutput,
            metadata = metadata
        )
    }
}

class BatchRunner {

    private val logger = LoggerFactory.getLogger(BatchRunner::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progressFlows = ConcurrentHashMap<String, MutableStateFlow<Progress>>()

    companion object {
        @Volatile
        private var instance: BatchRunner? = null
        private lateinit var appContext: Context

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    instance = BatchRunner()
                    appContext = context.applicationContext
                }
            }
        }

        fun getInstance(): BatchRunner {
            return instance ?: throw IllegalStateException(
                appContext.getString(R.string.error_batch_runner_not_initialized)
            )
        }
    }

    suspend fun runBatch(
        items: List<DatasetLoader.DatasetItem>,
        processor: suspend (DatasetLoader.DatasetItem) -> ProcessingResult,
        batchSize: Int = 4,
        timeoutMs: Long = 30000,
        onProgress: ((Progress) -> Unit)? = null
    ): BatchResult {
        val batchId = UUID.randomUUID().toString()
        val progress = MutableStateFlow(Progress(0, items.size, 0, 0, Progress.Status.RUNNING))
        progressFlows[batchId] = progress

        val channel = Channel<DatasetLoader.DatasetItem>(capacity = batchSize)
        val results = mutableListOf<ProcessingResult>()
        val errors = mutableListOf<ErrorInfo>()
        
        val dispatcher = Dispatchers.Default.limitedParallelism(batchSize)

        val producerJob = launch(dispatcher) {
            items.forEach { item ->
                channel.send(item)
            }
            channel.close()
        }

        val consumerJobs = List(batchSize) {
            launch(dispatcher) {
                for (item in channel) {
                    try {
                        val result = withTimeoutOrNull(timeoutMs) {
                            processor(item)
                        } ?: ProcessingResult(item.id, null, null, ProcessingResult.Status.TIMEOUT)
                        
                        if (result.status == ProcessingResult.Status.SUCCESS) {
                            results.add(result)
                            progress.value = progress.value.copy(completed = progress.value.completed + 1, successes = progress.value.successes + 1)
                        } else {
                            errors.add(ErrorInfo(item.id, result.status.name, result.error ?: appContext.getString(R.string.error_unknown)))
                            progress.value = progress.value.copy(completed = progress.value.completed + 1, failures = progress.value.failures + 1)
                        }
                        
                        onProgress?.invoke(progress.value)
                    } catch (e: Exception) {
                        errors.add(ErrorInfo(item.id, "EXCEPTION", e.message ?: appContext.getString(R.string.error_unknown_exception)))
                        progress.value = progress.value.copy(completed = progress.value.completed + 1, failures = progress.value.failures + 1)
                        onProgress?.invoke(progress.value)
                    }
                }
            }
        }

        producerJob.join()
        consumerJobs.forEach { it.join() }

        progress.value = progress.value.copy(status = Progress.Status.COMPLETED)
        progressFlows.remove(batchId)

        return BatchResult(batchId, results, errors, items.size)
    }

    fun getProgressFlow(batchId: String): Flow<Progress>? {
        return progressFlows[batchId]?.asStateFlow()
    }

    suspend fun cancelBatch(batchId: String) {
        jobs[batchId]?.cancelAndJoin()
        jobs.remove(batchId)
    }

    data class Progress(
        val completed: Int,
        val total: Int,
        val successes: Int,
        val failures: Int,
        val status: Status
    ) {
        enum class Status { RUNNING, COMPLETED, CANCELLED, FAILED }
    }

    data class ProcessingResult(
        val itemId: String,
        val output: String? = null,
        val trajectory: TrajectoryData? = null,
        val status: Status,
        val error: String? = null
    ) {
        enum class Status { SUCCESS, FAILED, TIMEOUT, SKIPPED }
    }

    data class BatchResult(
        val batchId: String,
        val results: List<ProcessingResult>,
        val errors: List<ErrorInfo>,
        val totalItems: Int
    )

    data class ErrorInfo(
        val itemId: String,
        val errorType: String,
        val message: String
    )

    private suspend fun <T> withTimeoutOrNull(timeMs: Long, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }
}

class CheckpointManager {

    private val logger = LoggerFactory.getLogger(CheckpointManager::class.java)
    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()

    companion object {
        @Volatile
        private var instance: CheckpointManager? = null
        private lateinit var appContext: Context

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    instance = CheckpointManager()
                    appContext = context.applicationContext
                }
            }
        }

        fun getInstance(): CheckpointManager {
            return instance ?: throw IllegalStateException(
                appContext.getString(R.string.error_checkpoint_manager_not_initialized)
            )
        }
    }

    suspend fun saveCheckpoint(batchId: String, progress: BatchRunner.Progress, processedItems: List<String>) {
        withContext(Dispatchers.IO) {
            val checkpoint = Checkpoint(
                batchId = batchId,
                timestamp = System.currentTimeMillis(),
                completed = progress.completed,
                total = progress.total,
                successes = progress.successes,
                failures = progress.failures,
                processedItems = processedItems,
                status = progress.status.name
            )

            checkpoints[batchId] = checkpoint

            val json = Json.encodeToString(checkpoint)
            val file = File(getCheckpointPath(batchId))
            file.parentFile?.mkdirs()
            FileWriter(file).use { it.write(json) }

            logger.info("Checkpoint saved for batch ${batchId}: ${progress.completed}/${progress.total}")
        }
    }

    suspend fun loadCheckpoint(batchId: String): Checkpoint? {
        return withContext(Dispatchers.IO) {
            val file = File(getCheckpointPath(batchId))
            if (!file.exists()) {
                return@withContext null
            }

            try {
                val json = FileReader(file).readText()
                val checkpoint = Json.decodeFromString<Checkpoint>(json)
                checkpoints[batchId] = checkpoint
                checkpoint
            } catch (e: Exception) {
                logger.warn("Failed to load checkpoint for batch ${batchId}", e)
                null
            }
        }
    }

    suspend fun deleteCheckpoint(batchId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.remove(batchId)
            val file = File(getCheckpointPath(batchId))
            file.delete()
        }
    }

    fun listCheckpoints(): List<CheckpointSummary> {
        val checkpointDir = File(getCheckpointDirectory())
        if (!checkpointDir.exists()) {
            return emptyList()
        }

        return checkpointDir.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val json = FileReader(file).readText()
                    val checkpoint = Json.decodeFromString<Checkpoint>(json)
                    CheckpointSummary(
                        batchId = checkpoint.batchId,
                        progress = checkpoint.completed.toDouble() / checkpoint.total,
                        timestamp = checkpoint.timestamp,
                        status = checkpoint.status
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
    }

    private fun getCheckpointPath(batchId: String): String {
        return "${getCheckpointDirectory()}/checkpoint_${batchId}.json"
    }

    private fun getCheckpointDirectory(): String {
        return System.getProperty("java.io.tmpdir") + "/apex-agent/checkpoints"
    }

    @Serializable
    data class Checkpoint(
        val batchId: String,
        val timestamp: Long,
        val completed: Int,
        val total: Int,
        val successes: Int,
        val failures: Int,
        val processedItems: List<String>,
        val status: String
    )

    data class CheckpointSummary(
        val batchId: String,
        val progress: Double,
        val timestamp: Long,
        val status: String
    )
}

class ResumableRunner private constructor() {

    private val logger = LoggerFactory.getLogger(ResumableRunner::class.java)

    companion object {
        @Volatile
        private var instance: ResumableRunner? = null
        private lateinit var appContext: Context

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    instance = ResumableRunner()
                    appContext = context.applicationContext
                }
            }
        }

        fun getInstance(): ResumableRunner {
            return instance ?: throw IllegalStateException(
                appContext.getString(R.string.error_resumable_runner_not_initialized)
            )
        }
    }

    suspend fun runResumable(
        batchId: String,
        items: List<DatasetLoader.DatasetItem>,
        processor: suspend (DatasetLoader.DatasetItem) -> BatchRunner.ProcessingResult,
        batchSize: Int = 4,
        checkpointInterval: Long = 30000
    ): BatchRunner.BatchResult {
        val checkpointManager = CheckpointManager.getInstance()
        val existingCheckpoint = checkpointManager.loadCheckpoint(batchId)

        val itemsToProcess = if (existingCheckpoint != null) {
            logger.info("Resuming batch ${batchId} from checkpoint: ${existingCheckpoint.completed}/${existingCheckpoint.total}")
            items.filter { !existingCheckpoint.processedItems.contains(it.id) }
        } else {
            items
        }

        val processedItems = existingCheckpoint?.processedItems?.toMutableList() ?: mutableListOf()
        val results = mutableListOf<BatchRunner.ProcessingResult>()
        val errors = mutableListOf<BatchRunner.ErrorInfo>()

        var lastCheckpointTime = System.currentTimeMillis()

        val progress = BatchRunner.Progress(
            completed = existingCheckpoint?.completed ?: 0,
            total = items.size,
            successes = existingCheckpoint?.successes ?: 0,
            failures = existingCheckpoint?.failures ?: 0,
            status = BatchRunner.Progress.Status.RUNNING
        )

        for (item in itemsToProcess) {
            try {
                val result = processor(item)
                processedItems.add(item.id)
                
                if (result.status == BatchRunner.ProcessingResult.Status.SUCCESS) {
                    results.add(result)
                    progress.successes++
                } else {
                    errors.add(BatchRunner.ErrorInfo(item.id, result.status.name, result.error ?: appContext.getString(R.string.error_unknown)))
                    progress.failures++
                }
                
                progress.completed++

                val now = System.currentTimeMillis()
                if (now - lastCheckpointTime >= checkpointInterval) {
                    checkpointManager.saveCheckpoint(batchId, progress, processedItems)
                    lastCheckpointTime = now
                }
            } catch (e: Exception) {
                errors.add(BatchRunner.ErrorInfo(item.id, "EXCEPTION", e.message ?: appContext.getString(R.string.error_unknown_exception)))
                progress.completed++
                progress.failures++
            }
        }

        checkpointManager.saveCheckpoint(batchId, progress.copy(status = BatchRunner.Progress.Status.COMPLETED), processedItems)
        checkpointManager.deleteCheckpoint(batchId)

        return BatchRunner.BatchResult(batchId, results, errors, items.size)
    }
}

class StatisticsAggregator {

    companion object {
        fun initialize() {
            LoggerFactory.getLogger(StatisticsAggregator::class.java).info("StatisticsAggregator initialized")
        }
    }

    fun aggregate(results: List<BatchRunner.ProcessingResult>): AggregatedStats {
        val toolUsage = mutableMapOf<String, Int>()
        val totalTokens = mutableListOf<Int>()
        val executionTimes = mutableListOf<Long>()

        results.forEach { result ->
            result.trajectory?.let { trajectory ->
                trajectory.turns.forEach { turn ->
                    turn.toolCall?.call?.toolName?.let { toolName ->
                        toolUsage[toolName] = (toolUsage[toolName] ?: 0) + 1
                    }
                }
                totalTokens.add(trajectory.getTokenCount())
            }
        }

        val successRate = if (results.isNotEmpty()) {
            results.count { it.status == BatchRunner.ProcessingResult.Status.SUCCESS }.toDouble() / results.size
        } else {
            0.0
        }

        return AggregatedStats(
            totalItems = results.size,
            successRate = successRate,
            totalToolCalls = toolUsage.values.sum(),
            toolUsageByTool = toolUsage.toList().sortedByDescending { it.second },
            avgTokens = if (totalTokens.isNotEmpty()) totalTokens.average() else 0.0,
            minTokens = totalTokens.minOrNull() ?: 0,
            maxTokens = totalTokens.maxOrNull() ?: 0,
            avgExecutionTime = if (executionTimes.isNotEmpty()) executionTimes.average() else 0.0
        )
    }

    fun generateTrajectoryOutput(results: List<BatchRunner.ProcessingResult>): List<TrajectoryOutput> {
        return results.mapNotNull { result ->
            result.trajectory?.let { trajectory ->
                TrajectoryOutput(
                    itemId = result.itemId,
                    from = trajectory.turns.firstOrNull()?.content ?: "",
                    value = trajectory.turns.lastOrNull()?.content ?: "",
                    status = result.status.name
                )
            }
        }
    }

    data class AggregatedStats(
        val totalItems: Int,
        val successRate: Double,
        val totalToolCalls: Int,
        val toolUsageByTool: List<Pair<String, Int>>,
        val avgTokens: Double,
        val minTokens: Int,
        val maxTokens: Int,
        val avgExecutionTime: Double
    )

    data class TrajectoryOutput(
        val itemId: String,
        val from: String,
        val value: String,
        val status: String
    )
}
