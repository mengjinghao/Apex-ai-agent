package com.apex.agent.orchestration.collaboration.modes

import android.content.Context
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.Task
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.collaboration.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class SupervisorExecutionModeTest {

    private lateinit var context: Context
    private lateinit var agentManager: AgentManager
    private lateinit var mode: SupervisorExecutionMode

    @Before
    fun setup() {
        context = Mockito.mock(Context::class.java)
        agentManager = AgentManager()
        mode = SupervisorExecutionMode(context, agentManager)
    }

    @Test
    fun execute_returnsFlowOfResults() = runBlocking {
        val supervisorAgent = Agent(
            id = "supervisor-1",
            name = "Supervisor",
            role = "supervisor coordinator"
        )
        agentManager.createAgent(supervisorAgent)

        val task = Task(
            id = "task-supervisor-1",
            title = "Supervised Task",
            description = "Task under supervisor",
            status = TaskState.PENDING.name,
            collaborationMode = "supervisor",
            agentIds = listOf("supervisor-1"),
            createdAt = 0L,
            updatedAt = 0L
        )

        val flow = mode.execute(task)

        assertTrue(flow is Flow<Result<Task>>)
        val first = flow.first()
        assertTrue(first is Result.Success)
        assertEquals(TaskState.RUNNING.name, (first as Result.Success).data.status)
    }
}
