package com.apex.application

import android.app.Application
import android.util.Log
import com.apex.core.kernel.ApexKernel
import com.apex.engine.chat.ChatEngine
import com.apex.engine.tools.ToolRegistry
import com.apex.engine.tools.builtin.BuiltInTools

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
    }
}
