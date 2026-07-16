package com.apex.agent.core.multiagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

class DynamicTopologyManager(private val context: Context) {

    companion object {
        private const val TAG = "DynamicTopologyManager"
        private const val GOSSIP_INTERVAL = 2000L
        private const val DISCOVERY_INTERVAL = 5000L
        private const val TOPOLOGY_UPDATE_INTERVAL = 10000L
        private const val MAX_HOPS = 5
        private const val REPLICATION_FACTOR = 3
        private const val FAILURE_THRESHOLD = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val agentRegistry = ConcurrentHashMap<String, AgentNode>()
    private val topologyGraph = ConcurrentHashMap<String, MutableSet<AgentEdge>>()
    private val gossipState = ConcurrentHashMap<String, GossipMessage>()
    private val capabilityIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private val _networkTopology = MutableStateFlow(NetworkTopology())
    val networkTopology: StateFlow<NetworkTopology> = _networkTopology

    private val _agentDiscovery = MutableSharedFlow<AgentDiscoveryEvent>()
    val agentDiscovery: SharedFlow<AgentDiscoveryEvent> = _agentDiscovery

    private val _roleAssignments = MutableStateFlow<Map<String, String>>(emptyMap())
    val roleAssignments: StateFlow<Map<String, String>> = _roleAssignments

    private var gossipJob: Job? = null
    private var discoveryJob: Job? = null
    private var topologyUpdateJob: Job? = null

    init {
        startGossipProtocol()
        startDiscoveryProtocol()
        startTopologyUpdate()
    }

    data class AgentNode(
        val agentId: String,
        val capabilities: MutableMap<String, Float>,
        var role: AgentRole,
        var status: NodeStatus,
        var health: Float = 1.0f,
        var load: Float = 0.0f,
        var latency: Long = 0,
        var lastSeen: Long = System.currentTimeMillis(),
        var failureCount: Int = 0,
        var metadata: MutableMap<String, Any> = mutableMapOf()
    ) {


    data class AgentEdge(
        val sourceId: String,
        val targetId: String,
        val connectionType: ConnectionType,
        var weight: Float = 1.0f,
        var latency: Long = 0,
        var bandwidth: Float = 1.0f,
        var lastUpdate: Long = System.currentTimeMillis()
    ) {

    data class GossipMessage(
        val messageId: String,
        val sourceAgentId: String,
        val targetAgentId: String?,
        val payload: GossipPayload,
        val timestamp: Long,
        val ttl: Int = MAX_HOPS,
        val vectorClock: Map<String, Long>
    ) {
        sealed class GossipPayload {
            data class Heartbeat(val status: AgentNode.NodeStatus, val load: Float) : GossipPayload()
            data class TopologyQuery(val queryId: String) : GossipPayload()
            data class TopologyResponse(val topology: NetworkTopology) : GossipPayload()
            data class RoleProposal(val proposedRole: AgentRole, val reason: String) : GossipPayload()
            data class CapabilityQuery(val capability: String) : GossipPayload()
            data class CapabilityResponse(val agents: List<String>) : GossipPayload()
        }
    }

    data class NetworkTopology(
        val nodes: Map<String, AgentNode> = emptyMap(),
        val edges: Map<String, Set<AgentEdge>> = emptyMap(),
        val centrality: Map<String, Float> = emptyMap(),
        val clusters: List<Set<String>> = emptyList(),
        val diameter: Int = 0,
        val avgPathLength: Float = 0f
    )

    data class AgentDiscoveryEvent(
        val eventType: EventType,
        val agentId: String,
        val details: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    ) {

    data class TopologyChange(
        val changeType: ChangeType,
        val affectedAgents: Set<String>,
        val oldTopology: NetworkTopology?,
        val newTopology: NetworkTopology,
        val timestamp: Long
    ) {

    fun registerAgent(agentId: String, capabilities: Map<String, Float>, initialRole: AgentRole = AgentRole.EXECUTOR): Boolean {
        val node = AgentNode(
            agentId = agentId,
            capabilities = capabilities.toMutableMap(),
            role = initialRole,
            status = AgentNode.NodeStatus.ACTIVE
        )

        agentRegistry[agentId] = node
        capabilityIndex[agentId] = capabilities.keys.toMutableSet()

        updateCapabilityIndex(agentId, capabilities)

        if (agentRegistry.size == 1) {
            assignRole(agentId, AgentRole.COORDINATOR)
        }

        scope.launch {
            _agentDiscovery.emit(
                AgentDiscoveryEvent(
                    eventType = AgentDiscoveryEvent.EventType.AGENT_JOINED,
                    agentId = agentId,
                    details = mapOf("role" to initialRole.name)
                )
            )
        }

        updateTopology()
        return true
    }

    fun unregisterAgent(agentId: String) {
        val node = agentRegistry[agentId] ?: return

        capabilityIndex[agentId]?.forEach { capability ->
            capabilityIndex[capability]?.remove(agentId)
        }
        capabilityIndex.remove(agentId)

        topologyGraph.remove(agentId)
        agentRegistry.remove(agentId)

        topologyGraph.values.forEach { edges ->
            edges.removeAll { it.sourceId == agentId || it.targetId == agentId }
        }

        if (node.role == AgentRole.COORDINATOR) {
            electNewCoordinator()
        }

        scope.launch {
            _agentDiscovery.emit(
                AgentDiscoveryEvent(
                    eventType = AgentDiscoveryEvent.EventType.AGENT_LEFT,
                    agentId = agentId
                )
            )
        }

        updateTopology()
    }

    fun updateAgentCapabilities(agentId: String, capabilities: Map<String, Float>) {
        val node = agentRegistry[agentId] ?: return

        capabilityIndex[agentId]?.forEach { capability ->
            capabilityIndex[capability]?.remove(agentId)
        }

        node.capabilities.clear()
        node.capabilities.putAll(capabilities)

        updateCapabilityIndex(agentId, capabilities)

        scope.launch {
            _agentDiscovery.emit(
                AgentDiscoveryEvent(
                    eventType = AgentDiscoveryEvent.EventType.CAPABILITY_UPDATED,
                    agentId = agentId,
                    details = mapOf("capabilities" to capabilities)
                )
            )
        }
    }

    private fun updateCapabilityIndex(agentId: String, capabilities: Map<String, Float>) {
        capabilities.forEach { (capability, score) ->
            capabilityIndex.getOrPut(capability) { mutableSetOf() }.add(agentId)
        }
    }

    fun findAgentsByCapability(capability: String, minScore: Float = 0.5f): List<Pair<String, Float>> {
        return capabilityIndex[capability]?.mapNotNull { agentId ->
            val node = agentRegistry[agentId]
            if (node != null && node.status == AgentNode.NodeStatus.ACTIVE) {
                val score = node.capabilities[capability] ?: 0f
                if (score >= minScore) {
                    agentId to score
                } else null
            } else null
        }?.sortedByDescending { it.second } ?: emptyList()
    }

    fun assignRole(agentId: String, newRole: AgentRole): Boolean {
        val node = agentRegistry[agentId] ?: return false
        val oldRole = node.role

        node.role = newRole

        if (oldRole == AgentRole.COORDINATOR && newRole != AgentRole.COORDINATOR) {
            electNewCoordinator()
        }

        scope.launch {
            _agentDiscovery.emit(
                AgentDiscoveryEvent(
                    eventType = AgentDiscoveryEvent.EventType.ROLE_CHANGED,
                    agentId = agentId,
                    details = mapOf("oldRole" to oldRole.name, "newRole" to newRole.name)
                )
            )
        }

        updateTopology()
        return true
    }

    fun addConnection(sourceId: String, targetId: String, type: AgentEdge.ConnectionType = AgentEdge.ConnectionType.DIRECT): Boolean {
        val source = agentRegistry[sourceId] ?: return false
        val target = agentRegistry[targetId] ?: return false

        val edge = AgentEdge(
            sourceId = sourceId,
            targetId = targetId,
            connectionType = type
        )

        topologyGraph.getOrPut(sourceId) { mutableSetOf() }.add(edge)

        if (type == AgentEdge.ConnectionType.DIRECT) {
            val reverseEdge = AgentEdge(
                sourceId = targetId,
                targetId = sourceId,
                connectionType = type
            )
            topologyGraph.getOrPut(targetId) { mutableSetOf() }.add(reverseEdge)
        }

        updateTopology()
        return true
    }

    fun removeConnection(sourceId: String, targetId: String) {
        topologyGraph[sourceId]?.removeAll { it.targetId == targetId }
        topologyGraph[targetId]?.removeAll { it.targetId == sourceId }
        updateTopology()
    }

    fun discoverAgents(): Set<String> {
        return agentRegistry.keys.toSet()
    }

    fun findPath(sourceId: String, targetId: String): List<String>? {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<String>>>()

        queue.add(sourceId to listOf(sourceId))

        while (queue.isNotEmpty()) {
            val (current, path) = queue.poll()

            if (current == targetId) return path

            if (visited.contains(current)) continue
            visited.add(current)

            topologyGraph[current]?.forEach { edge ->
                if (!visited.contains(edge.targetId)) {
                    queue.add(edge.targetId to (path + edge.targetId))
                }
            }
        }

        return null
    }

    fun getShortestPath(sourceId: String, targetId: String): List<String>? {
        return findPath(sourceId, targetId)
    }

    fun getNearestAgent(sourceId: String, capability: String): String? {
        return findAgentsByCapability(capability)
            .filter { it.first != sourceId }
            .firstOrNull()?.first
    }

    fun getHopsBetween(sourceId: String, targetId: String): Int {
        return findPath(sourceId, targetId)?.size?.minus(1) ?: -1
    }

    private fun electNewCoordinator() {
        val activeAgents = agentRegistry.values
            .filter { it.status == AgentNode.NodeStatus.ACTIVE && it.role != AgentRole.COORDINATOR }
            .sortedByDescending { calculateAgentScore(it) }

        activeAgents.firstOrNull()?.let { newCoordinator ->
            assignRole(newCoordinator.agentId, AgentRole.COORDINATOR)

            scope.launch {
                _agentDiscovery.emit(
                    AgentDiscoveryEvent(
                        eventType = AgentDiscoveryEvent.EventType.COORDINATOR_ELECTED,
                        agentId = newCoordinator.agentId
                    )
                )
            }
        }
    }

    private fun calculateAgentScore(agent: AgentNode): Float {
        val capabilityScore = agent.capabilities.values.average().toFloat()
        val healthScore = agent.health
        val loadScore = 1.0f - agent.load
        val latencyScore = 1.0f / (1.0f + agent.latency / 1000f)

        return (capabilityScore * 0.4f + healthScore * 0.3f + loadScore * 0.2f + latencyScore * 0.1f)
    }

    private fun startGossipProtocol() {
        gossipJob = scope.launch {
            while (isActive) {
                delay(GOSSIP_INTERVAL)
                performGossipExchange()
            }
        }
    }

    private suspend fun performGossipExchange() {
        val activeAgents = agentRegistry.values.filter { it.status == AgentNode.NodeStatus.ACTIVE }

        if (activeAgents.size < 2) return

        activeAgents.forEach { agent ->
            val targetAgent = selectRandomAgent(agent.agentId) ?: return@forEach

            val gossipMessage = createGossipMessage(agent.agentId, targetAgent.agentId)

            gossipState[gossipMessage.messageId] = gossipMessage

            val response = deliverGossip(gossipMessage)

            response?.let { updateGossipState(it) }
        }
    }

    private fun selectRandomAgent(excludeAgentId: String): AgentNode? {
        return agentRegistry.values
            .filter { it.agentId != excludeAgentId && it.status == AgentNode.NodeStatus.ACTIVE }
            .randomOrNull()
    }

    private fun createGossipMessage(sourceId: String, targetId: String): GossipMessage {
        val sourceNode = agentRegistry[sourceId]

        return GossipMessage(
            messageId = UUID.randomUUID().toString(),
            sourceAgentId = sourceId,
            targetAgentId = targetId,
            payload = GossipMessage.GossipPayload.Heartbeat(
                status = sourceNode?.status ?: AgentNode.NodeStatus.ACTIVE,
                load = sourceNode?.load ?: 0f
            ),
            timestamp = System.currentTimeMillis(),
            vectorClock = mapOf(sourceId to System.currentTimeMillis())
        )
    }

    private fun deliverGossip(message: GossipMessage): GossipMessage? {
        val targetId = message.targetAgentId ?: return null

        if (message.ttl <= 0) return null

        val updatedMessage = message.copy(ttl = message.ttl - 1)

        val targetNode = agentRegistry[targetId]
        if (targetNode == null || targetNode.status == AgentNode.NodeStatus.OFFLINE) {
            return null
        }

        when (val payload = message.payload) {
            is GossipMessage.GossipPayload.CapabilityUpdate -> {
                agentRegistry[message.sourceAgentId]?.let { source ->
                    source.capabilities.putAll(payload.capabilities)
                }
            }
            is GossipMessage.GossipPayload.Heartbeat -> {
                agentRegistry[message.sourceAgentId]?.let { source ->
                    source.failureCount = 0
                    source.lastSeen = System.currentTimeMillis()
                }
            }
            else -> {}
        }

        return updatedMessage
    }

    private fun updateGossipState(message: GossipMessage) {
        val existing = gossipState[message.messageId]
        if (existing == null || message.timestamp > existing.timestamp) {
            gossipState[message.messageId] = message
        }
    }

    private fun startDiscoveryProtocol() {
        discoveryJob = scope.launch {
            while (isActive) {
                delay(DISCOVERY_INTERVAL)
                performDiscovery()
            }
        }
    }

    private suspend fun performDiscovery() {
        agentRegistry.values.forEach { agent ->
            if (agent.role == AgentRole.DISCOVERY || agent.role == AgentRole.COORDINATOR) {
                val nearbyAgents = discoverNearbyAgents(agent.agentId)
                nearbyAgents.forEach { nearbyId ->
                    if (agentRegistry[nearbyId] == null) {
                        _agentDiscovery.emit(
                            AgentDiscoveryEvent(
                                eventType = AgentDiscoveryEvent.EventType.AGENT_JOINED,
                                agentId = nearbyId
                            )
                        )
                    }
                }
            }
        }
    }

    private fun discoverNearbyAgents(agentId: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        queue.add(agentId)
        visited.add(agentId)

        var hops = 0
        while (queue.isNotEmpty() && hops < MAX_HOPS) {
            val currentSize = queue.size
            repeat(currentSize) {
                val current = queue.poll()
                topologyGraph[current]?.forEach { edge ->
                    if (!visited.contains(edge.targetId)) {
                        visited.add(edge.targetId)
                        queue.add(edge.targetId)
                    }
                }
            }
            hops++
        }

        visited.remove(agentId)
        return visited
    }

    private fun startTopologyUpdate() {
        topologyUpdateJob = scope.launch {
            while (isActive) {
                delay(TOPOLOGY_UPDATE_INTERVAL)
                updateTopology()
            }
        }
    }

    private fun updateTopology() {
        val nodes = agentRegistry.mapKeys { it.key }
        val edges = topologyGraph.mapValues { it.value.toSet() }

        val centrality = calculateCentrality()
        val clusters = detectClusters()
        val diameter = calculateDiameter()
        val avgPathLength = calculateAveragePathLength()

        _networkTopology.value = NetworkTopology(
            nodes = nodes,
            edges = edges,
            centrality = centrality,
            clusters = clusters,
            diameter = diameter,
            avgPathLength = avgPathLength
        )

        updateRoleAssignments()
    }

    private fun calculateCentrality(): Map<String, Float> {
        val centrality = mutableMapOf<String, Float>()

        agentRegistry.keys.forEach { agentId ->
            var totalDistance = 0
            var reachableCount = 0

            agentRegistry.keys.forEach { targetId ->
                if (agentId != targetId) {
                    val path = findPath(agentId, targetId)
                    if (path != null) {
                        totalDistance += path.size - 1
                        reachableCount++
                    }
                }
            }

            centrality[agentId] = if (reachableCount > 0) {
                reachableCount.toFloat() / totalDistance
            } else {
                0f
            }
        }

        return centrality
    }

    private fun detectClusters(): List<Set<String>> {
        val visited = mutableSetOf<String>()
        val clusters = mutableListOf<Set<String>>()

        agentRegistry.keys.forEach { startAgent ->
            if (!visited.contains(startAgent)) {
                val cluster = mutableSetOf<String>()
                val queue = ArrayDeque<String>()

                queue.add(startAgent)
                visited.add(startAgent)

                while (queue.isNotEmpty()) {
                    val current = queue.poll()
                    cluster.add(current)

                    topologyGraph[current]?.forEach { edge ->
                        if (!visited.contains(edge.targetId) && edge.weight > 0.5f) {
                            visited.add(edge.targetId)
                            queue.add(edge.targetId)
                        }
                    }
                }

                if (cluster.size > 1) {
                    clusters.add(cluster)
                }
            }
        }

        return clusters
    }

    private fun calculateDiameter(): Int {
        var maxDiameter = 0

        agentRegistry.keys.forEach { agentId ->
            agentRegistry.keys.forEach { targetId ->
                if (agentId != targetId) {
                    val path = findPath(agentId, targetId)
                    path?.let {
                        maxDiameter = maxOf(maxDiameter, it.size - 1)
                    }
                }
            }
        }

        return maxDiameter
    }

    private fun calculateAveragePathLength(): Float {
        var totalPathLength = 0
        var pathCount = 0

        agentRegistry.keys.forEach { agentId ->
            agentRegistry.keys.forEach { targetId ->
                if (agentId != targetId) {
                    val path = findPath(agentId, targetId)
                    path?.let {
                        totalPathLength += it.size - 1
                        pathCount++
                    }
                }
            }
        }

        return if (pathCount > 0) totalPathLength.toFloat() / pathCount else 0f
    }

    private fun updateRoleAssignments() {
        _roleAssignments.value = agentRegistry.mapValues { it.value.role.name }
    }

    fun getOptimalRoleDistribution(): Map<AgentRole, Int> {
        val totalAgents = agentRegistry.size

        return mapOf(
            AgentRole.COORDINATOR to 1,
            AgentRole.MONITOR to maxOf(1, totalAgents / 10),
            AgentRole.ROUTER to maxOf(1, totalAgents / 8),
            AgentRole.REPLICATOR to maxOf(1, totalAgents / 6),
            AgentRole.DISCOVERY to maxOf(1, totalAgents / 12),
            AgentRole.EXECUTOR to maxOf(totalAgents / 2, totalAgents - 5)
        )
    }

    fun rebalanceTopology() {
        val currentDistribution = agentRegistry.values.groupingBy { it.role }.eachCount()
        val optimalDistribution = getOptimalRoleDistribution()

        optimalDistribution.forEach { (role, optimalCount) ->
            val currentCount = currentDistribution[role] ?: 0

            if (currentCount < optimalCount) {
                val availableAgents = agentRegistry.values
                    .filter { it.role == AgentRole.EXECUTOR && it.status == AgentNode.NodeStatus.ACTIVE }
                    .take(optimalCount - currentCount)

                availableAgents.forEach { agent ->
                    assignRole(agent.agentId, role)
                }
            } else if (currentCount > optimalCount) {
                val excessAgents = agentRegistry.values
                    .filter { it.role == role && it.status == AgentNode.NodeStatus.ACTIVE }
                    .drop(optimalCount)

                excessAgents.forEach { agent ->
                    assignRole(agent.agentId, AgentRole.EXECUTOR)
                }
            }
        }
    }

    fun simulateFailure(agentId: String) {
        val node = agentRegistry[agentId] ?: return

        node.failureCount++
        node.health = maxOf(0f, node.health - 0.3f)

        if (node.failureCount >= FAILURE_THRESHOLD) {
            node.status = AgentNode.NodeStatus.FAILING

            scope.launch {
                _agentDiscovery.emit(
                    AgentDiscoveryEvent(
                        eventType = AgentDiscoveryEvent.EventType.FAILURE_DETECTED,
                        agentId = agentId,
                        details = mapOf("failureCount" to node.failureCount)
                    )
                )
            }

            if (node.role == AgentRole.COORDINATOR) {
                electNewCoordinator()
            }

            triggerRecovery(agentId)
        }
    }

    private fun triggerRecovery(agentId: String) {
        scope.launch {
            val replicationTargets = findReplicationTargets(agentId)

            replicationTargets.forEach { targetId ->
                addConnection(agentId, targetId, AgentEdge.ConnectionType.RELAY)
            }

            updateTopology()
        }
    }

    private fun findReplicationTargets(agentId: String): List<String> {
        val agent = agentRegistry[agentId] ?: return emptyList()

        return agentRegistry.values
            .filter { it.agentId != agentId && it.status == AgentNode.NodeStatus.ACTIVE }
            .sortedBy { abs(it.capabilities.map { c -> c.value }.average() - agent.capabilities.map { c -> c.value }.average()) }
            .take(REPLICATION_FACTOR)
            .map { it.agentId }
    }

    fun getAgentNeighbors(agentId: String, maxHops: Int = 1): Set<String> {
        val neighbors = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()

        queue.add(agentId to 0)
        visited.add(agentId)

        while (queue.isNotEmpty()) {
            val (current, hops) = queue.poll()

            if (hops < maxHops) {
                topologyGraph[current]?.forEach { edge ->
                    if (!visited.contains(edge.targetId)) {
                        neighbors.add(edge.targetId)
                        visited.add(edge.targetId)
                        queue.add(edge.targetId to hops + 1)
                    }
                }
            }
        }

        return neighbors
    }

    fun exportTopology(): String {
        return """
            {
                "nodes": ${agentRegistry.size},
                "edges": ${topologyGraph.values.sumOf { it.size } / 2},
                "diameter": ${_networkTopology.value.diameter},
                "avgPathLength": ${_networkTopology.value.avgPathLength},
                "clusters": ${_networkTopology.value.clusters.size}
            }
        """.trimIndent()
    }

    fun shutdown() {
        gossipJob?.cancel()
        discoveryJob?.cancel()
        topologyUpdateJob?.cancel()
        scope.cancel()
    }
}
