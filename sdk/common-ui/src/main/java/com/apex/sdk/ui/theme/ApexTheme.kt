package com.apex.sdk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Apex 套件统一主题包装。
 * 所有 APK 使用本主题确保视觉一致性。
 */
@Composable
fun ApexTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
