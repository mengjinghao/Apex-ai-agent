#include "terminal_session.h"
#include <android/log.h>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <pwd.h>
#include <limits.h>
#include <unistd.h>
#include <errno.h>
#include <sys/wait.h>
#include <signal.h>
#include <cstdlib>

#define LOG_TAG "TerminalSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 辅助函数：获取当前工作目�?
static std::string get_current_dir() {
    char buffer[PATH_MAX];
    if (getcwd(buffer, sizeof(buffer)) != nullptr) {
        return std::string(buffer);
    }
    // 获取用户主目录作为备�?
    struct passwd* pw = getpwuid(getuid());
    if (pw != nullptr && pw->pw_dir != nullptr) {
        return std::string(pw->pw_dir);
    }
    // 最后备�?
    return "/data/data/com.ai.assistance.operit/files";
}

// 会话构�?
TerminalSession::TerminalSession(std::string id, TerminalEventCallback cb)
    : sessionId(std::move(id)), 
      shellPid(-1), 
      state(SessionState::CREATED), 
      callback(std::move(cb)) {
    
    // 初始化管�?
    if (pipe(shellFd) == -1) {
        LOGE("Failed to create pipe for session %s: %s", sessionId.c_str(), strerror(errno));
    }
    
    // 设置读端为非阻塞
    fcntl(shellFd[0], F_SETFL, O_NONBLOCK);
    
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

// 启动会话（支持多Shell�?
bool TerminalSession::start(const std::string& shellType) {
    if (state != SessionState::CREATED) {
        LOGE("Session %s is not in CREATED state", sessionId.c_str());
        return false;
    }
    
    if (shellFd[0] == -1 || shellFd[1] == -1) {
        LOGE("Session %s pipe not initialized", sessionId.c_str());
        return false;
    }
    
    pid_t pid = fork();
    if (pid == 0) { // 子进程：启动Shell
        LOGD("Child process forked for session %s", sessionId.c_str());
        
        // 关闭不需要的管道�?
        ::close(shellFd[0]);
        
        // 重定向标准输入输出错�?
        if (shellFd[1] != STDIN_FILENO) {
            dup2(shellFd[1], STDIN_FILENO);
            dup2(shellFd[1], STDOUT_FILENO);
            dup2(shellFd[1], STDERR_FILENO);
            ::close(shellFd[1]);
        }
        
        // 设置工作目录
        if (chdir(cwd.c_str()) != 0) {
            LOGE("Failed to chdir to %s: %s", cwd.c_str(), strerror(errno));
        }
        
        // 设置环境变量
        for (auto& envPair : env) {
            setenv(envPair.first.c_str(), envPair.second.c_str(), 1);
        }
        
        // 启动指定Shell
        LOGI("Executing shell: %s", shellType.c_str());
        execlp(shellType.c_str(), shellType.c_str(), nullptr);
        
        // 如果执行失败
        LOGE("Failed to execute shell %s: %s", shellType.c_str(), strerror(errno));
        exit(EXIT_FAILURE);
        
    } else if (pid > 0) { // 父进程：记录PID
        // 关闭写端（子进程使用�?
        ::close(shellFd[1]);
        shellFd[1] = -1;
        
        shellPid = pid;
        state = SessionState::RUNNING;
        
        LOGD("Session %s started with Shell PID: %d", sessionId.c_str(), pid);
        
        // 触发回调
        if (callback) {
            callback(TerminalEventType::SESSION_STATE_CHANGED, "RUNNING", 0);
        }
        
        return true;
        
    } else {
        LOGE("Fork failed for session %s: %s", sessionId.c_str(), strerror(errno));
        return false;
    }
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
        LOGE("Session %s shell pipe not available", sessionId.c_str());
        return false;
    }
    
    // 记录命令历史
    commandHistory.push_back(cmd);
    LOGD("Session %s executing command: %s", sessionId.c_str(), cmd.c_str());
    
    // 写入命令到Shell管道
    std::string cmdWithNewline = cmd + "\n";
    ssize_t written = write(shellFd[1], cmdWithNewline.c_str(), cmdWithNewline.length());
    if (written < 0) {
        LOGE("Write command failed for session %s: %s", sessionId.c_str(), strerror(errno));
        if (callback) {
            callback(TerminalEventType::ERROR_OCCURRED, "Write command failed", -2);
        }
        return false;
    }
    
    // 异步读取输出（简化版，实际可封装线程�?
    char buffer[4096];
    ssize_t readLen;
    std::string output;
    
    while ((readLen = read(shellFd[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[readLen] = '\0';
        output.append(buffer);
    }
    
    // 触发输出事件（对标事件通知�?
    if (callback && !output.empty()) {
        callback(TerminalEventType::COMMAND_OUTPUT, output, 0);
    }
    
    // 检查命令执行结�?
    int status;
    pid_t waitResult = waitpid(shellPid, &status, WNOHANG);
    int exitCode = 0;
    
    if (waitResult > 0 && WIFEXITED(status)) {
        exitCode = WEXITSTATUS(status);
        LOGD("Command finished with exit code: %d", exitCode);
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
    
    // 关闭文件描述�?
    if (shellFd[0] != -1) {
        ::close(shellFd[0]);
        shellFd[0] = -1;
    }
    if (shellFd[1] != -1) {
        ::close(shellFd[1]);
        shellFd[1] = -1;
    }
    
    // 结束Shell进程
    if (shellPid > 0) {
        kill(shellPid, SIGKILL);
        waitpid(shellPid, nullptr, 0);
        shellPid = -1;
    }
    
    // 更新状�?
    state = SessionState::CLOSED;
    
    // 触发回调
    if (callback) {
        callback(TerminalEventType::SESSION_STATE_CHANGED, "CLOSED", 0);
    }
    
    LOGD("Session %s closed", sessionId.c_str());
}

// 会话池单�?
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

// 关闭所有会�?
void TerminalSessionPool::closeAllSessions() {
    for (auto& pair : sessions) {
        pair.second->close();
        delete pair.second;
    }
    sessions.clear();
    currentSessionId.clear();
    LOGD("Closed all sessions");
}

