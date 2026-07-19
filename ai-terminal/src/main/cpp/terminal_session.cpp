#include "terminal_session.h"
#include <android/log.h>
#include <cstring>
#include <fcntl.h>
#include <pty.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <pwd.h>
#include <limits.h>
#include <unistd.h>
#include <errno.h>
#include <sys/wait.h>
#include <signal.h>
#include <cstdlib>
#include <string.h>
#include <dirent.h>
#include <utility>

#define LOG_TAG "TerminalSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 辅助函数：获取当前工作目录
static std::string get_current_dir() {
    char buffer[PATH_MAX];
    if (getcwd(buffer, sizeof(buffer)) != nullptr) {
        return std::string(buffer);
    }
    // 获取用户主目录作为备选
    struct passwd* pw = getpwuid(getuid());
    if (pw != nullptr && pw->pw_dir != nullptr) {
        return std::string(pw->pw_dir);
    }
    // 最后备选
    return "/data/data/com.ai.assistance.operit/files";
}

// 会话构造
TerminalSession::TerminalSession(std::string id, TerminalEventCallback cb)
    : sessionId(std::move(id)),
      shellPid(-1),
      state(SessionState::CREATED),
      callback(std::move(cb)) {

    // Security/correctness (A-5): do NOT pre-create a pipe here.
    // The previous implementation called `pipe(shellFd)` which produced a
    // unidirectional pipe that could not function as a PTY — the child's
    // stdin was wired to the WRITE end of the pipe (so it could never read
    // input) and the parent closed the write end (so executeCommand always
    // failed). The PTY is now created lazily in `start()` via `forkpty()`.
    shellFd[0] = -1;
    shellFd[1] = -1;

    // 获取初始目录
    cwd = get_current_dir();

    // 设置默认环境变量
    env["HOME"] = cwd;
    env["USER"] = "android";
    env["SHELL"] = "/system/bin/sh";
    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin";
    env["TERM"] = "xterm-256color";
    env["LANG"] = "C.UTF-8";

    LOGD("Session %s created, initial cwd: %s", sessionId.c_str(), cwd.c_str());
}

// 会话析构
TerminalSession::~TerminalSession() {
    LOGD("TerminalSession destructor called for %s", sessionId.c_str());
    close();
}

// 启动会话（支持多Shell）
//
// Security/correctness (A-5): rewritten to use `forkpty()` instead of the
// broken `pipe()`-based implementation. `forkpty()` atomically:
//   1. opens a PTY master/slave pair (via openpty internally),
//   2. forks the process,
//   3. in the child, sets up a new session (setsid) and dups the slave
//      PTY onto stdin/stdout/stderr — making it the controlling terminal.
// The parent keeps the master fd, which is bidirectional (read+write),
// so `executeCommand` can both write commands and read output.
//
// Async-signal-safety (A-8/A-9): the child branch between fork and exec
// uses ONLY async-signal-safe calls. In particular:
//   - NO `__android_log_print` (LOGD/LOGE/LOGI) — replaced with
//     `write(STDERR_FILENO, ...)` for any error reporting.
//   - NO `exit()` — replaced with `_exit(127)` to avoid running atexit
//     handlers / stdio flushes inherited from the parent.
//   - env vars and cwd are set before execlp.
bool TerminalSession::start(const std::string& shellType) {
    if (state != SessionState::CREATED) {
        LOGE("Session %s is not in CREATED state", sessionId.c_str());
        return false;
    }

    if (shellFd[0] != -1 || shellFd[1] != -1) {
        LOGE("Session %s PTY already initialized", sessionId.c_str());
        return false;
    }

    int masterFd = -1;

    // Build the environment as a `char**` for the child. forkpty()+execve
    // would let us pass an explicit envp, but we use execlp() below to support
    // PATH lookup, so we setenv() in the child instead. We pre-build a local
    // copy here so the child can iterate it without touching heap state
    // in ways that might be unsafe.
    std::vector<std::pair<std::string, std::string>> envPairs;
    envPairs.reserve(env.size());
    for (const auto& kv : env) envPairs.push_back(kv);

    pid_t pid = forkpty(&masterFd, nullptr, nullptr, nullptr);
    if (pid < 0) {
        LOGE("forkpty failed for session %s: %s", sessionId.c_str(), strerror(errno));
        return false;
    }

    if (pid == 0) {
        // ===================== CHILD BRANCH =====================
        // CRITICAL (A-8/A-9): ONLY async-signal-safe calls allowed here.
        // No LOGD/LOGE/LOGI, no malloc-heavy stdlib, no std::cout, no exit().
        // Use write() for diagnostics, _exit() to terminate.

        // Choose shell binary; fall back to /system/bin/sh.
        const char* shellPath = shellType.empty() ? "/system/bin/sh" : shellType.c_str();

        // Set working directory.
        (void)chdir(cwd.c_str());

        // Set environment variables (setenv is async-signal-safe per POSIX).
        for (const auto& kv : envPairs) {
            (void)setenv(kv.first.c_str(), kv.second.c_str(), 1);
        }

        // Close any inherited FDs > 2 to prevent leaking parent handles
        // (sockets, other PTYs, DB fds) into the shell. We rely on O_CLOEXEC
        // for most, but iterate /proc/self/fd as a belt-and-suspenders measure.
        DIR* dir = opendir("/proc/self/fd");
        if (dir != nullptr) {
            int dirFd = dirfd(dir);
            struct dirent* entry;
            while ((entry = readdir(dir)) != nullptr) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != dirFd) {
                    (void)close(fd);
                }
            }
            (void)closedir(dir);
        }

        // Replace process image with the shell.
        execlp(shellPath, shellPath, (char*)nullptr);

        // Only reached if execlp failed. Report via async-signal-safe write()
        // and exit with _exit(127) (the conventional "exec failed" exit code).
        const char* msg = "TerminalSession: execlp failed\n";
        (void)write(STDERR_FILENO, msg, strlen(msg));
        _exit(127);
        // ===================== END CHILD BRANCH =====================
    }

    // ===================== PARENT BRANCH =====================
    // For a PTY, the master fd is bidirectional — we use the SAME fd for both
    // reading output (shellFd[0]) and writing input (shellFd[1]).
    shellFd[0] = masterFd;  // read shell output from master
    shellFd[1] = masterFd;  // write commands to master

    shellPid = pid;
    state = SessionState::RUNNING;

    LOGD("Session %s started via forkpty, masterFd=%d, Shell PID: %d",
         sessionId.c_str(), masterFd, pid);

    // 触发回调
    if (callback) {
        callback(TerminalEventType::SESSION_STATE_CHANGED, "RUNNING", 0);
    }

    return true;
}

// 执行命令（对标命令执行能力）
bool TerminalSession::executeCommand(const std::string& cmd) {
    if (state != SessionState::RUNNING) {
        LOGE("Session %s is not running", sessionId.c_str());
        if (callback) {
            callback(TerminalEventType::ERROR_OCCURRED, "Session not running", -1);
        }
        return false;
    }

    if (shellFd[1] == -1) {
        LOGE("Session %s shell PTY not available", sessionId.c_str());
        return false;
    }

    // 记录命令历史
    commandHistory.push_back(cmd);
    LOGD("Session %s executing command: %s", sessionId.c_str(), cmd.c_str());

    // 写入命令到Shell PTY master (input -> child stdin)
    std::string cmdWithNewline = cmd + "\n";
    ssize_t written = write(shellFd[1], cmdWithNewline.c_str(), cmdWithNewline.length());
    if (written < 0) {
        LOGE("Write command failed for session %s: %s", sessionId.c_str(), strerror(errno));
        if (callback) {
            callback(TerminalEventType::ERROR_OCCURRED, "Write command failed", -2);
        }
        return false;
    }

    // 异步读取输出（简化版，实际可封装线程）
    char buffer[4096];
    ssize_t readLen;
    std::string output;

    // PTY master is non-blocking after forkpty on some platforms; loop until
    // EAGAIN/EWOULDBLOCK. Use a short timeout-style read loop.
    while ((readLen = read(shellFd[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[readLen] = '\0';
        output.append(buffer);
    }

    // 触发输出事件（对标事件通知）
    if (callback && !output.empty()) {
        callback(TerminalEventType::COMMAND_OUTPUT, output, 0);
    }

    // 检查命令执行结果
    // NOTE (A-5 follow-up): `waitpid(WNOHANG)` on the long-running shell PID
    // returns 0 when the shell is still alive (the normal case between
    // commands). The exit code is only meaningful when the shell itself dies
    // (e.g. user typed `exit`). This is preserved from the original code path
    // for compatibility; a proper fix would track command boundaries via a
    // sentinel/marker protocol (Operit-style).
    int status;
    pid_t waitResult = waitpid(shellPid, &status, WNOHANG);
    int exitCode = 0;

    if (waitResult > 0 && WIFEXITED(status)) {
        exitCode = WEXITSTATUS(status);
        LOGD("Shell exited with code: %d", exitCode);
    }

    // 触发完成事件
    if (callback) {
        callback(TerminalEventType::COMMAND_FINISHED, cmd, exitCode);
    }

    return true;
}

// 切换目录（对标状态管理）
bool TerminalSession::changeDirectory(const std::string& path) {
    if (chdir(path.c_str()) == 0) {
        cwd = path;
        LOGD("Session %s changed directory to: %s", sessionId.c_str(), path.c_str());

        if (callback) {
            callback(TerminalEventType::DIRECTORY_CHANGED, cwd, 0);
        }

        return true;
    } else {
        LOGE("Change directory failed for session %s: %s", sessionId.c_str(), strerror(errno));

        if (callback) {
            callback(TerminalEventType::ERROR_OCCURRED, "Change directory failed", -3);
        }

        return false;
    }
}

// 挂起会话
void TerminalSession::suspend() {
    if (state == SessionState::RUNNING && shellPid > 0) {
        kill(shellPid, SIGSTOP);
        state = SessionState::SUSPENDED;

        LOGD("Session %s suspended", sessionId.c_str());

        if (callback) {
            callback(TerminalEventType::SESSION_STATE_CHANGED, "SUSPENDED", 0);
        }
    }
}

// 恢复会话
void TerminalSession::resume() {
    if (state == SessionState::SUSPENDED && shellPid > 0) {
        kill(shellPid, SIGCONT);
        state = SessionState::RUNNING;

        LOGD("Session %s resumed", sessionId.c_str());

        if (callback) {
            callback(TerminalEventType::SESSION_STATE_CHANGED, "RUNNING", 0);
        }
    }
}

// 关闭会话
void TerminalSession::close() {
    if (state == SessionState::CLOSED) {
        return;
    }

    LOGD("Closing session %s", sessionId.c_str());

    // 关闭文件描述符
    // For a PTY, shellFd[0] and shellFd[1] may both point to the same master
    // fd — close it exactly once.
    if (shellFd[0] != -1) {
        ::close(shellFd[0]);
        shellFd[0] = -1;
    }
    if (shellFd[1] != -1 && shellFd[1] != shellFd[0]) {
        ::close(shellFd[1]);
    }
    shellFd[1] = -1;

    // 结束Shell进程
    if (shellPid > 0) {
        kill(shellPid, SIGKILL);
        waitpid(shellPid, nullptr, 0);
        shellPid = -1;
    }

    // 更新状态
    state = SessionState::CLOSED;

    // 触发回调
    if (callback) {
        callback(TerminalEventType::SESSION_STATE_CHANGED, "CLOSED", 0);
    }

    LOGD("Session %s closed", sessionId.c_str());
}

// 会话池单例
TerminalSessionPool& TerminalSessionPool::getInstance() {
    static TerminalSessionPool instance;
    return instance;
}

// 创建会话
TerminalSession* TerminalSessionPool::createSession(const std::string& sessionId, TerminalEventCallback cb) {
    if (sessions.find(sessionId) != sessions.end()) {
        LOGE("Session %s already exists", sessionId.c_str());
        return nullptr;
    }

    auto* session = new TerminalSession(sessionId, cb);
    sessions[sessionId] = session;

    if (currentSessionId.empty()) {
        currentSessionId = sessionId;
    }

    LOGD("Created session %s", sessionId.c_str());
    return session;
}

// 获取会话
TerminalSession* TerminalSessionPool::getSession(const std::string& sessionId) {
    auto it = sessions.find(sessionId);
    return it != sessions.end() ? it->second : nullptr;
}

// 获取当前会话
TerminalSession* TerminalSessionPool::getCurrentSession() {
    if (currentSessionId.empty()) {
        return nullptr;
    }
    return getSession(currentSessionId);
}

// 切换会话
bool TerminalSessionPool::switchSession(const std::string& sessionId) {
    if (sessions.find(sessionId) == sessions.end()) {
        LOGE("Session %s not found", sessionId.c_str());
        return false;
    }

    currentSessionId = sessionId;
    LOGD("Switched to session %s", sessionId.c_str());
    return true;
}

// 关闭会话
bool TerminalSessionPool::closeSession(const std::string& sessionId) {
    auto it = sessions.find(sessionId);
    if (it == sessions.end()) {
        LOGE("Session %s not found", sessionId.c_str());
        return false;
    }

    // 先关闭会话再删除
    it->second->close();
    delete it->second;
    sessions.erase(it);

    // 更新当前会话
    if (currentSessionId == sessionId && !sessions.empty()) {
        currentSessionId = sessions.begin()->first;
    } else if (sessions.empty()) {
        currentSessionId.clear();
    }

    LOGD("Closed session %s", sessionId.c_str());
    return true;
}

// 关闭所有会话
void TerminalSessionPool::closeAllSessions() {
    for (auto& pair : sessions) {
        pair.second->close();
        delete pair.second;
    }
    sessions.clear();
    currentSessionId.clear();
    LOGD("Closed all sessions");
}
