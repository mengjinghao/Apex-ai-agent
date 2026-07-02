package com.apex.agent.kernel.interaction.multimodal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CrossModalAdapterTest {

    private lateinit var adapter: CrossModalAdapter

    @Before
    fun setup() {
        adapter = CrossModalAdapter()
    }

    // --- canConvert ---

    @Test
    fun `canConvert returns true for supported conversions`() = runTest {
        assertTrue(adapter.canConvert(Modality.TEXT, Modality.VOICE))
        assertTrue(adapter.canConvert(Modality.VOICE, Modality.TEXT))
        assertTrue(adapter.canConvert(Modality.TEXT, Modality.CHART))
        assertTrue(adapter.canConvert(Modality.TEXT, Modality.FLOWCHART))
        assertTrue(adapter.canConvert(Modality.DATA_TABLE, Modality.CHART))
        assertTrue(adapter.canConvert(Modality.TEXT, Modality.DATA_TABLE))
        assertTrue(adapter.canConvert(Modality.CODE, Modality.TEXT))
        assertTrue(adapter.canConvert(Modality.TEXT, Modality.IMAGE))
        assertTrue(adapter.canConvert(Modality.IMAGE, Modality.TEXT))
    }

    @Test
    fun `canConvert returns false for unsupported conversions`() = runTest {
        assertFalse(adapter.canConvert(Modality.CODE, Modality.VOICE))
        assertFalse(adapter.canConvert(Modality.VIDEO, Modality.TEXT))
        assertFalse(adapter.canConvert(Modality.CHART, Modality.FLOWCHART))
        assertFalse(adapter.canConvert(Modality.FLOWCHART, Modality.CHART))
        assertFalse(adapter.canConvert(Modality.VOICE, Modality.IMAGE))
    }

    @Test
    fun `convert returns failure for unsupported conversion`() = runTest {
        val request = CrossModalRequest(
            sourceModality = Modality.CODE,
            targetModality = Modality.VOICE,
            content = "val x = 1"
        )

        val result = adapter.convert(request)

        assertFalse(result.success)
        assertEquals(0f, result.quality, 0.001f)
        assertTrue(result.warnings.any { it.contains("Unsupported conversion") })
    }

    @Test
    fun `listSupportedConversions returns all supported pairs`() = runTest {
        val conversions = adapter.listSupportedConversions()

        assertEquals(9, conversions.size)
        assertTrue(conversions.contains(Modality.TEXT to Modality.VOICE))
        assertTrue(conversions.contains(Modality.TEXT to Modality.CHART))
        assertTrue(conversions.contains(Modality.TEXT to Modality.FLOWCHART))
    }

    // --- textToVoice SSML generation ---

    @Test
    fun `convert text to voice produces SSML`() = runTest {
        val text = """# Main Title
## Sub Title
- List item
Some paragraph text."""

        val request = CrossModalRequest(Modality.TEXT, Modality.VOICE, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertEquals(Modality.VOICE, result.targetModality)
        assertTrue(result.convertedContent.startsWith("<speak>"))
        assertTrue(result.convertedContent.endsWith("</speak>"))
        assertTrue(result.convertedContent.contains("<emphasis level=\"strong\">"))
        assertTrue(result.convertedContent.contains("<emphasis level=\"moderate\">"))
        assertTrue(result.convertedContent.contains("<s>"))
    }

    @Test
    fun `textToVoice adds warnings for code blocks`() = runTest {
        val text = "Some text\n```\ncode block\n```\nmore text"
        val request = CrossModalRequest(Modality.TEXT, Modality.VOICE, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Code blocks") })
    }

    @Test
    fun `textToVoice adds warning for long text`() = runTest {
        val text = "A".repeat(4000)
        val request = CrossModalRequest(Modality.TEXT, Modality.VOICE, text)
        val result = adapter.convert(request)

        assertTrue(result.warnings.any { it.contains("voice duration") })
    }

    // --- textToChart data extraction ---

    @Test
    fun `convert text to chart extracts numeric data`() = runTest {
        val text = """
            Q1 Revenue: 1000000
            Q2 Revenue: 1500000
            Q3 Revenue: 2000000
            Q4 Revenue: 2500000
        """.trimIndent()

        val request = CrossModalRequest(Modality.TEXT, Modality.CHART, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertEquals(Modality.CHART, result.targetModality)
        assertTrue(result.convertedContent.contains("\"type\": \"bar\""))
        assertTrue(result.convertedContent.contains("\"labels\""))
        assertTrue(result.convertedContent.contains("\"datasets\""))
    }

    @Test
    fun `textToChart adds warning for less than 3 data points`() = runTest {
        val text = "Value: 42"
        val request = CrossModalRequest(Modality.TEXT, Modality.CHART, text)
        val result = adapter.convert(request)

        assertTrue(result.warnings.any { it.contains("Less than 3") })
    }

    @Test
    fun `textToChart handles text with no numbers`() = runTest {
        val text = "Just words without any digits"
        val request = CrossModalRequest(Modality.TEXT, Modality.CHART, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        // Falls back to "Value" with 0.0
        assertTrue(result.convertedContent.contains("\"Value\""))
    }

    // --- textToFlowchart Mermaid generation ---

    @Test
    fun `convert text to flowchart produces Mermaid`() = runTest {
        val text = """
            1. Initialize the system
            2. Load configuration
            3. Start processing
        """.trimIndent()

        val request = CrossModalRequest(Modality.TEXT, Modality.FLOWCHART, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertEquals(Modality.FLOWCHART, result.targetModality)
        assertTrue(result.convertedContent.startsWith("flowchart TD"))
        assertTrue(result.convertedContent.contains("S1"))
        assertTrue(result.convertedContent.contains("-->"))
    }

    @Test
    fun `textToFlowchart handles step prefix format`() = runTest {
        val text = "Step 1: First action\nStep 2: Second action\nStep 3: Third action"
        val request = CrossModalRequest(Modality.TEXT, Modality.FLOWCHART, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("First action"))
        assertTrue(result.convertedContent.contains("Second action"))
    }

    @Test
    fun `textToFlowchart falls back to sentence splitting when no steps`() = runTest {
        val text = "This is a long sentence that describes a process. Here is another step in the process. And this concludes the process."
        val request = CrossModalRequest(Modality.TEXT, Modality.FLOWCHART, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("flowchart TD"))
    }

    // --- textToDataTable Markdown table generation ---

    @Test
    fun `convert text to data table produces Markdown table`() = runTest {
        val text = """
            Product, Price, Rating
            Widget A, 100, 4.5
            Widget B, 200, 4.0
            Widget C, 150, 4.2
        """.trimIndent()

        val request = CrossModalRequest(Modality.TEXT, Modality.DATA_TABLE, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertEquals(Modality.DATA_TABLE, result.targetModality)
        assertTrue(result.convertedContent.contains("|"))
        assertTrue(result.convertedContent.contains("---"))
        assertTrue(result.convertedContent.contains("Widget A"))
    }

    @Test
    fun `textToDataTable handles pipe-delimited input`() = runTest {
        val text = """
            | Name | Age | City |
            | Bob | 30 | NY |
            | Alice | 25 | LA |
        """.trimIndent()

        val request = CrossModalRequest(Modality.TEXT, Modality.DATA_TABLE, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("Name"))
        assertTrue(result.convertedContent.contains("Age"))
    }

    @Test
    fun `textToDataTable falls back to sentence splitting when no table structure`() = runTest {
        val text = "This is a long sentence about something important. Here is another sentence with more details."
        val request = CrossModalRequest(Modality.TEXT, Modality.DATA_TABLE, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("|"))
    }

    // --- conversion from other modalities to TEXT ---

    @Test
    fun `convert voice to text strips SSML tags`() = runTest {
        val text = "<speak><s>Hello world</s></speak>"
        val request = CrossModalRequest(Modality.VOICE, Modality.TEXT, text)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertEquals(Modality.TEXT, result.targetModality)
        assertFalse(result.convertedContent.contains("<"))
    }

    @Test
    fun `convert code to text produces summary`() = runTest {
        val code = "```kotlin\nval x = 1\nval y = 2\n```"
        val request = CrossModalRequest(Modality.CODE, Modality.TEXT, code)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("Code Summary"))
        assertTrue(result.convertedContent.contains("kotlin block"))
    }

    @Test
    fun `convert chart to text extracts data summary`() = runTest {
        val chartData = """{"type": "bar", "data": [1, 2, 3]}"""
        val request = CrossModalRequest(Modality.CHART, Modality.TEXT, chartData)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.startsWith("Chart data extracted"))
    }

    @Test
    fun `convert image to text returns description with warning`() = runTest {
        val imageDesc = "A red car on a sunny road"
        val request = CrossModalRequest(Modality.IMAGE, Modality.TEXT, imageDesc)
        val result = adapter.convert(request)

        assertTrue(result.success)
        assertTrue(result.convertedContent.contains("[Image description:"))
        assertTrue(result.warnings.any { it.contains("may not be fully accurate") })
    }
}
