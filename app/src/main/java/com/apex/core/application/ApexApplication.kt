package com.apex.core.application

import android.content.Context

object ApexApplication {
    @Volatile
    private var appContext: Context? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
    }
    
    val context: Context
        get() = appContext ?: throw IllegalStateException("ApexApplication not initialized")
    
    val isDebug: Boolean = false
    val appStartupTimeMs: Long = System.currentTimeMillis()
    val instance: ApexApplication get() = this
}
