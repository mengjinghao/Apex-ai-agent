package com.apex.agent.infrastructure.knowledgegraph

import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write


    fun addNode(node: KnowledgeNode)

    fun addEdge(edge: KnowledgeEdge)

    fun removeNode(id: String)

    fun removeEdge(id: String)

    fun findNode(id: String): KnowledgeNode?

    fun findEdgesFrom(sourceId: String): List<KnowledgeEdge>

    fun snapshot(): KnowledgeGraph

    fun clear()

    @Singleton

        private val nodes = ConcurrentHashMap<String, KnowledgeNode>()
        private val edges = ConcurrentHashMap<String, KnowledgeEdge>()

        override fun addNode(node: KnowledgeNode) {
            nodes[node.id] = node
        }

        override fun addEdge(edge: KnowledgeEdge) {
            edges[edge.id] = edge
        }

        override fun removeNode(id: String) {
            nodes.remove(id)
        }

        override fun removeEdge(id: String) {
            edges.remove(id)
        }

        override fun findNode(id: String): KnowledgeNode? {
            return nodes[id]
        }

        override fun findEdgesFrom(sourceId: String): List<KnowledgeEdge> {
            return edges.values.filter { it.sourceId == sourceId }
        }

        override fun snapshot(): KnowledgeGraph {
            val snapshotNodes = nodes.values.toList()
            val snapshotEdges = edges.values.toList()
            return KnowledgeGraph(snapshotNodes, snapshotEdges)
        }

        override fun clear() {
            nodes.clear()
            edges.clear()
        }
    }
}
