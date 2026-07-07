package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.flow.Flow

interface EventSubscriber {

    fun <T : Any> subscribe(eventClass: Class<T>): Flow<T>
}

class DefaultEventSubscriber(
    private val eventBus: EventBus
) : EventSubscriber {

    override fun <T : Any> subscribe(eventClass: Class<T>): Flow<T> {
        return eventBus.subscribe(eventClass)
    }
}
