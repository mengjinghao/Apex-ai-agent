#include "process_manager.h"
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <cstring>

#define LOG_TAG "ProcessManager"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

bool ProcessManager::killProcess(pid_t pid, int signal) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = processes.find(pid);
    if (it == processes.end()) {
        return false;
    }
    
    int result = ::kill(pid, signal);
    if (result == 0) {
        it->second.state = ProcessState::TERMINATED;
        it->second.endTime = std::chrono::steady_clock::now();
        notifyProcessEvent(it->second);
        LOGI("Killed process: PID=%d with signal=%d", pid, signal);
    }
    return result == 0;
}

bool ProcessManager::killProcessGroup(pid_t pgid, int signal) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = processGroups.find(pgid);
    if (it == processGroups.end()) {
        return false;
    }
    
    for (pid_t pid : it->second) {
        ::kill(pid, signal);
    }
    
    int result = ::kill(-pgid, signal);
    LOGI("Killed process group: PGID=%d with signal=%d", pgid, signal);
    return result == 0;
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

std::vector<ProcessInfo> ProcessManager::getProcessesBySession(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    std::vector<ProcessInfo> result;
    for (auto& pair : processes) {
        if (pair.second.sessionId == sessionId) {
            result.push_back(pair.second);
        }
    }
    return result;
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
    std::lock_guard<std::mutex> lock(mtx);
    auto sessionProcesses = getProcessesBySession(sessionId);
    for (auto& info : sessionProcesses) {
        killProcess(info.pid, SIGKILL);
    }
    LOGI("Cleaned up processes for session: %s", sessionId.c_str());
}

void ProcessManager::cleanupAll() {
    std::lock_guard<std::mutex> lock(mtx);
    for (auto& pair : processes) {
        ::kill(pair.first, SIGKILL);
    }
    processes.clear();
    processGroups.clear();
    LOGI("Cleaned up all processes");
}
