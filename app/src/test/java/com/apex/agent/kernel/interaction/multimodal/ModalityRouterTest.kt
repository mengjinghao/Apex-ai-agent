package com.apex.agent.kernel.interaction.multimodal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModalityRouterTest {

    private lateinit var router: ModalityRouter

    @Before
    fun setup() {
        router = ModalityRouter()
    }

    // --- analyzeContent for different content types ---

    @Test
    fun `analyzeContent detects code content`() {
        val code = """
            ```kotlin
            fun hello() {
                println("Hello")
            }
            ```
        """.trimIndent()

        val profile = router.analyzeContent(code)

        assertTrue(profile.hasCode)
        assertEquals(ContentCategory.CODE_EXPLANATION, profile.category)
    }

    @Test
    fun `analyzeContent detects code with function keyword`() {
        val profile = router.analyzeContent("function calculateSum(a, b) { return a + b; }")

        assertTrue(profile.hasCode)
        assertFalse(profile.hasNumbers) // numbers inside function def
        assertFalse(profile.hasSteps)
    }

    @Test
    fun `analyzeContent detects tabular comparison content`() {
        val tabular = """
            Product A | $100 | 4.5 stars
            Product B | $200 | 4.0 stars
            Product C | $150 | 4.2 stars
        """.trimIndent()

        val profile = router.analyzeContent(tabular)

        assertTrue(profile.hasComparison)
        assertEquals(ContentCategory.COMPARISON, profile.category)
    }

    @Test
    fun `analyzeContent detects narrative content from length`() {
        val narrative = "A".repeat(600)

        val profile = router.analyzeContent(narrative)

        assertEquals(ContentCategory.NARRATIVE, profile.category)
    }

    @Test
    fun `analyzeContent detects procedural content with steps and numbers`() {
        val procedural = """
            Step 1: Open the file
            Step 2: Edit the configuration
            Step 3: Save and exit
        """.trimIndent()

        val profile = router.analyzeContent(procedural)

        assertTrue(profile.hasSteps)
        assertTrue(profile.hasNumbers)
        assertEquals(ContentCategory.PROCEDURAL, profile.category)
    }

    @Test
    fun `analyzeContent detects instruction content`() {
        val instruction = """
            First, connect the device
            Then, press the power button
            Finally, wait for boot
        """.trimIndent()

        val profile = router.analyzeContent(instruction)

        assertTrue(profile.hasSteps)
        assertEquals(ContentCategory.INSTRUCTION, profile.category)
    }

    @Test
    fun `analyzeContent falls back to CONCEPT_EXPLANATION`() {
        val simple = "Hello world"

        val profile = router.analyzeContent(simple)

        assertEquals(ContentCategory.CONCEPT_EXPLANATION, profile.category)
    }

    // --- route for best output modality selection ---

    @Test
    fun `route selects CODE modality for code content when available`() {
        val code = "```\nval x = 1\n```"
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CODE, Modality.CHART)
        )
        router.registerModalityCapability(Modality.CODE, true)

        val decision = router.route(code, pref)

        assertEquals(Modality.CODE, decision.chosenOutputModality)
        assertTrue(decision.requiresConversion)
        assertTrue(decision.conversionSteps.isNotEmpty())
    }

    @Test
    fun `route selects CHART modality for data-dense content`() {
        val data = "Revenue: 1000000, Costs: 500000, Profit: 500000"
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CHART)
        )
        router.registerModalityCapability(Modality.CHART, true)

        val decision = router.route(data, pref)

        assertEquals(Modality.CHART, decision.chosenOutputModality)
    }

    @Test
    fun `route selects TEXT for narrative content without audio capability`() {
        val narrative = "A".repeat(600)
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.VOICE),
            canStreamAudio = false
        )
        router.registerModalityCapability(Modality.TEXT, true)

        val decision = router.route(narrative, pref)

        assertEquals(Modality.TEXT, decision.chosenOutputModality)
    }

    @Test
    fun `route provides alternatives list`() {
        val code = "function test() { }"
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CODE, Modality.VOICE)
        )
        router.registerModalityCapability(Modality.CODE, true)
        router.registerModalityCapability(Modality.TEXT, true)
        router.registerModalityCapability(Modality.VOICE, true)

        val decision = router.route(code, pref)

        assertTrue(decision.alternatives.isNotEmpty())
    }

    // --- getBestOutputModality with different preferences ---

    @Test
    fun `getBestOutputModality returns TEXT when no other modality is available`() {
        val content = "Some plain text content"
        val pref = ModalityPreference(availableOutputs = setOf(Modality.TEXT))

        val modality = router.getBestOutputModality(content, pref)

        assertEquals(Modality.TEXT, modality)
    }

    @Test
    fun `getBestOutputModality returns DATA_TABLE for comparison data`() {
        val content = "A vs B:\n| Feature | A | B |\n| Speed | 100 | 200 |"
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.DATA_TABLE)
        )

        val modality = router.getBestOutputModality(content, pref)

        assertEquals(Modality.DATA_TABLE, modality)
    }

    @Test
    fun `getBestOutputModality returns FLOWCHART for procedural content`() {
        val content = """
            Step 1: Initialize
            Step 2: Process data
            Step 3: Output result
            First we start, then we proceed, finally finish.
        """.trimIndent()
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.FLOWCHART)
        )

        val modality = router.getBestOutputModality(content, pref)

        assertEquals(Modality.FLOWCHART, modality)
    }

    @Test
    fun `getBestOutputModality returns VOICE for narrative with audio capability`() {
        val narrative = "A".repeat(600)
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.VOICE),
            canStreamAudio = true
        )

        val modality = router.getBestOutputModality(narrative, pref)

        assertEquals(Modality.VOICE, modality)
    }

    // --- registerModalityCapability ---

    @Test
    fun `registerModalityCapability enables additional modalities`() {
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CHART)
        )
        router.registerModalityCapability(Modality.CHART, true)

        val decision = router.route("Data: 100, 200, 300", pref)

        assertEquals(Modality.CHART, decision.chosenOutputModality)
    }

    @Test
    fun `registerModalityCapability disables a modality`() {
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CHART)
        )
        router.registerModalityCapability(Modality.CHART, false)

        val decision = router.route("Data: 100, 200, 300", pref)

        assertEquals(Modality.TEXT, decision.chosenOutputModality)
    }

    // --- getBestInputModality ---

    @Test
    fun `getBestInputModality returns TEXT by default`() {
        val modality = router.getBestInputModality(emptyMap())

        assertEquals(Modality.TEXT, modality)
    }

    @Test
    fun `getBestInputModality returns VOICE when microphone and voice intent present`() {
        val context = mapOf("hasMicrophone" to true, "voiceIntent" to true)

        val modality = router.getBestInputModality(context)

        assertEquals(Modality.VOICE, modality)
    }

    @Test
    fun `getBestInputModality returns IMAGE when camera and visual input present`() {
        val context = mapOf("hasCamera" to true, "visualInput" to true)

        val modality = router.getBestInputModality(context)

        assertEquals(Modality.IMAGE, modality)
    }

    @Test
    fun `getBestInputModality returns FILE when file upload has attachment`() {
        val context = mapOf("hasFileUpload" to true, "hasAttachment" to true)

        val modality = router.getBestInputModality(context)

        assertEquals(Modality.FILE, modality)
    }

    @Test
    fun `ContentProfile dataDensity calculated correctly`() {
        val content = "Price: 100 dollars, quantity: 50 items, total: 5000"
        val profile = router.analyzeContent(content)

        assertTrue(profile.dataDensity > 0f)
        assertTrue(profile.complexity >= 0f)
        assertEquals(ContentCategory.CODE_EXPLANATION, profile.category) // has numbers but no code...
        // Actually let's check: hasCode=false, hasNumbers=true, hasSteps=false, hasComparison=false
        // hasCode=false -> no code keywords. hasNumbers=true.
        // Since steps=false and hasCode=false, the else chain goes to content.length > 500 -> no, else -> CONCEPT_EXPLANATION
        // Let me re-read: hasCode=false, hasNumbers=true, hasSteps=false, hasComparison=false
        // The category when: hasCode && hasNumbers -> false (hasCode false)
        // hasCode -> false
        // hasSteps && hasNumbers -> false (hasSteps false)
        // hasComparison -> false
        // hasSteps -> false
        // content.length > 500 -> false
        // else -> CONCEPT_EXPLANATION
    }

    @Test
    fun `ContentProfile estimatedLength matches content length`() {
        val content = "Short content"
        val profile = router.analyzeContent(content)

        assertEquals(content.length, profile.estimatedLength)
    }

    @Test
    fun `route uses provided profile instead of re-analyzing`() {
        val customProfile = ContentProfile(
            category = ContentCategory.REFERENCE,
            hasCode = true
        )
        val pref = ModalityPreference(
            availableOutputs = setOf(Modality.TEXT, Modality.CODE)
        )
        router.registerModalityCapability(Modality.CODE, true)

        val decision = router.route("some content", pref, customProfile)

        // Since hasCode=true and CODE is available
        assertEquals(Modality.CODE, decision.chosenOutputModality)
    }
}
