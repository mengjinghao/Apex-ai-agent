package com.apex.agent.common.di

import com.apex.agent.data.repository.AgentRepositoryImpl
import com.apex.agent.data.repository.MessageRepositoryImpl
import com.apex.agent.data.repository.TaskRepositoryImpl
import com.apex.agent.data.repository.WorkflowRepositoryImpl
import com.apex.agent.domain.repository.AgentRepository
import com.apex.agent.domain.repository.MessageRepository
import com.apex.agent.domain.repository.TaskRepository
import com.apex.agent.domain.repository.WorkflowRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据仓库 Hilt 模块
 *
 * 将仓库接口绑定到对应的实现类，统一管理数据层注入。
 * 所有绑定均为单例作用域，确保全局唯一仓库实例。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: AgentRepositoryImpl): AgentRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindWorkflowRepository(impl: WorkflowRepositoryImpl): WorkflowRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository
}
