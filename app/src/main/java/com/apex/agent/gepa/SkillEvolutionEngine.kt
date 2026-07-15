package com.apex.gepa

import com.apex.agent.SubTask
import com.apex.data.gepa.SkillDao
import com.apex.data.gepa.SkillTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class SkillEvolutionEngine(
    private val skillDao: SkillDao
) {

    private val config = EvolutionConfig()

    suspend fun evolveSkill(skillId: Int, executionFeedback: ExecutionFeedback): EvolutionResult =
        withContext(Dispatchers.IO) {
            val skill = skillDao.getSkillById(skillId)
                ?: return@withContext EvolutionResult(false, "Skill not found")
        val evolvedSubtasks = evolveSubtasks(skill.subtaskStructure, executionFeedback)
        val evolvedSkill = skill.copy(
                subtaskStructure = evolvedSubtasks,
                version = skill.version + 1,
                updatedAt = System.currentTimeMillis()
            )

            skillDao.updateSkill(evolvedSkill)

            EvolutionResult(
                success = true,
                message = "Skill evolved from version ${skill.version} to ${evolvedSkill.version}",
                newVersion = evolvedSkill.version,
                improvementScore = calculateImprovement(skill, evolvedSkill, executionFeedback)
            )
        }
        private fun evolveSubtasks(subtaskJson: String, feedback: ExecutionFeedback): String {
        try {
            val subtasks = parseSubtasks(subtaskJson)
        if (subtasks.isEmpty()) return subtaskJson

            val population = generateInitialPopulation(subtasks, feedback)

            repeat(config.generations) { generation ->
                val fitnessScores = population.map { Individual(it, calculateFitness(it, feedback)) }
        val sorted = fitnessScores.sortedByDescending { it.fitness }
        if (sorted.first().fitness >= config.targetFitness) {
                    return serializeSubtasks(sorted.first().chromosome)
                }
        val selected = selection(sorted)
        val offspring = crossover(selected)
        val mutated = mutation(offspring)

                population.clear()
                population.addAll(mutated)
            }
        val bestIndividual = population
                .map { Individual(it, calculateFitness(it, feedback)) }
                .maxByOrNull { it.fitness }
        return if (bestIndividual != null && bestIndividual.fitness > calculateFitness(subtasks, feedback)) {
                serializeSubtasks(bestIndividual.chromosome)
            } else {
                subtaskJson
            }
        } catch (e: Exception) {
            GepaLogger.e("Failed to evolve subtasks", e, tag = "SkillEvolution")
        return subtaskJson
        }
    }
        private fun parseSubtasks(json: String): List<SubTaskGene> {
        return try {
            if (json.isBlank() || json == "[]") return emptyList()
        val regex = """\{[^}]+\}""".toRegex()
            regex.findAll(json).map { match ->
                parseSubtaskGene(match.value)
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
        private fun parseSubtaskGene(json: String): SubTaskGene {
        return SubTaskGene(
            taskType = extractJsonField(json, "taskType") ?: "general",
            description = extractJsonField(json, "description") ?: "",
            priority = extractJsonField(json, "priority")?.toIntOrNull() ?: 0,
            estimatedTime = extractJsonField(json, "estimatedTime")?.toLongOrNull() ?: 60000
        )
    }
        private fun extractJsonField(json: String, field: String): String? {
        val regex = """"${field}"\s*:\s*"?([^",}]*)"?""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.trim()
    }
        private fun serializeSubtasks(genes: List<SubTaskGene>): String {
        return genes.joinToString(",", "[", "]") { gene ->
            """{"taskType":"${gene.taskType}","description":"${gene.description}","priority":${gene.priority},"estimatedTime":${gene.estimatedTime}}"""
        }
    }
        private fun generateInitialPopulation(baseSubtasks: List<SubTaskGene>, feedback: ExecutionFeedback): MutableList<List<SubTaskGene>> {
        val population = mutableListOf<List<SubTaskGene>>()
        population.add(baseSubtasks)

        repeat(config.populationSize - 1) {
            val mutated = baseSubtasks.map { gene ->
                if (Random.nextFloat() < config.mutationRate) {
                    mutateGene(gene, feedback)
                } else {
                    gene
                }
            }
            population.add(mutated)
        }
        return population
    }
        private fun mutateGene(gene: SubTaskGene, feedback: ExecutionFeedback): SubTaskGene {
        val mutationType = Random.nextInt(4)
        return when (mutationType) {
            0 -> gene.copy(priority = gene.priority + Random.nextInt(-2, 3))
            1 -> gene.copy(estimatedTime = max(1000, gene.estimatedTime + Random.nextLong(-10000, 10001)))
            2 -> gene.copy(taskType = getRandomTaskType())
            3 -> gene
            else -> gene
        }
    }
        private fun getRandomTaskType(): String {
        val types = listOf("FileTask", "SearchTask", "DataTask", "WritingTask", "general")
        return types[Random.nextInt(types.size)]
    }
        private fun calculateFitness(chromosome: List<SubTaskGene>, feedback: ExecutionFeedback): Double {
        if (chromosome.isEmpty()) return 0.0

        var fitness = 0.0

        val successWeight = 0.4
        val timeWeight = 0.3
        val structureWeight = 0.3

        if (feedback.successCount > 0 && feedback.totalCount > 0) {
            fitness += (feedback.successCount.toFloat() / feedback.totalCount) * successWeight
        }
        val avgTime = chromosome.map { it.estimatedTime }.average()
        val timeScore = if (avgTime > 0) {
            max(0.0, 1.0 - (avgTime / config.maxTaskDurationMs))
        } else 0.5
        fitness += timeScore * timeWeight

        val structureScore = calculateStructureScore(chromosome)
        fitness += structureScore * structureWeight

        return fitness
    }
        private fun calculateStructureScore(chromosome: List<SubTaskGene>): Double {
        if (chromosome.isEmpty()) return 0.0

        val priorityVariety = chromosome.map { it.priority }.distinct().size.toDouble() / chromosome.size
        val typeVariety = chromosome.map { it.taskType }.distinct().size.toDouble() / chromosome.size

        return (priorityVariety + typeVariety) / 2.0
    }
        private fun selection(sortedPopulation: List<Individual>): List<List<SubTaskGene>> {
        val selected = mutableListOf<List<SubTaskGene>>()
        val tournamentSize = max(2, config.populationSize / 5)

        repeat(config.populationSize) {
            val tournament = sortedPopulation.shuffled().take(tournamentSize)
            selected.add(tournament.maxByOrNull { it.fitness }!!.chromosome)
        }
        return selected
    }
        private fun crossover(population: List<List<SubTaskGene>>): List<List<SubTaskGene>> {
        val offspring = mutableListOf<List<SubTaskGene>>()
        for (i in population.indices step 2) {
            val parent1 = population.getOrElse(i) { population.last() }
        val parent2 = population.getOrElse(i + 1) { parent1 }
        if (Random.nextFloat() < config.crossoverRate) {
                val crossPoint = min(parent1.size, parent2.size).coerceAtLeast(1)
        val child1 = parent1.take(crossPoint) + parent2.drop(crossPoint)
        val child2 = parent2.take(crossPoint) + parent1.drop(crossPoint)
                offspring.add(child1)
                offspring.add(child2)
            } else {
                offspring.add(parent1)
                offspring.add(parent2)
            }
        }
        return offspring
    }
        private fun mutation(population: List<List<SubTaskGene>>): List<List<SubTaskGene>> {
        return population.map { chromosome ->
            chromosome.map { gene ->
                if (Random.nextFloat() < config.mutationRate) {
                    mutateGene(gene, ExecutionFeedback())
                } else {
                    gene
                }
            }
        }
    }
        private fun calculateImprovement(original: SkillTemplate, evolved: SkillTemplate, feedback: ExecutionFeedback): Float {
        val originalFitness = calculateFitness(parseSubtasks(original.subtaskStructure), feedback)
        val evolvedFitness = calculateFitness(parseSubtasks(evolved.subtaskStructure), feedback)
        return ((evolvedFitness - originalFitness) / originalFitness * 100).toFloat()
    }

    suspend fun mergeSimilarSkills(taskType: String, similarityThreshold: Float = 0.8f): MergeResult =
        withContext(Dispatchers.IO) {
            val skills = skillDao.getSkillsByType(taskType).first()
        if (skills.size < 2) {
                return@withContext MergeResult(0, "Not enough skills to merge")
            }
        var mergedCount = 0

            val groupedSkills = skills.groupBy { skill ->
                val subtasks = parseSubtasks(skill.subtaskStructure)
                subtasks.map { it.taskType }.joinToString(",")
            }

            groupedSkills.values.forEach { group ->
                if (group.size > 1) {
                    val bestSkill = group.maxByOrNull { it.successRate } ?: return@forEach

                    val others = group.filter { it.id != bestSkill.id }

                    others.forEach { skill ->
                        val mergedExecutions = bestSkill.totalExecutions + skill.totalExecutions
                        val mergedSuccesses = bestSkill.successfulExecutions + skill.successfulExecutions
                        val newSuccessRate = if (mergedExecutions > 0) {
                            mergedSuccesses.toFloat() / mergedExecutions
                        } else bestSkill.successRate

                        skillDao.updateSkillStats(
                            id = bestSkill.id,
                            successRate = newSuccessRate,
                            totalExecutions = mergedExecutions,
                            successfulExecutions = mergedSuccesses
                        )

                        skillDao.setSkillActive(skill.id, false)
                        mergedCount++
                    }
                }
            }

            MergeResult(mergedCount, "Merged ${mergedCount} skills")
        }

    data class SubTaskGene(
        val taskType: String,
        val description: String,
        val priority: Int,
        val estimatedTime: Long
    )

    data class Individual(
        val chromosome: List<SubTaskGene>,
        val fitness: Double
    )

    data class EvolutionConfig(
        val populationSize: Int = 20,
        val generations: Int = 50,
        val mutationRate: Float = 0.15f,
        val crossoverRate: Float = 0.8f,
        val targetFitness: Double = 0.9,
        val maxTaskDurationMs: Long = 600000
    )

    data class EvolutionResult(
        val success: Boolean,
        val message: String,
        val newVersion: Int = 0,
        val improvementScore: Float = 0f
    )

    data class ExecutionFeedback(
        val totalCount: Int = 0,
        val successCount: Int = 0,
        val failedCount: Int = 0,
        val averageTimeMs: Long = 0,
        val failedTaskTypes: List<String> = emptyList()
    )

    data class MergeResult(
        val mergedCount: Int,
        val message: String
    )
}
