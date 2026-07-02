# 命令历史记录系统使用指南

## 概述

命令历史记录系统是一个独立的管理类，用于跟踪终端会话的命令执行历史，提供命令使用频率统计和历史数据持久化功能。

## 核心功能

1. **命令历史记录**：记录每个会话的命令执行历史
2. **命令使用频率统计**：统计命令的使用次数和频率
3. **历史数据持久化**：将历史数据保存到文件，重启后自动加载
4. **会话管理**：为每个会话维护独立的命令历史
5. **统计和分析**：提供命令使用统计、成功率分析等功能

## 使用方法

### 获取历史记录管理器实例

```kotlin
val historyManager = CommandHistoryManager.instance
```

### 记录命令执行

当执行命令时，系统会自动记录命令历史。如果需要手动记录命令，可以使用以下方法：

```kotlin
// 记录命令，exitCode默认为0（成功）
historyManager.recordCommand(sessionId, command)

// 记录命令并指定退出码
historyManager.recordCommand(sessionId, command, exitCode)
```

### 获取命令历史

```kotlin
// 获取指定会话的命令历史，默认返回最近100条
val sessionHistory = historyManager.getSessionHistory(sessionId)

// 获取所有会话的命令历史，默认返回最近500条
val allHistory = historyManager.getAllHistory()
```

### 搜索命令历史

```kotlin
// 搜索包含特定关键字的命令历史
val searchResults = historyManager.searchHistory("ls")
```

### 命令频率统计

```kotlin
// 获取所有命令的使用频率
val frequency = historyManager.getCommandFrequency()

// 获取最常用的命令，默认返回前10个
val mostUsed = historyManager.getMostUsedCommands()
```

### 统计和分析

```kotlin
// 获取命令使用统计信息
val stats = historyManager.getCommandStatistics()
println("总命令数: {stats.totalCommands}")
println("唯一命令数: {stats.uniqueCommands}")
println("最常用命令: {stats.mostUsedCommand} ({stats.mostUsedCommandCount}次)")
println("平均每个会话的命令数: {stats.averageCommandsPerSession}")
println("会话数: {stats.sessionCount}")

// 分析命令执行成功率
val successRates = historyManager.analyzeCommandSuccessRate()
successRates.forEach { (command, rate) ->
    println("命令: command, 成功率: rate%")
}

// 按时间段分析命令使用情况
val startTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 24小时前
val endTime = System.currentTimeMillis()
val timeRangeAnalysis = historyManager.analyzeCommandsByTimeRange(startTime, endTime)
```

### 清除历史记录

```kotlin
// 清除指定会话的历史记录
historyManager.clearSessionHistory(sessionId)

// 清除所有历史记录
historyManager.clearAllHistory()
```

## 数据持久化

历史数据会自动保存到以下路径：

```
/data/data/com.ai.assistance.aiterminal/files/command_history.json
```

系统启动时会自动加载历史数据，确保历史记录的连续性。

## 示例代码

```kotlin
// 示例：使用历史记录管理器
val historyManager = CommandHistoryManager.instance

// 记录命令
historyManager.recordCommand("session1", "ls -la")
historyManager.recordCommand("session1", "pwd")
historyManager.recordCommand("session1", "ls -la") // 重复命令

// 获取历史记录
val history = historyManager.getSessionHistory("session1")
println("会话1的命令历史:")
history.forEach { record ->
    println("[{record.formattedTime}] {record.command} (退出码: {record.exitCode})")
}

// 获取频率统计
val frequency = historyManager.getCommandFrequency()
println("命令使用频率:")
frequency.forEach { (command, count) ->
    println("$command: $count次")
}

// 获取最常用命令
val mostUsed = historyManager.getMostUsedCommands()
println("最常用的命令:")
mostUsed.forEachIndexed { index, (command, count) ->
    println("${index + 1}. $command ($count次)")
}

// 获取统计信息
val stats = historyManager.getCommandStatistics()
println("统计信息:")
println("总命令数: {stats.totalCommands}")
println("唯一命令数: {stats.uniqueCommands}")
println("最常用命令: {stats.mostUsedCommand}")
println("最常用命令次数: {stats.mostUsedCommandCount}")
println("平均每个会话的命令数: {stats.averageCommandsPerSession}")
println("会话数: {stats.sessionCount}")
```

## 注意事项

1. 历史记录系统会自动集成到TerminalManager中，执行命令时会自动记录
2. 历史数据会定期保存，确保数据不会丢失
3. 对于长时间运行的应用，建议定期清理历史记录，避免占用过多存储空间
4. 历史记录系统使用单例模式，确保全局唯一实例

## 故障排除

如果历史记录系统出现问题，可以尝试以下方法：

1. 检查历史记录文件路径是否存在且可写
2. 清除历史记录并重新开始
3. 检查应用权限，确保有文件读写权限

## 总结

命令历史记录系统为终端提供了强大的历史管理功能，帮助用户跟踪命令执行历史，分析命令使用 patterns，并提供数据持久化能力。通过简单的API调用，用户可以轻松获取和分析命令历史数据，提高终端使用效率。