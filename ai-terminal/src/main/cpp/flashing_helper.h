#ifndef FLASHING_HELPER_H
#define FLASHING_HELPER_H

#include <string>
#include <vector>
#include <unordered_map>
#include <functional>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <android/log.h>

#define LOG_TAG "FlashHelper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 结果类型
enum class FlashResultType {
    SUCCESS,
    ERROR,
    PARTIAL_SUCCESS
};

// 操作结果
struct FlashResult {
    FlashResultType type;
    std::string message;
    std::unordered_map<std::string, std::string> details;
    std::vector<std::string> succeededItems;
    std::vector<std::string> failedItems;
};

// 分区信息
struct PartitionInfo {
    std::string name;
    std::string path;
    std::string size;
    std::string type;
    bool isCritical;
};

// 备份信息
struct BackupInfo {
    std::string backupId;
    std::string partitionName;
    std::string backupPath;
    long long size;
    long long createdAt;
    std::string checksum;
    bool verified;
};

// 模块信息
struct MagiskModuleInfo {
    std::string id;
    std::string name;
    std::string version;
    std::string versionCode;
    std::string author;
    std::string description;
    bool enabled;
    bool updateAvailable;
    std::string installPath;
};

// Flashing助手（Root权限操作）
class FlashingHelper {
private:
    bool hasRootAccess;
    std::vector<std::string> criticalPartitions;
    std::vector<std::string> criticalSystemApps;
    std::string backupDir;

    bool checkRoot();
    std::string calculateChecksum(const std::string& filePath);
    bool verifyBackup(const BackupInfo& info);
    std::string executeRootCommand(const std::string& cmd);

public:
    FlashingHelper();
    ~FlashingHelper();

    // 初始化
    bool initialize();
    bool isRootAvailable() const { return hasRootAccess; }

    // ========== 分区操作 ==========
    std::vector<PartitionInfo> listPartitions();
    FlashResult backupPartition(const std::string& partitionName, const std::string& backupPath = "", bool verify = true);
    FlashResult restorePartition(const BackupInfo& backupInfo, bool verify = true, bool autoBackupFirst = true);
    FlashResult backupAllCriticalPartitions();
    FlashResult backupMultiplePartitions(const std::vector<std::string>& partitions);

    // ========== Magisk模块管理 ==========
    std::vector<MagiskModuleInfo> listMagiskModules();
    FlashResult installMagiskModule(const std::string& zipPath);
    FlashResult uninstallMagiskModule(const std::string& moduleId);
    FlashResult enableMagiskModule(const std::string& moduleId);
    FlashResult disableMagiskModule(const std::string& moduleId);
    FlashResult updateMagiskModule(const std::string& moduleId);
    FlashResult checkMagiskModuleCompatibility(const std::string& moduleId);

    // ========== 系统应用管理 ==========
    std::vector<std::pair<std::string, std::string>> listSystemApps();
    FlashResult uninstallSystemApp(const std::string& packageName, bool backup = true);
    FlashResult restoreSystemApp(const std::string& packageName);
    FlashResult freezeSystemApp(const std::string& packageName);
    FlashResult unfreezeSystemApp(const std::string& packageName);
    FlashResult batchManageSystemApps(const std::vector<std::string>& packages, bool uninstall);

    // ========== 刷机诊断 ==========
    FlashResult diagnoseBootIssues();
    FlashResult checkSystemIntegrity();
    FlashResult repairBootloop();
    FlashResult clearDalvikCache();
    FlashResult wipeCachePartition();
    FlashResult factoryReset(bool keepData = false);

    // ========== 备份/恢复管理 ==========
    std::vector<BackupInfo> listBackups();
    FlashResult deleteBackup(const std::string& backupId);
    FlashResult exportBackup(const std::string& backupId, const std::string& exportPath);
    FlashResult importBackup(const std::string& importPath);

    // ========== 配置 ==========
    void setBackupDirectory(const std::string& dir);
    std::string getBackupDirectory() const;
    void addCriticalPartition(const std::string& name);
    void removeCriticalPartition(const std::string& name);
    void addCriticalSystemApp(const std::string& package);
    void removeCriticalSystemApp(const std::string& package);
};

#endif // FLASHING_HELPER_H
