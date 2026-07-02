package com.apex.sdk.common

/**
 * 多 APK 套件全局常量。
 *
 * - 所有 APK 必须使用同一签名 + 同一 [SHARED_USER_ID] 才能共享 UID / 共享进程 / 共享权限。
 * - 主进程名 [MAIN_PROCESS] 是“零延迟”通信的关键：所有进入该进程的 APK 组件，
 *   相互调用就是 JVM 内的方法调用，无 Binder 序列化开销。
 * - 终端 APK 因为含有 C++/PTY 与持续 IO，允许独立到 [TERMINAL_PROCESS]，
 *   通过 [LocalSocket] 桥接，避免 native crash 拖垮主进程。
 */
object ApexSuite {

    /** 所有 APK 共享的 Linux UID。需配合相同签名才生效。 */
    const val SHARED_USER_ID = "com.apex.agent.suite"

    /** 主进程名 — 绝大多数 APK 的 Activity/Service 都进入该进程。 */
    const val MAIN_PROCESS = "com.apex.agent.mainprocess"

    /** 终端独立进程名 — 重 IO + native，独立进程提升稳定性。 */
    const val TERMINAL_PROCESS = "com.apex.agent.terminal"

    /** 诊断独立进程名 — 日志采集独立，避免互相影响。 */
    const val DIAGNOSTICS_PROCESS = "com.apex.agent.diagnostics"

    /** 跨 APK LocalSocket 命名空间前缀。 */
    const val LOCAL_SOCKET_NAMESPACE = "apex.agent"

    /** 看门狗心跳间隔（毫秒）。 */
    const val WATCHDOG_HEARTBEAT_INTERVAL_MS = 5_000L

    /** 看门狗超时阈值（毫秒），超过即判定对方 APK 已死，触发自愈。 */
    const val WATCHDOG_TIMEOUT_MS = 15_000L

    /** 各 APK 在 [ApkIdentity] 中的注册 ID。 */
    object ApkId {
        const val MAIN = "main"
        const val ENGINE = "engine"
        const val RAGE = "rage"
        const val MULTI_AGENT = "multi-agent"
        const val WORKFLOW = "workflow"
        const val MARKET = "market"
        const val TERMINAL = "terminal"
        const val WORKING_FILES = "working-files"
        const val DIAGNOSTICS = "diagnostics"
        const val VOICE = "voice"
    }
}
