package com.apex.agent.kernel.interaction.awareness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IntentClarifierTest {

    private lateinit var clarifier: IntentClarifier

    @Before
    fun setup() {
        clarifier = IntentClarifier()
    }

    // --- clarify for different query types ---

    @Test
    fun `analyze detects question starting with what`() = runTest {
        val result = clarifier.analyze("What is the capital of France?")

        assertEquals(IntentCategory.QUESTION, result.category)
        assertFalse(result.needsClarification)
        assertTrue(result.confidence >= 0.5f)
    }

    @Test
    fun `analyze detects question starting with how`() = runTest {
        val result = clarifier.analyze("How do I install the agent?")

        assertEquals(IntentCategory.QUESTION, result.category)
    }

    @Test
    fun `analyze detects question with question mark`() = runTest {
        val result = clarifier.analyze("Is this going to work?")

        assertEquals(IntentCategory.QUESTION, result.category)
    }

    @Test
    fun `analyze detects command starting with run`() = runTest {
        val result = clarifier.analyze("run the build script")

        assertEquals(IntentCategory.COMMAND, result.category)
    }

    @Test
    fun `analyze detects command starting with execute`() = runTest {
        val result = clarifier.analyze("execute the test suite")

        assertEquals(IntentCategory.COMMAND, result.category)
    }

    @Test
    fun `analyze detects command starting with delete`() = runTest {
        val result = clarifier.analyze("delete all temporary files")

        assertEquals(IntentCategory.COMMAND, result.category)
    }

    @Test
    fun `analyze detects request starting with translate`() = runTest {
        val result = clarifier.analyze("translate this text to French")

        assertEquals(IntentCategory.REQUEST, result.category)
    }

    @Test
    fun `analyze detects request starting with explain`() = runTest {
        val result = clarifier.analyze("explain how recursion works")

        assertEquals(IntentCategory.REQUEST, result.category)
    }

    @Test
    fun `analyze detects request starting with write`() = runTest {
        val result = clarifier.analyze("write a poem about AI")

        assertEquals(IntentCategory.REQUEST, result.category)
    }

    @Test
    fun `analyze detects feedback`() = runTest {
        val result = clarifier.analyze("good job on the explanation")

        assertEquals(IntentCategory.FEEDBACK, result.category)
    }

    @Test
    fun `analyze detects small talk`() = runTest {
        val result = clarifier.analyze("hi")

        assertEquals(IntentCategory.SMALL_TALK, result.category)
    }

    @Test
    fun `analyze returns UNKNOWN for unrecognized input`() = runTest {
        val result = clarifier.analyze("florb garble schnitzel")

        assertEquals(IntentCategory.UNKNOWN, result.category)
    }

    // --- confidence calculation ---

    @Test
    fun `analyze returns high confidence for clear questions`() = runTest {
        val result = clarifier.analyze("What is machine learning?")

        assertEquals(0.7f, result.confidence, 0.001f)
    }

    @Test
    fun `analyze returns low confidence for unknown category`() = runTest {
        val result = clarifier.analyze("xyzzzy")

        assertEquals(0.3f, result.confidence, 0.001f)
    }

    @Test
    fun `analyze sets needsClarification for UNKNOWN category`() = runTest {
        val result = clarifier.analyze("blah blah blah")

        assertTrue(result.needsClarification)
        assertNotNull(result.clarificationRequest)
    }

    @Test
    fun `analyze sets needsClarification for ambiguous short input`() = runTest {
        val result = clarifier.analyze("it")

        assertTrue(result.needsClarification)
    }

    @Test
    fun `analyze sets needsClarification for single word short input`() = runTest {
        val result = clarifier.analyze("ok")

        assertTrue(result.needsClarification)
    }

    @Test
    fun `analyze does not set needsClarification for clear questions`() = runTest {
        val result = clarifier.analyze("What is the weather today?")

        assertFalse(result.needsClarification)
        assertNull(result.clarificationRequest)
    }

    // --- suggestClarification produces clarifying questions ---

    @Test
    fun `clarificationRequest contains options for vague queries`() = runTest {
        val result = clarifier.analyze("do it")

        assertNotNull(result.clarificationRequest)
        assertEquals(AmbiguityType.MULTIPLE_INTERPRETATIONS, result.clarificationRequest!!.ambiguityType)
        assertEquals("do it", result.clarificationRequest!!.originalInput)
    }

    @Test
    fun `generateOptions returns four standard options`() = runTest {
        val options = clarifier.generateOptions("anything")

        assertEquals(4, options.size)
        assertEquals("ask_question", options[0].id)
        assertEquals("give_command", options[1].id)
        assertEquals("make_request", options[2].id)
        assertEquals("continue_discussion", options[3].id)
    }

    @Test
    fun `generateOptions returns options with labels`() = runTest {
        val options = clarifier.generateOptions("test")

        assertTrue(options.all { it.label.isNotBlank() })
        assertTrue(options.all { it.description.isNotBlank() })
    }

    @Test
    fun `resolveAmbiguity resolves to QUESTION category`() = runTest {
        val result = clarifier.resolveAmbiguity("vague input", "ask_question")

        assertEquals(IntentCategory.QUESTION, result.category)
        assertEquals(0.8f, result.confidence, 0.001f)
        assertFalse(result.needsClarification)
        assertNull(result.clarificationRequest)
    }

    @Test
    fun `resolveAmbiguity resolves to COMMAND category`() = runTest {
        val result = clarifier.resolveAmbiguity("vague input", "give_command")

        assertEquals(IntentCategory.COMMAND, result.category)
    }

    @Test
    fun `resolveAmbiguity resolves to REQUEST category`() = runTest {
        val result = clarifier.resolveAmbiguity("vague input", "make_request")

        assertEquals(IntentCategory.REQUEST, result.category)
    }

    @Test
    fun `resolveAmbiguity defaults to UNKNOWN for unrecognized option`() = runTest {
        val result = clarifier.resolveAmbiguity("vague input", "random_option")

        assertEquals(IntentCategory.UNKNOWN, result.category)
    }

    @Test
    fun `analyze with context parameter passes through`() = runTest {
        val context = mapOf("sessionId" to "abc-123", "userId" to "user-1")

        val result = clarifier.analyze("What is this?", context)

        assertEquals(IntentCategory.QUESTION, result.category)
    }

    @Test
    fun `IntentResult primaryIntent is truncated to 80 chars`() = runTest {
        val longInput = "A".repeat(200)
        val result = clarifier.analyze(longInput)

        assertEquals(80, result.primaryIntent.length)
    }

    @Test
    fun `intent with multiple question marks is ambiguous`() = runTest {
        val result = clarifier.analyze("What? Why? How?")

        assertEquals(IntentCategory.QUESTION, result.category)
        assertTrue(result.needsClarification)
    }
}
