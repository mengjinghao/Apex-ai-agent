package com.apex.agent.kernel.burst

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * BurstKernel 守护服务。
 *
 * 该服务的职责是：当应用被系统拉起但没有走 Application.onCreate 完整路径时
 * （例如系统重启后只拉起 service），兜底启动 BurstKernel。
 *
 * 重要：Application.onCreate 中会先调用 BurstKernel.start(app, ..., collaborationFramework = adapter)
 * 把 adapter 注入进去；如果 service 后启动，这里只做"未启动时兜底启动"，
 * 不会覆盖已经注入的 adapter。
 */
class BurstKernelService : Service() {
    override fun onCreate() {
        super.onCreate()
        // 守卫：仅在尚未启动时才调用 start。
        // BurstKernel.start 内部也有 if (state==RUNNING) return 的兜底，
        // 这里显式判断避免任何边缘时序下覆盖已注入的 collaborationFramework。
        if (BurstKernel.getState() == KernelState.STOPPED) {
            // 兜底启动：不传 collaborationFramework（保持 SWARM 回退到本地协程池）
            // 真正的 adapter 注入由 ApexAgentApplication.onCreate 通过 Hilt 完成
            BurstKernel.start(application)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 注意：service 被销毁不等于整个应用退出。仅当 BurstKernel 真的没人用时才 stop。
        // 当前实现保守起见直接 stop —— 如果发现 service 频繁被系统回收导致 BurstKernel
        // 反复启停，可改为引用计数或长期持有。
        if (BurstKernel.getState() == KernelState.RUNNING) {
            BurstKernel.stop()
        }
        super.onDestroy()
    }
}
