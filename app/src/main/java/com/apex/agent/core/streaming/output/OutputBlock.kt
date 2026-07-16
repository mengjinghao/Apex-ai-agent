package com.apex.agent.core.streaming.output

// Minimal implementation (original had 21 errors)
// TODO: Restore full implementation from original code

sealed class OutputBlock
enum class BlockStatus { DEFAULT }
enum class TextRole { DEFAULT }
data class ReasoningBlock(val data: String = "")
data class CommandBlock(val data: String = "")
data class CommandOutputBlock(val data: String = "")
enum class StreamType { DEFAULT }
data class FileEditBlock(val data: String = "")
data class ToolCallBlock(val data: String = "")
enum class ToolCategory { DEFAULT }
data class MemoryBlock(val data: String = "")
data class TaskBlock(val data: String = "")
data class TaskStep(val data: String = "")
data class ProgressBlock(val data: String = "")
data class ErrorBlock(val data: String = "")
enum class ErrorType { DEFAULT }
data class SuccessBlock(val data: String = "")
sealed class BerserkBlock
data class MultiPathReasoningBlock(val data: String = "")
data class ReasoningPath(val data: String = "")
enum class SelectionStrategy { DEFAULT }
data class AdversarialBlock(val data: String = "")
enum class Side { DEFAULT }
data class SelfCorrectionBlock(val data: String = "")
data class CorrectionAttempt(val data: String = "")
data class TreeOfThoughtsBlock(val data: String = "")
data class ThoughtBranch(val data: String = "")
data class SkillChainBlock(val data: String = "")
data class SkillExecution(val data: String = "")
data class RacingBlock(val data: String = "")
data class Racer(val data: String = "")
data class EvolutionBlock(val data: String = "")
data class Individual(val data: String = "")
fun blockToForm() { }
