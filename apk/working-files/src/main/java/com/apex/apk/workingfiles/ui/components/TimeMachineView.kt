package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 时间机器视图 — Apex 独有的"连续滑动预览"。
 *
 * **移动端手势**：
 *   - 水平滑动时间轴，实时预览任意时刻文件状态
 *   - 无需点击切换，连续变化
 *
 * 类似 iOS 照片应用的"时刻滑块"，但是用于代码版本。
 */
@Composable
fun TimeMachineView(
    timeline: List<SnapshotSummary>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    isPlaying: Boolean,
    onSpeedChange: (Float) -> Unit,
    currentSpeed: Float,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CodeColors.Surface)
            .padding(12.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Timelapse,
                contentDescription = null,
                tint = CodeColors.Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "时间机器",
                color = CodeColors.EditorForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${currentIndex + 1}/${timeline.size}",
                color = CodeColors.LineNumberForeground,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (timeline.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            // 当前快照信息
            val current = timeline.getOrNull(currentIndex)
            current?.let {
                Text(
                    text = dateFormat.format(Date(it.timestamp)),
                    color = CodeColors.EditorForeground,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = it.description.ifEmpty { "(无描述)" },
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 时间轴滑块（手势滑动）
            TimeMachineSlider(
                total = timeline.size,
                current = currentIndex,
                onIndexChange = onIndexChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (currentIndex > 0) onIndexChange(currentIndex - 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, "上一步", tint = CodeColors.EditorForeground, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { if (isPlaying) onPause() else onPlay() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = CodeColors.Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = { if (currentIndex < timeline.size - 1) onIndexChange(currentIndex + 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.SkipNext, "下一步", tint = CodeColors.EditorForeground, modifier = Modifier.size(20.dp))
                }

                // 速度选择
                Spacer(modifier = Modifier.width(8.dp))
                SpeedSelector(currentSpeed = currentSpeed, onSpeedChange = onSpeedChange)
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂无快照",
                color = CodeColors.Disabled,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TimeMachineSlider(
    total: Int,
    current: Int,
    onIndexChange: (Int) -> Unit
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CodeColors.SurfaceVariant)
            .pointerInput(total) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f }
                ) { _, dragAmount ->
                    dragAccumulator += dragAmount
                    val widthPx = size.width.toFloat()
                    if (widthPx > 0) {
                        val progress = (dragAccumulator / widthPx).coerceIn(0f, 1f)
                        val newIndex = (progress * (total - 1)).roundToInt().coerceIn(0, total - 1)
                        if (newIndex != current) {
                            onIndexChange(newIndex)
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 进度条
        val progress = if (total > 1) current.toFloat() / (total - 1) else 0f
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(CodeColors.Primary.copy(alpha = 0.3f))
        )
        // 圆点
        Box(
            modifier = Modifier
                .padding(start = (progress * 100).let { it * 8 / 100 + 4 }.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(50))
                .background(CodeColors.Primary)
                .align(Alignment.CenterStart)
        )
        // 文字提示
        Text(
            text = "← 滑动浏览历史 →",
            color = CodeColors.EditorForeground,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun SpeedSelector(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit
) {
    val speeds = listOf(0.5f, 1f, 2f, 5f, 10f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "速度",
            color = CodeColors.LineNumberForeground,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        speeds.forEach { speed ->
            val isSelected = kotlin.math.abs(speed - currentSpeed) < 0.01f
            FilterChip(
                selected = isSelected,
                onClick = { onSpeedChange(speed) },
                label = { Text("${speed}x", fontSize = 9.sp) },
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .height(24.dp)
            )
        }
    }
}
