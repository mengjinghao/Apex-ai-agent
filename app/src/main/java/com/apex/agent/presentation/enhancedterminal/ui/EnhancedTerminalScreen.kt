package com.apex.agent.presentation.enhancedterminal.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.presentation.enhancedterminal.data.*
import com.apex.agent.presentation.enhancedterminal.state.EnhancedTerminalViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedTerminalScreen(
    modifier: Modifier = Modifier,
    viewModel: EnhancedTerminalViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val paletteOpen by viewModel.paletteOpen.collectAsStateWithLifecycle()
    val reverseSearchOpen by viewModel.reverseSearchOpen.collectAsStateWithLifecycle()
    val reverseSearchQuery by viewModel.reverseSearchQuery.collectAsStateWithLifecycle()
    val quickCommands by viewModel.quickCommands.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val completionResults by viewModel.completionResults.collectAsStateWithLifecycle()
    val completionIndex by viewModel.completionIndex.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchMatches by viewModel.searchMatches.collectAsStateWithLifecycle()
    val searchMatchIndex by viewModel.searchMatchIndex.collectAsStateWithLifecycle()
    val pendingDangerous by viewModel.pendingDangerousCommand.collectAsStateWithLifecycle()
    val snippetEditorOpen by viewModel.snippetEditorOpen.collectAsStateWithLifecycle()
    val editingSnippet by viewModel.editingSnippet.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(activeSession?.lines?.size) {
        val lines = activeSession?.lines ?: return@LaunchedEffect
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = theme.backgroundColor,
        topBar = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().background(theme.backgroundColor).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.cycleTheme() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Palette, null, tint = theme.promptColor, modifier = Modifier.size(14.dp)) }
                    Text(theme.name, color = theme.foregroundColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.decreaseFontSize() }, modifier = Modifier.size(28.dp)) { Text("A-", color = theme.foregroundColor, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Text("${fontSize}sp", color = theme.foregroundColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = { viewModel.increaseFontSize() }, modifier = Modifier.size(28.dp)) { Text("A+", color = theme.foregroundColor, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.openSnippetEditor() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Code, null, tint = theme.successColor, modifier = Modifier.size(14.dp)) }
                    IconButton(onClick = { viewModel.openSearch() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Search, null, tint = theme.infoColor, modifier = Modifier.size(14.dp)) }
                }
                Surface(color = theme.backgroundColor) {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        sessions.forEach { s ->
                            val isActive = s.id == activeSessionId
                            Surface(color = if (isActive) theme.promptColor.copy(alpha = 0.15f) else Color.Transparent, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable { viewModel.switchSession(s.id) }) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, color = if (isActive) theme.promptColor else theme.commentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                    Text(" · ${s.shortDir}", color = theme.commentColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    if (s.isRunning) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(6.dp).background(theme.successColor, RoundedCornerShape(3.dp))) }
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, "关闭", tint = theme.commentColor, modifier = Modifier.size(12.dp).clickable { viewModel.closeSession(s.id) })
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.createSession() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "新建", tint = theme.promptColor, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = theme.backgroundColor) {
                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(quickCommands) { cmd ->
                        Surface(color = cmd.category.color.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.height(28.dp).clickable { viewModel.executeCommandWithCheck(cmd.command) }) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(cmd.icon, fontSize = 12.sp); Spacer(Modifier.width(4.dp)); Text(cmd.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(theme.backgroundColor)) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(activeSession?.lines ?: emptyList()) { line ->
                        val lineIdx = activeSession!!.lines.indexOf(line)
                        val isMatch = searchMatches.contains(lineIdx) && lineIdx == searchMatches.getOrNull(searchMatchIndex)
                        Row(Modifier.fillMaxWidth().background(if (isMatch) theme.promptColor.copy(alpha = 0.15f) else Color.Transparent)) {
                            val segments = remember(line.text) { AnsiParser.parse(line.text) }
                            if (segments.size == 1 && segments.first().color == null) {
                                Text(line.text, color = theme.colorFor(line.kind), fontSize = fontSize.sp, fontFamily = FontFamily.Monospace, lineHeight = (fontSize + 4).sp, modifier = Modifier.fillMaxWidth())
                            } else {
                                Text(buildAnnotatedString { segments.forEach { seg -> pushStyle(SpanStyle(color = seg.color ?: theme.colorFor(line.kind), fontWeight = if (seg.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (seg.italic) FontStyle.Italic else FontStyle.Normal, textDecoration = if (seg.underline) TextDecoration.Underline else TextDecoration.None)); append(seg.text); pop() } }, fontSize = fontSize.sp, fontFamily = FontFamily.Monospace, lineHeight = (fontSize + 4).sp, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = reverseSearchOpen) {
                    val match = viewModel.getReverseSearchMatch()
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    Surface(color = theme.backgroundColor) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("(reverse-i-search)`", color = theme.warningColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            BasicTextField(value = reverseSearchQuery, onValueChange = { viewModel.updateReverseSearchQuery(it) }, modifier = Modifier.weight(1f).focusRequester(focus).padding(horizontal = 4.dp), textStyle = androidx.compose.ui.text.TextStyle(color = theme.warningColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.warningColor), singleLine = true)
                            Text("': ", color = theme.warningColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(match ?: "无匹配", color = if (match != null) theme.foregroundColor else theme.commentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f), maxLines = 1)
                            if (match != null) TextButton(onClick = { inputText = match; viewModel.closeReverseSearch(); scope.launch { inputFocus.requestFocus() } }) { Text("↵", color = theme.successColor, fontSize = 11.sp) }
                            TextButton(onClick = { viewModel.closeReverseSearch(); scope.launch { inputFocus.requestFocus() } }) { Text("ESC", color = theme.commentColor, fontSize = 11.sp) }
                        }
                    }
                }

                AnimatedVisibility(visible = searchOpen) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    Surface(color = theme.backgroundColor) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("/", color = theme.warningColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(value = searchQuery, onValueChange = { viewModel.updateSearchQuery(it) }, modifier = Modifier.weight(1f).focusRequester(focus), textStyle = androidx.compose.ui.text.TextStyle(color = theme.foregroundColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.warningColor), singleLine = true, decorationBox = { if (searchQuery.isEmpty()) Text("搜索输出...", color = theme.commentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace); it })
                            if (searchQuery.isNotEmpty()) {
                                Text(if (searchMatches.isNotEmpty()) "${searchMatchIndex + 1}/${searchMatches.size}" else "无匹配", color = if (searchMatches.isNotEmpty()) theme.successColor else theme.errorColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                                IconButton(onClick = { viewModel.previousSearchMatch()?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.KeyboardArrowUp, "上一个", tint = theme.foregroundColor, modifier = Modifier.size(14.dp)) }
                                IconButton(onClick = { viewModel.nextSearchMatch()?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.KeyboardArrowDown, "下一个", tint = theme.foregroundColor, modifier = Modifier.size(14.dp)) }
                            }
                            IconButton(onClick = { viewModel.closeSearch(); scope.launch { inputFocus.requestFocus() } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "关闭", tint = theme.foregroundColor, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }

                AnimatedVisibility(visible = completionResults.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Surface(color = theme.backgroundColor) {
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            item { Text("Tab:", color = theme.warningColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 4.dp)) }
                            items(completionResults.size) { idx ->
                                val isSelected = idx == completionIndex
                                Surface(color = if (isSelected) theme.promptColor.copy(alpha = 0.3f) else theme.foregroundColor.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp), modifier = Modifier.height(20.dp).clickable { inputText = completionResults[idx]; viewModel.clearCompletions(); scope.launch { inputFocus.requestFocus() } }) {
                                    Text(completionResults[idx], color = if (isSelected) theme.promptColor else theme.foregroundColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }

                Surface(color = theme.backgroundColor) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.openPalette() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) { Icon(Icons.Default.Search, null, tint = theme.infoColor, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)); Text("命令面板", color = theme.infoColor, fontSize = 10.sp) }
                            TextButton(onClick = { viewModel.openReverseSearch() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) { Text("⌘R", color = theme.warningColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace); Spacer(Modifier.width(4.dp)); Text("反向搜索", color = theme.warningColor, fontSize = 10.sp) }
                        }
                        Row(Modifier.fillMaxWidth().background(theme.backgroundColor, RoundedCornerShape(8.dp)).border(1.dp, theme.commentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (activeSession?.isRunning == true) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.dp, color = theme.warningColor) else Text("❯", color = theme.promptColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("${activeSession?.shortDir ?: "~"} ", color = theme.infoColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            BasicTextField(value = inputText, onValueChange = { inputText = it; if (it.isNotBlank()) viewModel.requestCompletion(it) else viewModel.clearCompletions() }, modifier = Modifier.weight(1f).focusRequester(inputFocus), textStyle = androidx.compose.ui.text.TextStyle(color = theme.foregroundColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.promptColor), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank()) { viewModel.executeCommandWithCheck(inputText); inputText = ""; viewModel.clearCompletions() } }, onPrevious = { viewModel.getPreviousCommand()?.let { inputText = it } }, onNext = { viewModel.getNextCommand()?.let { inputText = it } }), decorationBox = { if (inputText.isEmpty()) Text("输入命令... (help / ↑↓ 历史 / Tab 补全)", color = theme.commentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace); it })
                            Spacer(Modifier.width(8.dp))
                            Surface(color = if (inputText.isNotBlank()) theme.promptColor else theme.commentColor.copy(alpha = 0.3f), shape = RoundedCornerShape(6.dp), modifier = Modifier.size(28.dp).clickable(enabled = inputText.isNotBlank()) { if (inputText.isNotBlank()) { viewModel.executeCommandWithCheck(inputText); inputText = ""; viewModel.clearCompletions() } }) { Box(contentAlignment = Alignment.Center) { Text("↵", color = if (inputText.isNotBlank()) Color.Black else theme.commentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold) } }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = paletteOpen, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically(), modifier = Modifier.fillMaxSize()) {
                CommandPaletteOverlay(viewModel = viewModel, onExecute = { item -> val cmd = viewModel.executePaletteItem(item); inputText = cmd; viewModel.closePalette(); if (cmd.isNotBlank()) { viewModel.executeCommandWithCheck(cmd); inputText = "" }; scope.launch { inputFocus.requestFocus() } }, onClose = { viewModel.closePalette() })
            }

            // 危险命令确认对话框
            pendingDangerous?.let { danger ->
                AlertDialog(
                    onDismissRequest = { viewModel.cancelDangerousCommand() },
                    icon = { Icon(Icons.Default.Warning, null, tint = Color(danger.level.color)) },
                    title = { Text("⚠ ${danger.level.label}", color = Color(danger.level.color), fontWeight = FontWeight.Bold) },
                    text = { Text("即将执行危险命令:\n\n${danger.description}\n\n确定要继续吗", fontSize = 13.sp) },
                    confirmButton = { TextButton(onClick = { viewModel.confirmDangerousCommand() }, colors = ButtonDefaults.textButtonColors(contentColor = Color(danger.level.color))) { Text("确认执行") } },
                    dismissButton = { TextButton(onClick = { viewModel.cancelDangerousCommand() }) { Text("取消") } },
                    containerColor = theme.backgroundColor,
                    titleContentColor = theme.foregroundColor,
                    textContentColor = theme.foregroundColor,
                )
            }

            // 代码段编辑器 BottomSheet
            if (snippetEditorOpen) {
                SnippetEditorSheet(
                    existing = editingSnippet,
                    theme = theme,
                    onSave = { name, content, lang, tags -> viewModel.saveSnippet(name, content, lang, tags) },
                    onClose = { viewModel.closeSnippetEditor() },
                )
            }
        }
    }
}

@Composable
private fun CommandPaletteOverlay(viewModel: EnhancedTerminalViewModel, onExecute: (CommandPaletteItem) -> Unit, onClose: () -> Unit) {
    val query by viewModel.paletteQuery.collectAsStateWithLifecycle()
    val results by viewModel.paletteResults.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onClose), contentAlignment = Alignment.TopCenter) {
        Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 80.dp, horizontal = 16.dp).fillMaxWidth().heightIn(max = 400.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(value = query, onValueChange = { viewModel.updatePaletteQuery(it) }, modifier = Modifier.weight(1f).focusRequester(focus), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00E5FF)), singleLine = true, decorationBox = { if (query.isEmpty()) Text("搜索命令 / 历史 / 别名...", color = Color(0xFF64748B), fontSize = 13.sp, fontFamily = FontFamily.Monospace); it })
                    Spacer(Modifier.width(8.dp))
                    Text("ESC", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Color(0xFF334155))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(results.size) { idx -> val item = results[idx]; Row(Modifier.fillMaxWidth().background(if (idx == 0) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent).clickable { onExecute(item) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (item) { is CommandPaletteItem.HistoryItem -> "📜"; is CommandPaletteItem.QuickCommandItem -> item.cmd.icon; is CommandPaletteItem.AliasItem -> "🔗"; is CommandPaletteItem.SnippetItem -> "📝"; is CommandPaletteItem.BuiltInItem -> "⚡" }
                        Text(icon, fontSize = 14.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { val title = when (item) { is CommandPaletteItem.HistoryItem -> item.command; is CommandPaletteItem.QuickCommandItem -> item.cmd.label; is CommandPaletteItem.AliasItem -> "${item.alias.alias} → ${item.alias.command}"; is CommandPaletteItem.SnippetItem -> item.snippet.name; is CommandPaletteItem.BuiltInItem -> item.name }; Text(title, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1); val desc = when (item) { is CommandPaletteItem.QuickCommandItem -> item.cmd.command; is CommandPaletteItem.AliasItem -> item.alias.description ?: ""; is CommandPaletteItem.SnippetItem -> item.snippet.content.take(40); is CommandPaletteItem.BuiltInItem -> item.description; else -> "" }; if (desc.isNotBlank()) Text(desc, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1) }
                    }}
                }
                if (results.isEmpty()) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("无匹配结果", color = Color(0xFF64748B), fontSize = 13.sp) }
            }
        }
    }
}

// ============ 代码段编辑器 BottomSheet ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditorSheet(
    existing: Snippet?,
    theme: TerminalTheme,
    onSave: (String, String, String, List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var language by remember { mutableStateOf(existing?.language ?: "bash") }
    var tagsText by remember { mutableStateOf(existing?.tags?.joinToString(" ") ?: "") }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = theme.backgroundColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (existing != null) "编辑代码段" else "新建代码段",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.foregroundColor,
            )

            // 名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称", color = theme.commentColor) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = theme.foregroundColor, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.promptColor,
                    unfocusedBorderColor = theme.commentColor.copy(alpha = 0.3f),
                    cursorColor = theme.promptColor,
                ),
            )

            // 语言
            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("语言", color = theme.commentColor) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = theme.foregroundColor, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.promptColor,
                    unfocusedBorderColor = theme.commentColor.copy(alpha = 0.3f),
                    cursorColor = theme.promptColor,
                ),
            )

            // 内容(多行)
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("命令内容(支持多行)", color = theme.commentColor) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = theme.foregroundColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.promptColor,
                    unfocusedBorderColor = theme.commentColor.copy(alpha = 0.3f),
                    cursorColor = theme.promptColor,
                ),
            )

            // 标签
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("标签(空格分隔)", color = theme.commentColor) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = theme.foregroundColor, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.promptColor,
                    unfocusedBorderColor = theme.commentColor.copy(alpha = 0.3f),
                    cursorColor = theme.promptColor,
                ),
            )

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.commentColor),
                ) { Text("取消") }
                Button(
                    onClick = { if (name.isNotBlank() && content.isNotBlank()) onSave(name, content, language, tagsText.split(" ").filter { it.isNotBlank() }) },
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank() && content.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.promptColor, contentColor = Color.Black),
                ) { Text("保存") }
            }
        }
    }
}
