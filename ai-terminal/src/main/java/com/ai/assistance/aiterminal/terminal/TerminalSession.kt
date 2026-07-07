package com.ai.assistance.aiterminal.terminal

import android.os.ParcelFileDescriptor

data class TerminalSession(
    val fileDescriptor: ParcelFileDescriptor,
    val mode: Int,
    val sessionId: String = java.util.UUID.randomUUID().toString()
)
