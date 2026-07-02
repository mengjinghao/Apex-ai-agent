package com.apex.services

import android.os.Handler
import android.os.Looper
import com.apex.util.AppLogger
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Service生命周期所有者类，提供Compose所需的生命周期管�?* 用于为没有自然生命周期的服务组件提供Compose所需的生命周期管�?*/
class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val TAG = "ServiceLifecycleOwner"
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreField = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        // 确保在主线程上初始化
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 在主线程上，直接初始�?           savedStateRegistryController.performRestore(null)
        } else {
            // 如果不在主线程上，使用Handler将初始化转到主线�?           AppLogger.w(TAG, "Initializing ServiceLifecycleOwner not on main thread. Moving to main thread.")
            mainHandler.post { savedStateRegistryController.performRestore(null) }
        }
    }
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
        
    override val viewModelStore: ViewModelStore
        get() = viewModelStoreField
        
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
        
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        // 确保生命周期事件在主线程上处�?       if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.handleLifecycleEvent(event)
        } else {
            // 如果不在主线程上，使用Handler将调用转到主线程
            mainHandler.post { lifecycleRegistry.handleLifecycleEvent(event) }
        }
    }
} 