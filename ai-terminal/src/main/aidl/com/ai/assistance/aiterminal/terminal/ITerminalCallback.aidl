package com.ai.assistance.aiterminal.terminal;

interface ITerminalCallback {
    void onCommandOutput(in String sessionId, in String output);
    
    void onDirectoryChanged(in String sessionId, in String newDir);
    
    void onSessionStateChanged(in String sessionId, in String state);
    
    void onCommandFinished(in String sessionId, in String command, in int exitCode);
    
    void onErrorOccurred(in String sessionId, in String message, in int code);
    
    void onSessionDestroyed(in String sessionId);
}
