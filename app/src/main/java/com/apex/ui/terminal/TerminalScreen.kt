package com.apex.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.ui.mascot.AuraMascotView
import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot

/**
 * 终端界面(Android 独立界面)。
 *
 * 包含:
 * - 左侧:水母(形态跟随终端状态)+ 状态信息
 * - 右侧:命令输入 + 输出流
 */
@Composable
fun TerminalScreen(
    mascotForm: AuraMascot.AuraForm,
    lines: List<TerminalLine>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E1A))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 左:水母 + 状态
        Column(
            modifier = Modifier.width(200.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AuraMascotView(
                form = mascotForm,
                modifier = Modifier.size(180.dp),
                transitionEnabled = true,
            )
            Spacer(Modifier.height(12.dp))
            Text(AuraMascot.getEmoji(mascotForm), fontSize = 24.sp)
            Text(mascotForm.displayName, color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(mascotForm.description, color = Color(0xFF94A3B8), fontSize = 11.sp)
        }

        // 右:终端
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight()
                .background(Color(0xFF060912), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            // 输出
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(lines) { line -> TerminalLineRow(line) }
            }
            Spacer(Modifier.height(8.dp))
            // 输入框
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("❯ ", color = Color(0xFF00E5FF), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                TextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入命令...", color = Color(0xFF64748B), fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    singleLine = true,
                )
                Button(onClick = onSubmit, contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
                    Text("↵", color = Color.Black, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val color = when (line.kind) {
        TerminalLineKind.PROMPT -> Color(0xFF00E5FF)
        TerminalLineKind.OUTPUT -> Color(0xFF94A3B8)
        TerminalLineKind.SYSTEM -> Color(0xFF60A5FA)
        TerminalLineKind.ERROR -> Color(0xFFEF4444)
        TerminalLineKind.SUCCESS -> Color(0xFF4ADE80)
        TerminalLineKind.AGENT -> Color(0xFFE2E8F0)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(line.text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

/** 终端行类型 */
enum class TerminalLineKind { PROMPT, OUTPUT, SYSTEM, ERROR, SUCCESS, AGENT }

/** 终端行 */
