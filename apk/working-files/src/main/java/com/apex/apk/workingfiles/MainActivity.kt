package com.apex.apk.workingfiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.components.AgentFlowView
import com.apex.apk.workingfiles.ui.components.CodeEditorView
import com.apex.apk.workingfiles.ui.components.DiffView
import com.apex.apk.workingfiles.ui.components.FileTreeView
import com.apex.apk.workingfiles.ui.components.TimelineView
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.FileTreeNode
import com.apex.lib.workingfiles.agent.AgentFlow
import com.apex.lib.workingfiles.agent.AgentSession
import com.apex.lib.workingfiles.agent.AgentStep
import com.apex.lib.workingfiles.diff.FileDiff
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val facade: WorkingFilesServiceFacade?
        get() = com.apex.sdk.bridge.TypedServiceRegistry.get<WorkingFilesServiceFacade>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WorkingFilesScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WorkingFilesScreen() {
        var currentTab by rememberSaveable { mutableStateOf(WorkingFilesTab.EXPLORER) }
        var selectedFilePath by rememberSaveable { mutableStateOf<String?>(null) }
        var fileTree by remember { mutableStateOf<FileTreeNode?>(null) }
        var fileContent by remember { mutableStateOf<String>("") }
        var fileLanguage by remember { mutableStateOf("text") }
        var snapshots by remember { mutableStateOf<List<SnapshotSummary>>(emptyList()) }
        var selectedSnapshot by remember { mutableStateOf<SnapshotSummary?>(null) }
        var currentDiff by remember { mutableStateOf<FileDiff?>(null) }
        var agentFlows by remember { mutableStateOf<List<AgentSession>>(emptyList()) }
        var selectedFlow by remember { mutableStateOf<AgentFlow?>(null) }
        var rootPath by rememberSaveable { mutableStateOf("/sdcard") }
        var showRestoreDialog by remember { mutableStateOf<SnapshotSummary?>(null) }
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        // 加载文件树
        LaunchedEffect(rootPath) {
            scope.launch {
                facade?.let { f ->
                    val result = withContext(Dispatchers.IO) { f.getFileTree(rootPath) }
                    if (result is com.apex.sdk.common.BridgeResult.Success) {
                        fileTree = result.value
                    }
                }
            }
        }

        // 首次进入 Agent 流程 tab 时加载会话列表
        LaunchedEffect(currentTab) {
            if (currentTab == WorkingFilesTab.AGENT_FLOW && agentFlows.isEmpty()) {
                scope.launch {
                    facade?.let { f ->
                        val r = withContext(Dispatchers.IO) { f.listAgentSessions() }
                        if (r is com.apex.sdk.common.BridgeResult.Success) {
                            agentFlows = r.value
                        }
                    }
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Apex 工作文件区 · VSCode 式代码浏览器",
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    actions = {
                        TabSelector(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CodeColors.Surface,
                        titleContentColor = CodeColors.EditorForeground,
                        actionIconContentColor = CodeColors.EditorForeground
                    )
                )
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(CodeColors.EditorBackground)
            ) {
                // 左侧：文件树 / 时间线 / Agent 会话列表
                Box(modifier = Modifier.weight(0.35f)) {
                    when (currentTab) {
                        WorkingFilesTab.EXPLORER -> {
                            fileTree?.let { tree ->
                                FileTreeView(
                                    rootNode = tree,
                                    selectedPath = selectedFilePath,
                                    onFileClick = { node ->
                                        selectedFilePath = node.path
                                        scope.launch {
                                            facade?.let { f ->
                                                val r = withContext(Dispatchers.IO) { f.loadCodeFileWithTokens(node.path) }
                                                if (r is com.apex.sdk.common.BridgeResult.Success) {
                                                    r.value?.let { cf ->
                                                        fileContent = cf.content
                                                        fileLanguage = cf.language
                                                    }
                                                    // 同时加载快照历史
                                                    val snaps = withContext(Dispatchers.IO) { f.listSnapshots(node.path) }
                                                    if (snaps is com.apex.sdk.common.BridgeResult.Success) {
                                                        snapshots = snaps.value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            } ?: Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        WorkingFilesTab.TIMELINE -> {
                            TimelineView(
                                snapshots = snapshots,
                                selectedSnapshotId = selectedSnapshot?.id,
                                onSnapshotClick = { snap ->
                                    selectedSnapshot = snap
                                    scope.launch {
                                        facade?.let { f ->
                                            // 加载快照内容
                                            val snapR = withContext(Dispatchers.IO) { f.getSnapshot(snap.id) }
                                            if (snapR is com.apex.sdk.common.BridgeResult.Success) {
                                                snapR.value?.let { s ->
                                                    fileContent = s.content
                                                    fileLanguage = CodePreview.detectLanguage(s.relativePath)
                                                    selectedFilePath = s.filePath
                                                    // 加载与当前的 diff
                                                    val diffR = withContext(Dispatchers.IO) { f.diffWithCurrent(snap.id) }
                                                    if (diffR is com.apex.sdk.common.BridgeResult.Success) {
                                                        currentDiff = diffR.value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onRestoreClick = { snap ->
                                    showRestoreDialog = snap
                                }
                            )
                        }
                        WorkingFilesTab.AGENT_FLOW -> {
                            // 左侧 Agent 会话列表
                            AgentSessionListView(
                                sessions = agentFlows,
                                onSessionClick = { session ->
                                    scope.launch {
                                        facade?.let { f ->
                                            val r = withContext(Dispatchers.IO) { f.getAgentFlow(session.id) }
                                            if (r is com.apex.sdk.common.BridgeResult.Success) {
                                                selectedFlow = r.value
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Divider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = CodeColors.TimelineLine
                )

                // 右侧：代码 / Diff / Agent 流程
                Box(modifier = Modifier.weight(0.65f)) {
                    when (currentTab) {
                        WorkingFilesTab.EXPLORER -> {
                            CodeEditorView(
                                content = fileContent,
                                language = fileLanguage,
                                tokens = emptyList()
                            )
                        }
                        WorkingFilesTab.TIMELINE -> {
                            currentDiff?.let { diff ->
                                DiffView(diff = diff)
                            } ?: CodeEditorView(
                                content = fileContent,
                                language = fileLanguage,
                                tokens = emptyList()
                            )
                        }
                        WorkingFilesTab.AGENT_FLOW -> {
                            selectedFlow?.let { flow ->
                                AgentFlowView(
                                    flow = flow,
                                    onStepClick = { step ->
                                        scope.launch {
                                            facade?.let { f ->
                                                val r = withContext(Dispatchers.IO) { f.diffForStep(step.id) }
                                                if (r is com.apex.sdk.common.BridgeResult.Success) {
                                                    currentDiff = r.value
                                                }
                                            }
                                        }
                                    }
                                )
                            } ?: Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "选择左侧的 Agent 会话查看执行流程",
                                    color = CodeColors.Disabled
                                )
                            }
                        }
                    }
                }
            }
        }

        // 回退确认对话框
        showRestoreDialog?.let { snap ->
            AlertDialog(
                onDismissRequest = { showRestoreDialog = null },
                title = { Text("回退文件") },
                text = {
                    Text("确认将文件回退到此快照？\n\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(snap.timestamp))}\n描述：${snap.description.ifEmpty { "(无描述)" }}\n\n回退后会生成新的快照记录此次操作。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            facade?.let { f ->
                                val r = withContext(Dispatchers.IO) { f.restoreSnapshot(snap.id) }
                                val msg = if (r is com.apex.sdk.common.BridgeResult.Success && r.value) {
                                    "已回退到指定快照"
                                } else {
                                    "回退失败"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                        showRestoreDialog = null
                    }) { Text("回退") }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreDialog = null }) { Text("取消") }
                }
            )
        }
    }

    @Composable
    private fun TabSelector(
        currentTab: WorkingFilesTab,
        onTabSelected: (WorkingFilesTab) -> Unit
    ) {
        Row {
            WorkingFilesTab.values().forEach { tab ->
                FilterChip(
                    selected = currentTab == tab,
                    onClick = { onTabSelected(tab) },
                    label = { Text(tab.displayName, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun AgentSessionListView(
        sessions: List<AgentSession>,
        onSessionClick: (AgentSession) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CodeColors.Surface)
        ) {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = CodeColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Agent 会话",
                    color = CodeColors.EditorForeground,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SEMIBOLD
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${sessions.size}",
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp
                )
            }
            androidx.compose.material3.Divider(color = CodeColors.TimelineLine, thickness = 0.5.dp)

            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions) { session ->
                    val dateFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSessionClick(session) }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = session.agentName + " · " + session.mode.displayName,
                            color = CodeColors.EditorForeground,
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.MEDIUM
                        )
                        Text(
                            text = session.taskDescription,
                            color = CodeColors.LineNumberForeground,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "${dateFormat.format(java.util.Date(session.startTime))} · ${session.stepCount}步",
                            color = CodeColors.LineNumberForeground,
                            fontSize = 10.sp
                        )
                    }
                    androidx.compose.material3.Divider(color = CodeColors.TimelineLine.copy(alpha = 0.3f), thickness = 0.5.dp)
                }
            }
        }
    }
}

enum class WorkingFilesTab(val displayName: String) {
    EXPLORER("文件浏览"),
    TIMELINE("时间线"),
    AGENT_FLOW("Agent 流程")
}
