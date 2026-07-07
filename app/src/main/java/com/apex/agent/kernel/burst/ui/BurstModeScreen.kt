package com.apex.agent.kernel.burst.ui

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
import com.apex.agent.kernel.burst.KernelState
import com.apex.ui.mascot.AuraMascotView
import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot

/**
 * 狂暴模式界面(Android 独立界面)。
 *
 * 包含:
 * - 顶部:水母(狂暴形态)+ 内核状态 + 控制按钮
 * - 中部:技能链可视化(已加载的 Skill)
 * - 底部:实时执行日志流
 */
@Composable
fun BurstModeScreen(
    kernelState: KernelState,
    loadedSkills: List<String>,
    logs: List<BurstLogEntry>,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBerserk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E1A))
            .padding(16.dp)
    ) {
        // 顶部:水母 + 状态 + 控制
        BurstTopBar(
            kernelState = kernelState,
            skillCount = loadedSkills.size,
            logCount = logs.size,
            onStart = onStart,
            onPause = onPause,
            onStop = onStop,
            onBerserk = onBerserk,
        )
        Spacer(Modifier.height(12.dp))

        // 技能链
        SkillChainPanel(skills = loadedSkills, modifier = Modifier.weight(0.35f))
        Spacer(Modifier.height(12.dp))

        // 日志流
        LogStreamPanel(logs = logs, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun BurstTopBar(
    kernelState: KernelState,
    skillCount: Int,
    logCount: Int,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBerserk: () -> Unit,
) {
    val stateColor = when (kernelState) {
        KernelState.STOPPED -> Color(0xFF64748B)
        KernelState.STARTING -> Color(0xFFFBBF24)
        KernelState.RUNNING -> Color(0xFF00E5FF)
        KernelState.PAUSED -> Color(0xFFFBBF24)
        KernelState.STOPPING -> Color(0xFF60A5FA)
        KernelState.ERROR -> Color(0xFFEF4444)
    }
    val stateName = when (kernelState) {
        KernelState.STOPPED -> "已停止"
        KernelState.STARTING -> "启动中"
        KernelState.RUNNING -> "运行中"
        KernelState.PAUSED -> "已暂停"
        KernelState.STOPPING -> "停止中"
        KernelState.ERROR -> "错误"
    }
    val isBerserk = kernelState == KernelState.RUNNING

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 水母(狂暴形态)
        AuraMascotView(
            form = if (isBerserk) AuraMascot.AuraForm.BERSERK else AuraMascot.AuraForm.IDLE,
            modifier = Modifier.size(72.dp),
            transitionEnabled = true,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("狂暴模式", color = Color(0xFFFF6B9D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(stateColor, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text(stateName, color = stateColor, fontSize = 12.sp)
                Text("  ·  $skillCount 技能  ·  $logCount 日志", color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
        // 控制按钮
        Button(onClick = onStart, enabled = kernelState == KernelState.STOPPED, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80))) { Text("启动", color = Color.Black, fontSize = 12.sp) }
        Button(onClick = onPause, enabled = kernelState == KernelState.RUNNING, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24))) { Text("暂停", color = Color.Black, fontSize = 12.sp) }
        Button(onClick = onStop, enabled = kernelState != KernelState.STOPPED, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Text("停止", color = Color.White, fontSize = 12.sp) }
        Button(onClick = onBerserk, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B9D))) { Text("🔥 狂暴", color = Color.White, fontSize = 12.sp) }
    }
}

@Composable
private fun SkillChainPanel(skills: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF111827), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text("技能链 (Skill Chain)", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (skills.isEmpty()) {
            Text("未加载技能", color = Color(0xFF64748B), fontSize = 11.sp)
        } else {
            skills.forEachIndexed { i, skill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[${i + 1}]", color = Color(0xFF00E5FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(skill, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (i < skills.size - 1) {
                        Spacer(Modifier.width(8.dp))
                        Text("→", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun LogStreamPanel(logs: List<BurstLogEntry>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF060912), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1A2332), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text("实时日志", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(logs.takeLast(200)) { log -> LogRow(log) }
        }
    }
}

@Composable
private fun LogRow(log: BurstLogEntry) {
    val levelColor = when (log.level) {
        BurstLogLevel.DEBUG -> Color(0xFF64748B)
        BurstLogLevel.INFO -> Color(0xFF00E5FF)
        BurstLogLevel.WARN -> Color(0xFFFBBF24)
        BurstLogLevel.ERROR -> Color(0xFFEF4444)
        BurstLogLevel.BERSERK -> Color(0xFFFF6B9D)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(log.timestamp, color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("[${log.level.name}]", color = levelColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(log.message, color = Color(0xFFE2E8F0), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

/** 日志级别 */
enum class BurstLogLevel { DEBUG, INFO, WARN, ERROR, BERSERK }

/** 日志条目 */
data class BurstLogEntry(
    val timestamp: String,
    val level: BurstLogLevel,
    val message: String,
)
