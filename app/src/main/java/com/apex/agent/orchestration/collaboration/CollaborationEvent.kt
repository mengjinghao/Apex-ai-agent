package com.apex.agent.orchestration.collaboration

import com.apex.agent.AgentMessage

    class TaskPaused(taskId: String) : CollaborationEvent(taskId)
    class TaskResumed(taskId: String) : CollaborationEvent(taskId)
    class TaskStopped(taskId: String) : CollaborationEvent(taskId)
    class MessageSubmitted(taskId: String, val message: AgentMessage) : CollaborationEvent(taskId)
    class AgentMessageEvent(taskId: String, val message: AgentMessage) : CollaborationEvent(taskId)
}
