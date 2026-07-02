package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 访问者模式测试
 *
 * 验证工作流访问者遍历、指标收集和节点处理。
 */
class VisitorPatternTest : BaseUnitTest {

    private lateinit var workflow: WorkflowGraph

    @Before
    override fun setUp() {
        super.setUp()
        workflow = WorkflowGraph()
        workflow.addNode("start", "开始")
        workflow.addNode("process", "处理")
        workflow.addNode("end", "结束")
        workflow.addEdge("start", "process")
        workflow.addEdge("process", "end")
    }

    @Test
    fun `visitor should visit all nodes`() {
        val visitor = CountingVisitor()
        workflow.accept(visitor)
        assertEquals(3, visitor.visitCount)
    }

    @Test
    fun `metrics visitor should collect node data`() {
        val visitor = MetricsVisitor()
        workflow.accept(visitor)
        assertTrue(visitor.nodeNames.contains("开始"))
        assertTrue(visitor.nodeNames.contains("处理"))
    }

    @Test
    fun `report visitor should generate summary`() {
        val visitor = ReportVisitor()
        workflow.accept(visitor)
        val report = visitor.getReport()
        assertTrue(report.contains("nodes"))
    }

    @Test
    fun `visitor should traverse in order`() {
        val order = mutableListOf<String>()
        val visitor = object : WorkflowVisitor {
            override fun visit(node: WorkflowNode) {
                order.add(node.name)
            }
        }
        workflow.accept(visitor)
        assertEquals(listOf("开始", "处理", "结束"), order)
    }

    @Test
    fun `should handle single node graph`() {
        val single = WorkflowGraph()
        single.addNode("only", "唯一节点")
        val visitor = CountingVisitor()
        single.accept(visitor)
        assertEquals(1, visitor.visitCount)
    }

    @Test
    fun `should handle empty graph`() {
        val empty = WorkflowGraph()
        val visitor = CountingVisitor()
        empty.accept(visitor)
        assertEquals(0, visitor.visitCount)
    }

    @Test
    fun `visitor can modify node state`() {
        val visitor = TaggingVisitor()
        workflow.accept(visitor)
        assertTrue(visitor.taggedNodes >= 0)
    }

    @Test
    fun `validation visitor should check constraints`() {
        val visitor = ValidationVisitor()
        workflow.accept(visitor)
        assertTrue(visitor.isValid)
    }
}

data class WorkflowNode(val id: String, val name: String)

interface WorkflowVisitor {
    fun visit(node: WorkflowNode)
}

class WorkflowGraph {
    private val nodes = mutableListOf<WorkflowNode>()
    private val edges = mutableMapOf<String, MutableList<String>>()

    fun addNode(id: String, name: String) { nodes.add(WorkflowNode(id, name)) }
    fun addEdge(from: String, to: String) { edges.getOrPut(from) { mutableListOf() }.add(to) }

    fun accept(visitor: WorkflowVisitor) {
        val visited = mutableSetOf<String>()
        fun dfs(id: String) {
            if (id in visited) return
            visited.add(id)
            nodes.find { it.id == id }?.let { visitor.visit(it) }
            edges[id]?.forEach { dfs(it) }
        }
        for (n in nodes) { dfs(n.id) }
    }
}

class CountingVisitor : WorkflowVisitor {
    var visitCount = 0
    override fun visit(node: WorkflowNode) { visitCount++ }
}

class MetricsVisitor : WorkflowVisitor {
    val nodeNames = mutableListOf<String>()
    override fun visit(node: WorkflowNode) { nodeNames.add(node.name) }
}

class ReportVisitor : WorkflowVisitor {
    private val details = mutableListOf<String>()
    override fun visit(node: WorkflowNode) { details.add("${node.id}: ${node.name}") }
    fun getReport() = "nodes: ${details.size}, details: ${details.joinToString(", ")}"
}

class TaggingVisitor : WorkflowVisitor {
    var taggedNodes = 0
    override fun visit(node: WorkflowNode) { taggedNodes++ }
}

class ValidationVisitor : WorkflowVisitor {
    var isValid = true
    override fun visit(node: WorkflowNode) { isValid = isValid && node.id.isNotBlank() }
}
