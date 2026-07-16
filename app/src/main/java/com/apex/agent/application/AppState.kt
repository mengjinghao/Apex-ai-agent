package com.apex.agent.application

import android.app.Application
import android.content.Context

object AppState {

    private var application: Application? = null

    val context: Context
        get() = application?.applicationContext ?: throw IllegalStateException("AppState not initialized")

    fun initialize(application: Application) {
        this.application = application
    }
}
