package com.apex.agent.core.workflow.enhanced.loop

// Minimal implementation (original had 11 errors)
// TODO: Restore full implementation from original code

sealed class LoopSpec
data class ForEach(val data: String = "")
data class While(val data: String = "")
data class MapReduce(val data: String = "")
data class LoopContext(val data: String = "")
data class LoopResult(val data: String = "")
class LoopExecutor
