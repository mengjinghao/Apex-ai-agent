package com.apex.apk.rage.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.rage.ui.theme.RageColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 狂暴模式主界面。
 *
 * 4 个区域：
 * 1. 顶部 — 汉堡菜单 + 标题 + 预设选择
 * 2. 任务输入 — 大输入框 + 技能选择 + 发起狂暴
 * 3. 执行进度 — 实时思考/执行/结果（流式）
 * 4. 底部指标 — 任务数/成功率/并发/Token
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RageScreen(
    onMenuClick: () -> Unit = {}
) {
    var taskInput by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(RagePreset.BALANCED) }
    var selectedSkill by remember { mutableStateOf("auto") }
    var showSkillPicker by remember { mutableStateOf(false) }
    var isExecuting by remember { mutableStateOf(false) }
    val executionSteps = remember { mutableStateListOf<ExecutionStep>() }
    val scope = rememberCoroutineScope()

    // 指标
    var totalTasks by remember { mutableStateOf(0L) }
    var successRate by remember { mutableStateOf(0f) }
    var currentConcurrency by remember { mutableStateOf(0) }
    var tokensProcessed by remember { mutableStateOf(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡ 狂暴模式", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    // 预设选择
                    PresetSelector(selectedPreset) { selectedPreset = it }
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 执行进度区域（占大部分空间）
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (executionSteps.isEmpty() && !isExecuting) {
                    item { EmptyState() }
                }
                items(executionSteps) { step -> ExecutionStepCard(step) }
                if (isExecuting) item { TypingIndicator() }
            }

            // 底部指标栏
            MetricsBar(totalTasks, successRate, currentConcurrency, tokensProcessed)

            // 任务输入区
            RageInputBar(
                text = taskInput,
                onTextChange = { taskInput = it },
                selectedSkill = selectedSkill,
                onSkillClick = { showSkillPicker = true },
                isExecuting = isExecuting,
                onExecute = {
                    if (taskInput.isNotBlank() && !isExecuting) {
                        val task = taskInput
                        taskInput = ""
                        scope.launch {
                            isExecuting = true
                            simulateExecution(executionSteps, task, selectedSkill, selectedPreset)
                            isExecuting = false
                            totalTasks++
                            successRate = if (executionSteps.any { !it.success }) successRate * 0.9f else (successRate + 0.1f).coerceAtMost(1f)
                            tokensProcessed += (500..2000).random().toLong()
                        }
                    }
                },
                onStop = { isExecuting = false }
            )
        }
    }

    if (showSkillPicker) {
        SkillPickerDialog(selectedSkill, { showSkillPicker = false }, { selectedSkill = it; showSkillPicker = false })
    }
}

// ============================================================
// 预设选择器
// ============================================================

@Composable
private fun PresetSelector(selected: RagePreset, onSelect: (RagePreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text("${selected.icon} ${selected.displayName}", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = RageColors.DarkPrimaryContainer,
                selectedLabelColor = RageColors.DarkOnPrimaryContainer
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RagePreset.values().forEach { preset ->
                DropdownMenuItem(
                    text = { Text("${preset.icon} ${preset.displayName} — ${preset.description}") },
                    onClick = { onSelect(preset); expanded = false }
                )
            }
        }
    }
}

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⚡", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("狂暴模式就绪", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("31 个内置技能 · 7 种预设 · 4 种执行模式", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text("输入任务描述，选择技能和预设，开始狂暴执行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
// 执行步骤卡片
// ============================================================

@Composable
private fun ExecutionStepCard(step: ExecutionStep) {
    val color = when (step.type) {
        StepType.THINKING -> RageColors.Thinking
        StepType.EXECUTING -> RageColors.Executing
        StepType.SUCCESS -> RageColors.Success
        StepType.FAILED -> RageColors.Failed
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 步骤图标
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(step.icon, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(10.dp))
                // 步骤标题
                Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // 耗时
                Text("${step.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (step.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (step.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1A1C1E)) {
                    Text(step.output, Modifier.padding(8.dp, 6.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = RageColors.Success)
                }
            }
        }
    }
}

// ============================================================
// 打字指示器
// ============================================================

@Composable
private fun TypingIndicator() {
    val t = rememberInfiniteTransition("rage_typing")
    val a1 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "r1")
    val a2 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse, delayMillis = 150), "r2")
    val a3 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse, delayMillis = 300), "r3")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(RageColors.Executing.copy(alpha = 0.2f)), Alignment.Center) { Text("⚡") }
        Spacer(Modifier.width(10.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(14.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a1))
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a2))
                Text("●", color = RageColors.Executing, modifier = Modifier.alpha(a3))
            }
        }
    }
}

// ============================================================
// 指标栏
// ============================================================

@Composable
private fun MetricsBar(total: Long, rate: Float, concurrency: Int, tokens: Long) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricItem("任务", total.toString(), RageColors.DarkPrimary)
            MetricItem("成功率", "${(rate * 100).toInt()}%", RageColors.Success)
            MetricItem("并发", concurrency.toString(), RageColors.Executing)
            MetricItem("Token", if (tokens > 1000) "${tokens / 1000}k" else tokens.toString(), RageColors.Thinking)
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
// 任务输入栏
// ============================================================

@Composable
private fun RageInputBar(
    text: String, onTextChange: (String) -> Unit,
    selectedSkill: String, onSkillClick: () -> Unit,
    isExecuting: Boolean,
    onExecute: () -> Unit, onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column {
            // 技能选择
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = selectedSkill != "auto",
                    onClick = onSkillClick,
                    label = { Text("⚡ ${SKILLS.find { it.id == selectedSkill }?.name ?: "自动选择"}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RageColors.DarkPrimaryContainer,
                        selectedLabelColor = RageColors.DarkOnPrimaryContainer
                    )
                )
            }
            // 输入框
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("描述任务，狂暴模式执行...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RageColors.DarkPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                if (isExecuting) {
                    FilledIconButton(onStop, shape = RoundedCornerShape(50), colors = FilledIconButtonDefaults.colors(containerColor = RageColors.Failed)) {
                        Icon(Icons.Default.Stop, "停止")
                    }
                } else {
                    FilledIconButton(onExecute, enabled = text.isNotBlank(), shape = RoundedCornerShape(50), colors = FilledIconButtonDefaults.colors(containerColor = RageColors.DarkPrimary)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "狂暴执行")
                    }
                }
            }
        }
    }
}

// ============================================================
// 技能选择弹窗
// ============================================================

@Composable
private fun SkillPickerDialog(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择技能") },
        text = {
            Column {
                Text("31 个内置技能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                SKILLS.forEach { skill ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(skill.id) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(skill.icon, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(skill.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (skill.id == selected) Icon(Icons.Default.Check, "已选", tint = RageColors.DarkPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 模拟执行（流式）
// ============================================================

private suspend fun simulateExecution(
    steps: MutableList<ExecutionStep>,
    task: String,
    skill: String,
    preset: RagePreset
) {
    // 1. 思考
    val think = ExecutionStep(StepType.THINKING, "💭", "分析任务", "理解：${task.take(60)}\n技能：${SKILLS.find { it.id == skill }?.name}\n预设：${preset.displayName}", "", 0, true)
    steps.add(think)
    delay(800)
    steps[steps.lastIndex] = think.copy(durationMs = 800)

    // 2. 执行
    val exec = ExecutionStep(StepType.EXECUTING, "⚡", "执行中", "正在使用 ${SKILLS.find { it.id == skill }?.name ?: "ReAct"} 处理...", "", 0, true)
    steps.add(exec)
    delay(1200)
    steps[steps.lastIndex] = exec.copy(durationMs = 1200)

    // 3. 结果
    val success = (1..10).random() > 2  // 80% 成功率
    val result = if (success) {
        ExecutionStep(StepType.SUCCESS, "✅", "完成", "任务执行完成", "Result: ${task.take(40)} → Done\nTokens: ${(500..2000).random()}", (800..2000).random(), true)
    } else {
        ExecutionStep(StepType.FAILED, "❌", "失败", "执行超时或结果不合格", "Error: timeout after 30s", (500..1500).random(), false)
    }
    steps.add(result)
}

// ============================================================
// 数据
// ============================================================

enum class RagePreset(val displayName: String, val icon: String, val description: String) {
    PERFORMANCE("性能", "🚀", "最大并发 · 长超时"),
    BALANCED("平衡", "⚖️", "适中并发 · 合理超时"),
    POWER_SAVER("省电", "🔋", "低并发 · 短超时"),
    LOCAL_INFERENCE("本地推理", "💻", "离线运行"),
    CLOUD_INFERENCE("云端推理", "☁️", "DeepSeek API"),
    STREAMING("流式", "🌊", "超大文本增量"),
    TEST("测试", "🧪", "无 LLM · 纯工具")
}

enum class StepType { THINKING, EXECUTING, SUCCESS, FAILED }

data class ExecutionStep(
    val type: StepType,
    val icon: String,
    val title: String,
    val detail: String,
    val output: String,
    val durationMs: Long,
    val success: Boolean
)

data class RageSkill(val id: String, val name: String, val icon: String, val desc: String)

val SKILLS = listOf(
    RageSkill("auto", "自动选择", "🤖", "根据任务自动选择最佳技能"),
    RageSkill("reasoning.react", "ReAct", "🧠", "推理 + 工具调用循环"),
    RageSkill("reasoning.chain-of-thought", "思维链", "🔗", "逐步分解复杂问题"),
    RageSkill("reasoning.tree-of-thoughts", "思维树", "🌳", "多路径探索选最优解"),
    RageSkill("reasoning.self-consistency", "自一致性", "🎯", "多路径推理选最一致答案"),
    RageSkill("reasoning.reflexion", "反思", "🔄", "自省推理 + 自我纠错"),
    RageSkill("extreme_reasoning", "极限推理", "🔥", "多路径并行 + 红蓝对抗"),
    RageSkill("berserk_execution", "狂暴执行", "💥", "无限制并发 + 自动熔断"),
    RageSkill("adaptive_execution", "自适应执行", "📡", "监控资源动态调策略"),
    RageSkill("tool_racing", "工具竞速", "🏁", "多工具并行取最先成功"),
    RageSkill("tool_fusion", "工具熔断", "🔧", "失败时并行试备选工具"),
    RageSkill("brute_force_ui", "暴力 UI", "👆", "全方位 UI 交互尝试"),
    RageSkill("red_blue_adversarial", "红蓝对抗", "⚔️", "对抗自修正 + 多轮收敛"),
    RageSkill("task_graph", "任务图", "📊", "递归分解为 DAG"),
    RageSkill("task_scheduler", "任务调度", "📅", "拓扑排序 + 优先级队列"),
    RageSkill("recovery", "断点续传", "💾", "快照管理 + 故障恢复"),
    RageSkill("recovery_chain", "恢复链", "🔗", "递进式恢复策略"),
    RageSkill("self_correction", "自修正", "🛠️", "迭代修正 + 质量评估"),
    RageSkill("code_quality_analyzer", "代码质量", "🔍", "复杂度 + 样式 + 安全"),
    RageSkill("api_client", "API 客户端", "🌐", "统一 API 调用"),
    RageSkill("memory_storage", "多级存储", "🗄️", "L1内存/L2文件/L3外部"),
    RageSkill("file_search", "文件搜索", "📂", "混合搜索引擎"),
    RageSkill("infinite_context", "无限上下文", "∞", "滑动窗口超长文本"),
    RageSkill("stream_processor", "流处理器", "🌊", "大文本分块并行"),
    RageSkill("security_manager", "安全管理", "🔒", "安全检查 + 敏感检测"),
    RageSkill("execution_logger", "执行日志", "📝", "事件追踪 + 报告"),
    RageSkill("knowledge_graph", "知识图谱", "🕸️", "实体关系 + 语义搜索"),
    RageSkill("rag_pipeline", "RAG 管道", "📚", "向量存储 + 智能检索"),
    RageSkill("template_manager", "模板管理", "📋", "创建/应用/导出/导入"),
    RageSkill("thinking_agent", "思考 Agent", "💭", "任务分析 + 动态规划"),
    RageSkill("tool_recommendation", "工具推荐", "💡", "智能推荐工具组合")
)
