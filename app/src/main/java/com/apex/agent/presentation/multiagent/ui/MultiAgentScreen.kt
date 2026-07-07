package com.apex.agent.presentation.multiagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.presentation.multiagent.data.*
import com.apex.agent.presentation.multiagent.state.MultiAgentPageState
import com.apex.agent.presentation.multiagent.action.MultiAgentPresets
import com.apex.ui.mascot.AuraMascotView
import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot

/**
 * 多 Agent 协作界面(Android 独立界面)。
 */
@Composable
fun MultiAgentScreen(
    state: MultiAgentPageState,
    modifier: Modifier = Modifier,
) {
    val agents by state.agents.collectAsState()
    val tasks by state.tasks.collectAsState()
    val messages by state.messages.collectAsState()
    val topology by state.topology.collectAsState()
    val stats by state.stats.collectAsState()
    val currentMode by state.collaborationMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E1A))
            .padding(16.dp)
    ) {
        TopBar(
            mode = currentMode,
            stats = stats,
            onModeChange = { state.setCollaborationMode(it) },
            onAddAgent = {
                state.addAgent(
                    AgentCardData(
                        id = "agent_${System.currentTimeMillis()}",
                        name = "Agent${agents.size + 1}",
                        role = AgentRoleType.WORKER,
                    )
                )
            },
            onLoadPreset = { preset -> MultiAgentPresets.loadPreset(state, preset) },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AgentListPanel(agents = agents, modifier = Modifier.weight(1f), onRemove = { state.removeAgent(it) })
            TopologyPanel(topology = topology, modifier = Modifier.weight(1.2f))
            TaskMessagePanel(
                tasks = tasks, messages = messages, modifier = Modifier.weight(1f),
                onCreateTask = { t, d -> state.createTask(t, d) },
            )
        }
    }
}

@Composable
private fun TopBar(
    mode: CollaborationMode,
    stats: CollaborationStats,
    onModeChange: (CollaborationMode) -> Unit,
    onAddAgent: () -> Unit,
    onLoadPreset: (MultiAgentPresets.Preset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AuraMascotView(
            form = AuraMascot.AuraForm.COLLABORATING,
            modifier = Modifier.size(72.dp),
            transitionEnabled = false,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("多 Agent 协作", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${stats.activeAgents}/${stats.totalAgents} 活跃 · ${stats.completedTasks}/${stats.totalTasks} 任务 · ${stats.totalMessages} 消息", color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
        var modeMenuExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { modeMenuExpanded = true }) { Text(mode.displayName, color = Color(0xFF00E5FF), fontSize = 12.sp) }
            DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                CollaborationMode.values().forEach { m ->
                    DropdownMenuItem(text = { Text(m.displayName) }, onClick = { onModeChange(m); modeMenuExpanded = false })
                }
            }
        }
        Button(onClick = onAddAgent, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
            Text("+ Agent", color = Color.Black, fontSize = 12.sp)
        }
        var presetMenuExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { presetMenuExpanded = true }) { Text("预设", color = Color(0xFFFF6B9D), fontSize = 12.sp) }
            DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                MultiAgentPresets.Preset.values().forEach { preset ->
                    DropdownMenuItem(text = { Text("${preset.icon} ${preset.displayName}") }, onClick = { onLoadPreset(preset); presetMenuExpanded = false })
                }
            }
        }
    }
}

@Composable
private fun AgentListPanel(agents: List<AgentCardData>, modifier: Modifier = Modifier, onRemove: (String) -> Unit) {
    Column(modifier = modifier.fillMaxHeight().background(Color(0xFF111827), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("Agent 列表", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(agents) { agent -> AgentCard(agent = agent, onRemove = { onRemove(agent.id) }) }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentCardData, onRemove: () -> Unit) {
    val roleColor = when (agent.role) {
        AgentRoleType.SUPERVISOR -> Color(0xFF00E5FF)
        AgentRoleType.WORKER -> Color(0xFF4ADE80)
        AgentRoleType.REVIEWER -> Color(0xFFFBBF24)
        AgentRoleType.CRITIC -> Color(0xFFFF6B9D)
        AgentRoleType.OBSERVER -> Color(0xFF60A5FA)
        AgentRoleType.PLANNER -> Color(0xFFA78BFA)
        AgentRoleType.SYSTEM -> Color(0xFF94A3B8)
    }
    val statusColor = when (agent.status) {
        AgentStatus.IDLE -> Color(0xFF64748B)
        AgentStatus.THINKING -> Color(0xFF60A5FA)
        AgentStatus.EXECUTING -> Color(0xFF00E5FF)
        AgentStatus.WAITING -> Color(0xFFFBBF24)
        AgentStatus.COMPLETED -> Color(0xFF4ADE80)
        AgentStatus.ERROR -> Color(0xFFEF4444)
    }
    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2332), RoundedCornerShape(6.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(roleColor))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(agent.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("${agent.role.displayName} · ${agent.status.displayName}", color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove) { Text("✕", color = Color(0xFFEF4444), fontSize = 10.sp) }
    }
}

@Composable
private fun TopologyPanel(topology: CollaborationTopology, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxHeight().background(Color(0xFF111827), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("拓扑图 · ${topology.mode.displayName}", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A), RoundedCornerShape(6.dp))) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                topology.edges.forEach { edge ->
                    val from = topology.nodes.find { it.agentId == edge.fromId }
                    val to = topology.nodes.find { it.agentId == edge.toId }
                    if (from != null && to != null) {
                        drawLine(color = Color(edge.color), start = androidx.compose.ui.geometry.Offset(from.x * w, from.y * h), end = androidx.compose.ui.geometry.Offset(to.x * w, to.y * h), strokeWidth = 2f)
                    }
                }
                topology.nodes.forEach { node ->
                    drawCircle(color = Color(node.color), radius = 16f, center = androidx.compose.ui.geometry.Offset(node.x * w, node.y * h))
                    drawCircle(color = Color(node.color).copy(alpha = 0.3f), radius = 24f, center = androidx.compose.ui.geometry.Offset(node.x * w, node.y * h), style = Stroke(width = 2f))
                }
            }
        }
    }
}

@Composable
private fun TaskMessagePanel(tasks: List<CollaborationTaskCard>, messages: List<AgentMessageCard>, modifier: Modifier = Modifier, onCreateTask: (String, String) -> Unit) {
    var newTaskTitle by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxHeight().background(Color(0xFF111827), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("任务", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(value = newTaskTitle, onValueChange = { newTaskTitle = it }, placeholder = { Text("新任务...", fontSize = 11.sp) }, modifier = Modifier.weight(1f), textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, color = Color.White))
            Button(onClick = { if (newTaskTitle.isNotBlank()) { onCreateTask(newTaskTitle, ""); newTaskTitle = "" } }, contentPadding = PaddingValues(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) { Text("+", color = Color.Black) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(0.4f)) { items(tasks) { task -> TaskRow(task) } }
        HorizontalDivider(color = Color(0xFF1A2332), modifier = Modifier.padding(vertical = 6.dp))
        Text("消息流", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(0.6f)) { items(messages.takeLast(50)) { msg -> MessageRow(msg) } }
    }
}

@Composable
private fun TaskRow(task: CollaborationTaskCard) {
    val statusColor = when (task.status) {
        TaskStatus.PENDING -> Color(0xFF64748B)
        TaskStatus.ASSIGNED -> Color(0xFF60A5FA)
        TaskStatus.IN_PROGRESS -> Color(0xFF00E5FF)
        TaskStatus.REVIEWING -> Color(0xFFFBBF24)
        TaskStatus.COMPLETED -> Color(0xFF4ADE80)
        TaskStatus.FAILED -> Color(0xFFEF4444)
        TaskStatus.CANCELLED -> Color(0xFF64748B)
    }
    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2332), RoundedCornerShape(4.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
        Spacer(Modifier.width(6.dp))
        Text(task.title, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(task.status.displayName, color = statusColor, fontSize = 9.sp)
    }
}

@Composable
private fun MessageRow(msg: AgentMessageCard) {
    val typeColor = when (msg.type) {
        AgentMessageType.TEXT -> Color(0xFF94A3B8)
        AgentMessageType.TASK_ASSIGN -> Color(0xFF00E5FF)
        AgentMessageType.TASK_RESULT -> Color(0xFF4ADE80)
        AgentMessageType.REVIEW -> Color(0xFFFBBF24)
        AgentMessageType.CRITIQUE -> Color(0xFFFF6B9D)
        AgentMessageType.STATUS_UPDATE -> Color(0xFF60A5FA)
        AgentMessageType.ERROR -> Color(0xFFEF4444)
        AgentMessageType.SYSTEM -> Color(0xFF94A3B8)
    }
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2332), RoundedCornerShape(4.dp)).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(msg.fromAgentName, color = typeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(" → ", color = Color(0xFF64748B), fontSize = 10.sp)
            Text(msg.toAgentName ?: "广播", color = Color(0xFF94A3B8), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(msg.type.displayName, color = typeColor, fontSize = 8.sp)
        }
        Text(msg.content, color = Color(0xFFE2E8F0), fontSize = 11.sp)
    }
}
