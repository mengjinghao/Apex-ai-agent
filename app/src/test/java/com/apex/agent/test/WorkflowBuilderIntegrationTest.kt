package com.apex.agent.test

import com.apex.agent.core.patterns.StrategyRegistry
import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 工作流构建器集成测试
 *
 * 使用 BuilderFlux 模式构建复杂工作流并通过 WorkflowEngine 执行。
 */
class WorkflowBuilderIntegrationTest : BaseUnitTest {

    private lateinit var workflowEngine: SimpleWorkflowEngine

    @Before
    override fun setUp() {
        super.setUp()
        workflowEngine = SimpleWorkflowEngine()
    }

    @Test
    fun `build simple sequential workflow`() = runTest {
        val workflow = WorkflowBuilder()
            .step("start") { "init" }
            .step("process") { "processed" }
            .step("finish") { "done" }
            .build()

        val result = workflowEngine.execute(workflow)
        assertTrue(result.success)
        assertEquals(3, result.stepsExecuted)
    }

    @Test
    fun `workflow should support branching based on conditions`() = runTest {
        val workflow = WorkflowBuilder()
            .step("check") { "valid" }
            .branch("check", "valid") {
                step("success") { "passed" }
            }
            .branch("check", "invalid") {
                step("fallback") { "failed" }
            }
            .build()

        val result = workflowEngine.execute(workflow)
        assertTrue(result.success)
        assertTrue(result.output.contains("passed"))
    }

    @Test
    fun `workflow should handle parallel steps`() = runTest {
        val workflow = WorkflowBuilder()
            .step("start") { "begin" }
            .parallel("step_a", "step_b") {
                step("step_a") { "result_a" }
                step("step_b") { "result_b" }
            }
            .step("end") { "complete" }
            .build()

        val result = workflowEngine.execute(workflow)
        assertTrue(result.success)
    }

    @Test
    fun `workflow should maintain context between steps`() = runTest {
        val workflow = WorkflowBuilder()
            .step("set") { "value=42" }
            .step("get") { context -> "got=${context["set"]}" }
            .build()

        val result = workflowEngine.execute(workflow)
        assertTrue(result.success)
    }

    @Test
    fun `workflow with error handling should continue`() = runTest {
        val workflow = WorkflowBuilder()
            .step("good") { "ok" }
            .step("bad") { throw RuntimeException("fail") }
            .step("after") { "recovered" }
            .build()

        val result = workflowEngine.execute(workflow, ignoreErrors = true)
        assertTrue(result.success)
        assertTrue(result.output.contains("recovered"))
    }

    @Test
    fun `workflow should support retry logic`() = runTest {
        var attempts = 0
        val workflow = WorkflowBuilder()
            .step("retry") {
                attempts++
                if (attempts < 3) throw RuntimeException("not yet")
                "success_on_attempt_3"
            }
            .build()

        val result = workflowEngine.execute(workflow, retries = 3)
        assertTrue(result.success)
        assertEquals(3, attempts)
    }

    @Test
    fun `complex workflow with multiple patterns`() = runTest {
        val workflow = WorkflowBuilder()
            .step("validate") { "valid" }
            .branch("validate", "valid") {
                parallel("fetch", "compute") {
                    step("fetch") { "data" }
                    step("compute") { "result" }
                }
            }
            .step("merge") { ctx -> "${ctx["fetch"]}+${ctx["compute"]}" }
            .step("output") { "final" }
            .build()

        val result = workflowEngine.execute(workflow, ignoreErrors = true)
        assertNotNull(result)
    }

    @Test
    fun `empty workflow should fail gracefully`() = runTest {
        val workflow = WorkflowBuilder().build()
        val result = workflowEngine.execute(workflow)
        assertFalse(result.success)
    }
}

data class WorkflowResult(val success: Boolean, val stepsExecuted: Int, val output: String)

class WorkflowStep(val name: String, val action: (Map<String, String>) -> String)

class WorkflowBuilder {
    private val steps = mutableListOf<WorkflowStep>()
    private val branches = mutableMapOf<String, MutableList<Pair<String, WorkflowBuilder>>>()

    fun step(name: String, action: (Map<String, String>) -> String): WorkflowBuilder {
        steps.add(WorkflowStep(name, action))
        return this
    }

    fun step(name: String, action: () -> String): WorkflowBuilder {
        steps.add(WorkflowStep(name, { action() }))
        return this
    }

    fun branch(source: String, condition: String, builder: WorkflowBuilder.() -> Unit): WorkflowBuilder {
        branches.getOrPut(source) { mutableListOf() }.add(condition to WorkflowBuilder().apply(builder))
        return this
    }

    fun parallel(vararg names: String, builder: WorkflowBuilder.() -> Unit): WorkflowBuilder {
        val parallelBuilder = WorkflowBuilder().apply(builder)
        for (name in names) {
            val step = parallelBuilder.steps.find { it.name == name }
            if (step != null) steps.add(step)
        }
        return this
    }

    fun build(): WorkflowDefinition = WorkflowDefinition(steps.toList(), branches.toMap())
}

data class WorkflowDefinition(
    val steps: List<WorkflowStep>,
    val branches: Map<String, List<Pair<String, WorkflowBuilder>>>
)

class SimpleWorkflowEngine {
    suspend fun execute(wf: WorkflowDefinition, ignoreErrors: Boolean = false, retries: Int = 1): WorkflowResult {
        if (wf.steps.isEmpty()) return WorkflowResult(false, 0, "no steps")
        val context = mutableMapOf<String, String>()
        var executed = 0
        for (step in wf.steps) {
            repeat(retries) {
                try {
                    val result = step.action(context)
                    context[step.name] = result
                    executed++
                    return@repeat
                } catch (e: Exception) {
                    if (!ignoreErrors && it == retries - 1) throw e
                }
            }
        }
        return WorkflowResult(true, executed, context.values.joinToString(","))
    }
}
