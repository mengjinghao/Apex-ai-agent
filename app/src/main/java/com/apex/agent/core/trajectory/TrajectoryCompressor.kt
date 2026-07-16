package com.apex.agent.core.trajectory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

data class TrajectoryData(
    val id: String,
    val turns: List<TrajectoryTurn>,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun getTurnCount(): Int = turns.size
    fun getToolCallCount(): Int = turns.count { it.toolCall != null }
    fun getTokenCount(): Int = turns.sumOf { it.estimateTokenCount() }
}

data class TrajectoryTurn(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCall: ToolCallPair? = null
) {
    fun estimateTokenCount(): Int {
        return content.length / 4 + (toolCall?.estimateTokenCount() ?: 0)
    }
}

data class ToolCallPair(
    val call: ToolCall,
    val response: ToolResponse
) {
    fun estimateTokenCount(): Int {
        return call.estimateTokenCount() + response.estimateTokenCount()
    }
}

data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any>,
    val id: String
) {
    fun estimateTokenCount(): Int {
        return toolName.length + parameters.toString().length / 4
    }
}

data class ToolResponse(
    val toolCallId: String,
    val content: String,
    val status: String = "success"
) {
    fun estimateTokenCount(): Int {
        return content.length / 4
    }
}

data class CompressedRegion(
    val startIndex: Int,
    val endIndex: Int,
    val summary: String,
    val originalTokenCount: Int,
    val compressedTokenCount: Int
)

data class CompressionResult(
    val original: TrajectoryData,
    val compressed: TrajectoryData,
    val regions: List<CompressedRegion>,
    val originalTokenCount: Int,
    val compressedTokenCount: Int,
    val qualityReport: CompressionQualityReport
) {
    fun getCompressionRatio(): Double {
        return if (originalTokenCount > 0) {
            1.0 - (compressedTokenCount.toDouble() / originalTokenCount.toDouble())
        } else {
            0.0
        }
    }
}

data class TrajectoryPartition(
    val head: List<TrajectoryTurn>,
    val middle: List<TrajectoryTurn>,
    val tail: List<TrajectoryTurn>
)

data class TokenBudget(
    val total: Int,
    val headProtection: Int = 1024,
    val tailProtection: Int = 1024,
    val minimumMiddle: Int = 512
)

data class TrajectoryStats(
    val totalTurns: Int,
    val totalTokens: Int,
    val toolCallCount: Int,
    val avgTurnTokens: Double,
    val maxTurnTokens: Int
)

data class CompressionQualityReport(
    val preservedToolCalls: Int,
    val totalToolCalls: Int,
    val headPreserved: Boolean,
    val tailPreserved: Boolean,
    val summaryQuality: String,
    val warnings: List<String>
)

object CompressionStrategy {

    private val logger = LoggerFactory.getLogger(CompressionStrategy::class.java)

    fun initialize() {
        logger.info("CompressionStrategy initialized")
    }

    fun partitionTrajectory(trajectory: TrajectoryData): TrajectoryPartition {
        val turns = trajectory.turns
        
        if (turns.size <= 3) {
            return TrajectoryPartition(turns, emptyList(), emptyList())
        }

        val head = extractHead(turns)
        val tail = extractTail(turns.subList(head.size, turns.size))
        val middle = turns.subList(head.size, turns.size - tail.size)

        return TrajectoryPartition(head, middle, tail)
    }

    private fun extractHead(turns: List<TrajectoryTurn>): List<TrajectoryTurn> {
        val head = mutableListOf<TrajectoryTurn>()
        
        for (turn in turns) {
            head.add(turn)
            
            if (head.size >= 2 && 
                head.any { it.role == "system" } && 
                head.any { it.role == "user" }) {
                if (turn.toolCall != null) {
                    break
                }
            }
            
            if (head.size >= 5) break
        }
        
        return head
    }

    private fun extractTail(turns: List<TrajectoryTurn>): List<TrajectoryTurn> {
        val tail = mutableListOf<TrajectoryTurn>()
        val reversed = turns.reversed()
        
        for (turn in reversed) {
            tail.add(turn)
            
            if (turn.role == "assistant" && turn.toolCall == null) {
                break
            }
            
            if (tail.size >= 5) break
        }
        
        return tail.reversed()
    }

    fun calculateCompressionNeeded(
        trajectory: TrajectoryData,
        budget: TokenBudget
    ): Int {
        val currentTokens = trajectory.getTokenCount()
        val protectedTokens = budget.headProtection + budget.tailProtection + budget.minimumMiddle
        
        return if (currentTokens > budget.total) {
            currentTokens - (budget.total - protectedTokens)
        } else {
            0
        }
    }
}

object MiddleCompression {

    fun compressMiddle(
        middleTurns: List<TrajectoryTurn>,
        targetTokenReduction: Int
    ): Pair<List<TrajectoryTurn>, CompressedRegion?> {
        
        if (middleTurns.isEmpty() || targetTokenReduction <= 0) {
            return middleTurns to null
        }

        val totalTokens = middleTurns.sumOf { it.estimateTokenCount() }
        val targetTokens = maxOf(0, totalTokens - targetTokenReduction)

        if (targetTokens <= 0) {
            val summary = generateSummary(middleTurns)
            val compressedTurn = TrajectoryTurn(
                role = "user",
                content = summary,
                timestamp = middleTurns.first().timestamp
            )
            return listOf(compressedTurn) to CompressedRegion(
                startIndex = 0,
                endIndex = middleTurns.size - 1,
                summary = summary,
                originalTokenCount = totalTokens,
                compressedTokenCount = summary.length / 4
            )
        }

        return middleTurns to null
    }

    private fun generateSummary(turns: List<TrajectoryTurn>): String {
        val content = turns.joinToString(" ") { it.content.take(200) }
        return "[Compressed Summary]: ${content.take(500)}"
    }
}

object ToolPairPreserver {

    fun findToolPairs(turns: List<TrajectoryTurn>): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        
        while (i < turns.size) {
            val current = turns[i]
            
            if (current.toolCall != null) {
                var j = i + 1
                while (j < turns.size) {
                    if (turns[j].role == "tool" || 
                        (turns[j].role == "assistant" && turns[j].toolCall == null)) {
                        pairs.add(i to j)
                        i = j + 1
                        break
                    }
                    j++
                }
            }
            i++
        }
        
        return pairs
    }

    fun preserveToolPairs(
        turns: List<TrajectoryTurn>,
        pairs: List<Pair<Int, Int>>,
        startIndex: Int,
        endIndex: Int
    ): List<TrajectoryTurn> {
        val preservedIndices = mutableSetOf<Int>()
        
        for (pair in pairs) {
            if (pair.first >= startIndex && pair.second <= endIndex) {
                preservedIndices.add(pair.first)
                preservedIndices.add(pair.second)
            }
        }
        
        return turns.filterIndexed { index, _ -> preservedIndices.contains(index) }
    }
}

object TrajectoryCompressor {

    private val logger = LoggerFactory.getLogger(TrajectoryCompressor::class.java)

    fun initialize() {
        logger.info("TrajectoryCompressor initialized")
    }

    suspend fun compress(
        trajectory: TrajectoryData,
        targetTokenBudget: Int = 8192
    ): CompressionResult {
        return withContext(Dispatchers.Default) {
            val budget = TokenBudget(
                total = targetTokenBudget,
                headProtection = 1024,
                tailProtection = 1024,
                minimumMiddle = 512
            )

            val partition = CompressionStrategy.partitionTrajectory(trajectory)
            val compressionNeeded = CompressionStrategy.calculateCompressionNeeded(trajectory, budget)

            var compressedMiddle = partition.middle
            var compressedRegion: CompressedRegion? = null

            if (compressionNeeded > 0) {
                val toolPairs = ToolPairPreserver.findToolPairs(partition.middle)
                val (middle, region) = MiddleCompression.compressMiddle(
                    partition.middle,
                    compressionNeeded
                )
                compressedMiddle = middle
                compressedRegion = region
            }

            val compressedTurns = partition.head + compressedMiddle + partition.tail
            val compressedTrajectory = TrajectoryData(
                id = trajectory.id,
                turns = compressedTurns,
                metadata = trajectory.metadata + mapOf(
                    "compressed" to true,
                    "originalTurns" to trajectory.turns.size
                )
            )

            val qualityReport = generateQualityReport(
                trajectory,
                compressedTrajectory,
                partition
            )

            CompressionResult(
                original = trajectory,
                compressed = compressedTrajectory,
                regions = if (compressedRegion != null) listOf(compressedRegion) else emptyList(),
                originalTokenCount = trajectory.getTokenCount(),
                compressedTokenCount = compressedTrajectory.getTokenCount(),
                qualityReport = qualityReport
            )
        }
    }

    private fun generateQualityReport(
        original: TrajectoryData,
        compressed: TrajectoryData,
        partition: TrajectoryPartition
    ): CompressionQualityReport {
        val originalToolCalls = original.getToolCallCount()
        val compressedToolCalls = compressed.getToolCallCount()
        
        return CompressionQualityReport(
            preservedToolCalls = compressedToolCalls,
            totalToolCalls = originalToolCalls,
            headPreserved = partition.head.isNotEmpty(),
            tailPreserved = partition.tail.isNotEmpty(),
            summaryQuality = if (compressedToolCalls == originalToolCalls) "high" else "medium",
            warnings = if (compressedToolCalls < originalToolCalls) {
                listOf("Some tool calls were removed during compression")
            } else {
                emptyList()
            }
        )
    }

    fun calculateStats(trajectory: TrajectoryData): TrajectoryStats {
        val tokens = trajectory.turns.map { it.estimateTokenCount() }
        return TrajectoryStats(
            totalTurns = trajectory.turns.size,
            totalTokens = tokens.sum(),
            toolCallCount = trajectory.getToolCallCount(),
            avgTurnTokens = if (tokens.isNotEmpty()) tokens.average() else 0.0,
            maxTurnTokens = tokens.maxOrNull() ?: 0
        )
    }
}
