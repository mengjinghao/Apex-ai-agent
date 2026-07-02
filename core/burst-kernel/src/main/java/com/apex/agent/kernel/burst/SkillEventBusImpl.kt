package com.apex.agent.kernel.burst

import android.util.Log
import com.apex.agent.plugins.burst.base.SkillEvent
import com.apex.agent.plugins.burst.base.SkillEventBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class SkillEventBusImpl : SkillEventBus {
    companion object {
        private const val TAG = "SkillEventBus"
    }

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<(SkillEvent) -> Unit>>()
    private val pendingEvents = ConcurrentHashMap<String, ConcurrentLinkedQueue<SkillEvent>>()

    override fun publish(event: SkillEvent) {
        if (event.targetSkillIds.isEmpty()) {
            subscribers[event.type]?.forEach { handler ->
                try { handler(event) } catch (e: Exception) { Log.e(TAG, "publish handler error: type=${event.type}", e) }
            }
        } else {
            event.targetSkillIds.forEach { skillId ->
                pendingEvents.computeIfAbsent(skillId) { ConcurrentLinkedQueue() }.add(event)
                subscribers[event.type]?.forEach { handler ->
                    try { handler(event) } catch (e: Exception) { Log.e(TAG, "publish handler error: type=${event.type}, skillId=$skillId", e) }
                }
            }
        }
    }

    override fun subscribe(eventType: String, handler: (SkillEvent) -> Unit): () -> Unit {
        subscribers.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add(handler)
        return { subscribers[eventType]?.remove(handler) }
    }

    override fun unsubscribe(eventType: String, handler: (SkillEvent) -> Unit) {
        subscribers[eventType]?.remove(handler)
    }

    override fun getPendingEvents(skillId: String): List<SkillEvent> {
        return pendingEvents[skillId]?.toList() ?: emptyList()
    }

    fun clearPendingEvents(skillId: String) {
        pendingEvents.remove(skillId)
    }

    fun clearAll() {
        subscribers.clear()
        pendingEvents.clear()
    }
}
