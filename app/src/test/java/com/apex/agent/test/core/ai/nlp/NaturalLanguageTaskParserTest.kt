package com.apex.agent.test.core.ai.nlp

import com.apex.agent.core.ai.LlamaEngineInterface
import com.apex.agent.core.ai.nlp.NaturalLanguageTaskParser
import com.apex.agent.core.ai.nlp.ValidationResult
import com.apex.agent.core.ai.prompt.PromptOptimizer
import com.apex.agent.data.burstmode.config.Complexity
import com.apex.agent.data.burstmode.model.BurstTask
import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class NaturalLanguageTaskParserTest : BaseUnitTest {

    private lateinit var promptOptimizer: PromptOptimizer
    private lateinit var llamaEngine: LlamaEngineInterface
    private lateinit var parser: NaturalLanguageTaskParser

    @Before
    override fun setUp() {
        super.setUp()
        promptOptimizer = mockRelaxed()
        llamaEngine = mockRelaxed()
        parser = NaturalLanguageTaskParser(
            promptOptimizer = promptOptimizer,
            llamaEngine = llamaEngine,
            availableTools = listOf("file_scanner", "file_reader", "code_analyzer")
        )
    }

    // ========== JSON parsing ==========

    @Test
    fun `parseTask should parse valid JSON response`() = runTest {
        val jsonResponse = """
            {
                "goal": "Analyze the codebase for bugs",
                "steps": ["Scan all files", "Analyze each file", "Report bugs"],
                "required_tools": ["file_scanner", "code_analyzer"],
                "estimated_complexity": "HIGH",
                "estimated_time_minutes": 30,
                "potential_challenges": ["Large codebase", "Multiple languages"]
            }
        """.trimIndent()

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(
            any(), any()
        )).thenReturn("some prompt")
        whenever(llamaEngine.generate(any())).thenReturn(jsonResponse)

        val task = parser.parseTask("find bugs in the project")

        assertEquals("Analyze the codebase for bugs", task.goal)
        assertEquals(Complexity.HIGH, task.complexity)
        assertEquals("find bugs in the project", task.metadata["original_input"])
        assertEquals(3, task.metadata["steps"]?.split("|||")?.size)
        assertEquals("file_scanner,code_analyzer", task.metadata["required_tools"])
        assertEquals("30", task.metadata["estimated_time_minutes"])
    }

    @Test
    fun `parseTask should extract steps from JSON array`() = runTest {
        val json = """{"goal":"Test","steps":["Step 1","Step 2","Step 3"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("test")

        val steps = task.metadata["steps"]?.split("|||") ?: emptyList()
        assertEquals(3, steps.size)
        assertTrue(steps.contains("Step 1"))
        assertTrue(steps.contains("Step 3"))
    }

    @Test
    fun `parseTask should handle empty JSON arrays`() = runTest {
        val json = """{"goal":"Simple","steps":[],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("simple task")

        val steps = task.metadata["steps"] ?: ""
        assertTrue(steps.isEmpty())
        val tools = task.metadata["required_tools"] ?: ""
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `parseTask should parse EXTREME complexity`() = runTest {
        val json = """{"goal":"Hard","steps":["Do it"],"required_tools":[],"estimated_complexity":"EXTREME","estimated_time_minutes":120,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("hard task")

        assertEquals(Complexity.EXTREME, task.complexity)
    }

    // ========== Text fallback (non-JSON response) ==========

    @Test
    fun `parseTask should fallback to regex extraction for non-JSON text`() = runTest {
        val textResponse = """
            The task is:
            goal: "Write unit tests"
            steps: ["Create test file", "Write assertions", "Run tests"]
            required_tools: ["file_writer", "code_analyzer"]
            estimated_complexity: "MEDIUM"
            estimated_time_minutes: "15"
            potential_challenges: ["Mocking dependencies"]
        """.trimIndent()

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(textResponse)

        val task = parser.parseTask("write tests")

        assertEquals("Write unit tests", task.goal)
        assertEquals(Complexity.MEDIUM, task.complexity)
    }

    @Test
    fun `parseTask should use text fallback for completely unstructured text`() = runTest {
        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn("I don't know how to do this task")

        val task = parser.parseTask("do something")

        assertEquals("do something", task.goal)
        assertEquals("true", task.metadata["fallback"])
    }

    // ========== JSON with code fence ==========

    @Test
    fun `parseTask should extract JSON block from code-fenced response`() = runTest {
        val response = """
            Here is the parsed task:
            ```json
            {"goal":"Fix bug in login","steps":["Reproduce","Fix","Test"],"required_tools":["code_analyzer"],"estimated_complexity":"MEDIUM","estimated_time_minutes":20,"potential_challenges":[]}
            ```
        """.trimIndent()

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(response)

        val task = parser.parseTask("fix login bug")

        assertEquals("Fix bug in login", task.goal)
        assertEquals(3, task.metadata["steps"]?.split("|||")?.size)
    }

    // ========== Edge cases ==========

    @Test
    fun `parseTask should throw for empty input`() {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { parser.parseTask("") }
        }
    }

    @Test
    fun `parseTask should throw for blank input`() {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { parser.parseTask("   ") }
        }
    }

    @Test
    fun `parseTask should throw for whitespace-only input`() {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { parser.parseTask("\n\t  \n") }
        }
    }

    @Test
    fun `parseTask should handle input with special characters`() = runTest {
        val json = """{"goal":"Handle '#$%^&*() chars","steps":["Process"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("special !@#$%^&*() chars")

        assertEquals("Handle '#$%^&*() chars", task.goal)
    }

    // ========== Task ID ==========

    @Test
    fun `parseTask should generate taskId starting with task_`() = runTest {
        val json = """{"goal":"Test","steps":["Do"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("test task id")

        assertNotNull(task.taskId)
        assertTrue(task.taskId.startsWith("task_"))
    }

    @Test
    fun `parseTask should generate unique taskIds`() = runTest {
        val json = """{"goal":"Test","steps":["Do"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task1 = parser.parseTask("first")
        val task2 = parser.parseTask("second")

        assertTrue(task1.taskId != task2.taskId)
    }

    // ========== Tools list ==========

    @Test
    fun `parseTask should use injected tools list in prompt construction`() = runTest {
        val json = """{"goal":"Test","steps":["Do"],"required_tools":["file_scanner"],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        var capturedTools: List<String>? = null
        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(
            any(), any()
        )).thenAnswer { invocation ->
            capturedTools = invocation.getArgument(1)
            "prompt"
        }
        whenever(llamaEngine.generate(any())).thenReturn(json)

        parser.parseTask("test")

        assertNotNull(capturedTools)
        assertTrue(capturedTools!!.contains("file_scanner"))
        assertTrue(capturedTools!!.contains("code_analyzer"))
        assertEquals(3, capturedTools!!.size)
    }

    @Test
    fun `parseTask should use default tools when none injected`() = runTest {
        val defaultParser = NaturalLanguageTaskParser(
            promptOptimizer = promptOptimizer,
            llamaEngine = llamaEngine
        )

        val json = """{"goal":"Default tools test","steps":["Do it"],"required_tools":["data_parser"],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        var capturedTools: List<String>? = null
        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(
            any(), any()
        )).thenAnswer { invocation ->
            capturedTools = invocation.getArgument(1)
            "prompt"
        }
        whenever(llamaEngine.generate(any())).thenReturn(json)

        defaultParser.parseTask("test default tools")

        assertNotNull(capturedTools)
        assertTrue(capturedTools!!.contains("data_parser"))
        assertTrue(capturedTools!!.contains("file_scanner"))
        assertTrue(capturedTools!!.contains("image_processor"))
    }

    // ========== validateParsedTask ==========

    @Test
    fun `validateParsedTask should return valid for well-formed task`() {
        val task = BurstTask(
            goal = "Analyze code",
            complexity = Complexity.MEDIUM,
            metadata = mapOf(
                "steps" to "Step 1|||Step 2",
                "required_tools" to "file_scanner",
                "potential_challenges" to "None"
            )
        )

        val result = parser.validateParsedTask(task)

        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `validateParsedTask should flag empty goal`() {
        val task = BurstTask(
            goal = "",
            metadata = mapOf("steps" to "Step 1", "required_tools" to "", "potential_challenges" to "")
        )

        val result = parser.validateParsedTask(task)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.contains("目标为空") })
    }

    @Test
    fun `validateParsedTask should flag missing steps`() {
        val task = BurstTask(
            goal = "Do something",
            metadata = mapOf("steps" to "", "required_tools" to "", "potential_challenges" to "")
        )

        val result = parser.validateParsedTask(task)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.contains("未定义执行步骤") })
    }

    @Test
    fun `validateParsedTask should flag unavailable tools`() {
        val task = BurstTask(
            goal = "Do something",
            metadata = mapOf(
                "steps" to "Step 1",
                "required_tools" to "non_existent_tool",
                "potential_challenges" to ""
            )
        )

        val result = parser.validateParsedTask(task)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.contains("不可用") })
    }

    @Test
    fun `validateParsedTask should flag complexity mismatch with many steps`() {
        val task = BurstTask(
            goal = "Big task",
            complexity = Complexity.LOW,
            metadata = mapOf(
                "steps" to (1..15).joinToString("|||") { "Step $it" },
                "required_tools" to "",
                "potential_challenges" to ""
            )
        )

        val result = parser.validateParsedTask(task)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.contains("复杂度标记为 LOW") })
    }

    @Test
    fun `validateParsedTask confidence should decrease with more issues`() {
        val badTask = BurstTask(
            goal = "",
            metadata = mapOf("steps" to "", "required_tools" to "bad_tool", "potential_challenges" to "")
        )

        val result = parser.validateParsedTask(badTask)

        assertTrue(result.confidence < 0.8f)
    }

    @Test
    fun `validateParsedTask confidence should be at least 0 point 1`() {
        val task = BurstTask(
            goal = "",
            metadata = mapOf(
                "steps" to "",
                "required_tools" to "bad_tool",
                "potential_challenges" to "Many challenges"
            )
        )

        val result = parser.validateParsedTask(task)

        assertTrue(result.confidence >= 0.1f)
    }

    // ========== parseTasks batch ==========

    @Test
    fun `parseTasks should parse multiple inputs`() = runTest {
        val json1 = """{"goal":"Task one","steps":["A"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""
        val json2 = """{"goal":"Task two","steps":["B"],"required_tools":[],"estimated_complexity":"MEDIUM","estimated_time_minutes":10,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json1, json2)

        val tasks = parser.parseTasks(listOf("first", "second"))

        assertEquals(2, tasks.size)
        assertEquals("Task one", tasks[0].goal)
        assertEquals("Task two", tasks[1].goal)
    }

    @Test
    fun `parseTasks should handle one failing and one succeeding`() = runTest {
        val json2 = """{"goal":"Second task","steps":["B"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenThrow(RuntimeException("LLM error")).thenReturn(json2)

        val tasks = parser.parseTasks(listOf("failing", "succeeding"))

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].metadata["fallback"] == "true")
        assertFalse(tasks[1].metadata.containsKey("fallback"))
    }

    // ========== Fallback task ==========

    @Test
    fun `parseTask should create fallback task on any exception`() = runTest {
        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenThrow(RuntimeException("Network error"))

        val task = parser.parseTask("do something")

        assertEquals("do something", task.goal)
        assertEquals("true", task.metadata["fallback"])
        assertTrue(task.metadata["error"]?.contains("Network error") ?: false)
        assertEquals(Complexity.MEDIUM, task.complexity)
    }

    @Test
    fun `fallback task should have metadata fields`() = runTest {
        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenThrow(RuntimeException("Error"))

        val task = parser.parseTask("urgent task")

        assertNotNull(task.metadata["steps"])
        assertNotNull(task.metadata["potential_challenges"])
        assertEquals("5", task.metadata["estimated_time_minutes"])
    }

    // ========== Not blank input is trimmed ==========

    @Test
    fun `parseTask should trim whitespace from input`() = runTest {
        val json = """{"goal":"Trimmed goal","steps":["Do"],"required_tools":[],"estimated_complexity":"LOW","estimated_time_minutes":5,"potential_challenges":[]}"""

        whenever(promptOptimizer.buildNaturalLanguageToTaskPrompt(any(), any())).thenReturn("p")
        whenever(llamaEngine.generate(any())).thenReturn(json)

        val task = parser.parseTask("  do this  ")

        assertEquals("do this", task.metadata["original_input"])
    }
}
