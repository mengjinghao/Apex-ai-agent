package com.apex.agent.kernel.model

import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {

    @Test
    fun `default state is IDLE with null sub-phases`() {
        val state = SessionState()
        assertEquals(SessionPhase.IDLE, state.phase)
        assertNull(state.chatId)
        assertNull(state.currentTurnId)
        assertNull(state.error)
        assertNull(state.thinkingSubPhase)
        assertNull(state.outputSubPhase)
        assertEquals(0f, state.phaseProgress, 0.001f)
        assertTrue(state.metadata.isEmpty())
    }

    @Test
    fun `isIdle is true when phase is IDLE`() {
        assertTrue(SessionState().isIdle)
        assertFalse(SessionState(phase = SessionPhase.INPUTTING).isIdle)
    }

    @Test
    fun `isProcessing is true for active processing phases`() {
        assertFalse(SessionState().isProcessing)
        assertTrue(SessionState(phase = SessionPhase.INPUTTING).isProcessing)
        assertTrue(SessionState(phase = SessionPhase.THINKING).isProcessing)
        assertTrue(SessionState(phase = SessionPhase.TOOL_EXECUTING).isProcessing)
        assertTrue(SessionState(phase = SessionPhase.OUTPUTTING).isProcessing)
        assertFalse(SessionState(phase = SessionPhase.ERROR).isProcessing)
        assertFalse(SessionState(phase = SessionPhase.IDLE).isProcessing)
    }

    @Test
    fun `isThinking is true when phase is THINKING`() {
        assertTrue(SessionState(phase = SessionPhase.THINKING).isThinking)
        assertFalse(SessionState(phase = SessionPhase.IDLE).isThinking)
        assertFalse(SessionState(phase = SessionPhase.OUTPUTTING).isThinking)
    }

    @Test
    fun `isOutputting is true when phase is OUTPUTTING`() {
        assertTrue(SessionState(phase = SessionPhase.OUTPUTTING).isOutputting)
        assertFalse(SessionState(phase = SessionPhase.THINKING).isOutputting)
        assertFalse(SessionState(phase = SessionPhase.IDLE).isOutputting)
    }

    @Test
    fun `copy preserves specified fields`() {
        val original = SessionState()
        val copied = original.copy(
            phase = SessionPhase.THINKING,
            chatId = "chat-1",
            currentTurnId = "turn-1",
            thinkingSubPhase = ThinkingSubPhase.REASONING,
            phaseProgress = 0.5f
        )

        assertEquals(SessionPhase.THINKING, copied.phase)
        assertEquals("chat-1", copied.chatId)
        assertEquals("turn-1", copied.currentTurnId)
        assertEquals(ThinkingSubPhase.REASONING, copied.thinkingSubPhase)
        assertEquals(0.5f, copied.phaseProgress, 0.001f)

        assertEquals(SessionPhase.IDLE, original.phase)
    }

    @Test
    fun `copy does not mutate original`() {
        val original = SessionState(phase = SessionPhase.IDLE)
        original.copy(phase = SessionPhase.THINKING)
        assertEquals(SessionPhase.IDLE, original.phase)
    }

    @Test
    fun `SessionEvent UserInputReceived has correct fields`() {
        val event = SessionEvent.UserInputReceived("sid-1", "hello", listOf(
            AttachmentRef("a1", "file://pic.jpg", "image/jpeg")
        ))
        assertEquals("sid-1", event.sessionId)
        assertEquals("hello", event.content)
        assertEquals(1, event.attachments.size)
    }

    @Test
    fun `SessionEvent ThinkingStarted has correct fields`() {
        val event = SessionEvent.ThinkingStarted("sid-1", "gpt-4")
        assertEquals("gpt-4", event.modelId)
    }

    @Test
    fun `SessionEvent ChunkReceived has correct fields`() {
        val event = SessionEvent.ChunkReceived("sid-1", "Hello", "Hello World")
        assertEquals("Hello", event.chunk)
        assertEquals("Hello World", event.accumulated)
    }

    @Test
    fun `SessionEvent ToolCallDetected has correct fields`() {
        val params = mapOf("url" to "https://example.com")
        val event = SessionEvent.ToolCallDetected("sid-1", "fetch", params)
        assertEquals("fetch", event.toolName)
        assertEquals(params, event.parameters)
    }

    @Test
    fun `SessionEvent ToolExecutionStarted and Completed`() {
        val started = SessionEvent.ToolExecutionStarted("sid-1", "fetch", "inv-1")
        assertEquals("fetch", started.toolName)
        assertEquals("inv-1", started.invocationId)

        val result = ToolResultData(success = true, data = "response")
        val completed = SessionEvent.ToolExecutionCompleted("sid-1", "fetch", "inv-1", result)
        assertEquals(result, completed.result)
    }

    @Test
    fun `SessionEvent ToolExecutionFailed has correct fields`() {
        val event = SessionEvent.ToolExecutionFailed("sid-1", "fetch", "inv-1", "timeout")
        assertEquals("timeout", event.error)
    }

    @Test
    fun `SessionEvent TurnCompleted has correct fields`() {
        val event = SessionEvent.TurnCompleted("sid-1", 100, 50, 3)
        assertEquals(100, event.inputTokens)
        assertEquals(50, event.outputTokens)
        assertEquals(3, event.toolCallCount)
    }

    @Test
    fun `SessionEvent ErrorOccurred has correct fields`() {
        val error = SessionError("ERR_001", "Something went wrong")
        val event = SessionEvent.ErrorOccurred("sid-1", error)
        assertEquals(error, event.error)
    }

    @Test
    fun `SessionEvent PhaseChanged has correct fields`() {
        val event = SessionEvent.PhaseChanged("sid-1", SessionPhase.INPUTTING, SessionPhase.THINKING)
        assertEquals(SessionPhase.INPUTTING, event.from)
        assertEquals(SessionPhase.THINKING, event.to)
    }

    @Test
    fun `SessionEvent SubStateChanged has correct fields`() {
        val event = SessionEvent.SubStateChanged(
            sessionId = "sid-1",
            parentPhase = SessionPhase.THINKING,
            fromSubState = "INITIALIZING",
            toSubState = "ANALYZING_INPUT",
            progress = 0.3f
        )
        assertEquals(SessionPhase.THINKING, event.parentPhase)
        assertEquals("INITIALIZING", event.fromSubState)
        assertEquals("ANALYZING_INPUT", event.toSubState)
        assertEquals(0.3f, event.progress, 0.001f)
    }

    @Test
    fun `SessionEvent timestamp is set to current time`() {
        val before = System.currentTimeMillis()
        val event = SessionEvent.UserInputReceived("sid-1", "hi")
        val after = System.currentTimeMillis()
        assertTrue(event.timestamp in before..after)
    }

    @Test
    fun `SessionError has correct fields`() {
        val cause = RuntimeException("root cause")
        val error = SessionError(
            code = "ERR_002",
            message = "Recoverable error",
            throwable = cause,
            recoverable = true
        )
        assertEquals("ERR_002", error.code)
        assertEquals("Recoverable error", error.message)
        assertEquals(cause, error.throwable)
        assertTrue(error.recoverable)
    }

    @Test
    fun `SessionError default recoverable is false`() {
        val error = SessionError("E", "m")
        assertFalse(error.recoverable)
        assertNull(error.throwable)
    }

    @Test
    fun `AttachmentRef has correct fields`() {
        val ref = AttachmentRef(id = "att-1", uri = "file://doc.pdf", mimeType = "application/pdf", sizeBytes = 1024)
        assertEquals("att-1", ref.id)
        assertEquals(1024L, ref.sizeBytes)
    }

    @Test
    fun `ToolResultData success variant`() {
        val result = ToolResultData(success = true, data = "ok")
        assertTrue(result.success)
        assertEquals("ok", result.data)
        assertNull(result.error)
    }

    @Test
    fun `ToolResultData failure variant`() {
        val result = ToolResultData(success = false, error = "failed")
        assertFalse(result.success)
        assertEquals("failed", result.error)
        assertNull(result.data)
    }

    @Test
    fun `all SessionEvent subtypes are sealed`() {
        val events: List<SessionEvent> = listOf(
            SessionEvent.UserInputReceived("s", ""),
            SessionEvent.UserInputSanitized("s", "", ""),
            SessionEvent.ContextPrepared("s", 1, 100),
            SessionEvent.ThinkingStarted("s", "m"),
            SessionEvent.ChunkReceived("s", "", ""),
            SessionEvent.ToolCallDetected("s", "", emptyMap()),
            SessionEvent.ToolExecutionStarted("s", "", ""),
            SessionEvent.ToolExecutionCompleted("s", "", "", ToolResultData(true)),
            SessionEvent.ToolExecutionFailed("s", "", "", ""),
            SessionEvent.TurnCompleted("s", 0, 0, 0),
            SessionEvent.ErrorOccurred("s", SessionError("E", "")),
            SessionEvent.PhaseChanged("s", SessionPhase.IDLE, SessionPhase.INPUTTING),
            SessionEvent.SubStateChanged("s", SessionPhase.THINKING, null, "INITIALIZING")
        )
        assertEquals(13, events.size)
    }
}
