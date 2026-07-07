package com.ai.assistance.aiterminal.terminal.agent.task

import android.util.Log

private const val TAG = "ErrorAnalyzer"

/**
 * 错误分析器 - 分析错误原因并生成修复方案
 */
class ErrorAnalyzer {
    
    /**
     * 分析错误
     */
    fun analyzeError(
        originalCommand: String,
        exitCode: Int,
        stdout: String,
        stderr: String
    ): ErrorAnalysis {
        Log.d(TAG, "Analyzing error for command: $originalCommand")
        Log.d(TAG, "ExitCode: $exitCode, Stderr: $stderr")
        
        // 分析错误类型
        val (errorType, rootCause, suggestedFix) = analyzeErrorType(originalCommand, exitCode, stdout, stderr)
        
        // 生成修复命令
        val fixedCommand = generateFixedCommand(originalCommand, errorType, stderr)
        
        return ErrorAnalysis(
            originalCommand = originalCommand,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            errorType = errorType,
            rootCause = rootCause,
            suggestedFix = suggestedFix,
            fixedCommand = fixedCommand,
            confidence = calculateConfidence(errorType, stderr)
        )
    }
    
    /**
     * 分析错误类型
     */
    private fun analyzeErrorType(
        originalCommand: String,
        exitCode: Int,
        stdout: String,
        stderr: String
    ): Triple<ErrorType, String, String> {
        val combinedOutput = stdout + stderr
        
        // 1. 权限问题
        if (combinedOutput.contains("Permission denied", ignoreCase = true) ||
            combinedOutput.contains("Operation not permitted", ignoreCase = true) ||
            combinedOutput.contains("Not allowed", ignoreCase = true)) {
            
            if (combinedOutput.contains("SELinux", ignoreCase = true)) {
                return Triple(
                    ErrorType.SELINUX_BLOCKED,
                    "SELinux 策略阻止了操作执行",
                    "尝试执行 'setenforce 0' 临时关闭 SELinux，或使用 magisk 模块修改 SELinux 策略"
                )
            }
            
            if (combinedOutput.contains("Read-only file system", ignoreCase = true)) {
                return Triple(
                    ErrorType.PARTITION_READ_ONLY,
                    "目标分区为只读状态",
                    "尝试执行 'mount -o remount,rw <partition>' 重新挂载为读写"
                )
            }
            
            return Triple(
                ErrorType.PERMISSION_DENIED,
                "权限不足，可能需要 Root 权限或更高权限",
                "尝试使用 Root 权限执行，或调整命令参数"
            )
        }
        
        // 2. 命令未找到
        if (combinedOutput.contains("not found", ignoreCase = true) ||
            combinedOutput.contains("No such file or directory", ignoreCase = true) ||
            exitCode == 127) {
            
            return Triple(
                ErrorType.COMMAND_NOT_FOUND,
                "命令或文件不存在",
                "检查命令路径是否正确，或安装缺失的工具"
            )
        }
        
        // 3. 无效参数
        if (combinedOutput.contains("Invalid argument", ignoreCase = true) ||
            combinedOutput.contains("invalid option", ignoreCase = true) ||
            combinedOutput.contains("Unknown option", ignoreCase = true)) {
            
            return Triple(
                ErrorType.INVALID_ARGUMENT,
                "命令参数不正确",
                "检查命令参数，参考命令帮助文档"
            )
        }
        
        // 4. pm disable 特定问题
        if (originalCommand.contains("pm disable", ignoreCase = true)) {
            if (combinedOutput.contains("--user", ignoreCase = true) ||
                combinedOutput.contains("user 0", ignoreCase = true)) {
                return Triple(
                    ErrorType.VERSION_INCOMPATIBLE,
                    "高版本 Android 需要指定用户 ID",
                    "使用 'pm disable-user --user 0 <package>' 替代原命令"
                )
            }
        }
        
        // 5. 版本不兼容
        if (combinedOutput.contains("requires API", ignoreCase = true) ||
            combinedOutput.contains("SDK version", ignoreCase = true) ||
            combinedOutput.contains("not supported", ignoreCase = true)) {
            
            return Triple(
                ErrorType.VERSION_INCOMPATIBLE,
                "命令与当前 Android 版本不兼容",
                "查找适用于当前系统版本的替代方案"
            )
        }
        
        // 6. 文件相关问题
        if (combinedOutput.contains("No such file or directory", ignoreCase = true) &&
            originalCommand.containsAny(listOf("rm", "cat", "dd", "cp"))) {
            
            return Triple(
                ErrorType.FILE_NOT_FOUND,
                "目标文件不存在",
                "检查文件路径是否正确，或确认文件已创建"
            )
        }
        
        if (combinedOutput.contains("File exists", ignoreCase = true)) {
            return Triple(
                ErrorType.FILE_EXISTS,
                "目标文件已存在",
                "使用 -f 参数强制覆盖，或先删除已存在的文件"
            )
        }
        
        // 7. 网络问题
        if (combinedOutput.contains("Network", ignoreCase = true) ||
            combinedOutput.contains("Connection", ignoreCase = true) ||
            combinedOutput.contains("timeout", ignoreCase = true)) {
            
            return Triple(
                ErrorType.NETWORK_ERROR,
                "网络连接问题",
                "检查网络连接，或稍后重试"
            )
        }
        
        // 未知错误
        return Triple(
            ErrorType.UNKNOWN,
            "未知错误，请检查原始输出",
            "手动检查命令和系统状态"
        )
    }
    
    /**
     * 生成修复命令
     */
    private fun generateFixedCommand(
        originalCommand: String,
        errorType: ErrorType,
        stderr: String
    ): String? {
        return when (errorType) {
            ErrorType.PERMISSION_DENIED -> {
                if (!originalCommand.startsWith("su")) {
                    "su -c \"$originalCommand\""
                } else {
                    null
                }
            }
            
            ErrorType.PARTITION_READ_ONLY -> {
                if (originalCommand.contains("/system")) {
                    "mount -o remount,rw /system && $originalCommand"
                } else if (originalCommand.contains("/vendor")) {
                    "mount -o remount,rw /vendor && $originalCommand"
                } else {
                    null
                }
            }
            
            ErrorType.VERSION_INCOMPATIBLE -> {
                // pm disable 的特殊处理
                if (originalCommand.contains("pm disable ")) {
                    val pkg = originalCommand.substringAfter("pm disable ").trim()
                    "pm disable-user --user 0 $pkg"
                } else if (originalCommand.contains("pm enable ")) {
                    val pkg = originalCommand.substringAfter("pm enable ").trim()
                    "pm enable --user 0 $pkg"
                } else {
                    null
                }
            }
            
            ErrorType.FILE_EXISTS -> {
                if (originalCommand.startsWith("cp ")) {
                    originalCommand.replaceFirst("cp ", "cp -f ")
                } else if (originalCommand.startsWith("mv ")) {
                    originalCommand.replaceFirst("mv ", "mv -f ")
                } else if (originalCommand.startsWith("dd ")) {
                    // dd 通常覆盖，不需要修改
                    originalCommand
                } else {
                    null
                }
            }
            
            ErrorType.SELINUX_BLOCKED -> {
                "setenforce 0 && $originalCommand"
            }
            
            else -> {
                // 尝试一些常见的修复
                when {
                    originalCommand.startsWith("pm disable ") -> {
                        val pkg = originalCommand.substringAfter("pm disable ").trim()
                        "pm disable-user --user 0 $pkg"
                    }
                    originalCommand.startsWith("rm ") -> {
                        if (!originalCommand.contains("-f")) {
                            originalCommand.replaceFirst("rm ", "rm -f ")
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
        }
    }
    
    /**
     * 计算置信度
     */
    private fun calculateConfidence(errorType: ErrorType, stderr: String): Float {
        return when (errorType) {
            ErrorType.PERMISSION_DENIED -> 0.9f
            ErrorType.COMMAND_NOT_FOUND -> 0.85f
            ErrorType.INVALID_ARGUMENT -> 0.8f
            ErrorType.FILE_NOT_FOUND -> 0.85f
            ErrorType.FILE_EXISTS -> 0.9f
            ErrorType.SELINUX_BLOCKED -> 0.75f
            ErrorType.PARTITION_READ_ONLY -> 0.85f
            ErrorType.VERSION_INCOMPATIBLE -> 0.8f
            ErrorType.NETWORK_ERROR -> 0.7f
            ErrorType.UNKNOWN -> 0.3f
        }
    }
    
    /**
     * 生成用户友好的错误报告
     */
    fun generateUserFriendlyErrorReport(errorAnalysis: ErrorAnalysis): String {
        return buildString {
            appendLine("❌ 操作失败")
            appendLine()
            appendLine("【问题分析】")
            appendLine("${errorAnalysis.errorType}: ${errorAnalysis.rootCause}")
            appendLine()
            appendLine("【原始命令】")
            appendLine(errorAnalysis.originalCommand)
            appendLine()
            appendLine("【错误输出】")
            if (errorAnalysis.stderr.isNotBlank()) {
                appendLine(errorAnalysis.stderr.trim())
            } else {
                appendLine("无详细错误输出")
            }
            appendLine()
            appendLine("【建议方案】")
            appendLine(errorAnalysis.suggestedFix)
            appendLine()
            
            if (errorAnalysis.fixedCommand != null) {
                appendLine("【修复命令】")
                appendLine(errorAnalysis.fixedCommand)
                appendLine("是否让我尝试自动修复？")
            }
        }
    }
}

// ==================== 扩展函数 ====================

private fun String.containsAny(strings: List<String>): Boolean {
    return strings.any { this.contains(it, ignoreCase = true) }
}
