package com.apex.agent

import android.app.Application
import com.apex.core.application.ApexApplication

class SimpleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApexApplication.init(this)
    }
}
