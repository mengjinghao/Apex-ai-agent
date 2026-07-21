package com.apex.application

import android.app.Application
import android.util.Log
import com.apex.core.kernel.ApexKernel
import com.apex.engine.chat.ChatEngine
import com.apex.engine.tools.ToolRegistry
import com.apex.engine.tools.builtin.BuiltInTools
import dagger.hilt.android.HiltAndroidApp
import com.apex.bridge.SelfModifyBridgeHandler
import com.apex.di.SelfModifyEntryPoint
import com.apex.sdk.bridge.InProcessRegistry
import dagger.hilt.android.EntryPointAccessors

/**
 * Apex Application — @HiltAndroidApp 触发 Hilt 单例组件的代码生成。
 *
 * 迁移策略：Hilt 与既有 ServiceLocator 共存——
 *   - ServiceLocator 仍由 ApexKernel.boot() 初始化并注册 ChatEngine / ToolRegistry
 *     （已有 UI/ViewModel 通过 serviceLocator.resolve() 拿这些实例）
 *   - Hilt 提供 DatabaseRepository / ChatEngine / ToolExecutor 等单例，
 *     供 @AndroidEntryPoint / @HiltViewModel 注入
 * 未来逐步把 UI 迁移到 Hilt 注入后，可移除 ServiceLocator 注册。
 */
@HiltAndroidApp
class ApexApplication : Application() {
    companion object {
        private const val TAG = "ApexApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ApexApplication 启动 — 初始化内核")
        ApexKernel.boot(this)

        // 注册服务
        val locator = ApexKernel.serviceLocator
        locator.singleton<ChatEngine>(ChatEngine())
        
        val toolRegistry = ToolRegistry()
        BuiltInTools.createAll(this).forEach { toolRegistry.register(it) }
        locator.singleton<ToolRegistry>(toolRegistry)

        Log.i(TAG, "内核初始化完成 — ${toolRegistry.list().size} 个工具已注册")

        // 注册 SelfModify bridge handler — 让 ApexClient.selfModify.* 可用
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SelfModifyEntryPoint::class.java)
            val selfModifySvc = entryPoint.selfModifyService()
            InProcessRegistry.register("selfModify", SelfModifyBridgeHandler(selfModifySvc))
            Log.i(TAG, "SelfModify bridge handler 已注册")
        } catch (e: Exception) {
            Log.w(TAG, "SelfModify bridge 注册失败: ${e.message}")
        }
    }
}
