package com.apex.agent.orchestration.agent

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.agent.model.Agent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentManagerTest {

    private lateinit var agentManager: AgentManager

    @Before
    fun setup() {
        agentManager = AgentManager()
    }

    @After
    fun tearDown() {
        agentManager.clear()
    }

    @Test
    fun createAgent_returnsGeneratedIdAndStoresAgent() {
        val agent = Agent(id = "", name = "Test Agent", role = "tester")

        val result = agentManager.createAgent(agent)

        assertTrue(result is Result.Success)
        val createdId = (result as Result.Success).data
        assertTrue(createdId.isNotEmpty())

        val fetched = agentManager.getAgent(createdId)
        assertTrue(fetched is Result.Success)
        assertEquals("Test Agent", (fetched as Result.Success).data.name)
    }

    @Test
    fun createAgent_preservesProvidedId() {
        val agent = Agent(id = "agent-123", name = "Named Agent", role = "worker")

        val result = agentManager.createAgent(agent)

        assertEquals(Result.Success("agent-123"), result)
        assertEquals("Named Agent", (agentManager.getAgent("agent-123") as Result.Success).data.name)
    }

    @Test
    fun getAgent_returnsFailureForUnknownId() {
        val result = agentManager.getAgent("missing")

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is NoSuchElementException)
    }

    @Test
    fun updateAgent_modifiesExistingAgent() {
        val agent = Agent(id = "agent-1", name = "Original", role = "worker")
        agentManager.createAgent(agent)

        val updated = agent.copy(name = "Updated")
        val result = agentManager.updateAgent(updated)

        assertEquals(Result.Success(Unit), result)
        assertEquals("Updated", (agentManager.getAgent("agent-1") as Result.Success).data.name)
    }

    @Test
    fun updateAgent_returnsFailureForMissingAgent() {
        val result = agentManager.updateAgent(Agent(id = "ghost", name = "Ghost", role = "none"))

        assertTrue(result is Result.Failure)
    }

    @Test
    fun deleteAgent_removesAgent() {
        val agent = Agent(id = "agent-2", name = "To Delete", role = "worker")
        agentManager.createAgent(agent)

        val result = agentManager.deleteAgent("agent-2")

        assertEquals(Result.Success(Unit), result)
        assertTrue(agentManager.getAgent("agent-2") is Result.Failure)
    }

    @Test
    fun deleteAgent_returnsFailureForUnknownAgent() {
        val result = agentManager.deleteAgent("unknown")

        assertTrue(result is Result.Failure)
    }

    @Test
    fun getAllAgents_returnsAllCreatedAgents() {
        agentManager.createAgent(Agent(id = "a", name = "A", role = "r1"))
        agentManager.createAgent(Agent(id = "b", name = "B", role = "r2"))

        val result = agentManager.getAllAgents()

        assertTrue(result is Result.Success)
        assertEquals(2, (result as Result.Success).data.size)
    }
}
