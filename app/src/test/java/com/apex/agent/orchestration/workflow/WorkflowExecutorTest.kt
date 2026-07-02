package com.apex.agent.orchestration.workflow

import com.apex.agent.common.result.Result
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowExecutorTest {

    private lateinit var executor: WorkflowExecutor

    @Before
    fun setup() {
        executor = WorkflowExecutor()
    }

    @Test
    fun execute_linearStartToEndFlow_emitsSuccessForEachNode() = runBlocking {
        val startNode = WorkflowNodeEntity(
            id = "start",
            type = NodeType.START,
            title = "Start",
            description = "Start node"
        )
        val endNode = WorkflowNodeEntity(
            id = "end",
            type = NodeType.END,
            title = "End",
            description = "End node"
        )
        val workflow = Workflow(
            id = "wf-1",
            name = "Linear Workflow",
            nodes = mutableListOf(startNode, endNode),
            edges = mutableListOf(
                WorkflowEdge(fromNodeId = "start", toNodeId = "end")
            )
        )

        val results = executor.execute(workflow).toList()

        assertEquals(2, results.size)
        assertTrue(results.all { it is Result.Success })

        val endResult = results.last() as Result.Success
        assertEquals("end", endResult.data.nodeId)
        assertEquals(true, endResult.data.output["finished"])
    }
}
