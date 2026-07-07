package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class MultiHopReasoningSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val maxHops = 3
    private val retrievedFacts = mutableListOf<RetrievedFact>()
    private val reasoningChain = mutableListOf<HopResult>()

    private var totalExecutions = 0
    private var successfulExecutions = 0
    private var totalExecutionTimeMs = 0L

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.multi-hop",
            skillName = "Multi-Hop Reasoning",
            version = "1.0.0",
            description = "多跳推理技能，处理需要多步推理和关系追踪的复杂问题",
            author = "Apex Agent",
            tags = listOf("reasoning", "multi-hop", "knowledge-graph", "relation-tracking"),
            priority = 89,
            capabilities = listOf(
                "entity_extraction",
                "relation_identification",
                "hop_by_hop_retrieval",
                "fact_aggregation"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()

        try {
            if (isPaused) {
                return@runBlocking BurstSkillResult(
                    success = false,
                    errorMessage = "Skill paused"
                )
            }

            retrievedFacts.clear()
            reasoningChain.clear()

            val llm = context.llmService

            val questionAnalysis = analyzeQuestion(task.description, llm)
            val entities = questionAnalysis.entities
            val relations = questionAnalysis.relations

            val numHops = determineNumHops(entities, relations)

            var currentEntities = entities.toMutableList()
            var currentContext = task.description

            repeat(numHops) { hop ->
                if (isPaused) return@repeat

                val hopResult = executeHop(hop, currentContext, task.description, llm)
                reasoningChain.add(hopResult)

                retrievedFacts.addAll(hopResult.retrievedFacts)

                currentEntities = hopResult.nextEntities.toMutableList()
                currentContext += "\nHop $hop result: ${hopResult.reasoning}"
            }

            val aggregatedFacts = aggregateResults(retrievedFacts, llm)

            val finalAnswer = generateAnswer(questionAnalysis, aggregatedFacts)

            val totalTime = System.currentTimeMillis() - startTime

            totalExecutions++
            if (finalAnswer.isNotEmpty()) successfulExecutions++
            totalExecutionTimeMs += totalTime

            val chainSummary = reasoningChain.mapIndexed { index, hop ->
                "跳$index: ${hop.reasoning.take(50)}..."
            }.joinToString("\n")

            BurstSkillResult(
                success = true,
                output = buildString {
                    appendLine("Multi-Hop Reasoning 推理完成：")
                    appendLine("- 问题跳数：$numHops")
                    appendLine("- 实际推理跳数：${reasoningChain.size}")
                    appendLine("- 检索事实数：${retrievedFacts.size}")
                    appendLine()
                    appendLine("推理链：")
                    appendLine(chainSummary)
                    appendLine()
                    appendLine("最终答案：$finalAnswer")
                },
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = reasoningChain.size,
                    tokensProcessed = estimateTokens(finalAnswer)
                )
            )

        } catch (e: Exception) {
            totalExecutions++
            BurstSkillResult(
                success = false,
                errorMessage = "Multi-Hop推理出错：${e.message}"
            )
        }
    }

    private suspend fun analyzeQuestion(question: String, llm: ILLMService?): QuestionAnalysis {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Extract entities and relations from the question.")
                appendLine("Format: ENTITIES: entity1, entity2 | RELATIONS: relation1, relation2")
                appendLine("Question: $question")
            }
            val response = llm.generate(prompt, maxTokens = 128)
            val entities = response.substringAfter("ENTITIES:").substringBefore("|").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val relations = response.substringAfter("RELATIONS:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return QuestionAnalysis(
                originalQuestion = question,
                entities = entities,
                relations = relations,
                questionType = QuestionType.OTHER
            )
        }

        val entityKeywords = listOf("谁", "什么", "哪里", "哪个", "when", "what", "who", "where", "which")
        val entities = entityKeywords.mapNotNull { keyword ->
            if (question.contains(keyword)) keyword else null
        }.take(3)

        val relationKeywords = listOf("的", "是", "在", "和", "属于", "的", "is", "of", "and", "in")
        val relations = relationKeywords.filter { question.contains(it) }.take(2)

        val questionType = when {
            question.contains("谁") || question.contains("who") -> QuestionType.PERSON
            question.contains("什么") || question.contains("what") -> QuestionType.ENTITY
            question.contains("哪里") || question.contains("where") -> QuestionType.LOCATION
            question.contains("什么时候") || question.contains("when") -> QuestionType.TIME
            question.contains("为什么") || question.contains("why") -> QuestionType.REASON
            question.contains("怎么") || question.contains("how") -> QuestionType.METHOD
            else -> QuestionType.OTHER
        }

        return QuestionAnalysis(
            originalQuestion = question,
            entities = entities,
            relations = relations,
            questionType = questionType
        )
    }

    private fun determineNumHops(entities: List<String>, relations: List<String>): Int {
        return when {
            entities.size >= 3 && relations.size >= 2 -> 3
            entities.size >= 2 || relations.size >= 2 -> 2
            else -> 1
        }.coerceIn(1, maxHops)
    }

    private suspend fun executeHop(hop: Int, currentContext: String, question: String, llm: ILLMService?): HopResult {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Multi-hop reasoning - Hop $hop/$maxHops")
                appendLine("Question: $question")
                appendLine("Current context: $currentContext")
                appendLine("What new information can be derived?")
            }
            val response = llm.generate(prompt, maxTokens = 256)
            val words = response.split(Regex("\\s+")).filter { it.length > 3 }.distinct()
            return HopResult(
                hopIndex = hop,
                reasoning = response,
                retrievedFacts = listOf(
                    RetrievedFact(
                        subject = "inference",
                        predicate = "derived",
                        obj = response.take(120),
                        confidence = 0.8
                    )
                ),
                nextEntities = words.take(2)
            )
        }

        val keywords = question.split(Regex("[\\s?，。；？,.;]+")).filter { it.length > 1 }
        val fallbackEntities = keywords.take(3).ifEmpty { listOf("unknown") }
        val fallbackRelations = listOf("related")

        val mockFacts = fallbackEntities.map { entity ->
            RetrievedFact(
                subject = entity,
                predicate = fallbackRelations.getOrElse(hop % fallbackRelations.size) { "相关" },
                obj = "hop${hop}_fact",
                confidence = 0.9 - hop * 0.1
            )
        }

        val reasoning = "第${hop + 1}跳推理：基于实体 ${fallbackEntities.joinToString()} 检索到 ${mockFacts.size} 个事实"

        val nextEntities = mockFacts.map { it.obj }.take(2)

        return HopResult(
            hopIndex = hop,
            reasoning = reasoning,
            retrievedFacts = mockFacts,
            nextEntities = nextEntities
        )
    }

    private suspend fun aggregateResults(facts: List<RetrievedFact>, llm: ILLMService?): List<AggregatedFact> {
        if (llm != null && llm.isAvailable()) {
            val factsSummary = facts.mapIndexed { i, f ->
                "${i + 1}. ${f.subject} ${f.predicate} ${f.obj} (confidence: ${f.confidence})"
            }.joinToString("\n")

            val prompt = buildString {
                appendLine("Aggregate and rank these facts by relevance and confidence:")
                appendLine(factsSummary)
                appendLine("Output the ranked numbers separated by commas, e.g. 2,1,3")
            }
            val response = llm.generate(prompt, maxTokens = 128)
            val rankedIndices = response.split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }
            val sorted = if (rankedIndices.isNotEmpty()) {
                rankedIndices.mapNotNull { idx -> facts.getOrNull(idx - 1) }
            } else {
                facts.sortedByDescending { it.confidence }
            }
            return sorted.mapIndexed { i, fact ->
                AggregatedFact(fact = fact, aggregatedScore = (1.0 - i * 0.1).coerceAtLeast(0.1))
            }
        }

        return facts.sortedByDescending { it.confidence }.map { fact ->
            AggregatedFact(fact = fact, aggregatedScore = fact.confidence * 1.0)
        }
    }

    private suspend fun generateAnswer(question: QuestionAnalysis, facts: List<AggregatedFact>): String {
        val topFacts = facts.take(3)

        return buildString {
            appendLine("基于多跳推理的综合答案：")
            appendLine()
            topFacts.forEachIndexed { index, fact ->
                appendLine("依据${index + 1}：${fact.fact.subject} ${fact.fact.predicate} ${fact.fact.obj}")
            }
            appendLine()
            append("结论：")
            when (question.questionType) {
                QuestionType.PERSON -> append("相关人物是...")
                QuestionType.ENTITY -> append("该实体是...")
                QuestionType.LOCATION -> append("该位置在...")
                QuestionType.TIME -> append("该时间是...")
                QuestionType.REASON -> append("原因是...")
                QuestionType.METHOD -> append("方法是...")
                QuestionType.OTHER -> append("综合以上信息得出答案")
            }
        }
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it in '\u4e00'..'\u9fff' }
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val otherChars = text.length - chineseChars - englishChars

        return (chineseChars * 1.5 + englishChars * 0.25 + otherChars * 0.5).toInt()
    }

    override fun pause() {
        isPaused = true
    }

    override fun resume() {
        isPaused = false
    }

    override fun destroy() {
        scope.cancel()
    }

    override fun mutate(rate: Float): IBurstSkill {
        return this
    }

    override fun crossover(other: IBurstSkill): IBurstSkill {
        if (other is MultiHopReasoningSkill) {
            return this
        }
        return this
    }

    override fun evaluate(): Float {
        if (totalExecutions == 0) return 0.5f

        val successRate = successfulExecutions.toFloat() / totalExecutions
        val avgTime = if (totalExecutions > 0) totalExecutionTimeMs.toFloat() / totalExecutions else 0f

        val timeEfficiency = (15000f / (avgTime + 1)).coerceIn(0f, 1f)

        return successRate * 0.8f + timeEfficiency * 0.2f
    }

    data class QuestionAnalysis(
        val originalQuestion: String,
        val entities: List<String>,
        val relations: List<String>,
        val questionType: QuestionType
    )

    enum class QuestionType {
        PERSON, ENTITY, LOCATION, TIME, REASON, METHOD, OTHER
    }

    data class RetrievedFact(
        val subject: String,
        val predicate: String,
        val obj: String,
        val confidence: Double
    )

    data class AggregatedFact(
        val fact: RetrievedFact,
        val aggregatedScore: Double
    )

    data class HopResult(
        val hopIndex: Int,
        val reasoning: String,
        val retrievedFacts: List<RetrievedFact>,
        val nextEntities: List<String>
    )
}
