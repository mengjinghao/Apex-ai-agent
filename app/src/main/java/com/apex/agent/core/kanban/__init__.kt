package com.apex.agent.core.kanban

/**
 * Kanban Multi-Agent Board System
 *
 * ?Agent 看板系统，提?
 * - 看板任务分发到多个阶段列
 * - Worker 插件架构，支持动态任务路? * - ?Agent 协作，支持状态跟踪和可视? *
 * ## 核心组件
 * - [KanbanBoard] - 看板数据模型
 * - [KanbanColumn] - 列定义，支持专业 Agent 配置
 * - [KanbanTask] - 任务模型，支持状态流? * - [WorkerRegistry] - Worker 插件发现和注? * - [TaskDispatcher] - 任务分发逻辑
 * - [KanbanViewModel] - 状态跟踪和可视? *
 * ## 使用示例
 * ```kotlin
 * // 创建看板
 * val board = KanbanBoard.createDefault("我的看板")
 *
 * // 创建 Worker 注册? * val registry = WorkerRegistry.getInstance()
 *
 * // 创建任务分发? * val dispatcher = TaskDispatcher(board, registry, collabFramework)
 *
 * // 创建 ViewModel
 * val viewModel = KanbanViewModel(board, registry, dispatcher)
 *
 * // 添加任务
 * val task = viewModel.addTask("实现功能", "描述", board.columns.first().id)
 *
 * // 分发任务
 * viewModel.scope.launch {
 *     viewModel.dispatchTask(task.id)
 * }
 *
 * // 观察状? * viewModel.uiState.collect { state ->
 *     // 更新 UI
 * }
 * ```
 */

// 类型别名，便于使?typealias KanbanBoardModel = KanbanBoard
typealias KanbanColumnModel = KanbanColumn
typealias KanbanTaskModel = KanbanTask

/**
 * 获取 Kanban 单例实例
 */
object KanbanProvider {
    private var defaultBoard: KanbanBoard? = null
    private var defaultRegistry: WorkerRegistry? = null

    fun getDefaultBoard(): KanbanBoard {
        return defaultBoard ?: synchronized(this) {
            defaultBoard ?: KanbanBoard.createDefault().also { defaultBoard = it }
        }
    }

    fun getDefaultRegistry(): WorkerRegistry {
        return defaultRegistry ?: synchronized(this) {
            defaultRegistry ?: WorkerRegistry.getInstance().also { defaultRegistry = it }
        }
    }

    fun reset() {
        defaultBoard = null
        defaultRegistry = null
    }
}
