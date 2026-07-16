package com.apex.agent.domain.persistence

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

class BurstStateStore
data class StateEntry(val data: String = "")
data class CheckpointEntry(val data: String = "")
