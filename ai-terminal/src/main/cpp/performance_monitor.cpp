#include "performance_monitor.h"
#include <fstream>
#include <sstream>
#include <sys/sysinfo.h>
#include <sys/resource.h>
#include <dirent.h>
#include <cstring>

#include <unistd.h>

PerformanceMonitor::PerformanceMonitor() : running(true) {
    monitorThread = std::thread(&PerformanceMonitor::monitorLoop, this);
    LOGI("PerformanceMonitor initialized");
}

PerformanceMonitor::~PerformanceMonitor() {
    running = false;
    if (monitorThread.joinable()) {
        monitorThread.join();
    }
    LOGI("PerformanceMonitor destroyed");
}

PerformanceMonitor& PerformanceMonitor::getInstance() {
    static PerformanceMonitor instance;
    return instance;
}

void PerformanceMonitor::monitorLoop() {
    while (running) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        updateSystemResources();
    }
}

void PerformanceMonitor::updateSystemResources() {
    // 更新当前执行命令的指标
    std::lock_guard<std::mutex> lock(mtx);
    for (auto& pair : currentMetrics) {
        // 这里应该更新实际的指标值
        // 简化实现
    }
}

void PerformanceMonitor::startCommand(const std::string& sessionId, const std::string& command) {
    std::lock_guard<std::mutex> lock(mtx);
    
    PerformanceMetrics metrics;
    metrics.sessionId = sessionId;
    metrics.command = command;
    metrics.startTime = std::chrono::steady_clock::now();
    metrics.cpuTimeUs = 0;
    metrics.memoryBytes = 0;
    metrics.threadCount = 0;
    metrics.outputSize = 0;
    metrics.exitCode = -1;
    
    currentMetrics[sessionId] = metrics;
    
    // 初始化或更新会话统计
    if (sessionStats.find(sessionId) == sessionStats.end()) {
        SessionPerformance stats;
        stats.sessionId = sessionId;
        stats.startTime = std::chrono::steady_clock::now();
        stats.totalCommands = 0;
        stats.totalDurationUs = 0;
        stats.totalCpuTimeUs = 0;
        stats.peakMemoryBytes = 0;
        stats.peakThreadCount = 0;
        stats.errorCount = 0;
        sessionStats[sessionId] = stats;
    }
    
    LOGD("Started command: %s in session: %s", command.c_str(), sessionId.c_str());
}

void PerformanceMonitor::endCommand(const std::string& sessionId, int exitCode, long long outputSize) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = currentMetrics.find(sessionId);
    if (it == currentMetrics.end()) {
        return;
    }
    
    auto& metrics = it->second;
    metrics.endTime = std::chrono::steady_clock::now();
    metrics.exitCode = exitCode;
    metrics.outputSize = outputSize;
    
    // 更新会话统计
    auto& stats = sessionStats[sessionId];
    auto durationUs = std::chrono::duration_cast<std::chrono::microseconds>(
        metrics.endTime - metrics.startTime).count();
    stats.totalCommands++;
    stats.totalDurationUs += durationUs;
    stats.totalCpuTimeUs += metrics.cpuTimeUs;
    stats.peakMemoryBytes = std::max(stats.peakMemoryBytes, metrics.memoryBytes);
    stats.peakThreadCount = std::max(stats.peakThreadCount, (long long)metrics.threadCount);
    if (exitCode != 0) {
        stats.errorCount++;
    }
    
    // 保存到历史
    history[sessionId].push_back(metrics);
    if (history[sessionId].size() > MAX_HISTORY_SIZE) {
        history[sessionId].erase(history[sessionId].begin());
    }
    
    // 通知回调
    for (auto& cb : eventCallbacks) {
        try {
            cb(metrics);
        } catch (...) {
            LOGE("Event callback exception");
        }
    }
    for (auto& cb : sessionCallbacks) {
        try {
            cb(stats);
        } catch (...) {
            LOGE("Session callback exception");
        }
    }
    
    currentMetrics.erase(it);
    LOGD("Ended command: %s in session: %s, exit code: %d", 
         metrics.command.c_str(), sessionId.c_str(), exitCode);
}

void PerformanceMonitor::updateCommandMetrics(const std::string& sessionId, 
    long long cpuTimeUs, long long memoryBytes, int threadCount) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = currentMetrics.find(sessionId);
    if (it == currentMetrics.end()) {
        return;
    }
    
    it->second.cpuTimeUs = cpuTimeUs;
    it->second.memoryBytes = memoryBytes;
    it->second.threadCount = threadCount;
}

PerformanceMetrics* PerformanceMonitor::getCurrentMetrics(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = currentMetrics.find(sessionId);
    return it != currentMetrics.end() ? &it->second : nullptr;
}

SessionPerformance* PerformanceMonitor::getSessionStats(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = sessionStats.find(sessionId);
    return it != sessionStats.end() ? &it->second : nullptr;
}

std::vector<PerformanceMetrics> PerformanceMonitor::getCommandHistory(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = history.find(sessionId);
    return it != history.end() ? it->second : std::vector<PerformanceMetrics>();
}

std::vector<SessionPerformance> PerformanceMonitor::getAllSessionStats() {
    std::lock_guard<std::mutex> lock(mtx);
    std::vector<SessionPerformance> result;
    for (auto& pair : sessionStats) {
        result.push_back(pair.second);
    }
    return result;
}

long long PerformanceMonitor::getSystemMemoryUsed() {
    struct sysinfo si;
    if (sysinfo(&si) == 0) {
        return (si.totalram - si.freeram - si.bufferram - si.sharedram) * si.mem_unit;
    }
    return 0;
}

long long PerformanceMonitor::getSystemMemoryAvailable() {
    struct sysinfo si;
    if (sysinfo(&si) == 0) {
        return (si.freeram + si.bufferram) * si.mem_unit;
    }
    return 0;
}

int PerformanceMonitor::getSystemCpuCount() {
    return (int)sysconf(_SC_NPROCESSORS_ONLN);
}

double PerformanceMonitor::getSystemCpuUsage() {
    static long long prevIdle = 0;
    static long long prevTotal = 0;
    
    std::ifstream file("/proc/stat");
    if (!file) {
        return 0.0;
    }
    
    std::string line;
    if (std::getline(file, line)) {
        std::istringstream iss(line);
        std::string cpu;
        long long user, nice, system, idle, iowait, irq, softirq;
        if (iss >> cpu >> user >> nice >> system >> idle >> iowait >> irq >> softirq) {
            long long total = user + nice + system + idle + iowait + irq + softirq;
            long long idleTime = idle + iowait;
            
            double usage = 0.0;
            if (prevTotal > 0 && total > prevTotal) {
                long long totalDiff = total - prevTotal;
                long long idleDiff = idleTime - prevIdle;
                usage = 100.0 * (totalDiff - idleDiff) / totalDiff;
            }
            
            prevIdle = idleTime;
            prevTotal = total;
            return usage;
        }
    }
    return 0.0;
}

long long PerformanceMonitor::getAppMemoryUsed() {
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) == 0) {
        return usage.ru_maxrss * 1024;
    }
    return 0;
}

int PerformanceMonitor::getAppThreadCount() {
    DIR* dir = opendir("/proc/self/task");
    if (!dir) {
        return 0;
    }
    
    int count = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type == DT_DIR && entry->d_name[0] != '.') {
            count++;
        }
    }
    closedir(dir);
    return count;
}

void PerformanceMonitor::addPerformanceCallback(PerformanceEventCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    eventCallbacks.push_back(cb);
}

void PerformanceMonitor::addSessionCallback(SessionPerformanceCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    sessionCallbacks.push_back(cb);
}

void PerformanceMonitor::removePerformanceCallback(PerformanceEventCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    eventCallbacks.erase(std::remove_if(eventCallbacks.begin(), eventCallbacks.end(),
        [&cb](const PerformanceEventCallback& existing) { return false; }), eventCallbacks.end());
}

void PerformanceMonitor::removeSessionCallback(SessionPerformanceCallback cb) {
    std::lock_guard<std::mutex> lock(mtx);
    sessionCallbacks.erase(std::remove_if(sessionCallbacks.begin(), sessionCallbacks.end(),
        [&cb](const SessionPerformanceCallback& existing) { return false; }), sessionCallbacks.end());
}

void PerformanceMonitor::clearSessionData(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mtx);
    currentMetrics.erase(sessionId);
    sessionStats.erase(sessionId);
    history.erase(sessionId);
    LOGD("Cleared performance data for session: %s", sessionId.c_str());
}

void PerformanceMonitor::clearAll() {
    std::lock_guard<std::mutex> lock(mtx);
    currentMetrics.clear();
    sessionStats.clear();
    history.clear();
    LOGI("Cleared all performance data");
}
