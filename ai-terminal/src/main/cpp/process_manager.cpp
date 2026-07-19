#include "process_manager.h"
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <cstring>

#define LOG_TAG "ProcessManager"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

ProcessManager::ProcessManager() : running(true) {
    cleanupThread = std::thread(&ProcessManager::cleanupLoop, this);
    LOGI("ProcessManager initialized");
}

ProcessManager::~ProcessManager() {
    running = false;
    if (cleanupThread.joinable()) {
        cleanupThread.join();
    }
    cleanupAll();
    LOGI("ProcessManager destroyed");
}

ProcessManager& ProcessManager::getInstance() {
    static ProcessManager instance;
    return instance;
}

void ProcessManager::cleanupLoop() {
    while (running) {
        std::this_thread::sleep_for(std::chrono::seconds(30));
        cleanupZombies();
    }
}

pid_t ProcessManager::addProcess(const std::string& command, const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    
    ProcessInfo info;
    info.pid = 0;
    info.pgid = 0;
    info.command = command;
    info.sessionId = sessionId;
    info.state = ProcessState::RUNNING;
    info.startTime = std::chrono::steady_clock::now();
    info.exitCode = -1;
    info.cpuTimeUs = 0;
    info.memoryBytes = 0;
    
    // 这里应该是实际创建进程的逻辑
    // 为了简化，我们返回一个模拟的PID
    static pid_t nextPid = 10000;
    info.pid = ++nextPid;
    info.pgid = info.pid;
    
    processes[info.pid] = info;
    processGroups[info.pgid].push_back(info.pid);
    
    notifyProcessEvent(info);
    LOGD("Process added: PID=%d, command=%s", info.pid, command.c_str());
    return info.pid;
}

bool ProcessManager::updateProcessState(pid_t pid, ProcessState state, int exitCode) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = processes.find(pid);
    if (it == processes.end()) {
        return false;
    }
    
    it->second.state = state;
    if (exitCode != -1) {
        it->second.exitCode = exitCode;
    }
    if (state == ProcessState::TERMINATED || state == ProcessState::ZOMBIE) {
        it->second.endTime = std::chrono::steady_clock::now();
    }
    
    notifyProcessEvent(it->second);
    LOGD("Process state updated: PID=%d, state=%d", pid, (int)state);
    return true;
}

// A-11: internal helper — caller MUST already hold `mtx`.
// Used by killProcess() and cleanupSessionProcesses() to avoid re-entrant
// deadlock (cleanupSessionProcesses holds `mtx` and used to call killProcess
// which tried to lock `mtx` again — std::mutex is non-recursive → deadlock).
bool ProcessManager::_killProcessUnlocked(pid_t pid, int signal) {
    auto it = processes.find(pid);
    if (it == processes.end()) {
        return false;
    }

    // Security (A-1): ProcessManager tracks LOGICAL tasks, not OS processes. The PIDs
    // returned by addProcess() are fake (starting at 10001) and do NOT correspond to
    // real child processes. Calling ::kill() on these fake PIDs can kill ARBITRARY
    // system processes that happen to have that PID (e.g. system_server, zygote),
    // which is a privilege-escalation / DoS vulnerability. Do NOT call ::kill here.
    LOGW("killProcess(%d) ignored — ProcessManager tracks logical tasks, not OS PIDs; killing arbitrary PIDs is a security risk", pid);
    return false;
}

bool ProcessManager::killProcess(pid_t pid, int signal) {
    std::lock_guard<std::mutex> lock(mtx);
    return _killProcessUnlocked(pid, signal);
}

bool ProcessManager::killProcessGroup(pid_t pgid, int signal) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = processGroups.find(pgid);
    if (it == processGroups.end()) {
        return false;
    }

    // Security (A-1): same vulnerability as killProcess — the PIDs in processGroups are
    // fake logical IDs, not real OS PIDs. Calling ::kill() on them (or on -pgid) can
    // kill arbitrary system processes. Do NOT call ::kill here.
    LOGW("killProcessGroup(%d) ignored — ProcessManager tracks logical tasks, not OS PIDs; killing arbitrary PIDs is a security risk", pgid);
    return false;
}

bool ProcessManager::suspendProcess(pid_t pid) {
    return killProcess(pid, SIGSTOP);
}

bool ProcessManager::resumeProcess(pid_t pid) {
    return killProcess(pid, SIGCONT);
}

ProcessInfo* ProcessManager::getProcess(pid_t pid) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = processes.find(pid);
    return it != processes.end() ? &it->second : nullptr;
}

// A-11: internal helper — caller MUST already hold `mtx`.
std::vector<ProcessInfo> ProcessManager::_getProcessesBySessionUnlocked(const std::string& sessionId) {
    std::vector<ProcessInfo> result;
    for (auto& pair : processes) {
        if (pair.second.sessionId == sessionId) {
            result.push_back(pair.second);
        }
    }
    return result;
}

std::vector<ProcessInfo> ProcessManager::getProcessesBySession(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    return _getProcessesBySessionUnlocked(sessionId);
}

std::vector<ProcessInfo> ProcessManager::getAllProcesses() {
    std::lock_guard<std::mutex> lock(mtx);
    std::vector<ProcessInfo> result;
    for (auto& pair : processes) {
        result.push_back(pair.second);
    }
    return result;
}

std::vector<pid_t> ProcessManager::getZombieProcesses() {
    std::lock_guard<std::mutex> lock(mtx);
    std::vector<pid_t> zombies;
    for (auto& pair : processes) {
        if (pair.second.state == ProcessState::ZOMBIE) {
            zombies.push_back(pair.first);
        }
    }
    return zombies;
}

bool ProcessManager::cleanupZombies() {
    std::lock_guard<std::mutex> lock(mtx);
    int cleaned = 0;
    
    auto it = processes.begin();
    while (it != processes.end()) {
        if (it->second.state == ProcessState::ZOMBIE) {
            pid_t pid = it->first;
            pid_t result = waitpid(pid, nullptr, WNOHANG);
            if (result > 0 || result == -1) {
                // 移除进程组
                auto pgIt = processGroups.find(it->second.pgid);
                if (pgIt != processGroups.end()) {
                    auto& group = pgIt->second;
                    group.erase(std::remove(group.begin(), group.end(), pid), group.end());
                    if (group.empty()) {
                        processGroups.erase(pgIt);
                    }
                }
                it = processes.erase(it);
                cleaned++;
                LOGD("Cleaned up zombie process: PID=%d", pid);
            } else {
                ++it;
            }
        } else {
            ++it;
        }
    }
    
    if (cleaned > 0) {
        LOGI("Cleaned up %d zombie processes", cleaned);
    }
    return cleaned > 0;
}

void ProcessManager::addCallback(ProcessEventCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    callbacks.push_back(cb);
}

void ProcessManager::removeCallback(ProcessEventCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    callbacks.erase(std::remove_if(callbacks.begin(), callbacks.end(),
        [&cb](const ProcessEventCallback& existing) {
            return false; // 简单实现，实际可比较函数地址
        }), callbacks.end());
}

void ProcessManager::notifyProcessEvent(ProcessInfo info) {
    for (auto& cb : callbacks) {
        try {
            cb(info);
        } catch (...) {
            LOGE("Callback exception");
        }
    }
}

long long ProcessManager::getProcessMemory(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/status";
    std::ifstream file(path);
    if (!file) {
        return 0;
    }
    
    std::string line;
    while (std::getline(file, line)) {
        if (line.find("VmRSS:") == 0) {
            std::istringstream iss(line);
            std::string key;
            long long kb;
            std::string unit;
            if (iss >> key >> kb >> unit) {
                return kb * 1024;
            }
        }
    }
    return 0;
}

long long ProcessManager::getProcessCpuTime(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream file(path);
    if (!file) {
        return 0;
    }
    
    std::string line;
    if (std::getline(file, line)) {
        std::istringstream iss(line);
        std::vector<std::string> parts;
        std::string part;
        while (iss >> part) {
            parts.push_back(part);
        }
        if (parts.size() > 15) {
            // utime + stime
            long long utime = std::stoll(parts[13]);
            long long stime = std::stoll(parts[14]);
            // 转换为微秒（假设HZ=100）
            return (utime + stime) * 10000;
        }
    }
    return 0;
}

double ProcessManager::getProcessCpuPercent(pid_t pid) {
    long long cpuTime = getProcessCpuTime(pid);
    auto info = getProcess(pid);
    if (!info) return 0.0;
    
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
        now - info->startTime).count();
    
    if (elapsed > 0) {
        return (cpuTime * 100.0) / elapsed;
    }
    return 0.0;
}

void ProcessManager::cleanupSessionProcesses(const std::string& sessionId) {
    // A-11: previously this method held `mtx` and then called
    // getProcessesBySession + killProcess — both of which try to lock `mtx`
    // again. std::mutex is non-recursive, so this was a guaranteed deadlock
    // on every call. Use the *Unlocked helpers instead.
    std::lock_guard<std::mutex> lock(mtx);
    auto sessionProcesses = _getProcessesBySessionUnlocked(sessionId);
    for (auto& info : sessionProcesses) {
        _killProcessUnlocked(info.pid, SIGKILL);
    }
    LOGI("Cleaned up processes for session: %s", sessionId.c_str());
}

void ProcessManager::cleanupAll() {
    std::lock_guard<std::mutex> lock(mtx);
    // Security (A-1): do NOT call ::kill() on the fake logical PIDs in `processes` —
    // they do not correspond to real child processes and killing them could kill
    // arbitrary system processes. Just clear the in-memory tracking maps.
    LOGW("cleanupAll() — skipping ::kill for %zu fake logical PIDs (security: A-1)", processes.size());
    processes.clear();
    processGroups.clear();
    LOGI("Cleaned up all processes");
}
