package com.apex.di

import android.content.Context
import com.apex.selfmodify.SelfModifyService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Hilt module — provides the [SelfModifyService] singleton and kicks off its
 * background initialization (rollback git-init + file watcher + code index).
 *
 * Per AGENT_SELF_MODIFY_SPEC §8.1 / §9 Phase 4.
 *
 * The service is injected into [com.apex.di.EngineModule.provideToolRegistry]
 * so the self-modify Agent tools (ReadSource/SearchCode/ModifyCode/CompileCheck/Rollback)
 * can be registered into the engine ToolRegistry.
 */
@Module
@InstallIn(SingletonComponent::class)
object SelfModifyModule {

    @Provides
    @Singleton
    fun provideSelfModifyService(@ApplicationContext ctx: Context): SelfModifyService {
        val svc = SelfModifyService(ctx)
        // Phase 5a: register an in-process bridge handler so that
        // `ApexClient.selfModify.readFile(...)` (and friends) route to this
        // service via ApexBridge.invoke("selfmodify/..."). The service-name key
        // MUST be "selfmodify" (all-lowercase) to match the SelfModifyClient's
        // method prefix. See AGENT_SELF_MODIFY_SPEC §8.2.
        com.apex.sdk.bridge.InProcessRegistry.register(
            "selfmodify",
            com.apex.bridge.SelfModifyBridgeHandler(svc)
        )
        // Fire-and-forget background init: git init + watcher start + full reindex.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { svc.init() }
        return svc
    }
}
