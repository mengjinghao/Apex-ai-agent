package com.ai.assistance.aiterminal.terminal;

parcelable CommandExecutionEvent {
    String sessionId;
    String command;
    int exitCode;
    long timestamp;
}
