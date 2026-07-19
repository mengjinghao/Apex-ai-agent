#ifndef TERMINAL_SESSION_H
#define TERMINAL_SESSION_H

#include <jni.h>
#include <string>
#include <unordered_map>
#include <functional>
#include <unistd.h>
#include <sys/wait.h>
#include <vector>
#include <signal.h>
#include <mutex>  // A-7: thread-safety for TerminalSessionPool

// 会话状态枚举
enum class SessionState {
    CREATED,   // 已创建
    RUNNING,   // 运行中
    SUSPENDED, // 挂起
    CLOSED     // 已关闭
};

// 终端事件类型（对标事件通知能力）
enum class TerminalEventType {
    COMMAND_OUTPUT,  // 命令输出
    DIRECTORY_CHANGED,// 目录变化
    SESSION_STATE_CHANGED, // 会话状态变化
    COMMAND_FINISHED, // 命令执行完成
    ERROR_OCCURRED   // 错误发生
};

// 终端事件回调
typedef std::function<void(TerminalEventType type, const std::string& data, int code)> TerminalEventCallback;

// 会话结构体（对标多会话管理）
struct TerminalSession {
    std::string sessionId;       // 会话ID
    pid_t shellPid;              // Shell进程PID
    int shellFd[2];              // Shell通信管道（stdin/stdout）
    SessionState state;          // 会话状态
    std::string cwd;             // 当前工作目录
    std::unordered_map<std::string, std::string> env; // 环境变量
    std::vector<std::string> commandHistory; // 命令历史（对标状态管理）
    TerminalEventCallback callback; // 事件回调

    // 构造/析构
    TerminalSession(std::string id, TerminalEventCallback cb);
    ~TerminalSession();

    // 会话操作
    bool start(const std::string& shellType = "sh"); // 启动会话（支持多Shell）
    bool executeCommand(const std::string& cmd);     // 执行命令
    bool changeDirectory(const std::string& path);   // 切换目录
    void suspend();                                  // 挂起会话
    void resume();                                   // 恢复会话
    void close();                                    // 关闭会话
};

// 会话池管理（单例）
//
// Security (A-7): all public methods acquire `m_mutex` to serialize
// concurrent JNI calls from multi-threaded Kotlin (e.g. coroutine
// dispatchers). To avoid deadlock, public methods MUST NOT call other
// public methods while holding the lock — they call private
// `*Unlocked` helpers instead.
class TerminalSessionPool {
private:
    std::unordered_map<std::string, TerminalSession*> sessions;
    std::string currentSessionId; // 当前激活会话
    std::mutex m_mutex;           // A-7: serializes all public method access

    TerminalSessionPool() = default;

    // A-7: internal helpers that assume the caller already holds `m_mutex`.
    // Used to avoid deadlock when a public method needs to invoke logic
    // shared with another public method.
    TerminalSession* getSessionUnlocked(const std::string& sessionId);
    bool closeSessionUnlocked(const std::string& sessionId);
public:
    static TerminalSessionPool& getInstance();

    // 会话管理 (all acquire m_mutex internally)
    TerminalSession* createSession(const std::string& sessionId, TerminalEventCallback cb);
    TerminalSession* getSession(const std::string& sessionId);
    TerminalSession* getCurrentSession();
    bool switchSession(const std::string& sessionId);
    bool closeSession(const std::string& sessionId);
    void closeAllSessions();
};

#endif // TERMINAL_SESSION_H
