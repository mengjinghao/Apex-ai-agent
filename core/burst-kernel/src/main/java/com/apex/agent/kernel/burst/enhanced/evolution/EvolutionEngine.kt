package com.apex.agent.kernel.burst.enhanced.evolution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B5: 多阶段进化（Multi-Stage Evolution）
 *
 * 激活现有 GEPA 演化接口（mutate/crossover/evaluate 全空实现）：
 * - 演化阶段：CALM → TRAINING → EVOLVED → MASTER
 * - 基于历史评分用遗传算法筛选最优变种
 * - 每阶段 Skill 配置（参数/策略）不同
 */
class EvolutionEngine(
    private val populationSize: Int = 10,
    private val mutationRate: Float = 0.1f,
    private val eliteRatio: Float = 0.2f
) {

    /**
     * 演化阶段
     */
    enum class EvolutionStage {
        CALM,       // 平静：默认配置
        TRAINING,   // 训练：尝试变种
        EVOLVED,    // 进化：应用最优
        MASTER      // 大师：稳定最优
    }

    /**
     * 个体（Skill 配置变种）
     */
    data class Individual(
        val id: String,
        val skillId: String,
        val config: Map<String, Any>,     // 变种的配置
        val fitness: Float,               // 适应度评分
        val generation: Int,              // 第几代
        val parentIds: List<String> = emptyList()
    )

    /**
     * 演化统计
     */
    data class EvolutionStats(
        val generation: Int,
        val stage: EvolutionStage,
        val populationSize: Int,
        val bestFitness: Float,
        val avgFitness: Float,
        val totalEvaluations: Int,
        val improvementRate: Float         // 相比上一代的提升
    )

    /**
     * 演化记录
     */
    data class EvolutionRecord(
        val generation: Int,
        val timestamp: Long,
        val bestIndividual: Individual,
        val avgFitness: Float,
        val stage: EvolutionStage
    )

    // ============ 状态 ============

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val _stage = MutableStateFlow(EvolutionStage.CALM)
    val stage: StateFlow<EvolutionStage> = _stage.asStateFlow()

    private val _bestIndividual = MutableStateFlow<Individual?>(null)
    val bestIndividual: StateFlow<Individual?> = _bestIndividual.asStateFlow()

    private val _stats = MutableStateFlow(EvolutionStats(0, EvolutionStage.CALM, 0, 0f, 0f, 0, 0f))
    val stats: StateFlow<EvolutionStats> = _stats.asStateFlow()

    private val population = ConcurrentHashMap<String, Individual>()
    private val history = mutableListOf<EvolutionRecord>()
    private val skillEvaluations = ConcurrentHashMap<String, MutableList<Float>>()  // skillId -> 历史评分
    private var totalEvaluations = 0

    // ============ 公共 API ============

    /**
     * 注册初始种群
     */
    fun initializePopulation(skillId: String, baseConfig: Map<String, Any>) {
        population.clear()
        // 生成初始种群：baseConfig + 变种
        repeat(populationSize) { i ->
            val config = if (i == 0) baseConfig else mutateConfig(baseConfig, mutationRate * i / populationSize)
            val individual = Individual(
                id = "ind_${skillId}_gen0_$i",
                skillId = skillId,
                config = config,
                fitness = 0.5f,  // 初始评分
                generation = 0
            )
            population[individual.id] = individual
        }
        _stage.value = EvolutionStage.TRAINING
        _generation.value = 0
    }

    /**
     * 记录个体评分
     */
    fun recordEvaluation(individualId: String, fitness: Float) {
        val individual = population[individualId] ?: return
        val updated = individual.copy(fitness = fitness)
        population[individualId] = updated
        totalEvaluations++

        // 记录 skill 评分历史
        skillEvaluations.computeIfAbsent(individual.skillId) { mutableListOf() }.add(fitness)

        // 更新最佳
        val currentBest = _bestIndividual.value
        if (currentBest == null || fitness > currentBest.fitness) {
            _bestIndividual.value = updated
        }
    }

    /**
     * 进化到下一代
     */
    fun evolve() {
        if (population.isEmpty()) return

        val currentGen = _generation.value
        val individuals = population.values.toList().sortedByDescending { it.fitness }

        // 精英保留
        val eliteCount = (populationSize * eliteRatio).toInt().coerceAtLeast(1)
        val elites = individuals.take(eliteCount)

        // 生成下一代
        val newPopulation = mutableMapOf<String, Individual>()
        elites.forEach { elite ->
            newPopulation[elite.id] = elite.copy(generation = currentGen + 1)
        }

        // 交叉 + 变种填充
        while (newPopulation.size < populationSize) {
            val parent1 = elites.random()
            val parent2 = elites.random()
            val childConfig = crossoverConfig(parent1.config, parent2.config)
            val mutatedConfig = mutateConfig(childConfig, mutationRate)
            val child = Individual(
                id = "ind_${parent1.skillId}_gen${currentGen + 1}_${newPopulation.size}",
                skillId = parent1.skillId,
                config = mutatedConfig,
                fitness = 0.5f,
                generation = currentGen + 1,
                parentIds = listOf(parent1.id, parent2.id)
            )
            newPopulation[child.id] = child
        }

        // 更新种群
        population.clear()
        population.putAll(newPopulation)

        // 更新代数
        _generation.value = currentGen + 1

        // 计算统计
        val avgFitness = population.values.map { it.fitness }.average().toFloat()
        val bestFitness = population.values.maxOf { it.fitness }
        val previousAvg = history.lastOrNull()?.avgFitness ?: 0f
        val improvement = if (previousAvg > 0) (avgFitness - previousAvg) / previousAvg else 0f

        // 阶段转换
        updateStage(bestFitness, avgFitness, improvement)

        // 记录历史
        val record = EvolutionRecord(
            generation = currentGen + 1,
            timestamp = System.currentTimeMillis(),
            bestIndividual = requireNotNull(_bestIndividual.value),
            avgFitness = avgFitness,
            stage = _stage.value
        )
        history.add(record)
        while (history.size > 100) history.removeAt(0)

        // 更新统计
        _stats.value = EvolutionStats(
            generation = currentGen + 1,
            stage = _stage.value,
            populationSize = population.size,
            bestFitness = bestFitness,
            avgFitness = avgFitness,
            totalEvaluations = totalEvaluations,
            improvementRate = improvement
        )
    }

    /**
     * 获取最佳配置
     */
    fun getBestConfig(skillId: String): Map<String, Any>? {
        return population.values
            .filter { it.skillId == skillId }
            .maxByOrNull { it.fitness }
            ?.config
    }

    /**
     * 获取演化历史
     */
    fun getHistory(): List<EvolutionRecord> = history.toList()

    /**
     * 重置
     */
    fun reset() {
        population.clear()
        history.clear()
        _generation.value = 0
        _stage.value = EvolutionStage.CALM
        _bestIndividual.value = null
        totalEvaluations = 0
    }

    // ============ 内部方法 ============

    private fun updateStage(bestFitness: Float, avgFitness: Float, improvement: Float) {
        val newStage = when {
            bestFitness > 0.95f && improvement < 0.01f -> EvolutionStage.MASTER
            bestFitness > 0.85f -> EvolutionStage.EVOLVED
            _generation.value > 2 -> EvolutionStage.EVOLVED
            else -> EvolutionStage.TRAINING
        }
        _stage.value = newStage
    }

    private fun mutateConfig(config: Map<String, Any>, rate: Float): Map<String, Any> {
        val mutated = config.toMutableMap()
        for ((key, value) in config) {
            if (Math.random() < rate) {
                mutated[key] = when (value) {
                    is Int -> value + ((Math.random() * 4 - 2).toInt())
                    is Float -> value * (1 + (Math.random() * 0.4 - 0.2)).toFloat()
                    is Double -> value * (1 + Math.random() * 0.4 - 0.2)
                    is Boolean -> !value
                    is String -> value  // 字符串不变种
                    else -> value
                }
            }
        }
        return mutated
    }

    private fun crossoverConfig(config1: Map<String, Any>, config2: Map<String, Any>): Map<String, Any> {
        val child = mutableMapOf<String, Any>()
        val allKeys = config1.keys + config2.keys
        for (key in allKeys) {
            child[key] = if (Math.random() < 0.5) config1[key] ?: config2.getValue(key) else config2[key] ?: config1.getValue(key)
        }
        return child
    }
}
