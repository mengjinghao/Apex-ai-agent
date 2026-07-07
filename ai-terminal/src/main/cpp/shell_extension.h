#ifndef SHELL_EXTENSION_H
#define SHELL_EXTENSION_H

#include <string>
#include <vector>
#include <unordered_map>
#include <functional>
#include <regex>
#include <memory>

// 命令类型
enum class CommandType {
    SIMPLE,
    PIPELINE,
    REDIRECT_STDOUT,
    REDIRECT_STDERR,
    REDIRECT_BOTH,
    BACKGROUND,
    SEQUENCE,
    SUBSTITUTE
};

// 解析后的命令
struct ShellCommand {
    CommandType type;
    std::vector<std::string> components;
    std::string raw;
    std::string redirectFile;
    bool append;
};

struct ParsedCommand {
    std::vector<ShellCommand> commands;
    std::unordered_map<std::string, std::string> env;
    std::string workingDir;
    bool background;
};

// 别名管理器
class AliasManager {
private:
    std::unordered_map<std::string, std::string> aliases;
    std::string aliasFilePath;

public:
    AliasManager(const std::string& file = "");
    ~AliasManager() = default;

    bool addAlias(const std::string& name, const std::string& command);
    bool removeAlias(const std::string& name);
    std::string getAlias(const std::string& name);
    std::unordered_map<std::string, std::string> getAllAliases();
    bool hasAlias(const std::string& name);
    std::string resolveAlias(const std::string& command);
    void clearAliases();
    bool saveToFile(const std::string& path);
    bool loadFromFile(const std::string& path);
};

// Shell扩展解析器
class ShellExtension {
private:
    std::unique_ptr<AliasManager> aliasManager;
    std::regex sequencePattern;
    std::regex envPattern;
    std::regex redirectPattern;
    std::regex simpleSplitPattern;

public:
    ShellExtension();
    ~ShellExtension();

    // 命令解析
    ParsedCommand parseCommand(const std::string& input);
    std::string expandEnvironment(const std::string& input);
    std::string expandTilde(const std::string& path);
    std::vector<std::string> splitCommand(const std::string& cmd);

    // 高级解析
    bool isPipeline(const std::string& cmd);
    bool isRedirect(const std::string& cmd);
    bool isBackground(const std::string& cmd);
    std::vector<std::string> parsePipeline(const std::string& cmd);
    std::pair<std::string, std::string> parseRedirect(const std::string& cmd);

    // 命令验证
    bool isCommandSafe(const std::string& cmd);
    std::vector<std::string> getDangerousPatterns();
    bool hasDangerousPattern(const std::string& cmd);

    // 别名管理
    AliasManager* getAliasManager();
};

#endif // SHELL_EXTENSION_H
