package com.apex.agent.application.di

import android.content.Context
import com.apex.agent.api.chat.EnhancedAIService
import com.apex.agent.core.collaboration.AgentCollaborationFramework
import com.apex.agent.core.collaboration.BurstCollaborationAdapter
import com.apex.agent.data.burstmode.swarm.IBurstCollaborationFramework
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Burst 模块的 Hilt 依赖图。
 *
 * 提供：
 * 1) [AgentCollaborationFramework] —— 单例，构造需要 (Context, AIService)。
 *    AIService 通过 [EnhancedAIService.getInstance] 拿到（framework 实际接受
 *    com.apex.agent.api.chat.llmprovider.AIService，EnhancedAIService 实现了该接口）。
 *
 * 2) [IBurstCollaborationFramework] —— 单例，由 [BurstCollaborationAdapter] 包装
 *    framework 实现。BurstKernel.start(collaborationFramework = adapter) 时注入。
 *
 * 设计选择：
 * - 这里只 @Provides 一个 IBurstCollaborationFramework（接口），不暴露
 *   BurstCollaborationAdapter 具体类型，避免下游耦合实现细节。
 * - AgentCollaborationFramework 同时被 @Provides 出来，是因为 :app 中已有两处
 *   （ApexAIIntegration / MultiAgentWorkspaceViewModel）直接 new 它们自己的实例，
 *   未来重构时可以改成 @Inject 这里的单例；本次不强行改它们，避免破坏面扩大。
 */
@Module
@InstallIn(SingletonComponent::class)
object BurstModule {

    @Provides
    @Singleton
    fun provideAgentCollaborationFramework(
        @ApplicationContext ctx: Context
    ): AgentCollaborationFramework {
        // EnhancedAIService 是单例，getInstance 内部双重检查锁
        val aiService: com.apex.agent.api.chat.llmprovider.AIService =
            EnhancedAIService.getInstance(ctx)
        return AgentCollaborationFramework(ctx, aiService)
    }

    @Provides
    @Singleton
    fun provideBurstCollaborationAdapter(
        framework: AgentCollaborationFramework
    ): IBurstCollaborationFramework {
        return BurstCollaborationAdapter(framework)
    }
}
