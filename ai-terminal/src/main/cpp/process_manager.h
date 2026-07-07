#ifndef PROCESS_MANAGER_H
#define PROCESS_MANAGER_H

#include <unordered_map>
#include <vector>
#include <functional>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>

// 进程状态
enum class ProcessState {
    RUNNING,
    STOPPED,
    ZOMBIE,
    TERMINATED
};

// 进程信息
struct ProcessInfo {
    pid_t pid;
    pid_t pgid;
    std::string command;
    std::string sessionId;
    ProcessState state;
    std::chrono::steady_clock::time_point startTime;
    std::chrono::steady_clock::time_point endTime;
    int exitCode;
    long long cpuTimeUs;
    long long memoryBytes;
};

// 进程事件回调
using ProcessEventCallback = std::function<void(ProcessInfo)>;

// 进程管理器（单例）
class ProcessManager {
private:
    std::unordered_map<pid_t, ProcessInfo> processes;
    std::unordered_map<pid_t, std::vector<pid_t>> processGroups;
    std::vector<ProcessEventCallback> callbacks;
    std::mutex mtx;
    std::atomic<bool> running;
    std::thread cleanupThread;

    ProcessManager();
    ~ProcessManager();

    void cleanupLoop();

public:
    static ProcessManager& getInstance();

    // 进程管理
    pid_t addProcess(const std::string& command, const std::string& sessionId);
    bool updateProcessState(pid_t pid, ProcessState state, int exitCode = -1);
    bool killProcess(pid_t pid, int signal = SIGKILL);
    bool killProcessGroup(pid_t pgid, int signal = SIGKILL);
    bool suspendProcess(pid_t pid);
    bool resumeProcess(pid_t pid);
    ProcessInfo* getProcess(pid_t pid);
    std::vector<ProcessInfo> getProcessesBySession(const std::string& sessionId);
    std::vector<ProcessInfo> getAllProcesses();
    std::vector<pid_t> getZombieProcesses();
    bool cleanupZombies();

    // 回调
    void addCallback(ProcessEventCallback cb);
    void removeCallback(ProcessEventCallback cb);
    void notifyProcessEvent(ProcessInfo info);

    // 资源监控
    long long getProcessMemory(pid_t pid);
    long long getProcessCpuTime(pid_t pid);
    double getProcessCpuPercent(pid_t pid);

    // 清理
    void cleanupSessionProcesses(const std::string& sessionId);
    void cleanupAll();
};

#endif // PROCESS_MANAGER_H
