package com.apex.agent.core.multiagent

import android.content.Context
import android.util.Base64
import com.apex.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.sqrt

class FederatedLearningManager(private val context: Context) {

    companion object {
        private const val TAG = "FederatedLearningManager"
        private const val MODEL_STORE_NAME = "federated_model_store"
        private const val AGGREGATION_INTERVAL = 5000L
        private const val MIN_CONTRIBUTION_THRESHOLD = 3
        private const val KNOWLEDGE_DISTILLATION_TEMP = 3.0f
        private const val DRIFT_THRESHOLD = 0.15f
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val localModels = ConcurrentHashMap<String, LocalAgentModel>()
    private val sharedKnowledgeBase = ConcurrentHashMap<String, SharedKnowledge>()
    private val agentContributions = ConcurrentHashMap<String, MutableList<ModelContribution>>()
    private val adaptationHistories = ConcurrentHashMap<String, MutableList<AdaptationRecord>>()

    private val _globalModelVersion = MutableStateFlow(0)
    val globalModelVersion: StateFlow<Int> = _globalModelVersion

    private val _trainingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val trainingProgress: StateFlow<Map<String, Float>> = _trainingProgress

    private val _knowledgeTransferStats = MutableStateFlow(KnowledgeTransferStats())
    val knowledgeTransferStats: StateFlow<KnowledgeTransferStats> = _knowledgeTransferStats

    private val prefs = context.getSharedPreferences(MODEL_STORE_NAME, Context.MODE_PRIVATE)

    private var aggregationJob: Job? = null

    init {
        loadFromDisk()
        startAggregationLoop()
    }

    data class LocalAgentModel(
        val agentId: String,
        val capabilityWeights: MutableMap<String, Float>,
        val performanceHistory: MutableList<Float>,
        val lastUpdateTime: Long,
        val version: Int,
        var confidence: Float = 1.0f,
        var driftDetected: Boolean = false
    )

    data class SharedKnowledge(
        val knowledgeId: String,
        val sourceAgents: Set<String>,
        val capability: String,
        val learnedPattern: String,
        val confidence: Float,
        val examples: MutableList<String>,
        val timestamp: Long,
        var validationCount: Int = 0,
        var successCount: Int = 0
    )

    data class ModelContribution(
        val agentId: String,
        val capabilityUpdates: Map<String, Float>,
        val performanceDelta: Float,
        val sampleSize: Int,
        val timestamp: Long,
        val taskType: String
    )

    data class AdaptationRecord(
        val trigger: AdaptationTrigger,
        val action: AdaptationAction,
        val result: AdaptationResult,
        val timestamp: Long,
        val context: Map<String, Any>
    ) {
        enum class AdaptationTrigger {
            POOR_PERFORMANCE, NEW_TASK_TYPE, KNOWLEDGE_GAP, AGENT_FAILURE, PERIODIC_REVIEW
        }

        enum class AdaptationAction {
            WEIGHT_ADJUSTMENT, PATTERN_INJECTION, MODEL_MERGE, STRATEGY_SWITCH, ROLE_REASSIGNMENT
        }

        enum class AdaptationResult {
            SUCCESS, PARTIAL, FAILED, ROLLED_BACK
        }
    }

    data class KnowledgeTransferStats(
        val totalTransfers: Int = 0,
        val successfulTransfers: Int = 0,
        val avgTransferTime: Float = 0f,
        val knowledgeSources: Map<String, Int> = emptyMap()
    )

    data class PredictionResult(
        val predictedCapability: Float,
        val confidence: Float,
        val basedOnSamples: Int,
        val recommendedAdjustment: Float
    )

    fun registerAgent(agentId: String, initialCapabilities: Map<String, Float>) {
        val model = LocalAgentModel(
            agentId = agentId,
            capabilityWeights = initialCapabilities.toMutableMap(),
            performanceHistory = mutableListOf(0.5f),
            lastUpdateTime = System.currentTimeMillis(),
            version = 0
        )
        localModels[agentId] = model
        agentContributions[agentId] = mutableListOf()
        saveToDisk()
    }

    fun recordTaskOutcome(
        agentId: String,
        taskType: String,
        success: Boolean,
        quality: Float,
        duration: Long,
        capabilities: Map<String, Float>
    ) {
        scope.launch {
            val model = localModels[agentId] ?: return@launch

            val performance = if (success) minOf(quality, 1.0f) else maxOf(quality - 0.3f, 0.0f)
            model.performanceHistory.add(performance)
            if (model.performanceHistory.size > 100) {
                model.performanceHistory.removeAt(0)
            }

            capabilities.forEach { (cap, value) ->
                val currentWeight = model.capabilityWeights[cap] ?: 1.0f
                val adjustment = calculateAdjustment(performance, value, success)
                model.capabilityWeights[cap] = (currentWeight + adjustment).coerceIn(0.1f, 2.0f)
            }

            model.lastUpdateTime = System.currentTimeMillis()
            model.version++

            val contribution = ModelContribution(
                agentId = agentId,
                capabilityUpdates = model.capabilityWeights.toMap(),
                performanceDelta = performance - (model.performanceHistory.getOrNull(model.performanceHistory.size - 2) ?: 0.5f),
                sampleSize = model.performanceHistory.size,
                timestamp = System.currentTimeMillis(),
                taskType = taskType
            )
            agentContributions[agentId]?.add(contribution)

            if (shouldTriggerAdaptation(model)) {
                triggerAdaptation(agentId, AdaptationRecord.AdaptationTrigger.POOR_PERFORMANCE, emptyMap())
            }

            updateProgress(agentId, performance)
            saveToDisk()
        }
    }

    private fun calculateAdjustment(performance: Float, capability: Float, success: Boolean): Float {
        val baseAdjustment = (performance - 0.5f) * 0.1f
        val capabilityBonus = if (success && capability > 0.8f) 0.05f else 0f
        val failurePenalty = if (!success) -0.1f else 0f
        return baseAdjustment + capabilityBonus + failurePenalty
    }

    private fun shouldTriggerAdaptation(model: LocalAgentModel): Boolean {
        if (model.performanceHistory.size < 5) return false

        val recentPerformance = model.performanceHistory.takeLast(5).average().toFloat()
        val olderPerformance = model.performanceHistory.take(maxOf(0, model.performanceHistory.size - 10)).average().toFloat()

        val drift = kotlin.math.abs(recentPerformance - olderPerformance)
        model.driftDetected = drift > DRIFT_THRESHOLD

        return drift > DRIFT_THRESHOLD || recentPerformance < 0.3f
    }

    fun distributeKnowledge(sourceAgentId: String, targetAgentId: String, knowledge: SharedKnowledge): Boolean {
        scope.launch {
            try {
                val sourceModel = localModels[sourceAgentId] ?: return@launch
                val targetModel = localModels[targetAgentId] ?: return@launch

                val capabilityKey = knowledge.capability
                val sourceWeight = sourceModel.capabilityWeights[capabilityKey] ?: 1.0f

                val currentWeight = targetModel.capabilityWeights[capabilityKey] ?: 1.0f
                val transferStrength = knowledge.confidence * 0.3f
                val newWeight = currentWeight + (sourceWeight - currentWeight) * transferStrength

                targetModel.capabilityWeights[capabilityKey] = newWeight.coerceIn(0.1f, 2.0f)
                targetModel.lastUpdateTime = System.currentTimeMillis()
                targetModel.version++

                sharedKnowledgeBase[knowledge.knowledgeId] = knowledge.copy(
                    sourceAgents = knowledge.sourceAgents + sourceAgentId,
                    validationCount = knowledge.validationCount + 1
                )

                val stats = _knowledgeTransferStats.value
                _knowledgeTransferStats.value = stats.copy(
                    totalTransfers = stats.totalTransfers + 1,
                    successfulTransfers = stats.successfulTransfers + 1,
                    knowledgeSources = stats.knowledgeSources + (sourceAgentId to (stats.knowledgeSources[sourceAgentId] ?: 0) + 1)
                )

                saveToDisk()
            } catch (e: Exception) {
                AppLogger.e(TAG, "saveKnowledgeContribution failed", e)
            }
        }
        return true
    }

    fun distillKnowledge(agentId: String): SharedKnowledge {
        val model = localModels[agentId] ?: throw IllegalArgumentException("Agent not found")

        val topCapabilities = model.capabilityWeights.entries
            .sortedByDescending { it.value }
            .take(5)
            .associate { it.key to it.value }

        val avgPerformance = model.performanceHistory.average().toFloat()

        return SharedKnowledge(
            knowledgeId = UUID.randomUUID().toString(),
            sourceAgents = setOf(agentId),
            capability = topCapabilities.keys.firstOrNull() ?: "unknown",
            learnedPattern = topCapabilities.toString(),
            confidence = avgPerformance,
            examples = mutableListOf(),
            timestamp = System.currentTimeMillis()
        )
    }

    fun predictCapability(agentId: String, capability: String): PredictionResult {
        val model = localModels[agentId]
        val history = agentContributions[agentId] ?: emptyList()

        if (history.isEmpty()) {
            return PredictionResult(
                predictedCapability = model?.capabilityWeights[capability] ?: 1.0f,
                confidence = 0.3f,
                basedOnSamples = 0,
                recommendedAdjustment = 0f
            )
        }

        val recentUpdates = history.takeLast(10)
        val capabilityUpdates = recentUpdates.mapNotNull { it.capabilityUpdates[capability] }

        if (capabilityUpdates.isEmpty()) {
            return PredictionResult(
                predictedCapability = model?.capabilityWeights[capability] ?: 1.0f,
                confidence = 0.5f,
                basedOnSamples = history.size,
                recommendedAdjustment = 0f
            )
        }

        val predictedCapability = capabilityUpdates.average().toFloat()
        val trend = if (capabilityUpdates.size > 1) {
            capabilityUpdates.last() - capabilityUpdates.first()
        } else 0f

        val confidence = minOf(capabilityUpdates.size.toFloat() / 20f, 1.0f)
        val recommendedAdjustment = trend * 0.5f

        return PredictionResult(
            predictedCapability = predictedCapability,
            confidence = confidence,
            basedOnSamples = capabilityUpdates.size,
            recommendedAdjustment = recommendedAdjustment
        )
    }

    fun aggregateModels(): GlobalAggregatedModel? {
        if (localModels.size < MIN_CONTRIBUTION_THRESHOLD) return null

        val aggregatedCapabilities = mutableMapOf<String, MutableList<Float>>()

        localModels.values.forEach { model ->
            model.capabilityWeights.forEach { (cap, weight) ->
                aggregatedCapabilities.getOrPut(cap) { mutableListOf() }.add(weight)
            }
        }

        val finalCapabilities = aggregatedCapabilities.mapValues { (_, weights) ->
            val filteredWeights = filterOutliers(weights)
            filteredWeights.average().toFloat()
        }

        val globalModel = GlobalAggregatedModel(
            capabilities = finalCapabilities,
            participatingAgents = localModels.keys.toSet(),
            version = _globalModelVersion.value + 1,
            timestamp = System.currentTimeMillis()
        )

        _globalModelVersion.value++

        localModels.values.forEach { model ->
            finalCapabilities.forEach { (cap, globalWeight) ->
                val localWeight = model.capabilityWeights[cap] ?: 1.0f
                model.capabilityWeights[cap] = (localWeight * 0.7f + globalWeight * 0.3f)
            }
            model.version = globalModel.version
        }

        saveToDisk()
        return globalModel
    }

    private fun filterOutliers(values: List<Float>): List<Float> {
        if (values.size < 3) return values

        val sorted = values.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5f * iqr
        val upperBound = q3 + 1.5f * iqr

        return values.filter { it in lowerBound..upperBound }
    }

    fun triggerAdaptation(agentId: String, trigger: AdaptationRecord.AdaptationTrigger, context: Map<String, Any>) {
        scope.launch {
            val record = AdaptationRecord(
                trigger = trigger,
                action = determineAdaptationAction(trigger, context),
                result = AdaptationRecord.AdaptationResult.SUCCESS,
                timestamp = System.currentTimeMillis(),
                context = context
            )

            adaptationHistories.getOrPut(agentId) { mutableListOf() }.add(record)

            when (record.action) {
                AdaptationRecord.AdaptationAction.WEIGHT_ADJUSTMENT -> {
                    performWeightAdjustment(agentId, context)
                }
                AdaptationRecord.AdaptationAction.PATTERN_INJECTION -> {
                    injectLearnedPattern(agentId, context)
                }
                AdaptationRecord.AdaptationAction.MODEL_MERGE -> {
                    performModelMerge(agentId, context)
                }
                AdaptationRecord.AdaptationAction.STRATEGY_SWITCH -> {
                    switchStrategy(agentId, context)
                }
                AdaptationRecord.AdaptationAction.ROLE_REASSIGNMENT -> {
                    reassignRole(agentId, context)
                }
            }

            saveToDisk()
        }
    }

    private fun determineAdaptationAction(trigger: AdaptationRecord.AdaptationTrigger, context: Map<String, Any>): AdaptationRecord.AdaptationAction {
        return when (trigger) {
            AdaptationRecord.AdaptationTrigger.POOR_PERFORMANCE -> AdaptationRecord.AdaptationAction.WEIGHT_ADJUSTMENT
            AdaptationRecord.AdaptationTrigger.NEW_TASK_TYPE -> AdaptationRecord.AdaptationAction.PATTERN_INJECTION
            AdaptationRecord.AdaptationTrigger.KNOWLEDGE_GAP -> AdaptationRecord.AdaptationAction.MODEL_MERGE
            AdaptationRecord.AdaptationTrigger.AGENT_FAILURE -> AdaptationRecord.AdaptationAction.STRATEGY_SWITCH
            AdaptationRecord.AdaptationTrigger.PERIODIC_REVIEW -> AdaptationRecord.AdaptationAction.WEIGHT_ADJUSTMENT
        }
    }

    private fun performWeightAdjustment(agentId: String, context: Map<String, Any>) {
        val model = localModels[agentId] ?: return
        val targetCapability = context["capability"] as? String ?: return

        val currentWeight = model.capabilityWeights[targetCapability] ?: 1.0f
        val adjustment = context["adjustment"] as? Float ?: -0.1f

        model.capabilityWeights[targetCapability] = (currentWeight + adjustment).coerceIn(0.1f, 2.0f)
        model.version++
    }

    private fun injectLearnedPattern(agentId: String, context: Map<String, Any>) {
        val pattern = context["pattern"] as? String ?: return
        sharedKnowledgeBase[pattern]?.let { knowledge ->
            val targetAgents = context["targetAgents"] as? List<String> ?: listOf(agentId)
            targetAgents.forEach { targetId ->
                distributeKnowledge(agentId, targetId, knowledge)
            }
        }
    }

    private fun performModelMerge(agentId: String, context: Map<String, Any>) {
        val sourceAgents = context["sourceAgents"] as? List<String> ?: return

        val targetModel = localModels[agentId] ?: return
        val sourceModels = sourceAgents.mapNotNull { localModels[it] }

        if (sourceModels.isEmpty()) return

        sourceModels.forEach { sourceModel ->
            sourceModel.capabilityWeights.forEach { (cap, weight) ->
                val targetWeight = targetModel.capabilityWeights[cap] ?: 1.0f
                targetModel.capabilityWeights[cap] = (targetWeight * 0.6f + weight * 0.4f)
            }
        }
        targetModel.version++
    }

    private fun switchStrategy(agentId: String, context: Map<String, Any>) {
        val newStrategy = context["strategy"] as? String ?: return
        localModels[agentId]?.let { model ->
            model.driftDetected = false
        }
    }

    private fun reassignRole(agentId: String, context: Map<String, Any>) {
        val newRole = context["role"] as? String ?: return
    }

    private fun startAggregationLoop() {
        aggregationJob = scope.launch {
            while (isActive) {
                delay(AGGREGATION_INTERVAL)
                aggregateModels()
            }
        }
    }

    private fun updateProgress(agentId: String, performance: Float) {
        val currentProgress = _trainingProgress.value.toMutableMap()
        currentProgress[agentId] = performance
        _trainingProgress.value = currentProgress
    }

    fun getAgentCapabilities(agentId: String): Map<String, Float>? {
        return localModels[agentId]?.capabilityWeights
    }

    fun getAdaptationHistory(agentId: String): List<AdaptationRecord> {
        return adaptationHistories[agentId]?.toList() ?: emptyList()
    }

    fun getSharedKnowledge(): List<SharedKnowledge> {
        return sharedKnowledgeBase.values.toList()
    }

    private fun saveToDisk() {
        try {
            val modelJson = gson.toJson(localModels.mapKeys { it.key })
            val knowledgeJson = gson.toJson(sharedKnowledgeBase)
            val contributionJson = gson.toJson(agentContributions.mapValues { it.value.toList() })

            prefs.edit()
                .putString("models", Base64.encodeToString(modelJson.toByteArray(), Base64.DEFAULT))
                .putString("knowledge", Base64.encodeToString(knowledgeJson.toByteArray(), Base64.DEFAULT))
                .putString("contributions", Base64.encodeToString(contributionJson.toByteArray(), Base64.DEFAULT))
                .apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "saveToDisk failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val modelJson = prefs.getString("models", null)
            val knowledgeJson = prefs.getString("knowledge", null)
            val contributionJson = prefs.getString("contributions", null)

            modelJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<Map<String, LocalAgentModel>>() {}.type
                val models: Map<String, LocalAgentModel> = gson.fromJson(json, type)
                models.forEach { (k, v) -> localModels[k] = v }
            }

            knowledgeJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<Map<String, SharedKnowledge>>() {}.type
                val knowledge: Map<String, SharedKnowledge> = gson.fromJson(json, type)
                knowledge.forEach { (k, v) -> sharedKnowledgeBase[k] = v }
            }

            contributionJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<Map<String, List<ModelContribution>>>() {}.type
                val contributions: Map<String, List<ModelContribution>> = gson.fromJson(json, type)
                contributions.forEach { (k, v) -> agentContributions[k] = v.toMutableList() }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadFromDisk failed", e)
        }
    }

    fun shutdown() {
        aggregationJob?.cancel()
        scope.cancel()
    }
}

data class GlobalAggregatedModel(
    val capabilities: Map<String, Float>,
    val participatingAgents: Set<String>,
    val version: Int,
    val timestamp: Long
)

class IncrementalLearningEngine {

    private val learningRates = ConcurrentHashMap<String, Float>()
    private val momentumBuffer = ConcurrentHashMap<String, Float>()
    private val gradientHistory = ConcurrentHashMap<String, MutableList<Float>>()

    companion object {
        private const val DEFAULT_LEARNING_RATE = 0.01f
        private const val MOMENTUM_FACTOR = 0.9f
        private const val GRADIENT_HISTORY_SIZE = 10
    }

    fun computeGradient(currentValue: Float, targetValue: Float, error: Float): Float {
        return (targetValue - currentValue) * error
    }

    fun updateWithMomentum(
        agentId: String,
        capability: String,
        gradient: Float,
        learningRate: Float = learningRates[capability] ?: DEFAULT_LEARNING_RATE
    ): Float {
        val key = "${agentId}:${capability}"

        val momentum = momentumBuffer[key] ?: 0f
        val newMomentum = MOMENTUM_FACTOR * momentum + (1 - MOMENTUM_FACTOR) * gradient

        momentumBuffer[key] = newMomentum

        val gradientHist = gradientHistory.getOrPut(key) { mutableListOf() }
        gradientHist.add(gradient)
        if (gradientHist.size > GRADIENT_HISTORY_SIZE) {
            gradientHist.removeAt(0)
        }

        val adaptiveLR = adaptLearningRate(key, gradient)
        learningRates[capability] = adaptiveLR

        return newMomentum * adaptiveLR
    }

    private fun adaptLearningRate(key: String, gradient: Float): Float {
        val history = gradientHistory[key] ?: return DEFAULT_LEARNING_RATE

        if (history.size < 3) return DEFAULT_LEARNING_RATE

        val recentGradients = history.takeLast(3)
        val variance = calculateVariance(recentGradients)

        val baseLR = learningRates[key.split(":").getOrNull(1) ?: ""] ?: DEFAULT_LEARNING_RATE

        return when {
            variance < 0.01f -> baseLR * 1.5f
            variance > 0.5f -> baseLR * 0.5f
            else -> baseLR
        }.coerceIn(0.001f, 0.1f)
    }

    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    fun resetForAgent(agentId: String) {
        momentumBuffer.keys.removeAll { it.startsWith("${agentId}:") }
        gradientHistory.keys.removeAll { it.startsWith("${agentId}:") }
    }
}
