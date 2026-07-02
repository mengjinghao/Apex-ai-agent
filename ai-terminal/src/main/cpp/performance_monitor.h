#ifndef PERFORMANCE_MONITOR_H
#define PERFORMANCE_MONITOR_H

#include <unordered_map>
#include <vector>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <functional>
#include <android/log.h>

#define LOG_TAG "PerfMonitor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 性能指标
struct PerformanceMetrics {
    std::string sessionId;
    std::string command;
    std::chrono::steady_clock::time_point startTime;
    std::chrono::steady_clock::time_point endTime;
    long long cpuTimeUs;
    long long memoryBytes;
    int threadCount;
    long long outputSize;
    int exitCode;
    
    double getDurationMs() const {
        auto end = endTime.time_since_epoch().count();
        auto start = startTime.time_since_epoch().count();
        return (end - start) / 1000000.0;
    }
    
    double getCpuPercent() const {
        if (getDurationMs() > 0) {
            return (cpuTimeUs / 1000.0) / getDurationMs() * 100.0;
        }
        return 0.0;
    }
};

// 会话性能统计
struct SessionPerformance {
    std::string sessionId;
    std::chrono::steady_clock::time_point startTime;
    long long totalCommands;
    long long totalDurationUs;
    long long totalCpuTimeUs;
    long long peakMemoryBytes;
    long long peakThreadCount;
    long long errorCount;
    
    double getAvgDurationMs() const {
        if (totalCommands > 0) {
            return (totalDurationUs / 1000.0) / totalCommands;
        }
        return 0.0;
    }
};

// 性能事件回调
using PerformanceEventCallback = std::function<void(const PerformanceMetrics&)>;
using SessionPerformanceCallback = std::function<void(const SessionPerformance&)>;

// 性能监控器（单例）
class PerformanceMonitor {
private:
    std::unordered_map<std::string, PerformanceMetrics> currentMetrics;
    std::unordered_map<std::string, SessionPerformance> sessionStats;
    std::unordered_map<std::string, std::vector<PerformanceMetrics>> history;
    std::vector<PerformanceEventCallback> eventCallbacks;
    std::vector<SessionPerformanceCallback> sessionCallbacks;
    std::mutex mtx;
    std::atomic<bool> running;
    std::thread monitorThread;
    const size_t MAX_HISTORY_SIZE = 1000;

    PerformanceMonitor();
    ~PerformanceMonitor();

    void monitorLoop();
    void updateSystemResources();

public:
    static PerformanceMonitor& getInstance();

    // 命令追踪
    void startCommand(const std::string& sessionId, const std::string& command);
    void endCommand(const std::string& sessionId, int exitCode, long long outputSize);
    void updateCommandMetrics(const std::string& sessionId, long long cpuTimeUs, long long memoryBytes, int threadCount);

    // 获取数据
    PerformanceMetrics* getCurrentMetrics(const std::string& sessionId);
    SessionPerformance* getSessionStats(const std::string& sessionId);
    std::vector<PerformanceMetrics> getCommandHistory(const std::string& sessionId);
    std::vector<SessionPerformance> getAllSessionStats();

    // 系统资源
    long long getSystemMemoryUsed();
    long long getSystemMemoryAvailable();
    int getSystemCpuCount();
    double getSystemCpuUsage();
    long long getAppMemoryUsed();
    int getAppThreadCount();

    // 回调
    void addPerformanceCallback(PerformanceEventCallback cb);
    void addSessionCallback(SessionPerformanceCallback cb);
    void removePerformanceCallback(PerformanceEventCallback cb);
    void removeSessionCallback(SessionPerformanceCallback cb);

    // 清理
    void clearSessionData(const std::string& sessionId);
    void clearAll();
};

#endif // PERFORMANCE_MONITOR_H
