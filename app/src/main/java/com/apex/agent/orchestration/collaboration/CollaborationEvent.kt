package com.apex.agent.orchestration.collaboration

import com.apex.agent.domain.entity.AgentMessage

sealed class CollaborationEvent(val taskId: String) {
    class TaskCreated(taskId: String) : CollaborationEvent(taskId)
    class TaskStarted(taskId: String) : CollaborationEvent(taskId)
    class TaskPaused(taskId: String) : CollaborationEvent(taskId)
    class TaskResumed(taskId: String) : CollaborationEvent(taskId)
    class TaskStopped(taskId: String) : CollaborationEvent(taskId)
    class TaskCompleted(taskId: String) : CollaborationEvent(taskId)
    class TaskFailed(taskId: String, val error: String) : CollaborationEvent(taskId)
    class MessageSubmitted(taskId: String, val message: AgentMessage) : CollaborationEvent(taskId)
    class AgentMessageEvent(taskId: String, val message: AgentMessage) : CollaborationEvent(taskId)
}
