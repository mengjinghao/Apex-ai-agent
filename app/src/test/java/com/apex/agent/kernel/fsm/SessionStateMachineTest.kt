package com.apex.agent.kernel.fsm

import com.apex.agent.kernel.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class SessionStateMachineTest {

    private val sessionId = "test-session"
    private val triggerEvent = SessionEvent.UserInputReceived(sessionId, "hello")
    private lateinit var machine: SessionStateMachine

    @Before
    fun setUp() {
        machine = SessionStateMachine()
    }

    @Test
    fun `defaultTransitions from IDLE to INPUTTING`() {
        val result = machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(SessionPhase.INPUTTING, (result as TransitionResult.Allowed).newState.phase)
        assertEquals(SessionPhase.INPUTTING, machine.currentState.phase)
    }

    @Test
    fun `defaultTransitions IDLE to INPUTTING to THINKING to TOOL_EXECUTING to OUTPUTTING to IDLE`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        assertEquals(SessionPhase.INPUTTING, machine.currentState.phase)

        machine.requestTransition(SessionPhase.THINKING, triggerEvent)
        assertEquals(SessionPhase.THINKING, machine.currentState.phase)

        machine.requestTransition(SessionPhase.TOOL_EXECUTING, triggerEvent)
        assertEquals(SessionPhase.TOOL_EXECUTING, machine.currentState.phase)

        machine.requestTransition(SessionPhase.OUTPUTTING, triggerEvent)
        assertEquals(SessionPhase.OUTPUTTING, machine.currentState.phase)

        machine.requestTransition(SessionPhase.IDLE, triggerEvent)
        assertEquals(SessionPhase.IDLE, machine.currentState.phase)
    }

    @Test
    fun `invalidTransition is denied`() {
        val result = machine.requestTransition(SessionPhase.THINKING, triggerEvent)
        assertTrue(result is TransitionResult.Denied)
    }

    @Test
    fun `invalidTransition from INPUTTING to IDLE is denied`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        val result = machine.requestTransition(SessionPhase.IDLE, triggerEvent)
        assertTrue(result is TransitionResult.Denied)
    }

    @Test
    fun `customRule can deny transition`() {
        val denyingRule = mock<StateTransitionRule> {
            on { evaluate(any(), any(), any()) } doReturn false
        }
        machine = SessionStateMachine(customRules = listOf(denyingRule))

        val result = machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        assertTrue(result is TransitionResult.Denied)
        assertEquals("Custom rules rejected phase transition", (result as TransitionResult.Denied).reason)
    }

    @Test
    fun `customRule can allow transition`() {
        val allowingRule = mock<StateTransitionRule> {
            on { evaluate(any(), any(), any()) } doReturn true
        }
        machine = SessionStateMachine(customRules = listOf(allowingRule))

        val result = machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        assertTrue(result is TransitionResult.Allowed)
    }

    @Test
    fun `requestTransition to THINKING auto-sets thinkingSubPhase to INITIALIZING`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        machine.requestTransition(SessionPhase.THINKING, triggerEvent)

        assertEquals(SessionPhase.THINKING, machine.currentState.phase)
        assertEquals(ThinkingSubPhase.INITIALIZING, machine.currentState.thinkingSubPhase)
        assertNull(machine.currentState.outputSubPhase)
    }

    @Test
    fun `requestTransition to OUTPUTTING auto-sets outputSubPhase to GENERATING`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        machine.requestTransition(SessionPhase.THINKING, triggerEvent)
        machine.requestTransition(SessionPhase.OUTPUTTING, triggerEvent)

        assertEquals(SessionPhase.OUTPUTTING, machine.currentState.phase)
        assertEquals(OutputSubPhase.GENERATING, machine.currentState.outputSubPhase)
        assertNull(machine.currentState.thinkingSubPhase)
    }

    @Test
    fun `requestTransition to non-THINKING non-OUTPUTTING clears sub-phases`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        machine.setChatId("chat-1")
        assertEquals("chat-1", machine.currentState.chatId)
    }

    @Test
    fun `thinking sub-state INITIALIZING to ANALYZING_INPUT`() {
        givenInThinkingPhase()
        val result = machine.requestThinkingSubTransition(ThinkingSubPhase.ANALYZING_INPUT, triggerEvent)
        assertTrue(result is SubStateTransitionResult.Allowed)
        assertEquals(ThinkingSubPhase.ANALYZING_INPUT, machine.currentState.thinkingSubPhase)
    }

    @Test
    fun `thinking sub-state full chain ANALYZING_INPUT to REASONING to SYNTHESIZING to PREPARING_OUTPUT`() {
        givenInThinkingPhase()

        machine.requestThinkingSubTransition(ThinkingSubPhase.ANALYZING_INPUT, triggerEvent)
        assertEquals(ThinkingSubPhase.ANALYZING_INPUT, machine.currentState.thinkingSubPhase)

        machine.requestThinkingSubTransition(ThinkingSubPhase.REASONING, triggerEvent)
        assertEquals(ThinkingSubPhase.REASONING, machine.currentState.thinkingSubPhase)

        machine.requestThinkingSubTransition(ThinkingSubPhase.SYNTHESIZING, triggerEvent)
        assertEquals(ThinkingSubPhase.SYNTHESIZING, machine.currentState.thinkingSubPhase)

        machine.requestThinkingSubTransition(ThinkingSubPhase.PREPARING_OUTPUT, triggerEvent)
        assertEquals(ThinkingSubPhase.PREPARING_OUTPUT, machine.currentState.thinkingSubPhase)
    }

    @Test
    fun `output sub-state GENERATING to STREAMING to FINALIZING`() {
        givenInOutputtingPhase()

        machine.requestOutputSubTransition(OutputSubPhase.STREAMING, triggerEvent)
        assertEquals(OutputSubPhase.STREAMING, machine.currentState.outputSubPhase)

        machine.requestOutputSubTransition(OutputSubPhase.FINALIZING, triggerEvent)
        assertEquals(OutputSubPhase.FINALIZING, machine.currentState.outputSubPhase)
    }

    @Test
    fun `invalid thinking sub-state transition is denied`() {
        givenInThinkingPhase()
        val result = machine.requestThinkingSubTransition(ThinkingSubPhase.PREPARING_OUTPUT, triggerEvent)
        assertTrue(result is SubStateTransitionResult.Denied)
    }

    @Test
    fun `invalid output sub-state transition is denied`() {
        givenInOutputtingPhase()
        val result = machine.requestOutputSubTransition(OutputSubPhase.GENERATING, triggerEvent)
        assertTrue(result is SubStateTransitionResult.Denied)
    }

    @Test
    fun `thinking sub-state transition when not in THINKING phase is denied`() {
        val result = machine.requestThinkingSubTransition(ThinkingSubPhase.ANALYZING_INPUT, triggerEvent)
        assertTrue(result is SubStateTransitionResult.Denied)
        assertEquals("Not in THINKING phase", (result as SubStateTransitionResult.Denied).reason)
    }

    @Test
    fun `output sub-state transition when not in OUTPUTTING phase is denied`() {
        val result = machine.requestOutputSubTransition(OutputSubPhase.STREAMING, triggerEvent)
        assertTrue(result is SubStateTransitionResult.Denied)
        assertEquals("Not in OUTPUTTING phase", (result as SubStateTransitionResult.Denied).reason)
    }

    @Test
    fun `handleEvent dispatches to correct phase handler`() = runTest {
        val handler = mock<StateHandler> {
            on { phase } doReturn SessionPhase.INPUTTING
            on { handleEvent(any(), any()) } doReturn SessionState(phase = SessionPhase.INPUTTING)
        }
        machine = SessionStateMachine(handlers = mapOf(SessionPhase.INPUTTING to handler))
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)

        machine.handleEvent(triggerEvent)

        verify(handler).handleEvent(any(), eq(triggerEvent))
    }

    @Test
    fun `handleEvent dispatches to thinking sub-handler`() = runTest {
        val subHandler = mock<SubStateHandler> {
            on { thinkingSubPhase } doReturn ThinkingSubPhase.INITIALIZING
            on { handle(any(), any()) } doReturn SessionState(phase = SessionPhase.THINKING, thinkingSubPhase = ThinkingSubPhase.INITIALIZING)
        }
        machine = SessionStateMachine(
            thinkingSubHandlers = mapOf(ThinkingSubPhase.INITIALIZING to subHandler)
        )

        givenInThinkingPhase()
        machine.handleEvent(triggerEvent)

        verify(subHandler).handle(any(), eq(triggerEvent))
    }

    @Test
    fun `handleEvent dispatches to output sub-handler`() = runTest {
        val subHandler = mock<SubStateHandler> {
            on { outputSubPhase } doReturn OutputSubPhase.GENERATING
            on { handle(any(), any()) } doReturn SessionState(phase = SessionPhase.OUTPUTTING, outputSubPhase = OutputSubPhase.GENERATING)
        }
        machine = SessionStateMachine(
            outputSubHandlers = mapOf(OutputSubPhase.GENERATING to subHandler)
        )

        givenInOutputtingPhase()
        machine.handleEvent(triggerEvent)

        verify(subHandler).handle(any(), eq(triggerEvent))
    }

    @Test
    fun `handleEvent returns current state when no handler matches`() = runTest {
        val result = machine.handleEvent(triggerEvent)
        assertEquals(SessionPhase.IDLE, result.phase)
    }

    @Test
    fun `onTransition callback is invoked on phase transition`() {
        val captured = mutableListOf<Triple<SessionPhase, SessionPhase, SessionEvent>>()
        machine = SessionStateMachine(
            onTransition = { from, to, event ->
                captured.add(Triple(from, to, event))
            }
        )

        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)

        assertEquals(1, captured.size)
        assertEquals(SessionPhase.IDLE, captured[0].first)
        assertEquals(SessionPhase.INPUTTING, captured[0].second)
        assertEquals(triggerEvent, captured[0].third)
    }

    @Test
    fun `onSubStateTransition callback is invoked on thinking sub-state transition`() {
        val captured = mutableListOf<Quad>()
        machine = SessionStateMachine(
            onSubStateTransition = { phase, from, to, event ->
                captured.add(Quad(phase, from, to, event))
            }
        )

        givenInThinkingPhase()
        machine.requestThinkingSubTransition(ThinkingSubPhase.ANALYZING_INPUT, triggerEvent)

        assertEquals(1, captured.size)
        assertEquals(SessionPhase.THINKING, captured[0].phase)
        assertEquals(ThinkingSubPhase.INITIALIZING.name, captured[0].from)
        assertEquals(ThinkingSubPhase.ANALYZING_INPUT.name, captured[0].to)
        assertEquals(triggerEvent, captured[0].event)
    }

    @Test
    fun `onSubStateTransition callback is invoked on output sub-state transition`() {
        val captured = mutableListOf<Quad>()
        machine = SessionStateMachine(
            onSubStateTransition = { phase, from, to, event ->
                captured.add(Quad(phase, from, to, event))
            }
        )

        givenInOutputtingPhase()
        machine.requestOutputSubTransition(OutputSubPhase.STREAMING, triggerEvent)

        assertEquals(1, captured.size)
        assertEquals(SessionPhase.OUTPUTTING, captured[0].phase)
        assertEquals(OutputSubPhase.GENERATING.name, captured[0].from)
        assertEquals(OutputSubPhase.STREAMING.name, captured[0].to)
        assertEquals(triggerEvent, captured[0].event)
    }

    @Test
    fun `updateProgress updates phaseProgress`() {
        machine.updateProgress(0.5f)
        assertEquals(0.5f, machine.currentState.phaseProgress, 0.001f)
    }

    @Test
    fun `updateProgress clamps value to 0`() {
        machine.updateProgress(-0.1f)
        assertEquals(0f, machine.currentState.phaseProgress, 0.001f)
    }

    @Test
    fun `updateProgress clamps value to 1`() {
        machine.updateProgress(1.5f)
        assertEquals(1f, machine.currentState.phaseProgress, 0.001f)
    }

    @Test
    fun `setError transitions to ERROR`() {
        val error = SessionError(code = "TEST_ERR", message = "test error")
        machine.setError(error)

        assertEquals(SessionPhase.ERROR, machine.currentState.phase)
        assertEquals(error, machine.currentState.error)
    }

    @Test
    fun `setError invokes onError callback`() {
        var capturedError: SessionError? = null
        machine = SessionStateMachine(
            onError = { capturedError = it }
        )

        val error = SessionError(code = "ERR", message = "msg")
        machine.setError(error)

        assertEquals(error, capturedError)
    }

    @Test
    fun `reset restores default state`() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        machine.setChatId("chat-42")
        machine.updateProgress(0.8f)

        machine.reset()

        assertEquals(SessionPhase.IDLE, machine.currentState.phase)
        assertEquals(0f, machine.currentState.phaseProgress, 0.001f)
        assertNull(machine.currentState.chatId)
    }

    @Test
    fun `currentState reflects latest state`() {
        val state = machine.currentState
        assertEquals(SessionPhase.IDLE, state.phase)
        assertFalse(state.isProcessing)
    }

    @Test
    fun `error transitions from ERROR back to IDLE`() {
        machine.setError(SessionError("E", "m"))
        assertEquals(SessionPhase.ERROR, machine.currentState.phase)

        val result = machine.requestTransition(SessionPhase.IDLE, triggerEvent)
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(SessionPhase.IDLE, machine.currentState.phase)
    }

    @Test
    fun `error transitions from ERROR to INPUTTING`() {
        machine.setError(SessionError("E", "m"))

        val result = machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(SessionPhase.INPUTTING, machine.currentState.phase)
    }

    private fun givenInThinkingPhase() {
        machine.requestTransition(SessionPhase.INPUTTING, triggerEvent)
        machine.requestTransition(SessionPhase.THINKING, triggerEvent)
        assertEquals(SessionPhase.THINKING, machine.currentState.phase)
    }

    private fun givenInOutputtingPhase() {
        givenInThinkingPhase()
        machine.requestTransition(SessionPhase.OUTPUTTING, triggerEvent)
        assertEquals(SessionPhase.OUTPUTTING, machine.currentState.phase)
    }

    private data class Quad(
        val phase: SessionPhase,
        val from: String?,
        val to: String,
        val event: SessionEvent
    )
}
