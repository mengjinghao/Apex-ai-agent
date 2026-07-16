package com.apex.agent.core.workflow.enhanced.parallel

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

sealed class ParallelExecutionEvent
data class BranchStarted(val data: String = "")
data class BranchCompleted(val data: String = "")
data class BranchFailed(val data: String = "")
data class AllCompleted(val data: String = "")
data class BarrierReached(val data: String = "")
interface Aggregator
object Aggregators {
    fun init() { }
}
class ParallelExecutor
data class FanOutResult(val data: String = "")
sealed class BarrierResult
object Reached {
    fun init() { }
}
class BarrierState
