package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 状态模式测试
 *
 * 验证状态转换、历史记录和无效转换处理。
 */
class StatePatternTest : BaseUnitTest {

    private lateinit var machine: StateMachine

    @Before
    override fun setUp() {
        super.setUp()
        machine = StateMachine()
    }

    @Test
    fun `initial state should be idle`() {
        assertEquals("idle", machine.currentState())
    }

    @Test
    fun `valid transition should succeed`() {
        assertTrue(machine.transition("running"))
        assertEquals("running", machine.currentState())
    }

    @Test
    fun `invalid transition should fail`() {
        assertFalse(machine.transition("completed"))
        assertEquals("idle", machine.currentState())
    }

    @Test
    fun `history should track transitions`() {
        machine.transition("running")
        machine.transition("paused")
        machine.transition("running")
        assertEquals(3, machine.historySize())
    }

    @Test
    fun `should restore to previous state`() {
        machine.transition("running")
        machine.transition("paused")
        machine.restore()
        assertEquals("running", machine.currentState())
    }

    @Test
    fun `should detect if in terminal state`() {
        machine.transition("running")
        machine.transition("completed")
        assertTrue(machine.isTerminal())
    }

    @Test
    fun `should reject transition from terminal state`() {
        machine.transition("running")
        machine.transition("completed")
        assertFalse(machine.transition("running"))
    }

    @Test
    fun `should handle multiple consecutive transitions`() {
        assertTrue(machine.transition("running"))
        assertTrue(machine.transition("paused"))
        assertTrue(machine.transition("running"))
        assertTrue(machine.transition("error"))
        assertEquals("error", machine.currentState())
    }

    @Test
    fun `should allow transition to same state`() {
        assertTrue(machine.transition("running"))
        assertTrue(machine.transition("running"))
        assertEquals("running", machine.currentState())
    }
}

class StateMachine {
    private val states = listOf("idle", "running", "paused", "completed", "error")
    private val transitions = mapOf(
        "idle" to listOf("running"),
        "running" to listOf("paused", "completed", "error", "running"),
        "paused" to listOf("running", "error"),
        "completed" to emptyList(),
        "error" to listOf("idle")
    )
    private val history = mutableListOf<String>()
    private var current = "idle"

    init { history.add(current) }

    fun transition(to: String): Boolean {
        val allowed = transitions[current] ?: return false
        if (to !in allowed) return false
        current = to
        history.add(to)
        return true
    }

    fun currentState() = current
    fun historySize() = history.size

    fun restore(): Boolean {
        if (history.size <= 1) return false
        history.removeLast()
        current = history.last()
        return true
    }

    fun isTerminal(): Boolean {
        return transitions[current]?.isEmpty() == true
    }
}
