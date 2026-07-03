package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 代码编辑器视图 — 显示带行号和语法高亮的代码。
 *
 * - 行号列（VSCode 风格灰色）
 * - 语法高亮（基于 CodeToken）
 * - 等宽字体
 * - 水平 + 垂直滚动
 * - 选中的行高亮（可选）
 */
@Composable
fun CodeEditorView(
    content: String,
    language: String,
    tokens: List<CodeToken>,
    highlightedLine: Int? = null,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) { content.split("\n") }
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val lineNumberWidth = remember(lines.size) {
        val digits = lines.size.toString().length
        (digits * 10 + 16).dp
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(CodeColors.EditorBackground)
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
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 0.dp)
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
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 简单的行级语法高亮。
 *
 * 完整实现需要按 token 切分行，这里用简化的策略：
 * - 单行注释（// 或 #）：整行绿色
 * - 字符串（"..."/'...'）：橙色
 * - 其他：默认色
 *
 * 真实高亮应该用 token 化后的 spans，但需要建立 token → 行的映射。
 * 这里保留 tokens 参数供未来扩展。
 */
private fun highlightLine(line: String, language: String, tokens: List<CodeToken>?, lineIndex: Int): AnnotatedString {
    // 简化：检测行级特征
    val trimmed = line.trimStart()
    return when {
        // 注释
        trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("*") ->
            buildAnnotatedString { withStyle(SpanStyle(color = CodeColors.Comment)) { append(line) } }

        // 整行是字符串（不太常见）
        else -> {
            buildAnnotatedString {
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    when {
                        // 字符串
                        c == '"' || c == '\'' || c == '`' -> {
                            val end = findStringEnd(line, i, c)
                            append(line.substring(i, end + 1))
                            // 这里应该用橙色样式，简化版不实现
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
