package com.apex.apk.workingfiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apex.sdk.ui.theme.ApexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApexTheme {
                WorkingFilesScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkingFilesScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Apex Working Files") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Apex Working Files",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "APK ID: working-files\n" +
                       "Process: com.apex.agent.mainprocess\n" +
                       "SharedUserId: com.apex.agent.suite",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "This APK is part of the Apex Suite. " +
                       "Invoked by other APKs with zero latency via " +
                       "android:process sharing + InProcessRegistry.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
