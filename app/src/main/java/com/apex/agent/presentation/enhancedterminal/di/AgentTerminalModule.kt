package com.apex.agent.presentation.enhancedterminal.di

import android.content.Context
import com.ai.assistance.aiterminal.terminal.ai.AgentTerminalExecutor
import com.ai.assistance.aiterminal.terminal.ai.AgentTerminalToolProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module — 提供 Agent 终端工具的依赖注入
 *
 * Agent 系统可通过 直接获取 [AgentTerminalExecutor] 和 [AgentTerminalToolProvider],
 * 无需手动创建。
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentTerminalModule {

    @Provides
    @Singleton
    fun provideAgentTerminalExecutor(@ApplicationContext context: Context): AgentTerminalExecutor {
        return AgentTerminalExecutor(context)
    }

    @Provides
    @Singleton
    fun provideAgentTerminalToolProvider(@ApplicationContext context: Context): AgentTerminalToolProvider {
        return AgentTerminalToolProvider(context)
    }
}
