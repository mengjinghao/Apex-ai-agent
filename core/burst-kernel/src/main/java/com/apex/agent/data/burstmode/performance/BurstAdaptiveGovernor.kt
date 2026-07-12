package com.apex.agent.data.burstmode.performance

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Burst 自适应调速器
 *
 * 监听设备状态（电池电量 / 充电状态 / 热状态 / CPU 占用 / 内存压力），
 * 并基于当前状态计算执行策略（最大并发数 / CPU 预算 / 内存预算 / 是否允许重度任务）。
 *
 * 设计要点：
 * 1) [currentState] 是 StateFlow<DeviceState?>，初值 null —— 调用方（BurstKernel）
 *    的 collectLatest 会忽略 null 状态，避免在采样未完成时下发错误策略
 * 2) [computeStrategy] 是同步函数，基于最近一次采样结果计算；如果还未采样过，返回默认策略
 * 3) 启动时注册电池广播 + 定时采样 CPU / 内存；[release] 时反注册广播 + 取消采样协程
 * 4) 模块化设备能力探测：低端机（coreCount <= 4 或 totalMemMb < 2048）采用更保守的策略
 *
 * 该实现是 [BurstKernel] 反馈循环的"传感器 + 决策器"组合。
 */
class BurstAdaptiveGovernor(private val app: Application) {

    companion object {
        private const val TAG = "BurstAdaptiveGovernor"
        private const val SAMPLE_INTERVAL_MS = 5_000L  // 5 秒采样一次 CPU / 内存

        // 并发上下限 —— 与 BurstTaskScheduler.MAX_CONCURRENCY_CAP (32) 对齐
        private const val MIN_MAX_CONCURRENCY = 1
        private const val MAX_MAX_CONCURRENCY = 32

        // 热状态阈值（PowerManager.THERMAL_STATUS_*）
        // 0=NONE, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=Critical, 5=Emergency, 6=Shutdown
        private const val THERMAL_LIGHT = 1
        private const val THERMAL_MODERATE = 2
        private const val THERMAL_SEVERE = 3

        // 与 BurstTaskScheduler.DEFAULT_MAX_CONCURRENCY 对齐
        private const val DEFAULT_MAX_CONCURRENCY = 3
    }

    /**
     * 当前设备状态快照。
     * null 表示尚未采样（典型出现在 Governor 刚启动时）。
     */
    data class DeviceState(
        /** 电池电量 0..100，-1 表示未知（如非手机设备） */
        val batteryLevel: Int = -1,
        val isCharging: Boolean = false,
        /** Android PowerManager.THERMAL_STATUS_* (0..6) */
        val thermalStatus: Int = 0,
        /** 估算的 CPU 占用率 0..1（基于 /proc/stat 节拍差） */
        val cpuUsage: Float = 0f,
        /** 可用内存 MB */
        val availableMemoryMb: Int = 0,
        /** 设备总内存 MB（首次采样后恒定） */
        val totalMemoryMb: Int = 0,
        /** 逻辑 CPU 核心数 */
        val cpuCoreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 执行策略 —— 基于当前 DeviceState 推导出的可调参数。
     *
     * BurstKernel 会把 [maxConcurrency] 下发给 BurstTaskScheduler；
     * 其它字段预留给未来扩展（如 LLM 推理 batch size、内存预算等）。
     */
    data class ExecutionStrategy(
        val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        /** 0..1，建议的 CPU 时间预算占比，可用于限制 LLM 推理吞吐 */
        val cpuBudgetPercent: Float = 1.0f,
        /** 建议的内存预算上限 MB；超过时调用方应主动降级 */
        val memoryBudgetMb: Int = Int.MAX_VALUE,
        /** 是否允许执行重度任务（如 SWARM 多路径冗余、C++ 加速计算） */
        val allowHeavyTasks: Boolean = true,
        /** 策略原因，便于调试与可观测 */
        val reason: String = "default"
    )

    private val _currentState = MutableStateFlow<DeviceState?>(null)
    val currentState: StateFlow<DeviceState?> = _currentState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val strategyLock = Mutex()
    private var cachedStrategy: ExecutionStrategy = ExecutionStrategy()
    private var sampleJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var released = false

    // 用于 CPU 占用差分计算的上一拍快照
    private var prevCpuTotal: Long = 0L
    private var prevCpuIdle: Long = 0L
    private var firstSample = true

    /** 设备能力画像（一次性探测） */
    private val deviceProfile: DeviceProfile by lazy { probeDeviceProfile() }

    init {
        startSampling()
    }

    // ==================== 公开 API ====================

    /**
     * 同步计算当前策略。如果还未采样过，返回基于设备画像的默认策略。
     * 该方法应在 [BurstKernel] 的 collectLatest 块里被调用，避免阻塞协程。
     */
    fun computeStrategy(): ExecutionStrategy {
        return cachedStrategy
    }

    /**
     * 释放资源：反注册广播、取消采样协程。
     * 幂等，可重复调用。
     */
    fun release() {
        if (released) return
        released = true
        runCatching {
            batteryReceiver?.let { app.unregisterReceiver(it) }
        }
        batteryReceiver = null
        sampleJob?.cancel()
        sampleJob = null
        scope.cancel()
    }

    // ==================== 内部实现 ====================

    private fun startSampling() {
        // 1) 注册电池广播 —— 充电状态变化时立即触发一次采样
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    scope.launch { try { sampleOnce(forceBatteryIntent = intent) } catch (_: Exception) {} }
                }
            }
            app.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
            )
            batteryReceiver = receiver
        }.onFailure {
            // 注册失败不影响其它采样，但记录日志
            Log.w(TAG, "registerReceiver failed: ${it.message}")
        }

        // 2) 立即采样一次 + 定时循环采样
        sampleJob = scope.launch {
            while (!released) {
                runCatching { sampleOnce() }
                    .onFailure { Log.w(TAG, "sample failed: ${it.message}") }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * 采样一次设备状态，更新 [_currentState] 和 [cachedStrategy]。
     *
     * @param forceBatteryIntent 若由广播触发，传入收到的 Intent 避免二次 sticky 广播读取
     */
    private suspend fun sampleOnce(forceBatteryIntent: Intent? = null) {
        val batteryIntent: Intent? = forceBatteryIntent
            ?: runCatching {
                // ACTION_BATTERY_CHANGED 是 sticky broadcast，registerReceiver(null, ...) 直接返回当前状态
                app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()

        val level: Int
        val isCharging: Boolean
        if (batteryIntent != null) {
            val rawLevel = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else -1
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        } else {
            level = -1
            isCharging = false
        }

        val thermalStatus: Int = runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm?.currentThermalStatus ?: 0 else 0
        }.getOrDefault(0)

        val cpuUsage = sampleCpuUsage()
        val memInfo = sampleMemoryInfo()

        val state = DeviceState(
            batteryLevel = level,
            isCharging = isCharging,
            thermalStatus = thermalStatus,
            cpuUsage = cpuUsage,
            availableMemoryMb = memInfo.first,
            totalMemoryMb = memInfo.second,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            timestamp = System.currentTimeMillis()
        )

        _currentState.value = state
        strategyLock.withLock {
            cachedStrategy = computeStrategyLocked(state)
        }
    }

    /**
     * 从 /proc/stat 读取 CPU 节拍，做差分得到占用率。
     * 第一次调用时只记录基线，返回 0f。
     */
    private fun sampleCpuUsage(): Float {
        return runCatching {
            val f = java.io.File("/proc/stat")
            if (!f.exists()) return@runCatching 0f
            val firstLine = f.bufferedReader().readLine() ?: return@runCatching 0f
            // 形如: cpu  user nice system idle iowait irq softirq steal guest guest_nice
            val parts = firstLine.split("\\s+".toRegex())
            if (parts.size < 5) return@runCatching 0f
            // parts[0] == "cpu"
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

            val total = user + nice + system + idle + iowait + irq + softirq
            val totalIdle = idle + iowait

            val usage = if (firstSample || prevCpuTotal == 0L) {
                0f
            } else {
                val totalDiff = (total - prevCpuTotal).coerceAtLeast(1L)
                val idleDiff = (totalIdle - prevCpuIdle).coerceAtLeast(0L)
                1f - (idleDiff.toFloat() / totalDiff.toFloat())
            }
            prevCpuTotal = total
            prevCpuIdle = totalIdle
            firstSample = false
            usage.coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }

    /**
     * 读取可用内存 / 总内存（MB）。
     * 使用 Debug.MemoryInfo + ActivityManager.MemoryInfo 组合，避免反射。
     */
    private fun sampleMemoryInfo(): Pair<Int, Int> {
        return runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availMb = (memInfo.availMem / (1024L * 1024L)).toInt()
            val totalMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
            availMb to totalMb
        }.getOrDefault(0 to 0)
    }

    /**
     * 一次性探测设备能力画像，用于首次采样前的默认策略。
     */
    private fun probeDeviceProfile(): DeviceProfile {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // 总内存需 ActivityManager，懒加载在第一次 sampleOnce 之前可能拿不到，故保守估计
        val totalMb = runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)

        return when {
            cores <= 4 || totalMb in 1..2048 -> DeviceProfile.LOW_END
            cores in 5..7 || totalMb in 2049..4096 -> DeviceProfile.MID_RANGE
            else -> DeviceProfile.HIGH_END
        }
    }

    /**
     * 基于当前 [state] 计算执行策略。
     * 规则表（自上而下，第一个匹配的规则生效）：
     *
     * | 条件                                              | maxConcurrency | cpuBudget | allowHeavy | reason          |
     * |---------------------------------------------------|----------------|-----------|------------|-----------------|
     * | thermalStatus >= SEVERE                           | 1              | 0.3       | false      | thermal-severe  |
     * | thermalStatus == MODERATE                         | 2              | 0.5       | false      | thermal-mod     |
     * | not charging && batteryLevel in 0..15             | 1              | 0.4       | false      | battery-critical|
     * | not charging && batteryLevel in 16..30            | 2              | 0.6       | true       | battery-low     |
     * | cpuUsage > 0.85                                   | 2              | 0.6       | true       | cpu-saturated   |
     * | availableMemoryMb < 256                           | 1              | 0.5       | false      | mem-starved     |
     * | charging && batteryLevel >= 80                    | cores (≤cap)   | 1.0       | true       | charging-full   |
     * | else                                              | (cores / 2)    | 0.8       | true       | balanced        |
     *
     * 低端机额外约束：maxConcurrency 永不超过 2，除非充电且电量 ≥ 80。
     */
    private fun computeStrategyLocked(state: DeviceState?): ExecutionStrategy {
        // 首次采样前的默认策略
        if (state == null) {
            val baseConcurrency = when (deviceProfile) {
                DeviceProfile.LOW_END -> 1
                DeviceProfile.MID_RANGE -> 2
                DeviceProfile.HIGH_END -> 3
            }
            return ExecutionStrategy(
                maxConcurrency = baseConcurrency.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY),
                cpuBudgetPercent = 0.8f,
                memoryBudgetMb = 512,
                allowHeavyTasks = deviceProfile != DeviceProfile.LOW_END,
                reason = "default-${deviceProfile.name.lowercase()}"
            )
        }

        val cores = state.cpuCoreCount
        val balancedConcurrency = (cores / 2).coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
        val highConcurrency = cores.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)

        // 规则自上而下匹配
        return when {
            state.thermalStatus >= THERMAL_SEVERE -> ExecutionStrategy(
                maxConcurrency = 1,
                cpuBudgetPercent = 0.3f,
                memoryBudgetMb = 256,
                allowHeavyTasks = false,
                reason = "thermal-severe"
            )
            state.thermalStatus == THERMAL_MODERATE -> ExecutionStrategy(
                maxConcurrency = 2,
                cpuBudgetPercent = 0.5f,
                memoryBudgetMb = 384,
                allowHeavyTasks = false,
                reason = "thermal-moderate"
            )
            !state.isCharging && state.batteryLevel in 0..15 -> ExecutionStrategy(
                maxConcurrency = 1,
                cpuBudgetPercent = 0.4f,
                memoryBudgetMb = 256,
                allowHeavyTasks = false,
                reason = "battery-critical"
            )
            !state.isCharging && state.batteryLevel in 16..30 -> ExecutionStrategy(
                maxConcurrency = 2,
                cpuBudgetPercent = 0.6f,
                memoryBudgetMb = 384,
                allowHeavyTasks = true,
                reason = "battery-low"
            )
            state.cpuUsage > 0.85f -> ExecutionStrategy(
                maxConcurrency = 2,
                cpuBudgetPercent = 0.6f,
                memoryBudgetMb = 512,
                allowHeavyTasks = true,
                reason = "cpu-saturated"
            )
            state.availableMemoryMb in 1..255 -> ExecutionStrategy(
                maxConcurrency = 1,
                cpuBudgetPercent = 0.5f,
                memoryBudgetMb = 256,
                allowHeavyTasks = false,
                reason = "memory-starved"
            )
            state.isCharging && state.batteryLevel >= 80 -> ExecutionStrategy(
                maxConcurrency = highConcurrency,
                cpuBudgetPercent = 1.0f,
                memoryBudgetMb = Int.MAX_VALUE,
                allowHeavyTasks = true,
                reason = "charging-full"
            )
            else -> ExecutionStrategy(
                maxConcurrency = balancedConcurrency,
                cpuBudgetPercent = 0.8f,
                memoryBudgetMb = 768,
                allowHeavyTasks = true,
                reason = "balanced"
            )
        }.let { strategy ->
            // 低端机额外约束
            if (deviceProfile == DeviceProfile.LOW_END &&
                !(state.isCharging && state.batteryLevel >= 80)
            ) {
                strategy.copy(
                    maxConcurrency = strategy.maxConcurrency.coerceAtMost(2),
                    allowHeavyTasks = false,
                    reason = strategy.reason + "+low-end-cap"
                )
            } else {
                strategy
            }
        }
    }

    private enum class DeviceProfile { LOW_END, MID_RANGE, HIGH_END }

}
