#ifndef ROOT_SESSION_H
#define ROOT_SESSION_H

#include <string>
#include <unordered_map>
#include <mutex>
#include <sys/types.h>

// Security (A-2/A-3): Per-session PTY tracking.
//
// Previously terminal_jni.cpp kept a single global `g_shellPid` / `g_masterFd`
// pair, which meant only ONE root PTY could exist at a time — creating a
// second one would leak the previous shell process + master fd forever
// (zombie shell + fd exhaustion). Additionally the JNI returned only the
// master fd to Java, so Java had no way to kill the shell PID on session
// close (could only close the master fd, which does NOT kill the shell —
// the shell keeps running as a zombie orphan).
//
// This struct + map owns the {child PID, master FD} pair keyed by sessionId.
// `nativeCloseSession(sessionId)` then kills the child, reaps it, and closes
// the master fd — properly tearing down the whole PTY.
struct RootSession {
    pid_t pid;       // child shell PID (so we can SIGKILL on close)
    int masterFd;    // PTY master fd (closed on session close)
};

// Defined in terminal_jni.cpp. Shared by terminal_jni.cpp (nativeCreatePty,
// nativeCloseSession, nativeGetSessionPid) and root_terminal_core.cpp
// (nativeCreatePtyRoot) so that BOTH PTY-creation paths register their
// {pid, masterFd} pair into the same session-keyed map.
extern std::unordered_map<std::string, RootSession> g_rootSessions;
extern std::mutex g_rootSessionsMutex;

#endif // ROOT_SESSION_H
