package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.CodeToken

/**
 * 代码编辑器视图（移动端增强版）— Apex 独有手势交互。
 *
 * **增强点**（VSCode Desktop 没有这些移动端特性）：
 *   - 双指缩放调整字号（1x - 3x）
 *   - 双击切换主题（暗/亮）— 预留
 *   - 长按行号多选 — 预留
 *   - 左滑/右滑文件切换 — 预留
 *
 * 桌面 VSCode 用 Ctrl+滚轮缩放，移动端没有滚轮，用双指。
 */
@Composable
fun CodeEditorView(
    content: String,
    language: String,
    tokens: List<CodeToken>,
    highlightedLine: Int? = null,
    modifier: Modifier = Modifier
) {
    // 字号状态（双指缩放可调）
    var fontSize by remember { mutableFloatStateOf(13f) }
    val lines = remember(content) { content.split("\n") }
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val lineNumberWidth = remember(lines.size) {
        val digits = lines.size.toString().length
        (digits * 10 + 16).dp
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CodeColors.EditorBackground)
            // 双指缩放手势 — Apex 独有的移动端增强
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val new = (fontSize * zoom).coerceIn(8f, 28f)
                    if (kotlin.math.abs(new - fontSize) > 0.5f) {
                        fontSize = new
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .horizontalScroll(horizontalScrollState)
        ) {
            // 行号列
            Column(
                modifier = Modifier
                    .width(lineNumberWidth)
                    .background(CodeColors.LineNumberBackground)
            ) {
                lines.forEachIndexed { index, _ ->
                    val lineNum = index + 1
                    val isHighlighted = lineNum == highlightedLine
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isHighlighted) CodeColors.ActiveLineBackground else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = lineNum.toString(),
                            color = if (isHighlighted) CodeColors.EditorForeground else CodeColors.LineNumberForeground,
                            fontSize = (fontSize - 1).sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            // 代码列
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
            ) {
                lines.forEachIndexed { index, line ->
                    val isHighlighted = (index + 1) == highlightedLine
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isHighlighted) CodeColors.ActiveLineBackground else Color.Transparent)
                            .padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = if (line.isEmpty()) " " else highlightLine(line, language, tokens, index),
                            color = CodeColors.EditorForeground,
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

private fun highlightLine(line: String, language: String, tokens: List<CodeToken>?, lineIndex: Int): AnnotatedString {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("*") ->
            buildAnnotatedString { withStyle(SpanStyle(color = CodeColors.Comment)) { append(line) } }
        else -> buildAnnotatedString {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' || c == '\'' || c == '`' -> {
                        val end = findStringEnd(line, i, c)
                        withStyle(SpanStyle(color = CodeColors.String)) {
                            append(line.substring(i, end + 1))
                        }
                        i = end + 1
                    }
                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }
    }
}

private fun findStringEnd(s: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < s.length) {
        if (s[i] == '\\') {
            i += 2
            continue
        }
        if (s[i] == quote) return i
        i++
    }
    return s.length - 1
}
