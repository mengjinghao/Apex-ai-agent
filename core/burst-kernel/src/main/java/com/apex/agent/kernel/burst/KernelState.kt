package com.apex.agent.kernel.burst

import com.apex.agent.domain.model.TaskStatus

enum class KernelState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    ERROR
}