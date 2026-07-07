package com.apex.agent.kernel.interaction.progressive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContentLayerTest {

    private lateinit var layerManager: ContentLayerManager

    @Before
    fun setup() {
        layerManager = ContentLayerManager()
    }

    // --- ContentLayerManager layer creation for all ContentDepth values ---

    @Test
    fun `createLayer creates layers for all ContentDepth values`() {
        for (depth in ContentDepth.values()) {
            val layer = layerManager.createLayer(depth, "content for $depth", "summary of $depth")

            assertEquals(depth, layer.depth)
            assertEquals("content for $depth", layer.content)
            assertEquals("summary of $depth", layer.summary)
            assertTrue(layer.id.startsWith("layer_"))
            assertFalse(layer.isComplete)
            assertTrue(layer.expandableItems.isEmpty())
        }
    }

    @Test
    fun `createLayer overwrites existing layer at same depth`() {
        val first = layerManager.createLayer(ContentDepth.CORE_THESIS, "original", "summary1")
        val second = layerManager.createLayer(ContentDepth.CORE_THESIS, "updated", "summary2")

        val retrieved = layerManager.getLayer(ContentDepth.CORE_THESIS)
        assertNotNull(retrieved)
        assertEquals("updated", retrieved!!.content)
        assertEquals(second.id, retrieved.id)
    }

    // --- addExpandableItem and updateLayer ---

    @Test
    fun `addExpandableItem adds item to layer`() {
        val layer = layerManager.createLayer(ContentDepth.KEY_ARGUMENTS, "args", "summary")
        val item = ExpandableItem(
            id = "item-1",
            label = "Detail A",
            summary = "Extra detail",
            detailContent = "Full detail content here",
            contentType = ItemType.TEXT
        )

        layerManager.addExpandableItem(layer.id, item)

        val updated = layerManager.getLayer(ContentDepth.KEY_ARGUMENTS)
        assertEquals(1, updated!!.expandableItems.size)
        assertEquals("item-1", updated.expandableItems[0].id)
        assertEquals("Detail A", updated.expandableItems[0].label)
        assertEquals(ItemType.TEXT, updated.expandableItems[0].contentType)
    }

    @Test
    fun `addExpandableItem adds multiple items`() {
        val layer = layerManager.createLayer(ContentDepth.DETAILED_EXPLANATION, "detail", "summary")

        val items = listOf(
            ExpandableItem("c1", "Code example", "code", contentType = ItemType.CODE),
            ExpandableItem("d1", "Data table", "table", contentType = ItemType.DATA_TABLE),
            ExpandableItem("r1", "Reference", "ref", contentType = ItemType.REFERENCE)
        )

        items.forEach { layerManager.addExpandableItem(layer.id, it) }

        val updated = layerManager.getLayer(ContentDepth.DETAILED_EXPLANATION)
        assertEquals(3, updated!!.expandableItems.size)
    }

    @Test
    fun `addExpandableItem with non-existent layerId does nothing`() {
        val item = ExpandableItem("x", "label", "summary")
        layerManager.addExpandableItem("non-existent", item)

        assertTrue(layerManager.getAllLayers().isEmpty())
    }

    @Test
    fun `updateLayer updates content and completion state`() {
        val layer = layerManager.createLayer(ContentDepth.CORE_THESIS, "original", "summary")

        layerManager.updateLayer(layer.id, "revised content", true)

        val updated = layerManager.getLayer(ContentDepth.CORE_THESIS)
        assertEquals("revised content", updated!!.content)
        assertTrue(updated.isComplete)
    }

    @Test
    fun `updateLayer with non-existent layerId does nothing`() {
        layerManager.createLayer(ContentDepth.CORE_THESIS, "content", "summary")

        layerManager.updateLayer("non-existent", "new content", true)

        val layer = layerManager.getLayer(ContentDepth.CORE_THESIS)
        assertEquals("content", layer!!.content)
        assertFalse(layer.isComplete)
    }

    // --- getAllLayers ordering ---

    @Test
    fun `getAllLayers returns layers in ContentDepth enum order`() {
        layerManager.createLayer(ContentDepth.DETAILED_EXPLANATION, "detail", "summary3")
        layerManager.createLayer(ContentDepth.KEY_ARGUMENTS, "args", "summary2")
        layerManager.createLayer(ContentDepth.CORE_THESIS, "thesis", "summary1")

        val allLayers = layerManager.getAllLayers()

        assertEquals(3, allLayers.size)
        assertEquals(ContentDepth.CORE_THESIS, allLayers[0].depth)
        assertEquals(ContentDepth.KEY_ARGUMENTS, allLayers[1].depth)
        assertEquals(ContentDepth.DETAILED_EXPLANATION, allLayers[2].depth)
    }

    @Test
    fun `getAllLayers returns empty list when no layers exist`() {
        assertTrue(layerManager.getAllLayers().isEmpty())
    }

    @Test
    fun `getAllLayers only returns created layers`() {
        layerManager.createLayer(ContentDepth.CORE_THESIS, "thesis", "summary")

        val allLayers = layerManager.getAllLayers()
        assertEquals(1, allLayers.size)
        assertEquals(ContentDepth.CORE_THESIS, allLayers[0].depth)
    }

    // --- clear removes all layers ---

    @Test
    fun `clear removes all layers`() {
        layerManager.createLayer(ContentDepth.CORE_THESIS, "a", "s1")
        layerManager.createLayer(ContentDepth.KEY_ARGUMENTS, "b", "s2")
        layerManager.createLayer(ContentDepth.DETAILED_EXPLANATION, "c", "s3")
        assertEquals(3, layerManager.getAllLayers().size)

        layerManager.clear()

        assertTrue(layerManager.getAllLayers().isEmpty())
        assertNull(layerManager.getLayer(ContentDepth.CORE_THESIS))
        assertNull(layerManager.getLayer(ContentDepth.KEY_ARGUMENTS))
        assertNull(layerManager.getLayer(ContentDepth.DETAILED_EXPLANATION))
    }

    // --- ChunkedOutputStream emit/pause/resume/cancel state transitions ---

    @Test
    fun `ChunkedOutputStream starts in STREAMING state`() {
        val stream = ChunkedOutputStream()
        assertEquals(StreamState.STREAMING, stream.status.state)
        assertEquals(0, stream.status.chunksDelivered)
    }

    @Test
    fun `emit delivers chunk and updates counters`() {
        val stream = ChunkedOutputStream()
        val chunk = OutputChunk(depth = ContentDepth.CORE_THESIS, text = "first chunk", sequence = 1)

        stream.emit(chunk)

        assertEquals(1, stream.status.chunksDelivered)
        assertEquals(ContentDepth.CORE_THESIS, stream.status.currentDepth)
    }

    @Test
    fun `emit respects cancelled state`() {
        val stream = ChunkedOutputStream()
        stream.cancel()

        val chunk = OutputChunk(depth = ContentDepth.CORE_THESIS, text = "should not be emitted", sequence = 1)
        stream.emit(chunk)

        assertEquals(0, stream.status.chunksDelivered)
    }

    @Test
    fun `pause transitions state to PAUSED`() {
        val stream = ChunkedOutputStream()

        stream.pause()

        assertEquals(StreamState.PAUSED, stream.status.state)
    }

    @Test
    fun `resume transitions state back to STREAMING`() {
        val stream = ChunkedOutputStream()
        stream.pause()
        assertEquals(StreamState.PAUSED, stream.status.state)

        stream.resume()

        assertEquals(StreamState.STREAMING, stream.status.state)
    }

    @Test
    fun `cancel transitions state to CANCELLED and sets interrupted`() {
        val stream = ChunkedOutputStream()

        stream.cancel()

        assertEquals(StreamState.CANCELLED, stream.status.state)
        assertTrue(stream.status.isInterrupted)
    }

    @Test
    fun `markComplete transitions state to COMPLETED`() {
        val stream = ChunkedOutputStream()

        stream.markComplete()

        assertEquals(StreamState.COMPLETED, stream.status.state)
    }

    @Test
    fun `asFlow emits chunks in order`() = runTest {
        val stream = ChunkedOutputStream()

        stream.emit(OutputChunk(depth = ContentDepth.CORE_THESIS, text = "chunk1", sequence = 0))
        stream.emit(OutputChunk(depth = ContentDepth.KEY_ARGUMENTS, text = "chunk2", sequence = 1))
        stream.emit(OutputChunk(depth = ContentDepth.DETAILED_EXPLANATION, text = "chunk3", sequence = 2))

        val emitted = stream.asFlow().take(3).toList()
        assertEquals(3, emitted.size)
        assertEquals("chunk1", emitted[0].text)
        assertEquals("chunk2", emitted[1].text)
        assertEquals("chunk3", emitted[2].text)
    }

    @Test
    fun `reset restores status to defaults`() {
        val stream = ChunkedOutputStream()
        stream.emit(OutputChunk(depth = ContentDepth.KEY_ARGUMENTS, text = "data", sequence = 1))
        stream.pause()

        stream.reset()

        assertEquals(StreamState.STREAMING, stream.status.state)
        assertEquals(0, stream.status.chunksDelivered)
        assertEquals(ContentDepth.CORE_THESIS, stream.status.currentDepth)
        assertFalse(stream.status.isInterrupted)
    }

    @Test
    fun `adjustDepth emits control event`() = runTest {
        val stream = ChunkedOutputStream()

        stream.adjustDepth(ContentDepth.DETAILED_EXPLANATION)

        val event = stream.controlEvents.first()
        assertTrue(event is StreamControlEvent.AdjustDepth)
        assertEquals(ContentDepth.DETAILED_EXPLANATION, (event as StreamControlEvent.AdjustDepth).depth)
    }

    @Test
    fun `sendFeedback emits feedback control event`() = runTest {
        val stream = ChunkedOutputStream()

        stream.sendFeedback(FeedbackSignal.TOO_COMPLEX)

        val event = stream.controlEvents.first()
        assertTrue(event is StreamControlEvent.Feedback)
        assertEquals(FeedbackSignal.TOO_COMPLEX, (event as StreamControlEvent.Feedback).signal)
    }

    // --- RhythmDetector recommended depth calculation ---

    @Test
    fun `RhythmDetector returns CORE_THESIS when no data recorded`() {
        val detector = RhythmDetector()

        val depth = detector.getRecommendedDepth()

        assertEquals(ContentDepth.CORE_THESIS, depth)
    }

    @Test
    fun `RhythmDetector returns DETAILED_EXPLANATION when expand ratio exceeds 0 dot 7`() {
        val detector = RhythmDetector()

        for (i in 1..7) detector.recordExpand("item-$i")
        for (i in 1..3) detector.recordCollapse("col-$i")

        val depth = detector.getRecommendedDepth()

        assertEquals(ContentDepth.DETAILED_EXPLANATION, depth)
    }

    @Test
    fun `RhythmDetector returns KEY_ARGUMENTS when expand ratio between 0 dot 3 and 0 dot 7`() {
        val detector = RhythmDetector()

        for (i in 1..5) detector.recordExpand("item-$i")
        for (i in 1..5) detector.recordCollapse("col-$i")

        val depth = detector.getRecommendedDepth()

        assertEquals(ContentDepth.KEY_ARGUMENTS, depth)
    }

    @Test
    fun `RhythmDetector returns CORE_THESIS when expand ratio below 0 dot 3`() {
        val detector = RhythmDetector()

        detector.recordExpand("only-one")
        for (i in 1..10) detector.recordCollapse("col-$i")

        val depth = detector.getRecommendedDepth()

        assertEquals(ContentDepth.CORE_THESIS, depth)
    }

    @Test
    fun `getComplexityAdjustment returns positive for TOO_SIMPLE feedback`() {
        val detector = RhythmDetector()
        detector.recordFeedback(FeedbackSignal.TOO_SIMPLE)

        val adjustment = detector.getComplexityAdjustment()

        assertTrue(adjustment > 0f)
    }

    @Test
    fun `getComplexityAdjustment returns negative for TOO_COMPLEX feedback`() {
        val detector = RhythmDetector()
        detector.recordFeedback(FeedbackSignal.TOO_COMPLEX)

        val adjustment = detector.getComplexityAdjustment()

        assertTrue(adjustment < 0f)
    }

    @Test
    fun `getComplexityAdjustment returns zero when no feedback`() {
        val detector = RhythmDetector()

        assertEquals(0f, detector.getComplexityAdjustment(), 0.001f)
    }

    @Test
    fun `shouldAutoExpand returns true when expands significantly exceed collapses`() {
        val detector = RhythmDetector()
        detector.recordExpand("e1")
        detector.recordExpand("e2")
        detector.recordExpand("e3")
        detector.recordExpand("e4")
        detector.recordCollapse("c1")
        detector.recordCollapse("c2")

        assertTrue(detector.shouldAutoExpand())
    }

    @Test
    fun `shouldAutoExpand returns false when expands do not significantly exceed collapses`() {
        val detector = RhythmDetector()
        detector.recordExpand("e1")
        detector.recordExpand("e2")
        detector.recordCollapse("c1")
        detector.recordCollapse("c2")

        assertFalse(detector.shouldAutoExpand())
    }

    @Test
    fun `recordScroll updates metrics`() {
        val detector = RhythmDetector()
        detector.recordScroll(100f, 1000L)
        detector.recordScroll(200f, 2000L)

        assertTrue(detector.metrics.scrollSpeed > 0f)
    }

    @Test
    fun `recordPause updates average pause metrics`() {
        val detector = RhythmDetector()
        detector.recordPause(500L)
        detector.recordPause(1500L)

        assertEquals(1000L, detector.metrics.avgPauseMs)
    }

    @Test
    fun `reset clears all recorded data`() {
        val detector = RhythmDetector()
        detector.recordExpand("e1")
        detector.recordFeedback(FeedbackSignal.USEFUL)
        detector.recordPause(200L)
        assertTrue(detector.metrics.expandRatio > 0f)

        detector.reset()

        assertEquals(0f, detector.metrics.expandRatio, 0.001f)
        assertEquals(ContentDepth.CORE_THESIS, detector.getRecommendedDepth())
        assertEquals("", detector.metrics.complexityPreference.toDouble(), 0.001)
    }

    @Test
    fun `getSummary returns formatted string`() {
        val detector = RhythmDetector()
        detector.recordExpand("e1")

        val summary = detector.getSummary()

        assertTrue(summary.contains("Rhythm Summary"))
        assertTrue(summary.contains("Expand ratio"))
        assertTrue(summary.contains("Preferred depth"))
    }

    @Test
    fun `detailThirsty set when expand ratio exceeds 0 dot 6`() {
        val detector = RhythmDetector()
        for (i in 1..6) detector.recordExpand("e$i")
        detector.recordCollapse("c1")
        detector.recordCollapse("c2")
        detector.recordCollapse("c3")
        detector.recordCollapse("c4")

        assertTrue(detector.metrics.detailThirsty)
    }
}
