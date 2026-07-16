package com.apex.agent.infrastructure.memory

import javax.inject.Inject
import javax.inject.Singleton

interface ShortTermMemory {

    fun put(key: String, value: String)

    fun get(key: String): String?

    fun remove(key: String)

    fun clear()

    @Singleton

        private val store = mutableMapOf<String, String>()

        override fun put(key: String, value: String) {
            synchronized(store) { store[key] = value }
        }

        override fun get(key: String): String? {
            return synchronized(store) { store[key] }
        }

        override fun remove(key: String) {
            synchronized(store) { store.remove(key) }
        }

        override fun clear() {
            synchronized(store) { store.clear() }
        }
    }
}
