package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class KernelState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    ERROR
}