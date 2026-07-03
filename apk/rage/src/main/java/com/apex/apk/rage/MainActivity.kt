package com.apex.apk.rage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.apex.apk.rage.ui.screens.RageScreen
import com.apex.apk.rage.ui.theme.RageColors
import com.apex.apk.rage.ui.theme.RageDarkColors
import com.apex.apk.rage.ui.theme.RageLightColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RageTheme {
                RageScreen()
            }
        }
    }
}

@Composable
fun RageTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) RageDarkColors else RageLightColors,
        content = content
    )
}
