#include <string>
#include <vector>
#include <unordered_map>
#include <android/log.h>
#include <unistd.h>  // 加上这一行！解决X_OK未定义错误

#define LOG_TAG "ShellAdapter"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Shell 类型枚举
enum class ShellType {
    SH,          // 标准 sh shell
    BASH,        // bash shell
    ZSH,         // zsh shell
    FISH,        // fish shell
    ASH,         // ash shell
    MKSH,        // mksh shell
    DASH         // dash shell
};

// Shell 配置结构体
struct ShellConfig {
    std::string name;              // Shell 名称
    std::string path;              // Shell 路径
    std::string initCommand;       // 初始化命令
    std::string prompt;            // 提示符
    std::vector<std::string> features; // 支持的特性
    bool available;                // 是否可用
};

class ShellAdapter {
private:
    static std::unordered_map<ShellType, ShellConfig> shellConfigs;
    static bool initialized;
    
    // 初始化 Shell 配置
    static void initializeShellConfigs();
    
    // 检测 Shell 是否可用
    static bool isShellAvailable(const std::string& path);
    
public:
    // 获取所有支持的 Shell 类型
    static std::vector<ShellType> getSupportedShellTypes();
    
    // 获取默认 Shell 类型
    static ShellType getDefaultShellType();
    
    // 获取 Shell 配置
    static const ShellConfig* getShellConfig(ShellType type);
    
    // 获取 Shell 配置（通过名称）
    static const ShellConfig* getShellConfigByName(const std::string& name);
    
    // 检测并返回可用的 Shell 列表
    static std::vector<ShellType> getAvailableShells();
    
    // 检查 Shell 是否可用
    static bool isShellAvailable(ShellType type);
    
    // 获取 Shell 名称
    static std::string getShellName(ShellType type);
    
    // 获取 Shell 路径
    static std::string getShellPath(ShellType type);
};

// 初始化静态成员
std::unordered_map<ShellType, ShellConfig> ShellAdapter::shellConfigs;
bool ShellAdapter::initialized = false;

void ShellAdapter::initializeShellConfigs() {
    if (initialized) return;
    
    shellConfigs = {
        {
            ShellType::SH,
            {
                "sh",
                "/system/bin/sh",
                "",
                "$ ",
                {"basic", "redirect", "pipe"},
                true
            }
        },
        {
            ShellType::BASH,
            {
                "bash",
                "/system/bin/bash",
                "export PS1=\"bash \\$ \"",
                "bash $ ",
                {"history", "completion", "scripting"},
                false
            }
        },
        {
            ShellType::ZSH,
            {
                "zsh",
                "/system/bin/zsh",
                "export PS1=\"zsh \\$ \"",
                "zsh $ ",
                {"autocomplete", "plugins", "themes"},
                false
            }
        },
        {
            ShellType::FISH,
            {
                "fish",
                "/system/bin/fish",
                "fish_right_prompt",
                "fish> ",
                {"completion", "highlighting"},
                false
            }
        },
        {
            ShellType::ASH,
            {
                "ash",
                "/system/bin/ash",
                "",
                "ash $ ",
                {"lightweight", "fast"},
                false
            }
        },
        {
            ShellType::MKSH,
            {
                "mksh",
                "/system/bin/mksh",
                "",
                "mksh $ ",
                {"korn", "compatible"},
                false
            }
        },
        {
            ShellType::DASH,
            {
                "dash",
                "/system/bin/dash",
                "",
                "dash $ ",
                {"fast", "minimal"},
                false
            }
        }
    };
    
    // 检测哪些 Shell 可用
    for (auto& pair : shellConfigs) {
        pair.second.available = isShellAvailable(pair.second.path);
    }
    
    initialized = true;
}

bool ShellAdapter::isShellAvailable(const std::string& path) {
    return access(path.c_str(), X_OK) == 0;
}

std::vector<ShellType> ShellAdapter::getSupportedShellTypes() {
    initializeShellConfigs();
    
    std::vector<ShellType> types;
    for (const auto& pair : shellConfigs) {
        types.push_back(pair.first);
    }
    return types;
}

ShellType ShellAdapter::getDefaultShellType() {
    initializeShellConfigs();
    
    // 默认使用 sh，如果不可用则找第一个可用的
    if (shellConfigs[ShellType::SH].available) {
        return ShellType::SH;
    }
    
    for (const auto& pair : shellConfigs) {
        if (pair.second.available) {
            return pair.first;
        }
    }
    
    return ShellType::SH; // 最终默认
}

const ShellConfig* ShellAdapter::getShellConfig(ShellType type) {
    initializeShellConfigs();
    
    auto it = shellConfigs.find(type);
    return (it != shellConfigs.end()) ? &it->second : nullptr;
}

const ShellConfig* ShellAdapter::getShellConfigByName(const std::string& name) {
    initializeShellConfigs();
    
    for (const auto& pair : shellConfigs) {
        if (pair.second.name == name) {
            return &pair.second;
        }
    }
    
    return nullptr;
}

std::vector<ShellType> ShellAdapter::getAvailableShells() {
    initializeShellConfigs();
    
    std::vector<ShellType> available;
    for (const auto& pair : shellConfigs) {
        if (pair.second.available) {
            available.push_back(pair.first);
        }
    }
    
    LOGD("Found %zu available shells", available.size());
    return available;
}

bool ShellAdapter::isShellAvailable(ShellType type) {
    initializeShellConfigs();
    
    auto config = getShellConfig(type);
    return config && config->available;
}

std::string ShellAdapter::getShellName(ShellType type) {
    auto config = getShellConfig(type);
    return config ? config->name : "";
}

std::string ShellAdapter::getShellPath(ShellType type) {
    auto config = getShellConfig(type);
    return config ? config->path : "";
}
