package com.ai.assistance.aiterminal.terminal;

import com.ai.assistance.aiterminal.terminal.ITerminalCallback;

interface ITerminalService {
    boolean createSession(in String sessionId);
    boolean startSession(in String sessionId, in String shellType);
    boolean switchSession(in String sessionId);
    boolean closeSession(in String sessionId);
    void closeAllSessions();
    
    boolean executeCommand(in String sessionId, in String command);
    boolean changeDirectory(in String sessionId, in String path);
    String getCurrentDirectory(in String sessionId);
    
    void suspendSession(in String sessionId);
    void resumeSession(in String sessionId);
    
    void registerCallback(in ITerminalCallback callback);
    void unregisterCallback(in ITerminalCallback callback);
}
