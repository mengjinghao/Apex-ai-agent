package com.apex.agent.application.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * ViewModel 作用域 Hilt 模块
 *
 * 使用 @HiltViewModel 注解的 ViewModel 会自动由 Hilt 注入，无需在此显式绑定。
 * 本模块作为扩展预留，若将来需要绑定 ViewModel 接口或自定义 ViewModelFactory 可在此添加。
 *
 * 当前项目中通过 @HiltViewModel 自动注入的 ViewModel 列表：
 * - [com.apex.agent.presentation.main.MainViewModel]
 * - [com.apex.agent.presentation.multiagent.MultiAgentViewModel]
 */
@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule
