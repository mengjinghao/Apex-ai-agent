package com.apex.agent.core.scheduler

/**
 * Cron 调度系统包初始化文件
 * 
 * 提供完整的定时任务调度功支持:
 * - 自然语言 cron 表达式解? * - 多平台任务投?(Telegram, Discord, Email 等）
 * - 多种任务类型 (日报、备份、审计、自动报告等? * - WorkManager 后台调度
 * 
 * 主要组件:
 * - CronExpressionParser: 自然语言 cron 解析
 * - ScheduledTask: 定时任务数据模型
 * - CronScheduler: 调度器核? * - MultiPlatformDelivery: 多平台投? * - TaskTypeRegistry: 任务类型注册? */

// 导出主要?typealias CronScheduler = com.apex.agent.core.scheduler.CronScheduler
typealias CronExpressionParser = com.apex.agent.core.scheduler.CronExpressionParser
typealias ScheduledTask = com.apex.agent.core.scheduler.ScheduledTask
typealias MultiPlatformDelivery = com.apex.agent.core.scheduler.MultiPlatformDelivery
typealias TaskTypeRegistry = com.apex.agent.core.scheduler.TaskTypeRegistry
typealias CronWorker = com.apex.agent.core.scheduler.CronWorker

/**
 * Cron 调度系统版本
 */
const val CRON_SCHEDULER_VERSION = "1.0.0"
