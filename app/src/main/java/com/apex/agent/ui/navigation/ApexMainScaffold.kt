package com.apex.agent.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.screens.chat.ChatSession
import com.apex.agent.ui.screens.chat.ChatSessionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面 — 左侧抽屉导航（汉堡菜单）。
 *
 * 抽屉内容：
 * - 新建对话按钮
 * - 历史对话列表（置顶优先 + 按时间降序）
 * - 导航项（Agent / 套件 / 诊断 / 设置）
 * - 关于 + 开发者信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexMainScaffold() {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(ApexTab.CHAT) }

    // 会话管理
    val sessionManager = remember { ChatSessionManager(context) }
    var sessions by remember { mutableStateOf(sessionManager.listAll()) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var showSearchField by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }

    // 筛选
    val displaySessions = if (searchQuery.isBlank()) sessions else sessionManager.search(searchQuery)

    // 新建对话
    val onNewChat: () -> Unit = {
        val newSession = sessionManager.create()
        currentSessionId = newSession.id
        currentTab = ApexTab.CHAT
        sessions = sessionManager.listAll()
        scope.launch { drawerState.close() }
    }

    // 切换对话
    val onSwitchChat: (String) -> Unit = { id ->
        currentSessionId = id
        currentTab = ApexTab.CHAT
        scope.launch { drawerState.close() }
    }

    // 删除对话
    val onDeleteChat: (String) -> Unit = { id ->
        sessionManager.delete(id)
        if (currentSessionId == id) currentSessionId = null
        sessions = sessionManager.listAll()
    }

    // 置顶
    val onTogglePin: (String) -> Unit = { id ->
        sessionManager.togglePin(id)
        sessions = sessionManager.listAll()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                // 头部 — 新建对话 + 搜索
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Apex AI Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showSearchField = !showSearchField }) {
                            Icon(Icons.Default.Search, "搜索")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onNewChat,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("新建对话")
                    }
                    if (showSearchField) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索对话...") },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true
                        )
                    }
                }
                HorizontalDivider()

                // 历史对话列表
                if (displaySessions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "暂无对话\n点击「新建对话」开始" else "未找到匹配的对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(displaySessions) { session ->
                            SessionItem(
                                session = session,
                                isActive = session.id == currentSessionId,
                                onClick = { onSwitchChat(session.id) },
                                onDelete = { onDeleteChat(session.id) },
                                onPin = { onTogglePin(session.id) },
                                onRename = { renamingSession = session }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 导航项
                ApexTab.values().forEach { tab ->
                    NavigationDrawerItem(
                        label = { Text(tab.label) },
                        selected = currentTab == tab,
                        icon = { Icon(tab.icon, tab.description) },
                        onClick = { currentTab = tab; scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                HorizontalDivider()

                // 关于
                NavigationDrawerItem(
                    label = { Text("关于") },
                    selected = false,
                    icon = { Icon(Icons.Default.Info, "关于") },
                    onClick = {},
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Apex Suite · Material You 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("开发者：MJH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("QQ：2544240258", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("微信：meng4117222", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) {
        when (currentTab) {
            ApexTab.CHAT -> com.apex.agent.ui.screens.chat.ChatScreen(
                onMenuClick = { scope.launch { drawerState.open() } },
                onNewChat = onNewChat,
                sessionManager = sessionManager,
                currentSessionId = currentSessionId,
                onSessionUpdate = { id, lastMsg, count ->
                    sessionManager.updateLastMessage(id, lastMsg, count)
                    sessions = sessionManager.listAll()
                }
            )
            ApexTab.SUITE -> com.apex.agent.ui.screens.suite.SuiteScreen(onMenuClick = { scope.launch { drawerState.open() } })
            ApexTab.DIAGNOSTICS -> com.apex.agent.ui.screens.diagnostics.DiagnosticsScreen(onMenuClick = { scope.launch { drawerState.open() } })
            ApexTab.SETTINGS -> com.apex.agent.ui.screens.settings.SettingsScreen(onMenuClick = { scope.launch { drawerState.open() } })
        }
    }

    // 重命名弹窗
    renamingSession?.let { session ->
        var newTitle by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                FilledButton(onClick = {
                    sessionManager.rename(session.id, newTitle)
                    sessions = sessionManager.listAll()
                    renamingSession = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renamingSession = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val bg = if (isActive) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(Modifier.background(bg))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.pinned) {
                    Text("📌", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (session.lastMessage.isNotBlank()) {
                Text(
                    session.lastMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${dateFormat.format(Date(session.updatedAt))} · ${session.messageCount} 条",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.4f)
            )
        }

        // 操作菜单
        IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.PushPin,
                if (session.pinned) "取消置顶" else "置顶",
                tint = if (session.pinned) MaterialTheme.colorScheme.primary else fg.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, "重命名", tint = fg.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}
