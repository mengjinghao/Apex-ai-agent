package com.apex.util

data class PruningResult(
    val prunedMessages: List<Message>,
    val removedCount: Int,
    val savedTokens: Int,
    val pruningRatio: Float
)

class ContextPruningManager(
    private val minContextMessages: Int = 3,
    private val importanceThreshold: Float = 0.5f
) {
    private val importanceScorer = SemanticImportanceScorer()

    fun pruneContext(
        messages: List<Message>,
        currentInput: String,
        maxTokens: Int? = null,
        estimatedTokensPerMessage: Int = 150
    ): PruningResult {
        if (messages.isEmpty()) {
            return PruningResult(emptyList(), 0, 0, 0f)
        }

        val scoredMessages = importanceScorer.scoreMessages(messages)

        val (systemMessages, nonSystemMessages) = scoredMessages.partition {
            it.first.role.lowercase() == "system"
        }

        val prunedNonSystem = pruneByImportance(
            nonSystemMessages,
            currentInput,
            maxTokens,
            estimatedTokensPerMessage
        )

        val resultMessages = systemMessages.map { it.first } + prunedNonSystem

        val originalCount = messages.size
        val removedCount = originalCount - resultMessages.size
        val savedTokens = removedCount * estimatedTokensPerMessage
        val pruningRatio = if (originalCount > 0) removedCount.toFloat() / originalCount else 0f

        return PruningResult(
            prunedMessages = resultMessages,
            removedCount = removedCount,
            savedTokens = savedTokens,
            pruningRatio = pruningRatio
        )
    }

    private fun pruneByImportance(
        scoredMessages: List<Pair<Message, MessageImportanceScore>>,
        currentInput: String,
        maxTokens: Int?,
        estimatedTokensPerMessage: Int
    ): List<Message> {
        if (scoredMessages.isEmpty()) return emptyList()

        val sortedByRecent = scoredMessages.sortedByDescending { scored ->
            scoredMessages.indexOf(scored)
        }

        val messagesAboveThreshold = sortedByRecent.filter { it.second.score >= importanceThreshold }

        val selectedFromThreshold = if (messagesAboveThreshold.size >= minContextMessages) {
            messagesAboveThreshold
        } else {
            val allSorted = sortedByRecent.sortedByDescending { it.second.score }
            allSorted.take(maxOf(minContextMessages, (scoredMessages.size * 0.5).toInt()))
        }

        val tokenBudget = maxTokens?.let { it - estimateCurrentInputTokens(currentInput) }
            ?: Int.MAX_VALUE

        var result = selectedFromThreshold.map { it.first }.toMutableList()

        if (result.size > minContextMessages && tokenBudget != Int.MAX_VALUE) {
            var currentTokens = result.sumOf { estimateMessageTokens(it, estimatedTokensPerMessage) }
            val scoreIndex = scoredMessages.associate { it.first to it.second.score }

            while (result.size > minContextMessages && currentTokens > tokenBudget) {
                val lowestScoreIndex = result.indices
                    .filter { result[it].role.lowercase() != "user" }
                    .minByOrNull { scoreIndex[result[it]] ?: 0f } ?: break

                val removed = result.removeAt(lowestScoreIndex)
                currentTokens -= estimateMessageTokens(removed, estimatedTokensPerMessage)
            }
        }

        return result.ifEmpty {
            sortedByRecent.takeLast(minContextMessages).map { it.first }
        }
    }

    private fun estimateCurrentInputTokens(input: String): Int {
        return ChatUtils.estimateTokenCount(input)
    }

    private fun estimateMessageTokens(message: Message, baseEstimate: Int): Int {
        val contentTokens = ChatUtils.estimateTokenCount(message.content)
        return maxOf(contentTokens, baseEstimate / 2)
    }

    fun quickPrune(
        messages: List<Message>,
        minKeep: Int = 3
    ): List<Message> {
        if (messages.size <= minKeep) return messages

        val scoredMessages = importanceScorer.scoreMessages(messages)
        val sortedByScore = scoredMessages.sortedByDescending { it.second.score }

        val keepCount = maxOf(minKeep, (messages.size * 0.3).toInt())
        val toKeep = sortedByScore.take(keepCount).map { it.first }

        val result = messages.filter { msg -> toKeep.contains(msg) }
            .let { kept ->
                val keptSet = kept.toSet()
                val notKept = messages.filter { it !in keptSet }
                val scoreMap = scoredMessages.associate { it.first to it.second.score }
                val sortedNotKept = notKept.sortedByDescending { scoreMap[it] ?: 0f }
                kept + sortedNotKept.takeLast(messages.size - kept.size)
            }

        return result.take(messages.size - (messages.size - minKeep))
    }

    fun getPruningStats(messages: List<Message>): Map<String, Any> {
        if (messages.isEmpty()) {
            return mapOf(
                "total" to 0,
                "averageScore" to 0f,
                "highValueCount" to 0,
                "lowValueCount" to 0,
                "estimatedTokens" to 0
            )
        }

        val scoredMessages = importanceScorer.scoreMessages(messages)
        val scores = scoredMessages.map { it.second.score }

        return mapOf(
            "total" to messages.size,
            "averageScore" to scores.average().toFloat(),
            "highValueCount" to scores.count { it >= importanceThreshold },
            "lowValueCount" to scores.count { it < importanceThreshold },
            "estimatedTokens" to messages.sumOf { ChatUtils.estimateTokenCount(it.content) },
            "systemMessages" to messages.count { it.role.lowercase() == "system" },
            "userMessages" to messages.count { it.role.lowercase() == "user" },
            "assistantMessages" to messages.count { it.role.lowercase() == "assistant" }
        )
    }
}
