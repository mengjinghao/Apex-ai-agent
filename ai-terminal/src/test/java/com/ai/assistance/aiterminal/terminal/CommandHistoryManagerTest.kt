package com.ai.assistance.aiterminal.terminal

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandHistoryManagerTest {
    private lateinit var historyManager: CommandHistoryManager
    private val sessionId1 = "session1"
    private val sessionId2 = "session2"
    
    @Before
    fun setUp() {
        // 获取单例实例
        historyManager = CommandHistoryManager.instance
        // 清除所有历史，确保测试环境干净
        historyManager.clearAllHistory()
    }
    
    @Test
    fun testRecordCommand() {
        // 记录命令
        val command1 = "ls -la"
        val command2 = "pwd"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        
        // 验证历史记录
        val history = historyManager.getSessionHistory(sessionId1)
        assertEquals(2, history.size)
        assertEquals(command1, history[0].command)
        assertEquals(command2, history[1].command)
    }
    
    @Test
    fun testCommandFrequency() {
        // 记录重复命令
        val command1 = "ls -la"
        val command2 = "pwd"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        historyManager.recordCommand(sessionId1, command1) // 重复命令
        
        // 验证频率统计
        val frequency = historyManager.getCommandFrequency()
        assertEquals(2, frequency[command1])
        assertEquals(1, frequency[command2])
    }
    
    @Test
    fun testGetMostUsedCommands() {
        // 记录多个命令
        val command1 = "ls -la"
        val command2 = "pwd"
        val command3 = "cd .."
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command3)
        
        // 验证最常用命令
        val mostUsed = historyManager.getMostUsedCommands(2)
        assertEquals(2, mostUsed.size)
        assertEquals(command1, mostUsed[0].first)
        assertEquals(3, mostUsed[0].second)
        assertEquals(command2, mostUsed[1].first)
        assertEquals(1, mostUsed[1].second)
    }
    
    @Test
    fun testSearchHistory() {
        // 记录命令
        val command1 = "ls -la"
        val command2 = "pwd"
        val command3 = "ls -l"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        historyManager.recordCommand(sessionId1, command3)
        
        // 搜索包含"ls"的命令
        val results = historyManager.searchHistory("ls")
        assertEquals(2, results.size)
        assertTrue(results.any { it.command == command1 })
        assertTrue(results.any { it.command == command3 })
    }
    
    @Test
    fun testClearSessionHistory() {
        // 记录命令
        val command1 = "ls -la"
        val command2 = "pwd"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        historyManager.recordCommand(sessionId2, command1) // 不同会话
        
        // 清除会话1的历史
        historyManager.clearSessionHistory(sessionId1)
        
        // 验证会话1的历史已清除
        val history1 = historyManager.getSessionHistory(sessionId1)
        assertEquals(0, history1.size)
        
        // 验证会话2的历史仍然存在
        val history2 = historyManager.getSessionHistory(sessionId2)
        assertEquals(1, history2.size)
    }
    
    @Test
    fun testClearAllHistory() {
        // 记录命令
        val command1 = "ls -la"
        val command2 = "pwd"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId2, command2)
        
        // 清除所有历史
        historyManager.clearAllHistory()
        
        // 验证所有历史已清除
        val history1 = historyManager.getSessionHistory(sessionId1)
        val history2 = historyManager.getSessionHistory(sessionId2)
        val frequency = historyManager.getCommandFrequency()
        
        assertEquals(0, history1.size)
        assertEquals(0, history2.size)
        assertEquals(0, frequency.size)
    }
    
    @Test
    fun testCommandStatistics() {
        // 记录命令
        val command1 = "ls -la"
        val command2 = "pwd"
        
        historyManager.recordCommand(sessionId1, command1)
        historyManager.recordCommand(sessionId1, command2)
        historyManager.recordCommand(sessionId2, command1)
        
        // 获取统计信息
        val stats = historyManager.getCommandStatistics()
        
        assertEquals(3, stats.totalCommands)
        assertEquals(2, stats.uniqueCommands)
        assertEquals(command1, stats.mostUsedCommand)
        assertEquals(2, stats.mostUsedCommandCount)
        assertEquals(1.5, stats.averageCommandsPerSession)
        assertEquals(2, stats.sessionCount)
    }
    
    @Test
    fun testAnalyzeCommandSuccessRate() {
        // 记录命令，包含成功和失败的情况
        historyManager.recordCommand(sessionId1, "ls -la", 0) // 成功
        historyManager.recordCommand(sessionId1, "pwd", 0) // 成功
        historyManager.recordCommand(sessionId1, "invalid command", 1) // 失败
        historyManager.recordCommand(sessionId1, "ls -la", 0) // 成功
        
        // 分析成功率
        val successRates = historyManager.analyzeCommandSuccessRate()
        
        assertEquals(100.0, successRates["ls -la"])
        assertEquals(100.0, successRates["pwd"])
        assertEquals(0.0, successRates["invalid command"])
    }
}