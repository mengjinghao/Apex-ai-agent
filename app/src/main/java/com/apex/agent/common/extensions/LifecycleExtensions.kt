package com.apex.agent.common.extensions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.whenStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * [LifecycleOwner] 的扩展函数集合，提供生命周期安全的协程和 Flow 操作方法。
 */

/**
 * 在 [LifecycleOwner] 的 Lifecycle 处于 STARTED 状态时重复执行 [block]。
 * 当 Lifecycle 降到 STARTED 以下时自动取消协程，回到 STARTED 时重新启动。
 * 适用于需要一直观察数据并在前台更新 UI 的场景。
 *
 * @param block 要重复执行的可挂起代码块
 * @return 启动的 [Job]
 */
fun LifecycleOwner.repeatOnStarted(block: suspend CoroutineScope.() -> Unit): Job {
    return lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            block()
        }
    }
}

/**
 * 将 [Flow] 与 [LifecycleOwner] 的生命周期绑定，
 * 仅在 Lifecycle 处于 STARTED 状态时收集 Flow 数据。
 * 当 Lifecycle 降到 STARTED 以下时自动取消收集，回到 STARTED 时恢复收集。
 *
 * @param flow 待收集的 Flow
 * @return 生命周期安全的 Flow
 */
fun <T> LifecycleOwner.flowWithLifecycle(flow: Flow<T>): Flow<T> = callbackFlow {
    val job = lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { value ->
                send(value)
            }
        }
    }
    job.invokeOnCompletion { close(it) }
    awaitClose { job.cancel() }
}

/**
 * 在 [LifecycleOwner] 的 Lifecycle 至少为 STARTED 状态下执行 [block]。
 * 如果当前 Lifecycle 低于 STARTED，则等待直到状态变为 STARTED 后再执行。
 * 适合只需要执行一次且需确保在前台运行的场景。
 *
 * @param block 要执行的可挂起代码块
 * @return 启动的 [Job]
 */
fun LifecycleOwner.launchWhenStarted(block: suspend CoroutineScope.() -> Unit): Job {
    return lifecycleScope.launch {
        lifecycle.whenStarted {
            block()
        }
    }
}
