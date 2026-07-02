package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.BufferOverflow

/**
 * EventBus 配置类
 */
data class EventBusConfig(
    val bufferSize: Int = 100,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val enableMetrics: Boolean = false,
    val enableLogging: Boolean = false
) {
    companion object {
        /** 默认配置：100缓冲区，丢弃最旧 */
        val DEFAULT = EventBusConfig()

        /** 高吞吐配置：1000缓冲区，丢弃最旧，无日志 */
        val HIGH_THROUGHPUT = EventBusConfig(
            bufferSize = 1000,
            overflowStrategy = BufferOverflow.DROP_OLDEST,
            enableMetrics = true
        )

        /** 低延迟配置：50缓冲区，挂起策略 */
        val LOW_LATENCY = EventBusConfig(
            bufferSize = 50,
            overflowStrategy = BufferOverflow.SUSPEND
        )

        /** 可靠配置：500缓冲区，丢弃最旧，启用日志和指标 */
        val RELIABLE = EventBusConfig(
            bufferSize = 500,
            overflowStrategy = BufferOverflow.DROP_OLDEST,
            enableMetrics = true,
            enableLogging = true
        )
    }

    class Builder {
        private var bufferSize: Int = 100
        private var overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST
        private var dispatcher: CoroutineDispatcher = Dispatchers.Default
        private var enableMetrics: Boolean = false
        private var enableLogging: Boolean = false

        fun bufferSize(size: Int) = apply { this.bufferSize = size }
        fun overflowStrategy(strategy: BufferOverflow) = apply { this.overflowStrategy = strategy }
        fun dispatcher(dispatcher: CoroutineDispatcher) = apply { this.dispatcher = dispatcher }
        fun enableMetrics(enable: Boolean) = apply { this.enableMetrics = enable }
        fun enableLogging(enable: Boolean) = apply { this.enableLogging = enable }
        fun build() = EventBusConfig(bufferSize, overflowStrategy, dispatcher, enableMetrics, enableLogging)
    }
}
