package com.apex.ui.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.core.model.ApiConfig

@Composable
fun SettingsScreen() {
    var showApiConfig by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            SettingsCard(
                icon = Icons.Default.Api,
                title = "API 配置",
                subtitle = "配置 LLM Provider、API Key、模型",
                onClick = { showApiConfig = true }
            )
        }
        item {
            SettingsCard(
                icon = Icons.Default.Palette,
                title = "外观",
                subtitle = "主题、字体、配色",
                onClick = { /* TODO */ }
            )
        }
        item {
            SettingsCard(
                icon = Icons.Default.Info,
                title = "关于",
                subtitle = "版本信息、开源协议",
                onClick = { /* TODO */ }
            )
        }
    }

    if (showApiConfig) {
        ModalBottomSheet(onDismissRequest = { showApiConfig = false }) {
            ApiConfigSheetContent()
        }
    }
}

@Composable
private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ApiConfigSheetContent() {
    val store = com.apex.core.kernel.ApexKernel.configStore
    val keys = com.apex.core.kernel.ConfigKeys

    var endpoint by remember { mutableStateOf(store.getString(keys.API_ENDPOINT, ApiConfig.DEFAULT_ENDPOINT)) }
    var apiKey by remember { mutableStateOf(store.getString(keys.API_KEY, "")) }
    var model by remember { mutableStateOf(store.getString(keys.API_MODEL, ApiConfig.DEFAULT_MODEL)) }
    var systemPrompt by remember { mutableStateOf(store.getString(keys.SYSTEM_PROMPT, ApiConfig.DEFAULT_SYSTEM_PROMPT)) }
    var temperature by remember { mutableStateOf(store.getFloat(keys.TEMPERATURE, 0.7f)) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("API 配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // 预设
        Text("快速预设", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApiConfig.PRESETS.forEach { preset ->
                AssistChip(
                    onClick = {
                        endpoint = preset.endpoint
                        model = preset.model
                    },
                    label = { Text(preset.name) }
                )
            }
        }

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("API Endpoint") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
            }
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("系统提示词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 4
        )
        Text("Temperature: ${"%.1f".format(temperature)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            valueRange = 0f..2f
        )
        Button(
            onClick = {
                store.setString(keys.API_ENDPOINT, endpoint)
                store.setString(keys.API_KEY, apiKey)
                store.setString(keys.API_MODEL, model)
                store.setString(keys.SYSTEM_PROMPT, systemPrompt)
                store.setFloat(keys.TEMPERATURE, temperature)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotEmpty()
        ) {
            Text("保存配置")
        }
        if (saved) {
            Text("✓ 已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun rememberScrollState() = androidx.compose.foundation.rememberScrollState()
