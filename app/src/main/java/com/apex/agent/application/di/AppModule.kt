package com.apex.agent.application.di

import com.apex.agent.data.repository.AgentRepositoryImpl
import com.apex.agent.data.repository.MessageRepositoryImpl
import com.apex.agent.data.repository.TaskRepositoryImpl
import com.apex.agent.data.repository.WorkflowRepositoryImpl
import com.apex.agent.domain.repository.AgentRepository
import com.apex.agent.domain.repository.MessageRepository
import com.apex.agent.domain.repository.TaskRepository
import com.apex.agent.domain.repository.WorkflowRepository
import com.apex.agent.infrastructure.eventbus.EventBus
import com.apex.agent.infrastructure.model.LocalModelClient
import com.apex.agent.infrastructure.model.ModelClient
import com.apex.agent.orchestration.collaboration.modes.DebateReviewMode
import com.apex.agent.orchestration.collaboration.modes.FreeDialogMode
import com.apex.agent.orchestration.collaboration.modes.ParallelExecutionMode
import com.apex.agent.orchestration.collaboration.modes.SerialPipelineMode
import com.apex.agent.orchestration.collaboration.modes.SupervisorExecutionMode
import com.apex.agent.orchestration.collaboration.TaskExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Application-level Hilt module.
 *
 * Binds repository interfaces to their implementations and provides
 * singleton-scoped infrastructure objects (EventBus, ModelClient, etc.).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindAgentRepository(impl: AgentRepositoryImpl): AgentRepository

    @Binds
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    abstract fun bindWorkflowRepository(impl: WorkflowRepositoryImpl): WorkflowRepository

    @Binds
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindModelClient(impl: LocalModelClient): ModelClient

    @Binds
    @Singleton
    abstract fun bindEventBus(default: EventBus.Default): EventBus

    @Binds
    @Named("supervisor")
    abstract fun bindSupervisorExecutionMode(mode: SupervisorExecutionMode): TaskExecutor

    @Binds
    @Named("serial")
    abstract fun bindSerialPipelineMode(mode: SerialPipelineMode): TaskExecutor

    @Binds
    @Named("parallel")
    abstract fun bindParallelExecutionMode(mode: ParallelExecutionMode): TaskExecutor

    @Binds
    @Named("debate")
    abstract fun bindDebateReviewMode(mode: DebateReviewMode): TaskExecutor

    @Binds
    @Named("free")
    abstract fun bindFreeDialogMode(mode: FreeDialogMode): TaskExecutor
}
