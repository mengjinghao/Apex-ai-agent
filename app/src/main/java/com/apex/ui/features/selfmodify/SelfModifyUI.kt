package com.apex.ui.features.selfmodify

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.selfmodify.SelfModifyService
import com.apex.selfmodify.audit.AuditEntry
import com.apex.selfmodify.confirm.ConfirmationRequest
import com.apex.selfmodify.plan.RiskLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog for confirming HIGH/CRITICAL risk modification plans.
 * Per AGENT_SELF_MODIFY_SPEC §7.2 + §9 Phase 4.
 */
@Composable
fun SelfModifyConfirmationDialog(
    request: ConfirmationRequest?,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    if (request == null) return
    val plan = request.plan
    AlertDialog(
        onDismissRequest = { onReject(plan.id) },
        title = {
            Column {
                Text("代码修改确认")
                Text(
                    text = "风险等级: ${plan.riskLevel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (plan.riskLevel) {
                        RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
                        RiskLevel.HIGH -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原因: ${plan.reason}")
                Text("修改文件 (${plan.changes.size}):")
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(plan.changes) { change ->
                        Text(
                            text = "${change.type}: ${change.path}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (plan.riskLevel == RiskLevel.CRITICAL) {
                    Text(
                        "CRITICAL 修改可能影响安全/稳定性,请仔细确认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApprove(plan.id) }) { Text("批准") }
        },
        dismissButton = {
            TextButton(onClick = { onReject(plan.id) }) { Text("拒绝") }
        }
    )
}

/**
 * Audit log viewer screen.
 * Per AGENT_SELF_MODIFY_SPEC §9 Phase 4 "审计日志查看页".
 */
@Composable
fun AuditLogScreen(
    selfModifyService: SelfModifyService,
    modifier: Modifier = Modifier
) {
    var verified by remember { mutableStateOf<Boolean?>(null) }
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        verified = selfModifyService.audit.verify()
        entries = selfModifyService.audit.listEntries()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("自改源码审计日志", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        verified?.let { ok ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("链式哈希校验:", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (ok) "通过 — 日志未被篡改" else "失败 — 日志可能被篡改!",
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("审计条目: ${entries.size} 条", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries.reversed()) { entry ->  // newest first
                AuditEntryCard(entry, dateFormat)
            }
        }
    }
}

@Composable
private fun AuditEntryCard(entry: AuditEntry, dateFormat: SimpleDateFormat) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (entry.compileSuccess) "编译 OK" else "编译失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.compileSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (entry.reloadSuccess != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (entry.reloadSuccess == true) "热重载 OK" else "热重载失败",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text("Agent: ${entry.agentId}", style = MaterialTheme.typography.bodySmall)
            Text("Plan: ${entry.planId.take(8)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("文件: ${entry.filesChanged.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
