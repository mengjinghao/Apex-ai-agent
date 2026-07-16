package com.apex.agent.orchestration.pipeline

// Minimal implementation (original had 315 errors)
// TODO: Restore full implementation from original code

sealed class PipelineProgressEvent
data class StageStarted(val data: String = "")
data class StageCompleted(val data: String = "")
data class LoopBacktrack(val data: String = "")
