package com.apex.agent.application

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration

object GlobalLifecycleManager : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
    }

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle) = Unit
    override fun onActivityStarted(activity: android.app.Activity) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivityStopped(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() = Unit
    override fun onTrimMemory(level: Int) = Unit
}
