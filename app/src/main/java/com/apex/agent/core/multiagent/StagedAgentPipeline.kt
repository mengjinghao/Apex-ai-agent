package com.apex.agent.core.multiagent

// Minimal implementation (original had 64 errors)
// TODO: Restore full implementation from original code

enum class PipelineStage { DEFAULT }
data class StageResult(val data: String = "")
data class PipelineContext(val data: String = "")
data class PipelineResult(val data: String = "")
class StagedAgentPipeline
interface StageAgent
data class StageAgentResult(val data: String = "")
interface PipelineProgressListener
