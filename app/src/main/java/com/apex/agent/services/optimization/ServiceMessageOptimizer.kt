package com.apex.agent.services.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class MessageEnvelope(
    val id: String,
    val type: MessageType,
    val payload: Any,
    val priority: MessagePriority,
    val timestampMs: Long = System.currentTimeMillis(),
    val senderId: String,
    val targetId: String? = null,
    val correlationId: String? = null,
    val ttlMs: Long = 30000L,
    val retryCount: Int = 0,
    val sizeBytes: Int = 0
)

enum class MessageType {
    COMMAND, QUERY, EVENT, RESPONSE, ERROR, NOTIFICATION,
    STREAM_DATA, BATCH, CONTROL, HEARTBEAT
}

enum class MessagePriority(val level: Int) {
    CRITICAL(100), HIGH(75), NORMAL(50), LOW(25), BACKGROUND(10)
}

data class MessageBatch(
    val messages: List<MessageEnvelope>,
    val batchId: String,
    val createdMs: Long = System.currentTimeMillis(),
    val totalSizeBytes: Int = 0
)

data class MessageProcessingResult(
    val messageId: String,
    val success: Boolean,
    val processingTimeMs: Long,
    val errorMessage: String? = null,
    val outputPayload: Any? = null
)

data class MessageQueueMetrics(
    val totalEnqueued: Long,
    val totalProcessed: Long,
    val totalFailed: Long,
    val queueDepth: Int,
    val averageLatencyMs: Double,
    val p95LatencyMs: Double,
    val p99LatencyMs: Double,
    val processingRate: Double,
    val backpressureCount: Long,
    val channelUtilization: Double,
    val messageTypeDistribution: Map<MessageType, Int>
)

data class ServiceMessageConfig(
    val maxQueueSize: Int = 10000,
    val maxConcurrentProcessors: Int = 4,
    val enableBatching: Boolean = true,
    val batchMaxSize: Int = 100,
    val batchMaxWaitMs: Long = 100L,
    val enableBackpressure: Boolean = true,
    val backpressureThreshold: Int = 8000,
    val enableMetrics: Boolean = true,
    val defaultTtlMs: Long = 30000L
)

data class SubscriptionConfig(
    val subscriberId: String,
    val messageTypes: Set<MessageType>,
    val priorityFilter: MessagePriority? = null,
    val correlationId: String? = null,
    val maxBatchSize: Int = 10,
    val autoAcknowledge: Boolean = true
)

data class DeliveryReport(
    val subscriberId: String,
    val messagesDelivered: Int,
    val messagesFailed: Int,
    val totalLatencyMs: Long,
    val averageLatencyMs: Double
)

class ServiceMessageOptimizer private constructor() {

    private val messageQueue = Channel<MessageEnvelope>(Channel.UNLIMITED)
    private val subscribers = ConcurrentHashMap<String, SubscriptionConfig>()
    private val subscriberChannels = ConcurrentHashMap<String, Channel<MessageEnvelope>>()
    private val batchedOutputs = ConcurrentHashMap<String, Channel<List<MessageEnvelope>>>()
    private val processedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val enqueuedCount = AtomicLong(0)
    private val processingTimes = CopyOnWriteArrayList<Long>()
    private val typeCounts = ConcurrentHashMap<MessageType, AtomicInteger>()
    private val backpressureEvents = AtomicLong(0)
    private var isRunning = false
    private var scope: CoroutineScope? = null
    private val config = ServiceMessageConfig()

    companion object {
        @Volatile
        private var instance: ServiceMessageOptimizer? = null

        fun getInstance(): ServiceMessageOptimizer {
            return instance ?: synchronized(this) {
                instance ?: ServiceMessageOptimizer().also { instance = it }
            }
        }

        private const val METRICS_HISTORY = 500
        private const val DEFAULT_CHANNEL_CAPACITY = 100
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        isRunning = true
        coroutineScope.launch(Dispatchers.Default) { messageProcessorLoop() }
        if (config.enableBatching) {
            coroutineScope.launch(Dispatchers.Default) { batchProcessorLoop() }
        }
        if (config.enableMetrics) {
            coroutineScope.launch(Dispatchers.Default) { metricsCollectorLoop() }
        }
    }

    fun shutdown() {
        isRunning = false
        messageQueue.close()
        subscriberChannels.values.forEach { it.close() }
        subscriberChannels.clear()
        batchedOutputs.values.forEach { it.close() }
        batchedOutputs.clear()
        subscribers.clear()
    }

    suspend fun publish(message: MessageEnvelope) {
        enqueuedCount.incrementAndGet()
        typeCounts.computeIfAbsent(message.type) { AtomicInteger(0) }.incrementAndGet()

        if (config.enableBackpressure) {
            val queueSize = messageQueue.onSendOrNull?.let { 0 } ?: 0
            if (queueSize >= config.backpressureThreshold) {
                backpressureEvents.incrementAndGet()
            }
        }

        messageQueue.send(message)
    }

    suspend fun publishBatch(messages: List<MessageEnvelope>) {
        for (msg in messages) {
            publish(msg)
        }
    }

    suspend fun publishWithPriority(type: MessageType, payload: Any, priority: MessagePriority, senderId: String) {
        val msg = MessageEnvelope(
            id = "msg_${System.currentTimeMillis()}_${type.name}",
            type = type,
            payload = payload,
            priority = priority,
            senderId = senderId,
            sizeBytes = payload.toString().toByteArray().size
        )
        publish(msg)
    }

    fun subscribe(config: SubscriptionConfig) {
        subscribers[config.subscriberId] = config
        val channel = Channel<MessageEnvelope>(DEFAULT_CHANNEL_CAPACITY)
        subscriberChannels[config.subscriberId] = channel
    }

    fun unsubscribe(subscriberId: String) {
        subscribers.remove(subscriberId)
        subscriberChannels.remove(subscriberId)?.close()
        batchedOutputs.remove(subscriberId)?.close()
    }

    fun getSubscriberChannel(subscriberId: String): Channel<MessageEnvelope>? {
        return subscriberChannels[subscriberId]
    }

    fun getSubscriberFlow(subscriberId: String): Flow<MessageEnvelope>? {
        return subscriberChannels[subscriberId]?.receiveAsFlow()
    }

    fun getBatchedFlow(subscriberId: String): Flow<List<MessageEnvelope>>? {
        return batchedOutputs[subscriberId]?.receiveAsFlow()
    }

    suspend fun processMessage(message: MessageEnvelope): MessageProcessingResult {
        val startTime = System.nanoTime()

        try {
            val targetSubscribers = subscribers.filter { (_, config) ->
                config.messageTypes.contains(message.type) &&
                    (config.priorityFilter == null || message.priority.level >= config.priorityFilter.level) &&
                    (config.correlationId == null || message.correlationId == config.correlationId)
            }

            for ((subscriberId, _) in targetSubscribers) {
                val channel = subscriberChannels[subscriberId]
                if (channel != null && !channel.isClosedForSend) {
                    if (config.enableBatching) {
                        val batchChannel = batchedOutputs.computeIfAbsent(subscriberId) {
                            Channel(DEFAULT_CHANNEL_CAPACITY)
                        }
                        batchChannel.trySend(listOf(message))
                    } else {
                        channel.trySend(message)
                    }
                }
            }

            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            processingTimes.add(durationMs)
            if (processingTimes.size > METRICS_HISTORY) processingTimes.removeAt(0)
            processedCount.incrementAndGet()

            MessageProcessingResult(message.id, true, durationMs)
        } catch (e: Exception) {
            failedCount.incrementAndGet()
            MessageProcessingResult(message.id, false, (System.nanoTime() - startTime) / 1_000_000, e.message)
        }
    }

    suspend fun processBatch(messages: List<MessageEnvelope>): List<MessageProcessingResult> {
        messages.map { processMessage(it) }
    }

    fun acknowledge(subscriberId: String, messageIds: List<String>) {}

    fun getQueueDepth(): Int = 0

    fun getActiveSubscribers(): Int = subscribers.size

    fun getMetrics(): MessageQueueMetrics {
        val avgLatency = if (processingTimes.isNotEmpty()) processingTimes.average() else 0.0
        val sorted = processingTimes.sorted()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val processed = processedCount.get()
        val rate = if (processed > 0 && processingTimes.isNotEmpty()) {
            val totalTime = processingTimes.sum()
            if (totalTime > 0) processed.toDouble() / totalTime * 1000 else 0.0
        } else 0.0
        val typeDist = typeCounts.entries.associate { it.key to it.value.get() }
        MessageQueueMetrics(
            totalEnqueued = enqueuedCount.get(),
            totalProcessed = processed,
            totalFailed = failedCount.get(),
            queueDepth = getQueueDepth(),
            averageLatencyMs = avgLatency,
            p95LatencyMs = p95,
            p99LatencyMs = p99,
            processingRate = rate,
            backpressureCount = backpressureEvents.get(),
            channelUtilization = if (processed > 0) min(1.0, rate / config.maxConcurrentProcessors.toDouble()) else 0.0,
            messageTypeDistribution = typeDist
        )
    }

    fun getMetricsReport(): String {
        val m = getMetrics()
        """
        |Message Queue Metrics:
        |  Enqueued: ${m.totalEnqueued} | Processed: ${m.totalProcessed} | Failed: ${m.totalFailed}
        |  Depth: ${m.queueDepth} | Rate: ${"%.1f".format(m.processingRate)}/s
        |  Avg Latency: ${"%.1f".format(m.averageLatencyMs)}ms | P95: ${"%.1f".format(m.p95LatencyMs)}ms | P99: ${"%.1f".format(m.p99LatencyMs)}ms
        |  Backpressure Events: ${m.backpressureCount}
        |  Channel Utilization: ${"%.0f".format(m.channelUtilization * 100)}%
        """.trimMargin()
    }

    fun updateConfig(newConfig: ServiceMessageConfig): ServiceMessageConfig { newConfig }

    fun resetMetrics() {
        processedCount.set(0)
        failedCount.set(0)
        enqueuedCount.set(0)
        processingTimes.clear()
        typeCounts.clear()
        backpressureEvents.set(0)
    }

    private suspend fun messageProcessorLoop() {
        while (isRunning) {
            val message = messageQueue.receiveCatching().getOrNull() ?: continue
            processMessage(message)
        }
    }

    private suspend fun batchProcessorLoop() {
        while (isRunning) {
            val batch = mutableListOf<MessageEnvelope>()
            val deadline = System.currentTimeMillis() + config.batchMaxWaitMs

            while (batch.size < config.batchMaxSize && System.currentTimeMillis() < deadline) {
                val msg = withTimeoutOrNull(config.batchMaxWaitMs) {
                    messageQueue.receiveCatching().getOrNull()
                } ?: break
                if (msg != null) batch.add(msg)
            }

            if (batch.isNotEmpty()) {
                val results = processBatch(batch)
                results
            }
            delay(1)
        }
    }

    private suspend fun metricsCollectorLoop() {
        while (isRunning) {
            delay(60000L)
            getMetrics()
        }
    }
}
