package com.apex.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apex.agent.ui.navigation.ApexMainScaffold
import com.apex.agent.ui.theme.ApexTheme

/**
 * 主 Activity — Material You 3 风格。
 *
 * 5 个底部 Tab：
 * - Agent（聊天）
 * - 套件（APK 管理）
 * - 协作（模式切换：普通/狂暴/多Agent）
 * - 诊断（日志/性能/崩溃）
 * - 设置
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_MODE = "INITIAL_MODE"
        const val EXTRA_AUTO_ENTER_VOICE_CHAT = "auto_enter_voice_chat"
        const val EXTRA_CHAT_ID = "chat_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApexTheme {
                ApexMainScaffold()
            }
        }
    }
}
