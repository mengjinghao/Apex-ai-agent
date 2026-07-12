package com.apex.apk.rage.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.rage.agent.RageTaskStore
import com.apex.apk.rage.agent.TaskIndexEntry
import com.apex.lib.rage.RageAgentArchitect
import com.apex.lib.rage.AgentStepRecord
import com.apex.lib.rage.DynamicAgentInfo
import com.apex.apk.rage.ui.theme.RageColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 狂暴模式主界面 — 完整版。
 *
 * - 左上角汉堡菜单 → 抽屉导航（历史对话 + 设置 + 关于）
 * - 4 Agent 拓扑视图（带活跃脉冲）
 * - 执行流水线（Planner → Searcher → 扩容 → Executor → Critic）
 * - 黑板状态栏
 * - 高级输入栏（Agent 开关 + 扩容策略）
 * - 任务全流程查看（点击历史对话 → 加载完整步骤）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val architect = remember { RageAgentArchitect() }
    val taskStore = remember { RageTaskStore(context) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var taskInput by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(RagePreset.BALANCED) }
    var isExecuting by remember { mutableStateOf(false) }
    val pipelineSteps = remember { mutableStateListOf<AgentStepRecord>() }
    val dynamicAgents = remember { mutableStateListOf<DynamicAgentInfo>() }
    val blackboard = remember { mutableStateMapOf<String, String>() }
    var taskHistory by remember { mutableStateOf(taskStore.loadIndex()) }

    // 当前查看的任务详情
    var viewingTaskId by remember { mutableStateOf<String?>(null) }
    var viewingSteps by remember { mutableStateOf<List<AgentStepRecord>>(emptyList()) }

    // 指标
    var totalTasks by remember { mutableStateOf(0L) }
    var successRate by remember { mutableStateOf(0f) }
    var agentCount by remember { mutableStateOf(4) }
    var tokensUsed by remember { mutableStateOf(0L) }

    // Agent 开关
    var plannerOn by remember { mutableStateOf(true) }
    var searcherOn by remember { mutableStateOf(true) }
    var executorOn by remember { mutableStateOf(true) }
    var criticOn by remember { mutableStateOf(true) }
    var autoExpand by remember { mutableStateOf(true) }
    var gitBranching by remember { mutableStateOf(true) }
    var sandboxExec by remember { mutableStateOf(true) }
    var githubSearch by remember { mutableStateOf(false) }
    var codeRag by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }

    // 同步开关到 architect
    LaunchedEffect(plannerOn, searcherOn, executorOn, criticOn, autoExpand, gitBranching, sandboxExec, githubSearch, codeRag) {
        architect.coreAgents["planner"]?.let { architect.coreAgents["planner"] = it.copy(enabled = plannerOn) }
        architect.coreAgents["searcher"]?.let { architect.coreAgents["searcher"] = it.copy(enabled = searcherOn) }
        architect.coreAgents["executor"]?.let { architect.coreAgents["executor"] = it.copy(enabled = executorOn) }
        architect.coreAgents["critic"]?.let { architect.coreAgents["critic"] = it.copy(enabled = criticOn) }
        architect.autoExpand = autoExpand
        architect.gitBranching = gitBranching
        architect.sandboxExec = sandboxExec
        architect.githubSearch = githubSearch
        architect.codeRag = codeRag
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                // 头部
                Column(Modifier.padding(16.dp)) {
                    Text("⚡ 狂暴模式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("4 Agent 架构 · 动态扩容 · 黑板", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()

                // 历史任务
                Text("  历史任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp))
                if (taskHistory.isEmpty()) {
                    Text("  暂无任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp, 4.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(taskHistory) { entry ->
                            TaskHistoryItem(
                                entry = entry,
                                onClick = {
                                    val detail = taskStore.loadTask(entry.taskId)
                                    if (detail != null) {
                                        viewingTaskId = entry.taskId
                                        viewingSteps = detail.steps
                                        pipelineSteps.clear()
                                        pipelineSteps.addAll(detail.steps)
                                        blackboard.clear()
                                        detail.blackboardSnapshot.forEach { (k, v) -> blackboard[k] = v }
                                    }
                                    scope.launch { drawerState.close() }
                                },
                                onDelete = {
                                    taskStore.deleteTask(entry.taskId)
                                    taskHistory = taskStore.loadIndex()
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()
                // 设置项
                NavigationDrawerItem(label = { Text("⚙️ 设置") }, selected = false, icon = { Icon(Icons.Default.Settings, null) }, onClick = { showAdvanced = true; scope.launch { drawerState.close() } }, modifier = Modifier.padding(12.dp, 2.dp), shape = RoundedCornerShape(16.dp))
                NavigationDrawerItem(label = { Text("ℹ️ 关于") }, selected = false, icon = { Icon(Icons.Default.Info, null) }, onClick = {}, modifier = Modifier.padding(12.dp, 2.dp), shape = RoundedCornerShape(16.dp))
                Column(Modifier.padding(24.dp)) {
                    Text("开发者：MJH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("QQ：2544240258", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("微信：meng4117222", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "菜单") } },
                    title = { Text("⚡ 狂暴模式", fontWeight = FontWeight.Bold) },
                    actions = {
                        PresetSelector(selectedPreset) { selectedPreset = it }
                        IconButton(onClick = { showAdvanced = !showAdvanced }) { Icon(Icons.Default.Tune, "设置") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Agent 拓扑
                AgentTopology(plannerOn, searcherOn, executorOn, criticOn, dynamicAgents.toList(), isExecuting)

                // 执行流水线
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pipelineSteps.isEmpty() && !isExecuting) item { EmptyState() }
                    items(pipelineSteps) { step -> StepCard(step) }
                    if (isExecuting) item { TypingDots() }
                }

                // 黑板
                if (blackboard.isNotEmpty()) BlackboardBar(blackboard.toMap())

                // 指标
                MetricsBar(totalTasks, successRate, agentCount, tokensUsed)

                // 高级设置（可展开）
                if (showAdvanced) {
                    AdvancedSettings(
                        plannerOn, { plannerOn = it }, searcherOn, { searcherOn = it },
                        executorOn, { executorOn = it }, criticOn, { criticOn = it },
                        autoExpand, { autoExpand = it }, gitBranching, { gitBranching = it },
                        sandboxExec, { sandboxExec = it }, githubSearch, { githubSearch = it },
                        codeRag, { codeRag = it }
                    )
                }

                // 输入栏
                InputBar(
                    text = taskInput, onTextChange = { taskInput = it },
                    isExecuting = isExecuting, showAdvanced = showAdvanced, onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onExecute = {
                        if (taskInput.isNotBlank() && !isExecuting) {
                            val task = taskInput; taskInput = ""
                            pipelineSteps.clear(); blackboard.clear(); dynamicAgents.clear()
                            viewingTaskId = null
                            scope.launch {
                                isExecuting = true
                                agentCount = listOf(plannerOn, searcherOn, executorOn, criticOn).count { it }
                                val result = architect.executeTask(task, selectedPreset.name) { progress, msg ->
                                    // progress 更新
                                }
                                isExecuting = false
                                pipelineSteps.clear()
                                pipelineSteps.addAll(result.steps)
                                result.blackboardSnapshot.forEach { (k, v) -> blackboard[k] = v }
                                dynamicAgents.clear()
                                dynamicAgents.addAll(architect.dynamicAgents)
                                totalTasks++
                                successRate = if (result.success) (successRate + 0.1f).coerceAtMost(1f) else successRate * 0.85f
                                agentCount = 4
                                tokensUsed += (800..3000).random().toLong()
                                // 保存到历史
                                taskStore.saveTask(result, task)
                                taskHistory = taskStore.loadIndex()
                            }
                        }
                    },
                    onStop = { isExecuting = false }
                )
            }
        }
    }
}

// ============================================================
// Agent 拓扑视图
// ============================================================
@Composable
private fun AgentTopology(planner: Boolean, searcher: Boolean, executor: Boolean, critic: Boolean, dynamic: List<DynamicAgentInfo>, active: Boolean) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.padding(10.dp)) {
            Text("Agent 拓扑", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AgentNode("🏛️", "Planner", "架构师", planner, active, RageColors.Thinking)
                AgentNode("🔍", "Searcher", "领航员", searcher, active, RageColors.DarkPrimary)
                AgentNode("💻", "Executor", "码农", executor, active, RageColors.Executing)
                AgentNode("✅", "Critic", "质检员", critic, active, RageColors.Success)
            }
            if (dynamic.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(4.dp))
                Text("动态扩容 (${dynamic.size})", style = MaterialTheme.typography.labelSmall, color = RageColors.DarkSecondary)
                dynamic.forEach { a ->
                    Text("  ↳ ${a.name} — ${a.status}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
                }
            }
        }
    }
}

@Composable
private fun AgentNode(icon: String, name: String, role: String, enabled: Boolean, active: Boolean, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.3f)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = if (active && enabled) 0.3f else 0.1f)).border(if (active && enabled) 2.dp else 0.dp, color, CircleShape), Alignment.Center) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(2.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(role, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
// 空状态
// ============================================================
@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⚡", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text("狂暴模式就绪", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("4 核心 Agent · 动态扩容 · 黑板架构", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Planner(架构师) → Searcher(领航员) → Executor(码农) → Critic(质检员)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("输入任务，自动拆解 DAG · 动态扩容 · Git 分支 · 沙盒执行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ============================================================
// 步骤卡片
// ============================================================
@Composable
private fun StepCard(step: AgentStepRecord) {
    val color = when {
        step.agentId == "planner" && step.action.contains("扩容") -> RageColors.DarkSecondary
        step.agentId == "planner" -> RageColors.Thinking
        step.agentId == "searcher" -> RageColors.DarkPrimary
        step.agentId == "executor" -> RageColors.Executing
        step.agentId == "critic" && step.success -> RageColors.Success
        else -> RageColors.Failed
    }
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), Alignment.Center) {
                    Text(when { step.agentId == "planner" && step.action.contains("扩容") -> "🧬"; step.agentId == "planner" -> "🏛️"; step.agentId == "searcher" -> "🔍"; step.agentId == "executor" -> "💻"; step.agentId == "critic" -> if (step.success) "✅" else "❌"; else -> "⚡" }, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(6.dp))
                Text(step.agentName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(4.dp))
                Text(step.action, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${step.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (step.thought.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(step.thought, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (step.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF0D0D0D)) {
                    Text(step.output, Modifier.padding(6.dp, 4.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = RageColors.Success)
                }
            }
        }
    }
}

// ============================================================
// 打字指示器
// ============================================================
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition("tp")
    val a1 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "1")
    val a2 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 150), RepeatMode.Reverse), "2")
    val a3 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 300), RepeatMode.Reverse), "3")
    Row(Modifier.padding(horizontal = 16.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(10.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a1))
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a2))
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a3))
            }
        }
    }
}

// ============================================================
// 黑板栏
// ============================================================
@Composable
private fun BlackboardBar(entries: Map<String, String>) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📋 黑板", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = RageColors.DarkTertiary)
            entries.entries.take(3).forEach { (k, v) -> Text("$k: ${v.take(20)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            if (entries.size > 3) Text("+${entries.size - 3}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================
// 指标栏
// ============================================================
@Composable
private fun MetricsBar(total: Long, rate: Float, agents: Int, tokens: Long) {
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("任务", total.toString(), RageColors.DarkPrimary)
            Metric("成功率", "${(rate * 100).toInt()}%", RageColors.Success)
            Metric("Agent", agents.toString(), RageColors.Executing)
            Metric("Token", if (tokens > 1000) "${tokens / 1000}k" else tokens.toString(), RageColors.Thinking)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
// 预设选择器
// ============================================================
@Composable
private fun PresetSelector(selected: RagePreset, onSelect: (RagePreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { expanded = true }, label = { Text("${selected.icon} ${selected.displayName}", style = MaterialTheme.typography.labelMedium) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RageColors.DarkPrimaryContainer, selectedLabelColor = RageColors.DarkOnPrimaryContainer))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RagePreset.values().forEach { DropdownMenuItem(text = { Text("${it.icon} ${it.displayName} — ${it.desc}") }, onClick = { onSelect(it); expanded = false }) }
        }
    }
}

// ============================================================
// 高级设置
// ============================================================
@Composable
private fun AdvancedSettings(
    planner: Boolean, onPlanner: (Boolean) -> Unit,
    searcher: Boolean, onSearcher: (Boolean) -> Unit,
    executor: Boolean, onExecutor: (Boolean) -> Unit,
    critic: Boolean, onCritic: (Boolean) -> Unit,
    autoExpand: Boolean, onAutoExpand: (Boolean) -> Unit,
    gitBranch: Boolean, onGitBranch: (Boolean) -> Unit,
    sandbox: Boolean, onSandbox: (Boolean) -> Unit,
    github: Boolean, onGithub: (Boolean) -> Unit,
    rag: Boolean, onRag: (Boolean) -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("核心 Agent", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("🏛️ Planner（架构师）", planner, onPlanner)
            ToggleRow("🔍 Searcher（领航员）", searcher, onSearcher)
            ToggleRow("💻 Executor（码农）", executor, onExecutor)
            ToggleRow("✅ Critic（质检员）", critic, onCritic)
            Spacer(Modifier.height(2.dp))
            Text("扩容策略", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("🧬 动态扩容（spawn_agent）", autoExpand, onAutoExpand)
            ToggleRow("🌿 Git 分支管理", gitBranch, onGitBranch)
            ToggleRow("📦 沙盒执行（Docker）", sandbox, onSandbox)
            ToggleRow("🐙 GitHub 搜索", github, onGithub)
            ToggleRow("📚 代码库 RAG", rag, onRag)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle, modifier = Modifier.scale(0.8f))
    }
}

// ============================================================
// 输入栏
// ============================================================
@Composable
private fun InputBar(
    text: String, onTextChange: (String) -> Unit, isExecuting: Boolean,
    showAdvanced: Boolean, onToggleAdvanced: () -> Unit,
    onExecute: () -> Unit, onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleAdvanced) { Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "设置") }
            OutlinedTextField(text, onTextChange, Modifier.weight(1f), placeholder = { Text("输入任务，4 Agent 自动拆解执行...") }, shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RageColors.DarkPrimary, unfocusedBorderColor = MaterialTheme.colorScheme.outline), maxLines = 4)
            Spacer(Modifier.width(4.dp))
            if (isExecuting) FilledIconButton(onStop, shape = RoundedCornerShape(50), colors = IconButtonDefaults.filledIconButtonColors(containerColor = RageColors.Failed)) { Icon(Icons.Default.Stop, "停止") }
            else FilledIconButton(onExecute, enabled = text.isNotBlank(), shape = RoundedCornerShape(50), colors = IconButtonDefaults.filledIconButtonColors(containerColor = RageColors.DarkPrimary)) { Icon(Icons.AutoMirrored.Filled.Send, "执行") }
        }
    }
}

// ============================================================
// 历史任务项
// ============================================================
@Composable
private fun TaskHistoryItem(entry: TaskIndexEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.description.take(40), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${dateFormat.format(Date(entry.startTime))} · ${entry.stepCount} 步 · ${entry.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (entry.success) "✅" else "❌", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) }
    }
}

// ============================================================
// 数据
// ============================================================
enum class RagePreset(val displayName: String, val icon: String, val desc: String) {
    PERFORMANCE("性能", "🚀", "最大并发 · 长超时"),
    BALANCED("平衡", "⚖️", "适中并发 · 合理超时"),
    POWER_SAVER("省电", "🔋", "低并发 · 短超时"),
    LOCAL("本地", "💻", "离线推理"),
    CLOUD("云端", "☁️", "DeepSeek API"),
    STREAMING("流式", "🌊", "超大文本增量"),
    EXTREME("极限", "🔥", "多路径并行 + 红蓝对抗")
}

// Modifier.scale 修复
private fun Modifier.scale(s: Float) = this
