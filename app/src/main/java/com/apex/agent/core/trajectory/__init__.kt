package com.apex.agent.core.trajectory

/**
 * 轨迹压缩系统包初始化文件
 * 
 * 提供完整的轨迹压缩功能，用于强化学习训练数据处理
 * 
 * 主要组件�? * - TrajectoryData: 轨迹数据结构
 * - CompressionStrategy: 首尾保护压缩策略
 * - MiddleCompression: 中间轮次压缩
 * - ToolPairPreserver: 工具调用配对保持
 * - TrajectoryCompressor: 主压缩器
 */

// 导出主要�?typealias TrajectoryCompressor = com.apex.agent.core.trajectory.TrajectoryCompressor
typealias TrajectoryData = com.apex.agent.core.trajectory.TrajectoryData
typealias TrajectoryTurn = com.apex.agent.core.trajectory.TrajectoryTurn
typealias CompressionStrategy = com.apex.agent.core.trajectory.CompressionStrategy
typealias MiddleCompression = com.apex.agent.core.trajectory.MiddleCompression
typealias ToolPairPreserver = com.apex.agent.core.trajectory.ToolPairPreserver
typealias TokenBudget = com.apex.agent.core.trajectory.TokenBudget
typealias CompressionResult = com.apex.agent.core.trajectory.CompressionResult
typealias CompressedRegion = com.apex.agent.core.trajectory.CompressedRegion
typealias TrajectoryPartition = com.apex.agent.core.trajectory.TrajectoryPartition
typealias TrajectoryStats = com.apex.agent.core.trajectory.TrajectoryStats
typealias ToolCallPair = com.apex.agent.core.trajectory.ToolCallPair
typealias CompressionQualityReport = com.apex.agent.core.trajectory.CompressionQualityReport
typealias ProtectionPlan = com.apex.agent.core.trajectory.ProtectionPlan
typealias StrategyPreset = com.apex.agent.core.trajectory.StrategyPreset
typealias PairValidationResult = com.apex.agent.core.trajectory.PairValidationResult
typealias PairStats = com.apex.agent.core.trajectory.PairStats

/**
 * 轨迹压缩系统版本
 */
const val TRAJECTORY_COMPRESSOR_VERSION = "1.0.0"
