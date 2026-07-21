package com.apex.di

import com.apex.selfmodify.SelfModifyService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * EntryPoint for accessing [SelfModifyService] from non-Hilt code (e.g. ApexApplication.onCreate).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SelfModifyEntryPoint {
    fun selfModifyService(): SelfModifyService
}
