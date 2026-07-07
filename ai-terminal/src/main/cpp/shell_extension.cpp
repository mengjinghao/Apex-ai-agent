#include "shell_extension.h"
#include <pwd.h>
#include <unistd.h>
#include <fstream>
#include <sstream>
#include <algorithm>

// 危险命令模式
static const std::vector<std::string> DANGEROUS_PATTERNS = {
    "rm -rf /",
    "mkfs",
    "dd if=",
    ":(){ :|:& };:",
    "chmod -R 777",
    "rm -rf ~",
    "mv /* /dev/null",
    "> /dev/sd",
    "cat /dev/zero >"
};

AliasManager::AliasManager(const std::string& file) : aliasFilePath(file) {
    if (!file.empty()) {
        loadFromFile(file);
    }
}

bool AliasManager::addAlias(const std::string& name, const std::string& command) {
    if (name.empty() || command.empty()) {
        return false;
    }
    aliases[name] = command;
    if (!aliasFilePath.empty()) {
        saveToFile(aliasFilePath);
    }
    return true;
}

bool AliasManager::removeAlias(const std::string& name) {
    auto it = aliases.find(name);
    if (it != aliases.end()) {
        aliases.erase(it);
        if (!aliasFilePath.empty()) {
            saveToFile(aliasFilePath);
        }
        return true;
    }
    return false;
}

std::string AliasManager::getAlias(const std::string& name) {
    auto it = aliases.find(name);
    return it != aliases.end() ? it->second : "";
}

std::unordered_map<std::string, std::string> AliasManager::getAllAliases() {
    return aliases;
}

bool AliasManager::hasAlias(const std::string& name) {
    return aliases.find(name) != aliases.end();
}

std::string AliasManager::resolveAlias(const std::string& command) {
    std::istringstream iss(command);
    std::string firstWord;
    iss >> firstWord;
    
    auto it = aliases.find(firstWord);
    if (it != aliases.end()) {
        std::string remaining;
        std::getline(iss, remaining);
        return it->second + remaining;
    }
    return command;
}

void AliasManager::clearAliases() {
    aliases.clear();
    if (!aliasFilePath.empty()) {
        saveToFile(aliasFilePath);
    }
}

bool AliasManager::saveToFile(const std::string& path) {
    std::ofstream file(path);
    if (!file) {
        return false;
    }
    
    for (auto& pair : aliases) {
        file << pair.first << "=" << pair.second << "\n";
    }
    return file.good();
}

bool AliasManager::loadFromFile(const std::string& path) {
    std::ifstream file(path);
    if (!file) {
        return false;
    }
    
    std::string line;
    while (std::getline(file, line)) {
        size_t eqPos = line.find('=');
        if (eqPos != std::string::npos) {
            std::string name = line.substr(0, eqPos);
            std::string command = line.substr(eqPos + 1);
            aliases[name] = command;
        }
    }
    return file.good();
}

ShellExtension::ShellExtension() 
    : aliasManager(std::make_unique<AliasManager>()),
      sequencePattern("\\s*&&\\s*|\\s*\\|\\|\\s*|\\s*;\\s*"),
      envPattern("^\\s*(\\w+)=(.*)$"),
      redirectPattern("(.*?)\\s*(>>?|2>&1|2>>?)\\s*(.*)"),
      simpleSplitPattern("\\s+") {
}

ShellExtension::~ShellExtension() = default;

ParsedCommand ShellExtension::parseCommand(const std::string& input) {
    ParsedCommand result;
    result.background = false;
    
    if (input.empty()) {
        return result;
    }
    
    std::string trimmed = input;
    // 移除首尾空白
    trimmed.erase(0, trimmed.find_first_not_of(" \t\n\r"));
    trimmed.erase(trimmed.find_last_not_of(" \t\n\r") + 1);
    
    if (trimmed.empty() || trimmed[0] == '#' || trimmed[0] == ';') {
        return result;
    }
    
    // 检查后台任务
    if (trimmed.back() == '&') {
        result.background = true;
        trimmed.pop_back();
    }
    
    // 解析别名
    std::string resolved = aliasManager->resolveAlias(trimmed);
    
    // 检查命令类型
    if (isPipeline(resolved)) {
        auto pipeline = parsePipeline(resolved);
        for (auto& cmd : pipeline) {
            ShellCommand shellCmd;
            shellCmd.type = CommandType::PIPELINE;
            shellCmd.raw = cmd;
            shellCmd.components = splitCommand(cmd);
            result.commands.push_back(shellCmd);
        }
    } else if (isRedirect(resolved)) {
        auto [cmd, file] = parseRedirect(resolved);
        ShellCommand shellCmd;
        shellCmd.type = CommandType::REDIRECT_STDOUT;
        shellCmd.raw = cmd;
        shellCmd.redirectFile = file;
        shellCmd.components = splitCommand(cmd);
        result.commands.push_back(shellCmd);
    } else {
        ShellCommand shellCmd;
        shellCmd.type = CommandType::SIMPLE;
        shellCmd.raw = resolved;
        shellCmd.components = splitCommand(resolved);
        result.commands.push_back(shellCmd);
    }
    
    return result;
}

std::string ShellExtension::expandEnvironment(const std::string& input) {
    std::string result = input;
    // 简单的 $VAR 展开
    size_t pos = 0;
    while ((pos = result.find('$', pos)) != std::string::npos) {
        size_t end = pos + 1;
        while (end < result.size() && (isalnum(result[end]) || result[end] == '_')) {
            end++;
        }
        if (end > pos + 1) {
            std::string var = result.substr(pos + 1, end - pos - 1);
            const char* val = getenv(var.c_str());
            if (val) {
                result.replace(pos, end - pos, val);
            }
        }
        pos++;
    }
    return result;
}

std::string ShellExtension::expandTilde(const std::string& path) {
    if (path.empty() || path[0] != '~') {
        return path;
    }
    
    if (path.size() == 1 || path[1] == '/') {
        struct passwd* pw = getpwuid(getuid());
        if (pw) {
            return std::string(pw->pw_dir) + path.substr(1);
        }
    }
    return path;
}

std::vector<std::string> ShellExtension::splitCommand(const std::string& cmd) {
    std::vector<std::string> result;
    std::istringstream iss(cmd);
    std::string token;
    
    bool inQuotes = false;
    char quoteChar = '\0';
    std::string currentToken;
    
    for (char c : cmd) {
        if (c == '\'' || c == '"') {
            if (inQuotes && c == quoteChar) {
                inQuotes = false;
            } else if (!inQuotes) {
                inQuotes = true;
                quoteChar = c;
            } else {
                currentToken += c;
            }
        } else if (isspace(c) && !inQuotes) {
            if (!currentToken.empty()) {
                result.push_back(currentToken);
                currentToken.clear();
            }
        } else {
            currentToken += c;
        }
    }
    
    if (!currentToken.empty()) {
        result.push_back(currentToken);
    }
    
    return result;
}

bool ShellExtension::isPipeline(const std::string& cmd) {
    return cmd.find('|') != std::string::npos;
}

bool ShellExtension::isRedirect(const std::string& cmd) {
    return cmd.find('>') != std::string::npos || cmd.find("2>") != std::string::npos;
}

bool ShellExtension::isBackground(const std::string& cmd) {
    return !cmd.empty() && cmd.back() == '&';
}

std::vector<std::string> ShellExtension::parsePipeline(const std::string& cmd) {
    std::vector<std::string> result;
    std::istringstream iss(cmd);
    std::string part;
    
    while (std::getline(iss, part, '|')) {
        size_t start = part.find_first_not_of(" \t");
        size_t end = part.find_last_not_of(" \t");
        if (start != std::string::npos && end != std::string::npos) {
            result.push_back(part.substr(start, end - start + 1));
        }
    }
    
    return result;
}

std::pair<std::string, std::string> ShellExtension::parseRedirect(const std::string& cmd) {
    size_t pos = cmd.find('>');
    if (pos != std::string::npos) {
        std::string cmdPart = cmd.substr(0, pos);
        std::string filePart = cmd.substr(pos + 1);
        
        size_t fileStart = filePart.find_first_not_of(" \t");
        if (fileStart != std::string::npos) {
            filePart = filePart.substr(fileStart);
        }
        
        return {cmdPart, filePart};
    }
    return {cmd, ""};
}

bool ShellExtension::isCommandSafe(const std::string& cmd) {
    return !hasDangerousPattern(cmd);
}

std::vector<std::string> ShellExtension::getDangerousPatterns() {
    return DANGEROUS_PATTERNS;
}

bool ShellExtension::hasDangerousPattern(const std::string& cmd) {
    std::string lowerCmd = cmd;
    std::transform(lowerCmd.begin(), lowerCmd.end(), lowerCmd.begin(), ::tolower);
    
    for (auto& pattern : DANGEROUS_PATTERNS) {
        std::string lowerPattern = pattern;
        std::transform(lowerPattern.begin(), lowerPattern.end(), lowerPattern.begin(), ::tolower);
        
        if (lowerCmd.find(lowerPattern) != std::string::npos) {
            return true;
        }
    }
    return false;
}

AliasManager* ShellExtension::getAliasManager() {
    return aliasManager.get();
}
