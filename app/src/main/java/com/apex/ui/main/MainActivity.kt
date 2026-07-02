package com.apex.ui.main

import android.app.Activity

/**
 * 主 Activity — Stub。
 * 原 Compose 主界面已移除。此 stub 保留类引用，业务代码（BackgroundServiceManager /
 * AIForegroundService / AppIconManager）通过 Intent 启动此 Activity 来打开主界面。
 *
 * 恢复 UI 时，在此处添加 Compose setContent {} 即可。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_INITIAL_MODE = "INITIAL_MODE"
        const val EXTRA_AUTO_ENTER_VOICE_CHAT = "auto_enter_voice_chat"
        const val EXTRA_CHAT_ID = "chat_id"
    }

    override fun onResume() {
        super.onResume()
        // Stub: 原 UI 显示主聊天界面，已移除
        // 保留 Activity 让业务代码的 Intent 不会崩溃
    }
}
