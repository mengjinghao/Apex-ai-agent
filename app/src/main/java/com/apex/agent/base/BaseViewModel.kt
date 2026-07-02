package com.apex.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel 基类，提供通用的加载状态、错误处理和协程管理功能。
 *
 * 使用示例：
 * ```
 * class UserViewModel : BaseViewModel() {
 *     val users = mutableStateListOf<User>()
 *
 *     fun loadUsers() = launchWithLoading {
 *         val result = userRepository.getUsers()
 *         users.clear()
 *         users.addAll(result)
 *     }
 * }
 * ```
 */
abstract class BaseViewModel : ViewModel() {

    /** 加载状态 - 是否正在加载中 */
    private val _loadingState = MutableStateFlow(false)
    val loadingState: StateFlow<Boolean> = _loadingState.asStateFlow()

    /** 错误事件流 - 一次性错误消息 */
    private val _errorState = MutableSharedFlow<String>(replay = 0)
    val errorState: SharedFlow<String> = _errorState.asSharedFlow()

    /** 显示加载状态 */
    protected fun showLoading() {
        _loadingState.value = true
    }

    /** 隐藏加载状态 */
    protected fun hideLoading() {
        _loadingState.value = false
    }

    /**
     * 发送错误消息到错误事件流。
     *
     * @param message 错误消息内容
     */
    protected fun postError(message: String) {
        viewModelScope.launch {
            _errorState.emit(message)
        }
    }

    /**
     * 安全启动协程，自动捕获异常并通过 [postError] 发送错误消息。
     *
     * @param onError 自定义错误处理函数，如果提供则不会调用 [postError]
     * @param block 协程体
     * @return 协程的 Job
     */
    protected fun launchSafe(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: Throwable) {
            if (onError != null) {
                onError(e)
            } else {
                postError(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 启动协程并自动管理加载状态。
     * 协程开始时显示加载，结束时隐藏加载，异常时发送错误消息。
     *
     * @param onError 自定义错误处理函数
     * @param block 协程体
     * @return 协程的 Job
     */
    protected fun launchWithLoading(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch {
        try {
            _loadingState.value = true
            block()
        } catch (e: Throwable) {
            if (onError != null) {
                onError(e)
            } else {
                postError(e.message ?: "未知错误")
            }
        } finally {
            _loadingState.value = false
        }
    }

    /**
     * 在 IO 调度器上启动协程，适用于网络请求、数据库操作等耗时任务。
     *
     * @param onError 自定义错误处理函数
     * @param block 协程体
     * @return 协程的 Job
     */
    protected fun launchOnIO(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            block()
        } catch (e: Throwable) {
            if (onError != null) {
                onError(e)
            } else {
                postError(e.message ?: "未知错误")
            }
        }
    }
}
