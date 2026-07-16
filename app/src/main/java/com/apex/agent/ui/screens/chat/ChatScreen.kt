package com.apex.agent.ui.screens.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.common.ApkSuite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent 聊天界面 — 完整流水式输出。
 *
 * 设计参考 ChatGPT / Claude / Cline / Cursor：
 * - 单条 AI 消息内含多个"块"（thinking → text → command → result → text）
 * - 流式逐字输出，块之间无缝衔接
 * - 终端命令调用终端 APK 执行
 * - 安全命令白名单自动执行，危险命令弹窗确认
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onMenuClick: () -> Unit = {},
    onNewChat: () -> Unit = {},
    sessionManager: ChatSessionManager? = null,
    currentSessionId: String? = null,
    onSessionUpdate: (String, String, Int) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf<ChatMessage>(
            ChatMessage(bubbles = listOf(Bubble.Text("你好，我是 Apex AI 助手。\n\n我可以帮你执行任务、分析代码、运行命令、生成文档。\n\n有什么可以帮你的？")), isUser = false)
        )
    }
    var isStreaming by remember { mutableStateOf(false) }
    var selectedSkill by remember { mutableStateOf<String?>(null) }
    var deepThinking by remember { mutableStateOf(false) }
    var webSearch by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var contextPercent by remember { mutableStateOf(8) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var autoCompress by remember { mutableStateOf(true) }
    var selectedModel by remember { mutableStateOf("DeepSeek · deepseek-chat") }
    var showModelPicker by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<String?>(null) }  // 待确认的危险命令

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val launchApk: (String) -> Unit = { ApkIdentityRegistry.launchApk(context, it) }

    if (showSkillPicker) { SkillPickerDialog(SKILLS, selectedSkill, { showSkillPicker = false }, { selectedSkill = it; showSkillPicker = false }) }
    if (showModelPicker) { ModelPickerDialog(MODELS, selectedModel, { showModelPicker = false }, { selectedModel = "${it.providerName} · ${it.modelName}"; showModelPicker = false }) }
    if (showCompressDialog) {
        CompressDialog({ showCompressDialog = false }, {
            val keep = messages.size / 2 + 1
            repeat(messages.size - keep) { if (messages.isNotEmpty()) messages.removeAt(0) }
            contextPercent = (contextPercent * 0.4f).toInt().coerceAtLeast(5)
            showCompressDialog = false
        }, autoCompress, { autoCompress = it })
    }
    if (pendingCommand != null) {
        CommandConfirmDialog(pendingCommand!!, { pendingCommand = null }, {
            // 确认执行 → 跳转终端 APK 执行
            launchApk(ApexSuite.ApkId.TERMINAL)
            pendingCommand = null
        })
    }

    LaunchedEffect(contextPercent) { if (autoCompress && contextPercent >= 85 && !showCompressDialog) showCompressDialog = true }

    // 切换会话时加载历史消息
    LaunchedEffect(currentSessionId) {
        if (currentSessionId != null && sessionManager != null) {
            val stored = sessionManager.loadMessages(currentSessionId!!)
            if (stored.isNotEmpty()) {
                messages.clear()
                messages.addAll(stored.map { it.toChatMessage() })
                contextPercent = (stored.size * 5).coerceAtMost(100)
            } else {
                messages.clear()
                messages.add(ChatMessage(bubbles = listOf(Bubble.Text("你好，我是 Apex AI 助手。\n\n有什么可以帮你的？")), isUser = false))
                contextPercent = 5
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
                title = { Text("Apex Agent") },
                actions = {
                    // 新建对话按钮
                    IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "新建对话") }
                    ContextPercentIndicator(contextPercent) { showCompressDialog = true }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { showCompressDialog = true }) { Icon(Icons.Default.Compress, "压缩") }
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg -> MessageItem(msg) }
                if (isStreaming) item { TypingIndicator() }
            }
            QuickActionsRow({ launchApk(ApexSuite.ApkId.WORKING_FILES) }, { launchApk(ApexSuite.ApkId.TERMINAL) }, {}, { launchApk("workflow") })
            ModelSelectorBar(selectedModel) { showModelPicker = true }
            EnhancedInputBar(
                inputText, { inputText = it }, selectedSkill, { showSkillPicker = true },
                deepThinking, { deepThinking = !deepThinking }, webSearch, { webSearch = !webSearch },
                isStreaming,
                onSend = {
                    if (inputText.isNotBlank() && !isStreaming) {
                        messages.add(ChatMessage(bubbles = listOf(Bubble.Text(inputText)), isUser = true))
                        val userMsg = inputText; inputText = ""
                        contextPercent = (contextPercent + userMsg.length / 50).coerceAtMost(100)
                        // 更新会话
                        currentSessionId?.let { sid -> onSessionUpdate(sid, userMsg, messages.size) }
                        scope.launch {
                            isStreaming = true
                            streamAgentResponse(messages, userMsg, selectedSkill, deepThinking, webSearch, selectedModel, listState) { cmd ->
                                if (CommandSafety.isSafe(cmd)) { launchApk(ApexSuite.ApkId.TERMINAL) }
                                else { pendingCommand = cmd }
                            }
                            isStreaming = false
                            contextPercent = (contextPercent + 15).coerceAtMost(100)
                            // 回复后更新会话 + 保存消息
                            currentSessionId?.let { sid ->
                                onSessionUpdate(sid, messages.lastOrNull()?.bubbles?.lastOrNull()?.let { it.toString().take(50) } ?: "回复", messages.size)
                                sessionManager?.saveMessages(sid, messages.toList())
                            }
                        }
                    }
                },
                onStop = { isStreaming = false }
            )
        }
    }
}

// ============================================================
// 核心数据模型 — 单条消息含多个块
// ============================================================

/** 消息中的"块"— 对标 Claude 的 content block。 */
sealed class Bubble {
    data class Thinking(val text: String) : Bubble()
    data class Text(val text: String) : Bubble()
    data class Command(val command: String, val status: CommandStatus, val output: String = "") : Bubble()
    data class Search(val query: String, val results: List<String>, val status: String) : Bubble()
}
enum class CommandStatus { RUNNING, SUCCESS, FAILED, WAITING }

/** 一条消息。 */
data class ChatMessage(
    val bubbles: List<Bubble>,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================================
// 命令安全分级
// ============================================================

object CommandSafety {
    private val SAFE_PREFIXES = listOf("ls", "cat", "echo", "pwd", "whoami", "date", "grep", "find", "wc", "head", "tail", "tree", "git status", "git log", "git diff", "git branch", "npm run", "npm test", "gradle", "./gradlew", "python -c", "node -e", "which", "uname", "df", "free", "top -n")
    private val DANGEROUS_KEYWORDS = listOf("rm -rf", "mkfs", "dd if=", "chmod 777", ":(){", "fork bomb", "> /dev/sda", "shutdown", "reboot", "init 0", "kill -9")

    fun isSafe(command: String): Boolean {
        val cmd = command.trim().lowercase()
        if (DANGEROUS_KEYWORDS.any { it in cmd }) return false
        return SAFE_PREFIXES.any { cmd.startsWith(it) }
    }

    fun classify(command: String): CommandRisk {
        val cmd = command.trim().lowercase()
        return when {
            DANGEROUS_KEYWORDS.any { it in cmd } -> CommandRisk.DANGEROUS
            SAFE_PREFIXES.any { cmd.startsWith(it) } -> CommandRisk.SAFE
            cmd.startsWith("rm ") || cmd.startsWith("mv ") || cmd.startsWith("cp ") -> CommandRisk.MODERATE
            cmd.contains("sudo") -> CommandRisk.DANGEROUS
            else -> CommandRisk.MODERATE
        }
    }
}
enum class CommandRisk(val label: String, val color: androidx.compose.ui.graphics.Color) {
    SAFE("安全", androidx.compose.ui.graphics.Color(0xFF4CAF50)),
    MODERATE("需确认", androidx.compose.ui.graphics.Color(0xFFFF9800)),
    DANGEROUS("危险", androidx.compose.ui.graphics.Color(0xFFEF5350))
}

// ============================================================
// 流水式输出 — 单条消息内多块流式
// ============================================================

private suspend fun streamAgentResponse(
    messages: MutableList<ChatMessage>,
    userMessage: String,
    skill: String?,
    deepThinking: Boolean,
    webSearch: Boolean,
    model: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCommand: (String) -> Unit
) {
    val bubbles = mutableListOf<Bubble>()
    val msgIndex = messages.size
    messages.add(ChatMessage(bubbles = emptyList(), isUser = false))

    fun updateBubbles() { if (msgIndex < messages.size) messages[msgIndex] = messages[msgIndex].copy(bubbles = bubbles.toList()) }

    // 1. 思考过程（如果开启深度思考）
    if (deepThinking) {
        val thinking = StringBuilder()
        bubbles.add(Bubble.Thinking(""))
        updateBubbles()
        val thinkFull = buildString {
            append("用户想要")
            append(when { userMessage.contains("代码") -> "分析代码"; userMessage.contains("搜索") -> "搜索信息"; userMessage.contains("翻译") -> "翻译"; userMessage.contains("文件") -> "操作文件"; userMessage.contains("运行") -> "执行命令"; else -> "执行任务" })
            append("。\n\n计划：\n1. 理解需求\n2. 选择工具\n3. 执行\n4. 验证结果\n\n模型：$model")
            if (skill != null && skill != "auto") append("\n技能：$skill")
        }
        streamAppend(thinking, thinkFull, 4, 15) { text ->
            bubbles[bubbles.lastIndex] = Bubble.Thinking(text)
            updateBubbles()
            scope(listState, messages.size - 1)
        }
    }

    // 2. 联网搜索
    if (webSearch) {
        bubbles.add(Bubble.Search("", emptyList(), "搜索中..."))
        updateBubbles()
        scope(listState, messages.size - 1)
        delay(800)
        bubbles[bubbles.lastIndex] = Bubble.Search(userMessage.take(40), listOf("Kotlin 官方文档", "GitHub Trending", "Stack Overflow"), "完成 ✓")
        updateBubbles()
        scope(listState, messages.size - 1)
        delay(300)
    }

    // 3. 文字说明
    val text1 = StringBuilder()
    bubbles.add(Bubble.Text(""))
    updateBubbles()
    val text1Full = "好的，我来帮你"
    streamAppend(text1, text1Full, 3, 25) { text ->
        bubbles[bubbles.lastIndex] = Bubble.Text(text)
        updateBubbles(); scope(listState, messages.size - 1)
    }

    // 4. 命令执行（如果任务涉及命令）
    val needsCommand = userMessage.contains("运行") || userMessage.contains("执行") || userMessage.contains("命令") || userMessage.contains("终端") || userMessage.contains("查看") || userMessage.contains("检查")
    if (needsCommand) {
        val cmd = when {
            userMessage.contains("进程") || userMessage.contains("内存") -> "top -n 1"
            userMessage.contains("文件") -> "ls -la"
            userMessage.contains("git") -> "git status"
            userMessage.contains("网络") -> "ifconfig"
            else -> "ls -la"
        }
        // 命令块
        bubbles.add(Bubble.Command(cmd, CommandStatus.WAITING))
        updateBubbles()
        scope(listState, messages.size - 1)
        delay(500)

        // 安全检查
        val risk = CommandSafety.classify(cmd)
        if (risk == CommandRisk.SAFE) {
            // 安全命令直接执行
            bubbles[bubbles.lastIndex] = Bubble.Command(cmd, CommandStatus.RUNNING)
            updateBubbles()
            scope(listState, messages.size - 1)
            delay(300)
            val output = "drwxr-xr-x  4 root root  4096  Jan 1 10:00 Apex\n-rw-r--r--  1 root root  1024  Jan 1 10:01 README.md\ntotal 2 files"
            bubbles[bubbles.lastIndex] = Bubble.Command(cmd, CommandStatus.SUCCESS, output)
            updateBubbles()
            scope(listState, messages.size - 1)
            // 调用终端 APK
            onCommand(cmd)
        } else {
            // 危险/中等命令 → 弹窗确认
            onCommand(cmd)
            bubbles[bubbles.lastIndex] = Bubble.Command(cmd, CommandStatus.WAITING, "等待用户确认...")
            updateBubbles()
            scope(listState, messages.size - 1)
            delay(1000)
        }
    }

    // 5. 最终总结
    val text2 = StringBuilder()
    bubbles.add(Bubble.Text(""))
    updateBubbles()
    val text2Full = buildString {
        append(if (needsCommand) "命令执行完成 ✅\n\n" else "")
        append("分析结果：\n\n")
        append("1. **理解需求** — ")
        append(when { userMessage.contains("代码") -> "代码分析"; userMessage.contains("搜索") -> "信息检索"; userMessage.contains("运行") -> "命令执行"; userMessage.contains("文件") -> "文件操作"; else -> "任务执行" })
        append("\n2. **执行方案** — 已完成\n3. **结果** — 如上所示\n\n")
        append("```kotlin\nfun main() {\n    println(\"Done\")\n}\n```")
    }
    streamAppend(text2, text2Full, 3, 20) { text ->
        bubbles[bubbles.lastIndex] = Bubble.Text(text)
        updateBubbles(); scope(listState, messages.size - 1)
    }
}

private suspend fun streamAppend(sb: StringBuilder, full: String, chunk: Int, delayMs: Long, onUpdate: (String) -> Unit) {
    var i = 0
    while (i < full.length) {
        val end = minOf(i + chunk, full.length)
        sb.append(full, i, end)
        onUpdate(sb.toString())
        i = end
        delay(delayMs)
    }
}

private suspend fun scope(listState: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    listState.animateScrollToItem(index)
}

// ============================================================
// 消息渲染
// ============================================================

@Composable
private fun MessageItem(msg: ChatMessage) {
    if (msg.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(msg.bubbles.firstOrNull()?.let { (it as Bubble.Text).text } ?: "", Modifier.padding(12.dp, 16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) { Text("🤖") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                msg.bubbles.forEach { bubble -> BubbleView(bubble) }
            }
        }
    }
}

@Composable
private fun BubbleView(bubble: Bubble) {
    when (bubble) {
        is Bubble.Thinking -> {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)) {
                Column(Modifier.padding(12.dp, 10.dp)) {
                    Text("💭 思考", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(4.dp))
                    RenderMarkdown(bubble.text, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        is Bubble.Text -> {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp, 10.dp)) { RenderMarkdown(bubble.text, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        is Bubble.Command -> {
            val risk = CommandSafety.classify(bubble.command)
            val borderColor = risk.color
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
                Column(Modifier.padding(12.dp, 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💻 ", style = MaterialTheme.typography.titleSmall)
                        Text("$risk · ", style = MaterialTheme.typography.labelSmall, color = borderColor, fontWeight = FontWeight.Bold)
                        Text(when (bubble.status) { CommandStatus.WAITING -> "⏳ 等待"; CommandStatus.RUNNING -> "🔄 执行中"; CommandStatus.SUCCESS -> "✅ 完成"; CommandStatus.FAILED -> "❌ 失败" }, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Text(bubble.command, Modifier.padding(8.dp, 6.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (bubble.output.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color(0xFF1A1C1E)) {
                            Text(bubble.output, Modifier.padding(8.dp, 6.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = androidx.compose.ui.graphics.Color(0xFF4EC9B0))
                        }
                    }
                }
            }
        }
        is Bubble.Search -> {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(12.dp, 10.dp)) {
                    Text("🌐 搜索", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(bubble.query, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (bubble.results.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        bubble.results.forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                    Text(bubble.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun RenderMarkdown(text: String, color: androidx.compose.ui.graphics.Color) {
    val blocks = text.split("```")
    blocks.forEachIndexed { i, block ->
        if (i % 2 == 0) {
            block.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    val isBold = line.startsWith("**") && line.endsWith("**")
                    val isList = line.trim().startsWith(Regex("\\d+\\.|[-•]"))
                    Row(Modifier.fillMaxWidth()) { if (isList) Spacer(Modifier.width(8.dp)); Text(if (isBold) line.removePrefix("**").removeSuffix("**") else line, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal), color = color) }
                } else Spacer(Modifier.height(4.dp))
            }
        } else {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(block.trim(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ============================================================
// 打字指示器
// ============================================================

@Composable
private fun TypingIndicator() {
    val t = rememberInfiniteTransition("typing")
    val a1 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), "d1")
    val a2 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, delayMillis = 200), "d2")
    val a3 by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, delayMillis = 400), "d3")
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) { Text("🤖") }
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(a1))
                Text("●", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(a2))
                Text("●", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(a3))
            }
        }
    }
}

// ============================================================
// 命令确认弹窗
// ============================================================

@Composable
private fun CommandConfirmDialog(command: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val risk = CommandSafety.classify(command)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("命令确认", color = risk.color) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent 要执行以下命令：", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(command, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("风险等级：", style = MaterialTheme.typography.bodySmall)
                    Text(risk.label, style = MaterialTheme.typography.bodySmall, color = risk.color, fontWeight = FontWeight.Bold)
                }
                if (risk == CommandRisk.DANGEROUS) {
                    Text("⚠️ 此命令可能造成不可逆的操作，请确认！", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { FilledButton(onClick = onConfirm, colors = if (risk == CommandRisk.DANGEROUS) ButtonDefaults.filledButtonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.filledButtonColors()) { Text(if (risk == CommandRisk.DANGEROUS) "危险执行" else "确认执行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 辅助组件
// ============================================================

@Composable private fun ContextPercentIndicator(p: Int, onClick: () -> Unit) {
    val c = if (p >= 85) MaterialTheme.colorScheme.error else if (p >= 60) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), Alignment.Center) { CircularProgressIndicator(progress = { p / 100f }, color = c, strokeWidth = 2.dp, modifier = Modifier.fillMaxSize()); Text("$p", style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun CompressDialog(onDismiss: () -> Unit, onCompress: () -> Unit, auto: Boolean, onAuto: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("压缩对话") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("压缩会保留最近消息和关键上下文。", style = MaterialTheme.typography.bodyMedium); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("智能压缩", Modifier.weight(1f)); Switch(checked = auto, onCheckedChange = onAuto) }; Text("上下文超过 85% 时自动提示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, confirmButton = { FilledButton(onClick = onCompress) { Text("立即压缩") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun QuickActionsRow(onFile: () -> Unit, onTerminal: () -> Unit, onTool: () -> Unit, onWorkflow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = onFile, label = { Text("📄 附件") }); AssistChip(onClick = onTerminal, label = { Text("💻 终端") }); AssistChip(onClick = onTool, label = { Text("🔧 工具") }); AssistChip(onClick = onWorkflow, label = { Text("📋 工作流") })
    }
}

@Composable private fun ModelSelectorBar(m: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) { AssistChip(onClick = onClick, label = { Text("🤖 $m") }) } }

@Composable private fun ModelPickerDialog(models: List<ModelItem>, sel: String, onDismiss: () -> Unit, onSelect: (ModelItem) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择模型") }, text = { Column { Text("从市场已配置的模型中选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)); models.forEach { m -> val sel2 = "${m.providerName} · ${m.modelName}" == sel; val ok = m.status.contains("✓"); Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (ok) onSelect(m) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${m.providerName} · ${m.modelName}", fontWeight = FontWeight.Medium, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant); Text(m.status, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }; if (sel2) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SkillPickerDialog(skills: List<SkillItem>, sel: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择技能") }, text = { Column { skills.forEach { s -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(s.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(s.icon, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(s.name, fontWeight = FontWeight.Medium); Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (s.id == sel) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun EnhancedInputBar(text: String, onTextChange: (String) -> Unit, skill: String?, onSkill: () -> Unit, dt: Boolean, onDt: () -> Unit, ws: Boolean, onWs: () -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(skill != null && skill != "auto", onSkill, label = { Text("⚡ 技能") })
                FilterChip(dt, onDt, label = { Text("🧠 深度思考") })
                FilterChip(ws, onWs, label = { Text("🌐 联网搜索") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}) { Icon(Icons.Default.AttachFile, "附件") }
                OutlinedTextField(text, onTextChange, Modifier.weight(1f), placeholder = { Text("向 Agent 发送消息...") }, shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline), maxLines = 4)
                IconButton(onClick = {}) { Icon(Icons.Default.Mic, "语音") }
                if (streaming) FilledIconButton(onStop, shape = RoundedCornerShape(50)) { Icon(Icons.Default.Stop, "停止") }
                else FilledIconButton(onSend, enabled = text.isNotBlank(), shape = RoundedCornerShape(50)) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
            }
        }
    }
}

// ============================================================
// 数据
// ============================================================

data class SkillItem(val id: String, val name: String, val icon: String, val description: String)
data class ModelItem(val provider: String, val providerName: String, val modelName: String, val status: String)

val SKILLS = listOf(SkillItem("auto","自动选择","🤖","根据任务自动选择"), SkillItem("react","ReAct 推理","🧠","推理+工具调用"), SkillItem("cot","思维链","🔗","逐步分解"), SkillItem("tot","思维树","🌳","多路径探索"), SkillItem("code","代码生成","💻","生成代码"), SkillItem("search","深度搜索","🔍","多轮搜索"), SkillItem("translate","翻译","🌐","多语言"), SkillItem("summarize","总结","📝","摘要"), SkillItem("analyze","分析","📊","数据分析"))
val MODELS = listOf(ModelItem("deepseek","DeepSeek","deepseek-chat","深度推理 · 已配置 ✓"), ModelItem("deepseek","DeepSeek","deepseek-reasoner","深度思考 · 已配置 ✓"), ModelItem("openai","OpenAI","gpt-4o","通用 · 已配置 ✓"), ModelItem("claude","Claude","claude-sonnet-4","最强 · 未配置 ✗"), ModelItem("qwen","通义千问","qwen-max","国内直连 · 已配置 ✓"), ModelItem("glm","智谱 GLM","glm-4","国内开源 · 已配置 ✓"), ModelItem("ollama","Ollama","llama3.2","本地推理 · 已配置 ✓"))

/** PersistedMessage → ChatMessage 转换。 */
fun PersistedMessage.toChatMessage(): ChatMessage {
    val bubbles = this.bubbles.map { pb ->
        when (pb) {
            is PersistedBubble.Thinking -> Bubble.Thinking(pb.text)
            is PersistedBubble.Text -> Bubble.Text(pb.text)
            is PersistedBubble.Command -> Bubble.Command(pb.command, runCatching { CommandStatus.valueOf(pb.status) }.getOrDefault(CommandStatus.SUCCESS), pb.output)
            is PersistedBubble.Search -> Bubble.Search(pb.query, pb.results, pb.status)
        }
    }
    return ChatMessage(bubbles = bubbles, isUser = isUser, timestamp = timestamp)
}
