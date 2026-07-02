package com.apex.agent.core.rl

import android.content.Context
import com.apex.agent.R
import com.apex.agent.core.storage.BatchRunEntity
import com.apex.agent.core.storage.RLTrajectoryEntity
import com.apex.agent.core.storage.SessionDatabase
import com.apex.agent.data.repository.MemoryRepository
import com.apex.agent.util.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.exp
import kotlin.math.max

class ReinforcementLearningEngine(
    private val context: Context,
    private val memoryRepository: MemoryRepository
) {

    companion object {
        private const val TAG = "ReinforcementLearningEngine"
        private const val DEFAULT_LEARNING_RATE = 0.1
        private const val DEFAULT_DISCOUNT_FACTOR = 0.99
        private const val DEFAULT_EPSILON = 0.1
        private const val MIN_EPSILON = 0.01
        private const val EPSILON_DECAY = 0.995
        private const val MAX_EPISODES_IN_MEMORY = 1000

        private const val RL_EPISODES_BATCH_RUN_ID = "rl_episodes"
        private const val EPISODE_ACTION_PREFIX = "episode:"
    }

    private var qTable = mutableMapOf<String, MutableMap<String, QValue>>()
    private var policies = mutableMapOf<String, Policy>()
    private var currentEpisode: MutableList<Transition> = mutableListOf()
    private var currentTaskId: String? = null
    private val episodesHistory = mutableListOf<Episode>()

    private var learningRate = DEFAULT_LEARNING_RATE
    private var discountFactor = DEFAULT_DISCOUNT_FACTOR
    private var epsilon = DEFAULT_EPSILON

    private val gson = Gson()
    private var episodesLoaded = false

    private val rng = Random()

    suspend fun startEpisode(taskId: String) = withContext(Dispatchers.IO) {
        currentTaskId = taskId
        currentEpisode.clear()
        AppLogger.d(TAG, "Started new episode for task: ${taskId}")
    }

    suspend fun selectAction(state: State, possibleActions: List<Action>): Action = withContext(Dispatchers.IO) {
        if (possibleActions.isEmpty()) {
            throw IllegalArgumentException(context.getString(R.string.error_no_possible_actions))
        }

        val stateKey = stateToKey(state)

        if (rng.nextDouble() < epsilon) {
            val randomAction = possibleActions.random()
            AppLogger.d(TAG, "Exploring: ${randomAction.type}")
            return@withContext randomAction
        }

        val qValues = getQValuesForState(stateKey)
        
        if (qValues.isEmpty()) {
            initializeQValues(stateKey, possibleActions)
            return@withContext possibleActions.random()
        }

        val bestActionKey = qValues.maxByOrNull { it.value.value }?.key
        val bestAction = possibleActions.find { actionToKey(it) == bestActionKey }
        
        if (bestAction != null) {
            AppLogger.d(TAG, "Exploiting: ${bestAction.type}")
            return@withContext bestAction
        }

        possibleActions.random()
    }

    suspend fun learn(
        state: State,
        action: Action,
        nextState: State,
        reward: Reward,
        done: Boolean
    ) = withContext(Dispatchers.IO) {
        val stateKey = stateToKey(state)
        val actionKey = actionToKey(action)
        val nextStateKey = stateToKey(nextState)

        val transition = Transition(state, action, nextState, reward, done)
        currentEpisode.add(transition)

        val currentQValue = getQValue(stateKey, actionKey)
        val maxNextQValue = getMaxQValue(nextStateKey)

        val targetValue = if (done) {
            reward.value
        } else {
            reward.value + discountFactor * maxNextQValue
        }

        val delta = targetValue - currentQValue
        val newQValue = currentQValue + learningRate * delta

        updateQValue(stateKey, actionKey, newQValue)

        AppLogger.d(TAG, "Updated Q-value: ${stateKey} -> ${actionKey} = ${newQValue} (delta: ${delta})")

        if (done) {
            finishEpisode(reward.value, reward.value > 0)
        }
    }

    private fun getQValuesForState(stateKey: String): Map<String, QValue> {
        return qTable.getOrDefault(stateKey, mutableMapOf())
    }

    private fun getQValue(stateKey: String, actionKey: String): Double {
        return qTable[stateKey]?.get(actionKey)?.value ?: 0.0
    }

    private fun getMaxQValue(stateKey: String): Double {
        return qTable[stateKey]?.values?.maxOfOrNull { it.value } ?: 0.0
    }

    private fun updateQValue(stateKey: String, actionKey: String, value: Double) {
        val stateMap = qTable.getOrPut(stateKey) { mutableMapOf() }
        
        val existingQValue = stateMap[actionKey]
        val visits = existingQValue?.visits ?: 0
        
        stateMap[actionKey] = QValue(
            stateKey = stateKey,
            actionKey = actionKey,
            value = value,
            visits = visits + 1,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun initializeQValues(stateKey: String, actions: List<Action>) {
        val stateMap = qTable.getOrPut(stateKey) { mutableMapOf() }
        
        actions.forEach { action ->
            val actionKey = actionToKey(action)
            if (stateMap[actionKey] == null) {
                stateMap[actionKey] = QValue(
                    stateKey = stateKey,
                    actionKey = actionKey,
                    value = 0.0
                )
            }
        }
    }

    private fun stateToKey(state: State): String {
        return state.features.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }
    }

    private fun actionToKey(action: Action): String {
        return "${action.type.name}:${action.description.take(50)}"
    }

    private suspend fun finishEpisode(totalReward: Double, success: Boolean) = withContext(Dispatchers.IO) {
        val episode = Episode(
            taskId = currentTaskId ?: context.getString(R.string.rl_default_task_id),
            transitions = currentEpisode.toList(),
            totalReward = totalReward,
            success = success,
            durationMs = System.currentTimeMillis() - (currentEpisode.firstOrNull()?.state?.let { 0L } ?: 0L)
        )

        // 维护内存中的 episodes 历史列表
        episodesHistory.add(episode)
        if (episodesHistory.size > MAX_EPISODES_IN_MEMORY) {
            episodesHistory.removeAt(0)
        }

        saveEpisode(episode)
        
        epsilon = max(MIN_EPSILON, epsilon * EPSILON_DECAY)
        
        AppLogger.d(TAG, "Episode finished. Reward: ${totalReward}, Success: ${success}, Epsilon: ${epsilon}")
        
        currentEpisode.clear()
        currentTaskId = null
    }

    private suspend fun saveEpisode(episode: Episode) = withContext(Dispatchers.IO) {
        val database = SessionDatabase.getInstance(context)
        ensureDefaultBatchRun(database)

        val entity = RLTrajectoryEntity(
            id = episode.id,
            batchRunId = RL_EPISODES_BATCH_RUN_ID,
            stepIndex = episode.transitions.size,
            state = gson.toJson(episode),
            action = "${EPISODE_ACTION_PREFIX}${episode.id}",
            reward = episode.totalReward,
            nextState = "",
            isDone = true,
            createdAt = episode.timestamp
        )

        try {
            database.rlTrajectoryDao().insertTrajectory(entity)
            AppLogger.d(TAG, "Episode persisted to RL trajectory storage: ${episode.id}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist episode ${episode.id} to RL trajectory storage", e)
            throw IllegalStateException(context.getString(R.string.error_persist_rl_episode, episode.id), e)
        }
    }

    private suspend fun ensureDefaultBatchRun(database: SessionDatabase) = withContext(Dispatchers.IO) {
        val batchRunDao = database.batchRunDao()
        val existing = batchRunDao.getBatchRunById(RL_EPISODES_BATCH_RUN_ID)
        if (existing == null) {
            batchRunDao.insertBatchRun(
                BatchRunEntity(
                    id = RL_EPISODES_BATCH_RUN_ID,
                    batchRunId = RL_EPISODES_BATCH_RUN_ID,
                    taskDescription = context.getString(R.string.rl_episode_storage_description),
                    status = context.getString(R.string.rl_status_running),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun loadEpisodesFromStorage(): List<Episode> = withContext(Dispatchers.IO) {
        val database = SessionDatabase.getInstance(context)
        val trajectories = try {
            database.rlTrajectoryDao().getAllTrajectories()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load persisted episodes", e)
            throw IllegalStateException(context.getString(R.string.error_load_rl_episodes), e)
        }

        trajectories
            .filter { it.action.startsWith(EPISODE_ACTION_PREFIX) }
            .mapNotNull { entity ->
                try {
                    gson.fromJson(entity.state, Episode::class.java)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to deserialize persisted episode ${entity.id}", e)
                    null
                }
            }
            .sortedBy { it.timestamp }
    }

    suspend fun createPolicy(name: String): Policy = withContext(Dispatchers.IO) {
        val policy = Policy(name = name)
        policies[policy.id] = policy
        updatePolicyFromQTable(policy.id)
        policy
    }

    private fun updatePolicyFromQTable(policyId: String) {
        val policy = policies[policyId] ?: return
        
        val stateActions = mutableMapOf<String, List<ActionProbability>>()
        
        qTable.forEach { (stateKey, actionQValues) ->
            if (actionQValues.isNotEmpty()) {
                val bestActionKey = actionQValues.maxBy { it.value.value }.key
                val probabilities = actionQValues.entries.map { entry ->
                    ActionProbability(
                        actionKey = entry.key,
                        probability = if (entry.key == bestActionKey) 0.9 else 0.1 / (actionQValues.size - 1)
                    )
                }
                stateActions[stateKey] = probabilities
            }
        }
        
        policies[policyId] = policy.copy(
            stateActions = stateActions,
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun getPolicy(policyId: String): Policy? = withContext(Dispatchers.IO) {
        policies[policyId]
    }

    suspend fun updatePolicy(policyId: String, updates: List<QValueUpdate>) = withContext(Dispatchers.IO) {
        updates.forEach { update ->
            val stateMap = qTable.getOrPut(update.stateKey) { mutableMapOf() }
            stateMap[update.actionKey] = QValue(
                stateKey = update.stateKey,
                actionKey = update.actionKey,
                value = update.newValue,
                lastUpdated = System.currentTimeMillis()
            )
        }
        updatePolicyFromQTable(policyId)
    }

    suspend fun evaluatePolicy(policyId: String, testEpisodes: Int): TrainingStats = withContext(Dispatchers.IO) {
        // 获取策略
        val policy = policies[policyId]
        if (policy == null) {
            AppLogger.w(TAG, "Policy not found: ${policyId}, returning default stats")
            return@withContext TrainingStats(
                episodesTrained = 0,
                averageReward = 0.0,
                successRate = 0.0,
                epsilon = epsilon,
                learningRate = learningRate,
                discountFactor = discountFactor
            )
        }

        AppLogger.d(TAG, "Evaluating policy: ${policy.name} (id: ${policyId})")

        // 获取最近的 episodes 进行评估（包含持久化加载的历史记录）
        val recentEpisodes = getAllEpisodes().takeLast(testEpisodes.coerceAtLeast(1))

        if (recentEpisodes.isEmpty()) {
            AppLogger.d(TAG, "No episodes available for evaluation")
            return@withContext TrainingStats(
                episodesTrained = 0,
                averageReward = 0.0,
                successRate = 0.0,
                epsilon = epsilon,
                learningRate = learningRate,
                discountFactor = discountFactor
            )
        }

        // 计算统计信息
        val successCount = recentEpisodes.count { it.success }
        val avgReward = recentEpisodes.map { it.totalReward }.average()
        val totalDuration = recentEpisodes.map { it.durationMs }.average()

        // 计算策略覆盖的状态数
        val statesCovered = policy.stateActions.size

        AppLogger.d(TAG, "Policy evaluation complete: ${recentEpisodes.size} episodes, " +
                "success rate: ${successCount.toDouble() / recentEpisodes.size}, " +
                "avg reward: ${avgReward}")

        TrainingStats(
            episodesTrained = recentEpisodes.size,
            averageReward = avgReward,
            successRate = successCount.toDouble() / recentEpisodes.size,
            epsilon = epsilon,
            learningRate = learningRate,
            discountFactor = discountFactor
        )
    }

    suspend fun getTrainingStats(): TrainingStats = withContext(Dispatchers.IO) {
        val episodes = getAllEpisodes()
        val successCount = episodes.count { it.success }
        val avgReward = episodes.map { it.totalReward }.average()
        
        TrainingStats(
            episodesTrained = episodes.size,
            averageReward = avgReward,
            successRate = if (episodes.isEmpty()) 0.0 else successCount.toDouble() / episodes.size,
            epsilon = epsilon,
            learningRate = learningRate,
            discountFactor = discountFactor
        )
    }

    suspend fun getAllEpisodes(): List<Episode> = withContext(Dispatchers.IO) {
        if (!episodesLoaded) {
            val persistedEpisodes = loadEpisodesFromStorage()
            val existingIds = episodesHistory.map { it.id }.toSet()
            persistedEpisodes
                .filter { it.id !in existingIds }
                .forEach { episodesHistory.add(it) }
            episodesLoaded = true
        }
        episodesHistory.toList()
    }

    /**
     * 强制从持久化存储重新加载历史 episodes�?
     */
    suspend fun reloadEpisodesFromStorage() = withContext(Dispatchers.IO) {
        episodesLoaded = false
        getAllEpisodes()
    }

    fun setHyperparameters(
        learningRate: Double = this.learningRate,
        discountFactor: Double = this.discountFactor,
        epsilon: Double = this.epsilon
    ) {
        this.learningRate = learningRate.coerceIn(0.0, 1.0)
        this.discountFactor = discountFactor.coerceIn(0.0, 1.0)
        this.epsilon = epsilon.coerceIn(0.0, 1.0)
    }

    fun reset() {
        qTable.clear()
        policies.clear()
        currentEpisode.clear()
        currentTaskId = null
        episodesHistory.clear()
        episodesLoaded = false
        epsilon = DEFAULT_EPSILON
    }

    fun getQTableSize(): Int {
        return qTable.values.sumOf { it.size }
    }
}

class RewardCalculator(private val context: Context) {

    fun calculateReward(
        taskType: String,
        success: Boolean,
        durationMs: Long,
        resourceUsage: Double,
        qualityScore: Double
    ): Reward {
        var totalReward = 0.0
        var rewardType = RewardType.PROGRESS

        if (success) {
            totalReward += 100.0
            rewardType = RewardType.SUCCESS
            
            val timeBonus = calculateTimeBonus(durationMs)
            totalReward += timeBonus
            
            val resourceBonus = calculateResourceBonus(resourceUsage)
            totalReward += resourceBonus
            
            val qualityBonus = qualityScore * 20
            totalReward += qualityBonus
        } else {
            totalReward -= 50.0
            rewardType = RewardType.FAILURE
        }

        val reason = when {
            success -> context.getString(R.string.reward_task_completed)
            else -> context.getString(R.string.reward_task_failed)
        }

        return Reward(
            value = totalReward,
            type = rewardType,
            reason = reason
        )
    }

    private fun calculateTimeBonus(durationMs: Long): Double {
        val optimalTime = 30000.0
        if (durationMs <= optimalTime) return 20.0
        
        val excessRatio = (durationMs - optimalTime) / optimalTime
        return max(-10.0, 20.0 - excessRatio * 15)
    }

    private fun calculateResourceBonus(resourceUsage: Double): Double {
        return if (resourceUsage < 0.5) {
            10.0
        } else if (resourceUsage < 0.8) {
            5.0
        } else {
            -5.0
        }
    }

    fun calculateIntermediateReward(progress: Double): Reward {
        val reward = progress * 10
        return Reward(
            value = reward,
            type = RewardType.PROGRESS,
            reason = context.getString(R.string.reward_progress_format, progress * 100)
        )
    }
}