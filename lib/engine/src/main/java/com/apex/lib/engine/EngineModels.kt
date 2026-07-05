package com.apex.lib.engine

/**
 * 引擎领域模型集合。
 *
 * 本文件集中定义 `:lib:engine` 对外暴露的纯数据模型：
 *   - [ShellResult]：Shell/工具执行的统一返回结构
 *   - [ToolDescriptor]：工具元数据描述
 *   - [ContainerStatusInfo]：容器运行状态
 *   - [DeviceInfo]：设备硬件/系统信息
 *   - [ShizukuCommandResult]：Shizuku 通道执行的返回结构
 *
 * 这些模型原本散落在 `:apk:engine` 的 `EngineServiceFacade` 中，
 * 现提取到 lib 层供编排器、网关契约、跨进程序列化共同复用。
 */

/**
 * Shell / 工具执行的统一结果。
 *
 * @property stdout 标准输出
 * @property stderr 标准错误
 * @property exitCode 退出码（0 表示成功）
 * @property success 是否成功
 * @property executionTimeMs 执行耗时（毫秒）
 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean,
    val executionTimeMs: Long
) {
    /** 便捷判断：等价于 [success]。 */
    val isOk: Boolean get() = success
}

/**
 * 工具元数据描述。
 *
 * @property name 工具名（唯一）
 * @property description 工具用途说明
 * @property category 分类（如 "file" / "network" / "system" / "process" / "code"）
 * @property parameters 参数列表（工具自描述，便于上层做参数校验/提示）
 * @property requiresRoot 是否需要 root 权限
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val category: String,
    val parameters: List<String>,
    val requiresRoot: Boolean
)

/**
 * 容器运行状态信息。
 *
 * statusCode 与底层 `:engine` 模块约定一致：
 *   - `0`：STOPPED（已停止）
 *   - `1`：STARTING（启动中）
 *   - `2`：RUNNING（运行中）
 *   - `-1`：ERROR（异常）
 *
 * @property statusCode 状态码
 * @property statusMessage 状态描述文本
 * @property pid 容器进程 PID（未运行时为 0/-1）
 * @property startTime 启动时间戳（毫秒）
 * @property rootfsPath 容器根文件系统路径
 */
data class ContainerStatusInfo(
    val statusCode: Int,
    val statusMessage: String,
    val pid: Int,
    val startTime: Long,
    val rootfsPath: String
) {
    /** 是否运行中。 */
    val isRunning: Boolean get() = statusCode == 2
    /** 是否启动中。 */
    val isStarting: Boolean get() = statusCode == 1
    /** 是否已停止。 */
    val isStopped: Boolean get() = statusCode == 0
    /** 是否异常。 */
    val isError: Boolean get() = statusCode == -1
}

/**
 * 设备硬件 / 系统信息。
 *
 * @property brand 品牌（Build.BRAND）
 * @property model 型号（Build.MODEL）
 * @property sdkInt Android SDK 版本号
 * @property release Android 版本名
 * @property manufacturer 厂商
 * @property abis 支持的 ABI 列表
 */
data class DeviceInfo(
    val brand: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val abis: List<String>
)

/**
 * Shizuku 通道执行结果。
 *
 * lib 层独立于 `:engine` 的 `ShizukuManager.CommandResult`，
 * 保持 lib 纯 Kotlin 无 Android 依赖，便于单测。
 *
 * @property exitCode 退出码
 * @property output 标准输出
 * @property error 标准错误
 */
data class ShizukuCommandResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {
    /** 是否成功（exitCode == 0）。 */
    val isSuccess: Boolean get() = exitCode == 0
}
