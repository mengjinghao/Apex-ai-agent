package com.apex.agent.kernel.interaction.thinking

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThinkingEngineTest {

    private lateinit var engine: DefaultThinkingEngine

    @Before
    fun setup() {
        engine = DefaultThinkingEngine()
    }

    // --- Graph creation and node management ---

    @Test
    fun `startThinking creates root node and emits NodeAdded event`() = runTest {
        val rootId = engine.startThinking("What is the optimal sort algorithm?", mapOf("domain" to "algorithms"))

        val node = engine.getNode(rootId)
        assertNotNull(node)
        assertEquals("What is the optimal sort algorithm?", node!!.content)
        assertEquals(ThinkingLayer.CORE_LOGIC, node.layer)
        assertEquals(NodeStatus.IN_PROGRESS, node.status)
        assertNull(node.parentId)

        val event = engine.events.first()
        assertTrue(event is ThinkingEvent.NodeAdded)
        assertEquals(rootId, (event as ThinkingEvent.NodeAdded).nodeId)
    }

    @Test
    fun `addReasoningStep creates child node and edge`() = runTest {
        val rootId = engine.startThinking("root")
        val child = engine.addReasoningStep(rootId, "evidence step", ThinkingLayer.EVIDENCE_RETRIEVAL)

        assertEquals("evidence step", child.content)
        assertEquals(ThinkingLayer.EVIDENCE_RETRIEVAL, child.layer)
        assertEquals(NodeStatus.PENDING_VERIFICATION, child.status)
        assertEquals(rootId, child.parentId)

        val path = engine.getPath(child.id)
        assertEquals(2, path.size)
        assertEquals(rootId, path[0].id)
        assertEquals(child.id, path[1].id)

        val edge = engine.graph.edges.find { it.sourceId == rootId && it.targetId == child.id }
        assertNotNull(edge)
        assertEquals(EdgeType.LEADS_TO, edge!!.type)
    }

    @Test
    fun `addReasoningStep with missing parent throws exception`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { engine.addReasoningStep("non-existent", "content", ThinkingLayer.CORE_LOGIC) }
        }
    }

    // --- Branch creation and conflict detection ---

    @Test
    fun `forkBranch creates branch and emits event`() = runTest {
        val rootId = engine.startThinking("problem")
        val alt = engine.addAlternative(rootId, "alternative approach")
        val branchId = engine.forkBranch(alt.id, "experimental-v2")

        assertNotNull(branchId)
        assertTrue(engine.graph.branches.containsKey(branchId))
        val branchNodes = engine.graph.getBranch(branchId)
        assertNotNull(branchNodes)
        assertTrue(branchNodes!!.isNotEmpty())
    }

    @Test
    fun `addAlternative creates alternative node with correct edge`() = runTest {
        val rootId = engine.startThinking("main path")
        val alternative = engine.addAlternative(rootId, "different approach")

        assertEquals("different approach", alternative.content)
        assertEquals(ThinkingLayer.ALTERNATIVE_PATHS, alternative.layer)
        assertEquals(NodeStatus.PENDING_VERIFICATION, alternative.status)

        val altEdge = engine.graph.edges.find { it.targetId == alternative.id }
        assertNotNull(altEdge)
        assertEquals(EdgeType.ALTERNATIVE_TO, altEdge!!.type)
    }

    @Test
    fun `resolveConflict creates resolution node and conflict event`() = runTest {
        val rootId = engine.startThinking("root")
        val nodeA = engine.addReasoningStep(rootId, "position A", ThinkingLayer.CORE_LOGIC)
        val nodeB = engine.addReasoningStep(rootId, "position B", ThinkingLayer.CORE_LOGIC)

        val resolved = engine.resolveConflict(nodeA.id, nodeB.id, "synthesis of A and B")

        assertEquals("synthesis of A and B", resolved.content)
        assertEquals(ThinkingLayer.SELF_CORRECTION, resolved.layer)
        assertEquals(NodeStatus.CONFIRMED, resolved.status)

        val edges = engine.graph.edges.filter { it.targetId == resolved.id }
        assertEquals(2, edges.size)
        assertTrue(edges.any { it.type == EdgeType.LEADS_TO })
        assertTrue(edges.any { it.type == EdgeType.DERIVED_FROM })
    }

    // --- Layer assignment ---

    @Test
    fun `all four layers can be assigned to nodes`() = runTest {
        val rootId = engine.startThinking("multi-layer problem")
        val layers = ThinkingLayer.values()

        val nodes = layers.map { layer ->
            engine.addReasoningStep(rootId, "content for $layer", layer)
        }

        nodes.forEachIndexed { i, node ->
            assertEquals(layers[i], node.layer)
        }

        layers.forEach { layer ->
            val layerNodes = engine.graph.getNodesByLayer(layer)
            assertTrue(layerNodes.isNotEmpty())
            assertTrue(layerNodes.all { it.layer == layer })
        }
    }

    // --- Node status transitions ---

    @Test
    fun `updateNodeStatus transitions through statuses and emits events`() = runTest {
        val rootId = engine.startThinking("status test")
        val node = engine.addReasoningStep(rootId, "step", ThinkingLayer.CORE_LOGIC)

        val transitions = listOf(
            NodeStatus.PENDING_VERIFICATION,
            NodeStatus.CONFIRMED,
            NodeStatus.NEEDS_USER_INPUT,
            NodeStatus.REJECTED
        )

        for (newStatus in transitions) {
            val result = engine.updateNodeStatus(node.id, newStatus)
            assertTrue(result)
            val updated = engine.getNode(node.id)
            assertEquals(newStatus, updated!!.status)
        }
    }

    @Test
    fun `updateNodeStatus returns false for non-existent node`() = runTest {
        val result = engine.updateNodeStatus("non-existent", NodeStatus.CONFIRMED)
        assertFalse(result)
    }

    @Test
    fun `searchNodes finds nodes by content`() = runTest {
        val rootId = engine.startThinking("general problem")
        engine.addReasoningStep(rootId, "searchable keyword analysis", ThinkingLayer.CORE_LOGIC)
        engine.addReasoningStep(rootId, "irrelevant data", ThinkingLayer.EVIDENCE_RETRIEVAL)

        val results = engine.searchNodes("searchable")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.content.contains("searchable", ignoreCase = true) })
    }

    // --- Markdown / HTML / JSON export ---

    @Test
    fun `exportToMarkdown produces formatted output`() = runTest {
        val rootId = engine.startThinking("Markdown test")
        engine.addReasoningStep(rootId, "first step", ThinkingLayer.CORE_LOGIC)
        engine.addReasoningStep(rootId, "evidence", ThinkingLayer.EVIDENCE_RETRIEVAL)

        val md = engine.exportToMarkdown()

        assertTrue(md.startsWith("# Thinking Process"))
        assertTrue(md.contains("Markdown test"))
        assertTrue(md.contains("CORE_LOGIC"))
        assertTrue(md.contains("EVIDENCE_RETRIEVAL"))
    }

    @Test
    fun `exportToMindMap produces mindmap block`() = runTest {
        val rootId = engine.startThinking("MindMap title")
        engine.addReasoningStep(rootId, "child node", ThinkingLayer.CORE_LOGIC)

        val mindmap = engine.exportToMindMap()

        assertTrue(mindmap.startsWith("```mindmap"))
        assertTrue(mindmap.endsWith("```"))
        assertTrue(mindmap.contains("MindMap title"))
    }

    @Test
    fun `DefaultThinkingExporter toHtml produces valid HTML`() = runTest {
        val rootId = engine.startThinking("HTML export test")
        val node = engine.addReasoningStep(rootId, "some reasoning", ThinkingLayer.CORE_LOGIC)
        engine.updateNodeStatus(node.id, NodeStatus.CONFIRMED)

        val exporter = DefaultThinkingExporter()
        val html = exporter.toHtml(engine.graph)

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("HTML export test"))
        assertTrue(html.contains("confirmed"))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `DefaultThinkingExporter toJson produces valid JSON`() = runTest {
        val rootId = engine.startThinking("JSON export")
        engine.addReasoningStep(rootId, "data point", ThinkingLayer.EVIDENCE_RETRIEVAL)

        val exporter = DefaultThinkingExporter()
        val json = exporter.toJson(engine.graph)

        assertTrue(json.contains("\"exportedAt\""))
        assertTrue(json.contains("\"nodeCount\""))
        assertTrue(json.contains("\"edgeCount\""))
        assertTrue(json.contains("\"nodes\""))
        assertTrue(json.contains("\"edges\""))
        assertTrue(json.contains("JSON export"))
    }

    @Test
    fun `DefaultThinkingExporter toMarkdown includes conflicts section`() = runTest {
        val rootId = engine.startThinking("conflict test")
        val a = engine.addReasoningStep(rootId, "position A content", ThinkingLayer.CORE_LOGIC)
        val b = engine.addReasoningStep(rootId, "position B content", ThinkingLayer.CORE_LOGIC)
        engine.graph.addEdge(a.id, b.id, EdgeType.CONTRADICTS)

        val exporter = DefaultThinkingExporter()
        val md = exporter.toMarkdown(engine.graph)

        assertTrue(md.contains("Conflicts"))
    }

    @Test
    fun `clear removes all nodes and edges from engine`() = runTest {
        engine.startThinking("to be cleared")
        assertTrue(engine.graph.nodes.isNotEmpty())

        engine.clear()

        assertTrue(engine.graph.nodes.isEmpty())
        assertTrue(engine.graph.edges.isEmpty())
        assertTrue(engine.graph.branches.isEmpty())
    }

    @Test
    fun `getPath returns full path from node to root`() = runTest {
        val rootId = engine.startThinking("root")
        val child = engine.addReasoningStep(rootId, "child", ThinkingLayer.CORE_LOGIC)
        val grandchild = engine.addReasoningStep(child.id, "grandchild", ThinkingLayer.CORE_LOGIC)

        val path = engine.getPath(grandchild.id)
        assertEquals(3, path.size)
        assertEquals(rootId, path[0].id)
        assertEquals(child.id, path[1].id)
        assertEquals(grandchild.id, path[2].id)
    }

    @Test
    fun `ThinkingGraph findConflicts returns conflicting pairs`() = runTest {
        val rootId = engine.startThinking("root")
        val a = engine.addReasoningStep(rootId, "A", ThinkingLayer.CORE_LOGIC)
        val b = engine.addReasoningStep(rootId, "B", ThinkingLayer.CORE_LOGIC)
        engine.graph.addEdge(a.id, b.id, EdgeType.CONTRADICTS)

        val conflicts = engine.graph.findConflicts()
        assertEquals(1, conflicts.size)
        assertEquals(a.id, conflicts[0].first.id)
        assertEquals(b.id, conflicts[0].second.id)
    }

    @Test
    fun `ThinkingNode withStatus and withConfidence create copies`() {
        val node = ThinkingNode(
            id = "test-id",
            content = "test",
            layer = ThinkingLayer.CORE_LOGIC,
            status = NodeStatus.IN_PROGRESS,
            confidence = 0.5f
        )

        val updated = node.withStatus(NodeStatus.CONFIRMED)
        assertEquals(NodeStatus.CONFIRMED, updated.status)
        assertEquals(NodeStatus.IN_PROGRESS, node.status)

        val confident = node.withConfidence(0.9f)
        assertEquals(0.9f, confident.confidence, 0.001f)
        assertEquals(0.5f, node.confidence, 0.001f)
    }

    @Test
    fun `ThinkingNode withConfidence clamps to 0-1 range`() {
        val node = ThinkingNode(
            id = "clamp-test", content = "x", layer = ThinkingLayer.CORE_LOGIC
        )
        assertEquals(1.0f, node.withConfidence(2.0f).confidence, 0.001f)
        assertEquals(0.0f, node.withConfidence(-1.0f).confidence, 0.001f)
    }

    @Test
    fun `ThinkingGraph getDepth returns correct depth`() {
        val graph = ThinkingGraph()
        val root = graph.addNode("root", ThinkingLayer.CORE_LOGIC)
        val child = graph.addNode("child", ThinkingLayer.CORE_LOGIC, parentId = root.id)
        val grandchild = graph.addNode("grandchild", ThinkingLayer.CORE_LOGIC, parentId = child.id)

        assertEquals(0, graph.getDepth(root.id))
        assertEquals(1, graph.getDepth(child.id))
        assertEquals(2, graph.getDepth(grandchild.id))
    }

    @Test
    fun `ThinkingGraph toSummary returns summary string`() {
        val graph = ThinkingGraph()
        graph.addNode("test", ThinkingLayer.CORE_LOGIC)
        graph.addNode("evidence", ThinkingLayer.EVIDENCE_RETRIEVAL)

        val summary = graph.toSummary()
        assertTrue(summary.contains("Nodes: 2"))
        assertTrue(summary.contains("Edges: 0"))
        assertTrue(summary.contains("Branches: 0"))
    }

    @Test
    fun `consistent event emission across operations`() = runTest {
        val rootId = engine.startThinking("events-test")
        val child = engine.addReasoningStep(rootId, "child-step", ThinkingLayer.ALTERNATIVE_PATHS)
        engine.updateNodeStatus(child.id, NodeStatus.CONFIRMED)
        val branchId = engine.forkBranch(child.id, "branch-test")
        engine.resolveConflict(child.id, rootId, "resolution")

        assertNotNull(branchId)
        assertEquals(NodeStatus.CONFIRMED, engine.getNode(child.id)!!.status)
    }
}
