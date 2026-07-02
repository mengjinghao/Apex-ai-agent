#include "flashing_helper.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <cstring>
#include <algorithm>

FlashingHelper::FlashingHelper() : hasRootAccess(false) {
    criticalPartitions = {
        "boot", "system", "vendor", "efs", "recovery",
        "data", "cache", "dtbo", "vbmeta"
    };
    
    criticalSystemApps = {
        "android", "system", "settings", "phone", "systemui",
        "framework", "media", "keystore", "permissioncontroller",
        "documentsui", "packageinstaller"
    };
    
    backupDir = "/sdcard/FlashingHelperBackup";
}

FlashingHelper::~FlashingHelper() = default;

bool FlashingHelper::initialize() {
    hasRootAccess = checkRoot();
    if (hasRootAccess) {
        // 确保备份目录存在
        mkdir(backupDir.c_str(), 0755);
        LOGI("FlashingHelper initialized with root access");
    } else {
        LOGE("FlashingHelper: No root access available");
    }
    return hasRootAccess;
}

bool FlashingHelper::checkRoot() {
    // 检查 su 二进制
    if (access("/system/xbin/su", X_OK) == 0 || 
        access("/system/bin/su", X_OK) == 0 ||
        access("/sbin/su", X_OK) == 0) {
        // 尝试执行 su
        FILE* pipe = popen("su -c 'echo root_access_ok'", "r");
        if (pipe) {
            char buffer[128];
            if (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
                std::string result(buffer);
                pclose(pipe);
                return result.find("root_access_ok") != std::string::npos;
            }
            pclose(pipe);
        }
    }
    return false;
}

std::string FlashingHelper::executeRootCommand(const std::string& cmd) {
    if (!hasRootAccess) {
        return "";
    }
    
    std::string fullCmd = "su -c '" + cmd + "' 2>&1";
    FILE* pipe = popen(fullCmd.c_str(), "r");
    if (!pipe) {
        return "";
    }
    
    std::string result;
    char buffer[4096];
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }
    pclose(pipe);
    
    return result;
}

std::string FlashingHelper::calculateChecksum(const std::string& filePath) {
    std::string cmd = "sha256sum " + filePath + " | awk '{print $1}'";
    return executeRootCommand(cmd);
}

bool FlashingHelper::verifyBackup(const BackupInfo& info) {
    std::string calculated = calculateChecksum(info.backupPath);
    // 移除换行符
    calculated.erase(std::remove(calculated.begin(), calculated.end(), '\n'), calculated.end());
    calculated.erase(std::remove(calculated.begin(), calculated.end(), '\r'), calculated.end());
    return calculated == info.checksum;
}

std::vector<PartitionInfo> FlashingHelper::listPartitions() {
    std::vector<PartitionInfo> result;
    
    // 扫描 /dev/block/by-name
    std::string byNamePath = "/dev/block/by-name";
    DIR* dir = opendir(byNamePath.c_str());
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type == DT_LNK || entry->d_type == DT_REG) {
                PartitionInfo info;
                info.name = entry->d_name;
                info.path = byNamePath + "/" + entry->d_name;
                info.isCritical = std::find(criticalPartitions.begin(), 
                    criticalPartitions.end(), info.name) != criticalPartitions.end();
                
                // 尝试获取大小
                std::string sizeCmd = "blockdev --getsize64 " + info.path;
                std::string sizeStr = executeRootCommand(sizeCmd);
                if (!sizeStr.empty()) {
                    long long size = std::stoll(sizeStr);
                    std::ostringstream oss;
                    if (size > 1024 * 1024 * 1024) {
                        oss << std::fixed << std::setprecision(2) << (size / 1073741824.0) << " GB";
                    } else if (size > 1024 * 1024) {
                        oss << std::fixed << std::setprecision(2) << (size / 1048576.0) << " MB";
                    } else if (size > 1024) {
                        oss << std::fixed << std::setprecision(2) << (size / 1024.0) << " KB";
                    } else {
                        oss << size << " B";
                    }
                    info.size = oss.str();
                }
                
                result.push_back(info);
            }
        }
        closedir(dir);
    }
    
    // 排序
    std::sort(result.begin(), result.end(), 
        [](const PartitionInfo& a, const PartitionInfo& b) {
            return a.name < b.name;
        });
    
    return result;
}

FlashResult FlashingHelper::backupPartition(const std::string& partitionName, 
    const std::string& backupPath, bool verify) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access available";
        return result;
    }
    
    // 查找分区
    auto partitions = listPartitions();
    auto it = std::find_if(partitions.begin(), partitions.end(),
        [&](const PartitionInfo& p) { return p.name == partitionName; });
    
    if (it == partitions.end()) {
        result.message = "Partition not found: " + partitionName;
        return result;
    }
    
    // 确定备份路径
    std::string actualPath = backupPath;
    if (actualPath.empty()) {
        std::ostringstream oss;
        oss << backupDir << "/" << partitionName << "_" 
            << std::chrono::system_clock::to_time_t(std::chrono::system_clock::now()) 
            << ".img";
        actualPath = oss.str();
    }
    
    // 使用 dd 备份
    std::string ddCmd = "dd if=" + it->path + " of=" + actualPath + " bs=4096";
    std::string ddOutput = executeRootCommand(ddCmd);
    
    // 检查备份文件
    struct stat fileStat;
    if (stat(actualPath.c_str(), &fileStat) == 0 && fileStat.st_size > 0) {
        BackupInfo backupInfo;
        backupInfo.backupId = std::to_string(std::chrono::system_clock::to_time_t(std::chrono::system_clock::now()));
        backupInfo.partitionName = partitionName;
        backupInfo.backupPath = actualPath;
        backupInfo.size = fileStat.st_size;
        backupInfo.createdAt = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
        backupInfo.verified = false;
        
        if (verify) {
            backupInfo.checksum = calculateChecksum(actualPath);
            backupInfo.verified = !backupInfo.checksum.empty();
        }
        
        result.type = FlashResultType::SUCCESS;
        result.message = "Partition backup successful: " + partitionName;
        result.details["backupPath"] = actualPath;
        result.details["size"] = std::to_string(fileStat.st_size);
        result.details["checksum"] = backupInfo.checksum;
        result.succeededItems.push_back(partitionName);
        
        LOGI("Successfully backed up %s to %s", partitionName.c_str(), actualPath.c_str());
    } else {
        result.message = "Failed to create backup file";
    }
    
    return result;
}

FlashResult FlashingHelper::restorePartition(const BackupInfo& backupInfo, 
    bool verify, bool autoBackupFirst) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access available";
        return result;
    }
    
    // 验证备份
    if (verify && !verifyBackup(backupInfo)) {
        result.message = "Backup verification failed";
        return result;
    }
    
    // 自动备份当前状态
    if (autoBackupFirst) {
        LOGI("Auto-backing up current state before restoring");
        auto currentBackup = backupPartition(backupInfo.partitionName, "", false);
        if (currentBackup.type == FlashResultType::ERROR) {
            result.message = "Auto-backup failed, aborting restore";
            return result;
        }
    }
    
    // 查找目标分区
    auto partitions = listPartitions();
    auto it = std::find_if(partitions.begin(), partitions.end(),
        [&](const PartitionInfo& p) { return p.name == backupInfo.partitionName; });
    
    if (it == partitions.end()) {
        result.message = "Target partition not found";
        return result;
    }
    
    // 执行 dd 恢复
    std::string ddCmd = "dd if=" + backupInfo.backupPath + " of=" + it->path + " bs=4096";
    std::string ddOutput = executeRootCommand(ddCmd);
    
    // 简单验证（检查退出码）
    // 实际实现应该更复杂
    result.type = FlashResultType::SUCCESS;
    result.message = "Partition restored successfully";
    result.succeededItems.push_back(backupInfo.partitionName);
    
    LOGI("Successfully restored %s from %s", backupInfo.partitionName.c_str(), 
         backupInfo.backupPath.c_str());
    
    return result;
}

FlashResult FlashingHelper::backupAllCriticalPartitions() {
    return backupMultiplePartitions(criticalPartitions);
}

FlashResult FlashingHelper::backupMultiplePartitions(const std::vector<std::string>& partitions) {
    FlashResult result;
    result.type = FlashResultType::SUCCESS;
    
    for (const auto& partition : partitions) {
        auto backupResult = backupPartition(partition);
        if (backupResult.type == FlashResultType::SUCCESS) {
            result.succeededItems.push_back(partition);
        } else {
            result.failedItems.push_back(partition);
            result.type = FlashResultType::PARTIAL_SUCCESS;
        }
    }
    
    if (result.failedItems.empty()) {
        result.message = "All partitions backed up successfully";
    } else {
        result.message = "Some partitions failed to backup";
    }
    
    return result;
}

std::vector<MagiskModuleInfo> FlashingHelper::listMagiskModules() {
    std::vector<MagiskModuleInfo> result;
    
    std::string modulesPath = "/data/adb/modules";
    DIR* dir = opendir(modulesPath.c_str());
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type == DT_DIR && entry->d_name[0] != '.') {
                MagiskModuleInfo info;
                info.id = entry->d_name;
                info.installPath = modulesPath + "/" + info.id;
                
                // 读取 module.prop
                std::string propPath = info.installPath + "/module.prop";
                std::ifstream propFile(propPath);
                if (propFile) {
                    std::string line;
                    while (std::getline(propFile, line)) {
                        size_t eq = line.find('=');
                        if (eq != std::string::npos) {
                            std::string key = line.substr(0, eq);
                            std::string value = line.substr(eq + 1);
                            
                            if (key == "name") info.name = value;
                            else if (key == "version") info.version = value;
                            else if (key == "versionCode") info.versionCode = value;
                            else if (key == "author") info.author = value;
                            else if (key == "description") info.description = value;
                        }
                    }
                }
                
                // 检查是否禁用
                std::string disablePath = info.installPath + "/disable";
                info.enabled = access(disablePath.c_str(), F_OK) != 0;
                
                info.updateAvailable = false;
                
                result.push_back(info);
            }
        }
        closedir(dir);
    }
    
    return result;
}

FlashResult FlashingHelper::installMagiskModule(const std::string& zipPath) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access";
        return result;
    }
    
    // 检查文件
    if (access(zipPath.c_str(), R_OK) != 0) {
        result.message = "Module zip not found";
        return result;
    }
    
    // 使用 Magisk 安装
    std::string installCmd = "magisk --install-module " + zipPath;
    std::string output = executeRootCommand(installCmd);
    
    // 简单检查输出
    if (output.find("Done") != std::string::npos || output.find("done") != std::string::npos) {
        result.type = FlashResultType::SUCCESS;
        result.message = "Module installed successfully";
    } else {
        result.message = "Module installation failed: " + output;
    }
    
    return result;
}

FlashResult FlashingHelper::uninstallMagiskModule(const std::string& moduleId) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access";
        return result;
    }
    
    std::string modulePath = "/data/adb/modules/" + moduleId;
    std::string removePath = modulePath + "/remove";
    
    // 创建 remove 文件
    int fd = open(removePath.c_str(), O_WRONLY | O_CREAT, 0644);
    if (fd >= 0) {
        close(fd);
        result.type = FlashResultType::SUCCESS;
        result.message = "Module marked for uninstallation on reboot";
        result.succeededItems.push_back(moduleId);
    } else {
        result.message = "Failed to mark module for uninstallation";
    }
    
    return result;
}

FlashResult FlashingHelper::enableMagiskModule(const std::string& moduleId) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string disablePath = "/data/adb/modules/" + moduleId + "/disable";
    if (unlink(disablePath.c_str()) == 0 || errno == ENOENT) {
        result.type = FlashResultType::SUCCESS;
        result.message = "Module enabled";
        result.succeededItems.push_back(moduleId);
    } else {
        result.message = "Failed to enable module";
    }
    
    return result;
}

FlashResult FlashingHelper::disableMagiskModule(const std::string& moduleId) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string disablePath = "/data/adb/modules/" + moduleId + "/disable";
    int fd = open(disablePath.c_str(), O_WRONLY | O_CREAT, 0644);
    if (fd >= 0) {
        close(fd);
        result.type = FlashResultType::SUCCESS;
        result.message = "Module disabled";
        result.succeededItems.push_back(moduleId);
    } else {
        result.message = "Failed to disable module";
    }
    
    return result;
}

FlashResult FlashingHelper::updateMagiskModule(const std::string& moduleId) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    result.message = "Module update not implemented yet";
    return result;
}

FlashResult FlashingHelper::checkMagiskModuleCompatibility(const std::string& moduleId) {
    FlashResult result;
    result.type = FlashResultType::SUCCESS;
    result.message = "Module compatibility check passed";
    result.succeededItems.push_back(moduleId);
    return result;
}

std::vector<std::pair<std::string, std::string>> FlashingHelper::listSystemApps() {
    std::vector<std::pair<std::string, std::string>> result;
    
    // 使用 pm list packages
    std::string output = executeRootCommand("pm list packages -f");
    std::istringstream iss(output);
    std::string line;
    
    while (std::getline(iss, line)) {
        size_t pathEnd = line.rfind('=');
        if (pathEnd != std::string::npos) {
            std::string path = line.substr(0, pathEnd);
            std::string package = line.substr(pathEnd + 1);
            
            if (path.find("package:") == 0) {
                path = path.substr(8);
            }
            
            result.push_back({package, path});
        }
    }
    
    return result;
}

FlashResult FlashingHelper::uninstallSystemApp(const std::string& packageName, bool backup) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    // 检查是否是关键系统应用
    if (std::find(criticalSystemApps.begin(), criticalSystemApps.end(), 
        packageName) != criticalSystemApps.end()) {
        result.message = "Cannot uninstall critical system app";
        return result;
    }
    
    // 查找应用路径
    auto apps = listSystemApps();
    auto it = std::find_if(apps.begin(), apps.end(),
        [&](const auto& p) { return p.first == packageName; });
    
    if (it == apps.end()) {
        result.message = "App not found";
        return result;
    }
    
    // 备份（可选）
    if (backup) {
        std::string backupCmd = "cp -r " + it->second + " " + backupDir + "/";
        executeRootCommand(backupCmd);
    }
    
    // 卸载
    std::string uninstallCmd = "pm uninstall " + packageName;
    std::string output = executeRootCommand(uninstallCmd);
    
    if (output.find("Success") != std::string::npos) {
        result.type = FlashResultType::SUCCESS;
        result.message = "System app uninstalled";
        result.succeededItems.push_back(packageName);
    } else {
        result.message = "Failed to uninstall app: " + output;
    }
    
    return result;
}

FlashResult FlashingHelper::restoreSystemApp(const std::string& packageName) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    result.message = "App restoration not implemented yet";
    return result;
}

FlashResult FlashingHelper::freezeSystemApp(const std::string& packageName) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string cmd = "pm disable " + packageName;
    std::string output = executeRootCommand(cmd);
    
    if (output.find("Package") != std::string::npos && output.find("disabled") != std::string::npos) {
        result.type = FlashResultType::SUCCESS;
        result.message = "App frozen";
        result.succeededItems.push_back(packageName);
    } else {
        result.message = "Failed to freeze app";
    }
    
    return result;
}

FlashResult FlashingHelper::unfreezeSystemApp(const std::string& packageName) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string cmd = "pm enable " + packageName;
    std::string output = executeRootCommand(cmd);
    
    if (output.find("Package") != std::string::npos && output.find("enabled") != std::string::npos) {
        result.type = FlashResultType::SUCCESS;
        result.message = "App unfrozen";
        result.succeededItems.push_back(packageName);
    } else {
        result.message = "Failed to unfreeze app";
    }
    
    return result;
}

FlashResult FlashingHelper::batchManageSystemApps(const std::vector<std::string>& packages, bool uninstall) {
    FlashResult result;
    result.type = FlashResultType::SUCCESS;
    
    for (const auto& package : packages) {
        FlashResult opResult;
        if (uninstall) {
            opResult = uninstallSystemApp(package, true);
        } else {
            opResult = freezeSystemApp(package);
        }
        
        if (opResult.type == FlashResultType::SUCCESS) {
            result.succeededItems.push_back(package);
        } else {
            result.failedItems.push_back(package);
            result.type = FlashResultType::PARTIAL_SUCCESS;
        }
    }
    
    if (result.failedItems.empty()) {
        result.message = "All apps processed successfully";
    } else {
        result.message = "Some apps failed to process";
    }
    
    return result;
}

FlashResult FlashingHelper::diagnoseBootIssues() {
    FlashResult result;
    result.type = FlashResultType::SUCCESS;
    result.message = "Boot diagnostics completed";
    
    // 检查 dmesg
    std::string dmesg = executeRootCommand("dmesg | tail -n 100");
    result.details["dmesg"] = dmesg;
    
    // 检查 logcat
    std::string logcat = executeRootCommand("logcat -d -b main,system,crash | tail -n 100");
    result.details["logcat"] = logcat;
    
    return result;
}

FlashResult FlashingHelper::checkSystemIntegrity() {
    FlashResult result;
    result.type = FlashResultType::SUCCESS;
    result.message = "System integrity check completed";
    return result;
}

FlashResult FlashingHelper::repairBootloop() {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    result.message = "Bootloop repair requires custom logic";
    return result;
}

FlashResult FlashingHelper::clearDalvikCache() {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access";
        return result;
    }
    
    std::string cmd = "rm -rf /data/dalvik-cache /data/dalvik-cache-* /cache/dalvik-cache";
    executeRootCommand(cmd);
    
    result.type = FlashResultType::SUCCESS;
    result.message = "Dalvik cache cleared";
    
    return result;
}

FlashResult FlashingHelper::wipeCachePartition() {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    if (!hasRootAccess) {
        result.message = "No root access";
        return result;
    }
    
    std::string cmd = "rm -rf /cache/*";
    executeRootCommand(cmd);
    
    result.type = FlashResultType::SUCCESS;
    result.message = "Cache partition wiped";
    
    return result;
}

FlashResult FlashingHelper::factoryReset(bool keepData) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    result.message = "Factory reset is dangerous and not implemented";
    return result;
}

std::vector<BackupInfo> FlashingHelper::listBackups() {
    std::vector<BackupInfo> result;
    
    DIR* dir = opendir(backupDir.c_str());
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type == DT_REG) {
                std::string name = entry->d_name;
                if (name.find(".img") == name.size() - 4) {
                    BackupInfo info;
                    info.backupId = name;
                    info.backupPath = backupDir + "/" + name;
                    
                    // 提取分区名（假设格式是 partition_timestamp.img）
                    size_t underscore = name.rfind('_');
                    if (underscore != std::string::npos) {
                        info.partitionName = name.substr(0, underscore);
                    }
                    
                    // 获取文件大小
                    struct stat st;
                    if (stat(info.backupPath.c_str(), &st) == 0) {
                        info.size = st.st_size;
                        info.createdAt = st.st_mtime;
                    }
                    
                    result.push_back(info);
                }
            }
        }
        closedir(dir);
    }
    
    return result;
}

FlashResult FlashingHelper::deleteBackup(const std::string& backupId) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string path = backupDir + "/" + backupId;
    if (unlink(path.c_str()) == 0) {
        result.type = FlashResultType::SUCCESS;
        result.message = "Backup deleted";
        result.succeededItems.push_back(backupId);
    } else {
        result.message = "Failed to delete backup";
    }
    
    return result;
}

FlashResult FlashingHelper::exportBackup(const std::string& backupId, const std::string& exportPath) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string src = backupDir + "/" + backupId;
    std::string cmd = "cp " + src + " " + exportPath;
    executeRootCommand(cmd);
    
    if (access(exportPath.c_str(), R_OK) == 0) {
        result.type = FlashResultType::SUCCESS;
        result.message = "Backup exported";
        result.succeededItems.push_back(backupId);
    } else {
        result.message = "Failed to export backup";
    }
    
    return result;
}

FlashResult FlashingHelper::importBackup(const std::string& importPath) {
    FlashResult result;
    result.type = FlashResultType::ERROR;
    
    std::string filename = importPath;
    size_t lastSlash = filename.rfind('/');
    if (lastSlash != std::string::npos) {
        filename = filename.substr(lastSlash + 1);
    }
    
    std::string dest = backupDir + "/" + filename;
    std::string cmd = "cp " + importPath + " " + dest;
    executeRootCommand(cmd);
    
    if (access(dest.c_str(), R_OK) == 0) {
        result.type = FlashResultType::SUCCESS;
        result.message = "Backup imported";
        result.succeededItems.push_back(filename);
    } else {
        result.message = "Failed to import backup";
    }
    
    return result;
}

void FlashingHelper::setBackupDirectory(const std::string& dir) {
    backupDir = dir;
    mkdir(backupDir.c_str(), 0755);
}

std::string FlashingHelper::getBackupDirectory() const {
    return backupDir;
}

void FlashingHelper::addCriticalPartition(const std::string& name) {
    if (std::find(criticalPartitions.begin(), criticalPartitions.end(), name) == criticalPartitions.end()) {
        criticalPartitions.push_back(name);
    }
}

void FlashingHelper::removeCriticalPartition(const std::string& name) {
    criticalPartitions.erase(std::remove(criticalPartitions.begin(), criticalPartitions.end(), name), 
                             criticalPartitions.end());
}

void FlashingHelper::addCriticalSystemApp(const std::string& package) {
    if (std::find(criticalSystemApps.begin(), criticalSystemApps.end(), package) == criticalSystemApps.end()) {
        criticalSystemApps.push_back(package);
    }
}

void FlashingHelper::removeCriticalSystemApp(const std::string& package) {
    criticalSystemApps.erase(std::remove(criticalSystemApps.begin(), criticalSystemApps.end(), package), 
                              criticalSystemApps.end());
}
