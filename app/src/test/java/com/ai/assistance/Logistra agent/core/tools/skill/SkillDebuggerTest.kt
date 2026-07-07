package com.ai.assistance.`Apex agent`.core.tools.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ai.assistance.`Apex agent`.core.tools.skill.SkillDebugger.DebugState
import com.ai.assistance.`Apex agent`.core.tools.skill.SkillDebugger.PauseReason
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class SkillDebuggerTest {

    private lateinit var context: Context
    private lateinit var debugger: SkillDebugger
    private lateinit var breakpointManager: BreakpointManager
    private lateinit var executionTracer: ExecutionTracer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        debugger = SkillDebugger.getInstance(context)
        debugger.clearAllSessions()
        breakpointManager = debugger.getBreakpointManager()
        breakpointManager.clearAllBreakpoints()
        executionTracer = debugger.getExecutionTracer()
        executionTracer.clearTrace()
    }

    @After
    fun tearDown() {
        debugger.clearAllSessions()
        breakpointManager.clearAllBreakpoints()
        executionTracer.clearTrace()
    }

    @Test
    fun testStartDebugSession() {
        val session = debugger.startDebugSession("test_skill")
        assertNotNull(session)
        assertEquals("test_skill", session.skillName)
        assertEquals(DebugState.RUNNING, session.state)
        assertNotNull(session.currentContext)
        assertEquals(0L, session.currentContext?.toolCallCount)
        assertEquals(0L, session.currentContext?.errorCount)
    }

    @Test
    fun testEndDebugSession() {
        val session = debugger.startDebugSession("test_skill")
        val endedSession = debugger.endDebugSession(session.id)
        assertNotNull(endedSession)
        assertEquals(DebugState.TERMINATED, endedSession.state)
        assertNotNull(endedSession.endTime)
    }

    @Test
    fun testAddToolBreakpoint() {
        debugger.startDebugSession("test_skill")
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "tap",
            null
        )
        assertNotNull(breakpoint)
        assertEquals("tap", breakpoint.target)
        assertEquals(SkillDebugger.BreakpointType.TOOL_CALL, breakpoint.type)
        assertTrue(breakpoint.enabled)
    }

    @Test
    fun testAddLineBreakpoint() {
        debugger.startDebugSession("test_skill")
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.LINE_NUMBER,
            "42",
            null
        )
        assertNotNull(breakpoint)
        assertEquals("42", breakpoint.target)
        assertEquals(SkillDebugger.BreakpointType.LINE_NUMBER, breakpoint.type)
    }

    @Test
    fun testAddConditionBreakpoint() {
        debugger.startDebugSession("test_skill")
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.CONDITION,
            "toolCount >= 5",
            "toolCount >= 5"
        )
        assertNotNull(breakpoint)
        assertEquals("toolCount >= 5", breakpoint.condition)
    }

    @Test
    fun testBreakpointHit() {
        val session = debugger.startDebugSession("test_skill")
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "tap",
            null
        )

        debugger.onToolCallStart("tap", mapOf("x" to 100, "y" to 200))

        val context = session.currentContext
        assertTrue(breakpoint.isHit(context!!))
        assertEquals(1L, breakpoint.hitCount.get())
    }

    @Test
    fun testBreakpointNotHitWhenDisabled() {
        val session = debugger.startDebugSession("test_skill")
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "tap",
            null
        )
        debugger.enableBreakpoint(breakpoint.id, false)

        debugger.onToolCallStart("tap", mapOf("x" to 100, "y" to 200))

        val context = session.currentContext
        assertFalse(breakpoint.isHit(context!!))
    }

    @Test
    fun testPauseAndResume() {
        debugger.startDebugSession("test_skill")
        debugger.pauseExecution(PauseReason.MANUAL)
        assertTrue(debugger.isCurrentlyPaused())

        debugger.resumeExecution()
        assertFalse(debugger.isCurrentlyPaused())
    }

    @Test
    fun testStepOver() {
        debugger.startDebugSession("test_skill")
        debugger.stepOver()
        assertTrue(debugger.getActiveSession()?.state == DebugState.STEP_MODE)
    }

    @Test
    fun testToolCallTracking() {
        val session = debugger.startDebugSession("test_skill")

        debugger.onToolCallStart("tap", mapOf("x" to 100, "y" to 200))
        debugger.onToolCallEnd("tap", mapOf("success" to true), null)

        val context = session.currentContext
        assertEquals("tap", context?.currentTool)
        assertEquals(1L, context?.toolCallCount)

        val toolCalls = session.toolCalls
        assertEquals(1, toolCalls.size)
        assertEquals("tap", toolCalls[0].toolName)
        assertNotNull(toolCalls[0].endTime)
        assertNull(toolCalls[0].error)
    }

    @Test
    fun testErrorTracking() {
        debugger.startDebugSession("test_skill")
        debugger.onToolCallStart("tap", mapOf("x" to 100))
        debugger.onToolCallEnd("tap", null, "Error: tap failed")

        val context = debugger.getActiveSession()?.currentContext
        assertEquals(1L, context?.errorCount)
    }

    @Test
    fun testVariableTracking() {
        debugger.startDebugSession("test_skill")
        debugger.setVariable("counter", 0)
        debugger.setVariable("name", "test")

        val variables = debugger.getAllVariables()
        assertEquals(2, variables.size)
        assertEquals(0, variables["counter"])
        assertEquals("test", variables["name"])

        val counter = debugger.getVariable("counter")
        assertEquals(0, counter)
    }

    @Test
    fun testCallStack() {
        debugger.startDebugSession("test_skill")
        debugger.pushStackFrame("tap", 42)
        debugger.pushStackFrame("swipe", 100)

        val callStack = debugger.getCallStack()
        assertEquals(2, callStack.size)
        assertEquals("swipe", callStack[0].toolName)
        assertEquals(100, callStack[0].lineNumber)

        val popped = debugger.popStackFrame()
        assertEquals("swipe", popped?.toolName)
        assertEquals(1, debugger.getCallStack().size)
    }

    @Test
    fun testConditionalBreakpointEvaluation() {
        val session = debugger.startDebugSession("test_skill")

        session.currentContext?.toolCallCount = 5

        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.CONDITION,
            "toolCount >= 5",
            "toolCount >= 5"
        )

        val context = session.currentContext
        assertTrue(breakpoint.isHit(context!!))
    }

    @Test
    fun testMultipleBreakpoints() {
        debugger.startDebugSession("test_skill")
        val bp1 = debugger.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "tap", null)
        val bp2 = debugger.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "swipe", null)
        val bp3 = debugger.addBreakpoint(SkillDebugger.BreakpointType.LINE_NUMBER, "50", null)

        val allBreakpoints = debugger.getAllBreakpoints()
        assertEquals(3, allBreakpoints.size)

        debugger.removeBreakpoint(bp1.id)
        val remaining = debugger.getAllBreakpoints()
        assertEquals(2, remaining.size)
    }
}

@RunWith(JUnit4::class)
class BreakpointManagerTest {

    private lateinit var context: Context
    private lateinit var breakpointManager: BreakpointManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        breakpointManager = BreakpointManager()
        breakpointManager.clearAllBreakpoints()
    }

    @Test
    fun testAddBreakpoint() {
        val breakpoint = breakpointManager.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "test_tool",
            null
        )
        assertNotNull(breakpoint.id)
        assertEquals("test_tool", breakpoint.target)
        assertEquals(SkillDebugger.BreakpointType.TOOL_CALL, breakpoint.type)
    }

    @Test
    fun testRemoveBreakpoint() {
        val breakpoint = breakpointManager.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "test_tool",
            null
        )
        assertTrue(breakpointManager.removeBreakpoint(breakpoint.id))
        assertFalse(breakpointManager.removeBreakpoint(breakpoint.id))
    }

    @Test
    fun testGetBreakpointsByType() {
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "tap", null)
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "swipe", null)
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.LINE_NUMBER, "42", null)

        val toolBreakpoints = breakpointManager.getBreakpointsByType(SkillDebugger.BreakpointType.TOOL_CALL)
        assertEquals(2, toolBreakpoints.size)

        val lineBreakpoints = breakpointManager.getBreakpointsByType(SkillDebugger.BreakpointType.LINE_NUMBER)
        assertEquals(1, lineBreakpoints.size)
    }

    @Test
    fun testSetBreakpointEnabled() {
        val breakpoint = breakpointManager.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            "tap",
            null
        )
        assertTrue(breakpointManager.setBreakpointEnabled(breakpoint.id, false))

        val updated = breakpointManager.getBreakpoint(breakpoint.id)
        assertFalse(updated?.enabled ?: true)
    }

    @Test
    fun testGetBreakpointStats() {
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "tap", null)
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "swipe", null)
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.LINE_NUMBER, "42", null)

        val stats = breakpointManager.getBreakpointStats()
        assertEquals(3, stats.totalBreakpoints)
        assertEquals(3, stats.enabledBreakpoints)
        assertEquals(0, stats.disabledBreakpoints)
        assertEquals(2, stats.toolCallBreakpoints)
        assertEquals(1, stats.lineNumberBreakpoints)
    }

    @Test
    fun testExportImportBreakpoints() {
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.TOOL_CALL, "tap", null)
        breakpointManager.addBreakpoint(SkillDebugger.BreakpointType.LINE_NUMBER, "42", "condition: true")

        val exported = breakpointManager.exportBreakpoints()
        assertEquals(2, exported.size)

        breakpointManager.clearAllBreakpoints()
        assertEquals(0, breakpointManager.getAllBreakpoints().size)

        breakpointManager.importBreakpoints(exported)
        assertEquals(2, breakpointManager.getAllBreakpoints().size)
    }
}

@RunWith(JUnit4::class)
class DebugConsoleUITest {

    private lateinit var context: Context
    private lateinit var consoleUI: DebugConsoleUI

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        consoleUI = DebugConsoleUI.getInstance(context)
        consoleUI.clearConsole()
    }

    @Test
    fun testLogMessages() {
        consoleUI.log(DebugConsoleUI.ConsoleLevel.INFO, "Test message")
        val lines = consoleUI.getConsoleLines()
        assertEquals(1, lines.size)
        assertEquals("Test message", lines[0].message)
        assertEquals(DebugConsoleUI.ConsoleLevel.INFO, lines[0].level)
    }

    @Test
    fun testLogLevels() {
        consoleUI.debug("Debug message")
        consoleUI.info("Info message")
        consoleUI.warning("Warning message")
        consoleUI.error("Error message")
        consoleUI.success("Success message")

        val lines = consoleUI.getConsoleLines()
        assertEquals(5, lines.size)
        assertEquals(DebugConsoleUI.ConsoleLevel.DEBUG, lines[0].level)
        assertEquals(DebugConsoleUI.ConsoleLevel.INFO, lines[1].level)
        assertEquals(DebugConsoleUI.ConsoleLevel.WARNING, lines[2].level)
        assertEquals(DebugConsoleUI.ConsoleLevel.ERROR, lines[3].level)
        assertEquals(DebugConsoleUI.ConsoleLevel.SUCCESS, lines[4].level)
    }

    @Test
    fun testGetConsoleLinesByLevel() {
        consoleUI.debug("Debug message")
        consoleUI.info("Info message")
        consoleUI.error("Error message")

        val debugLines = consoleUI.getConsoleLines(DebugConsoleUI.ConsoleLevel.DEBUG)
        assertEquals(1, debugLines.size)

        val errorLines = consoleUI.getConsoleLines(DebugConsoleUI.ConsoleLevel.ERROR)
        assertEquals(1, errorLines.size)
    }

    @Test
    fun testToolCallLogging() {
        consoleUI.logToolCallStart("tap", mapOf("x" to 100, "y" to 200))
        consoleUI.logToolCallEnd("tap", mapOf("success" to true), null, 50L)

        val lines = consoleUI.getConsoleLines()
        assertTrue(lines.any { it.message.contains("Tool call START") })
        assertTrue(lines.any { it.message.contains("Tool call END") })
    }

    @Test
    fun testWatchVariables() {
        consoleUI.addWatchVariable("counter", "counter")
        consoleUI.addWatchVariable("name", "name")

        val watches = consoleUI.getWatchedVariables()
        assertEquals(2, watches.size)

        consoleUI.updateWatchedVariables(mapOf("counter" to 5, "name" to "updated"))

        consoleUI.removeWatchVariable("counter")
        val remaining = consoleUI.getWatchedVariables()
        assertEquals(1, remaining.size)
    }

    @Test
    fun testExportConsole() {
        consoleUI.info("Line 1")
        consoleUI.error("Line 2")

        val exported = consoleUI.exportConsole()
        assertTrue(exported.contains("Line 1"))
        assertTrue(exported.contains("Line 2"))
    }

    @Test
    fun testBuildVariableTable() {
        val variables = mapOf(
            "name" to "test",
            "count" to 42,
            "active" to true
        )

        val table = consoleUI.buildVariableTable(variables)
        assertTrue(table.contains("name"))
        assertTrue(table.contains("test"))
        assertTrue(table.contains("count"))
        assertTrue(table.contains("42"))
    }
}