package com.apex.agent.core.multiagent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.agentSystemDataStore by preferencesDataStore("agent_system_config")

data class AgentSystemConfig(
    val enableKnowledgeGraph: Boolean = true,
    val enableFederatedLearning: Boolean = true,
    val enableSelfHealing: Boolean = true,
    val enableRealTimeCollaboration: Boolean = true,
    val enableDistributedArchitecture: Boolean = false,
    val maxAgents: Int = 50,
    val learningRate: Float = 0.01f,
    val syncIntervalMs: Int = 30000,
    val heartbeatIntervalMs: Int = 5000,
    val logLevel: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

class AgentSystemConfigManager(private val context: Context) {

    companion object {
        private val KEY_ENABLE_KG = booleanPreferencesKey("enable_knowledge_graph")
        private val KEY_ENABLE_FL = booleanPreferencesKey("enable_federated_learning")
        private val KEY_ENABLE_SH = booleanPreferencesKey("enable_self_healing")
        private val KEY_ENABLE_RTC = booleanPreferencesKey("enable_realtime_collab")
        private val KEY_ENABLE_DIST = booleanPreferencesKey("enable_distributed")
        private val KEY_MAX_AGENTS = intPreferencesKey("max_agents")
        private val KEY_LR = floatPreferencesKey("learning_rate")
        private val KEY_LOG_LEVEL = stringPreferencesKey("log_level")
    }
        val configFlow: Flow<AgentSystemConfig> = context.agentSystemDataStore.data.map {
        AgentSystemConfig(
            enableKnowledgeGraph = it[KEY_ENABLE_KG] ?: true,
            enableFederatedLearning = it[KEY_ENABLE_FL] ?: true,
            enableSelfHealing = it[KEY_ENABLE_SH] ?: true,
            enableRealTimeCollaboration = it[KEY_ENABLE_RTC] ?: true,
            enableDistributedArchitecture = it[KEY_ENABLE_DIST] ?: false,
            maxAgents = it[KEY_MAX_AGENTS] ?: 50,
            learningRate = it[KEY_LR] ?: 0.01f,
            logLevel = try { LogLevel.valueOf(it[KEY_LOG_LEVEL] ?: "INFO") } catch (e: Exception) { LogLevel.INFO }
        )
    }

    suspend fun updateConfig(config: AgentSystemConfig) {
        context.agentSystemDataStore.edit {
            it[KEY_ENABLE_KG] = config.enableKnowledgeGraph
            it[KEY_ENABLE_FL] = config.enableFederatedLearning
            it[KEY_ENABLE_SH] = config.enableSelfHealing
            it[KEY_ENABLE_RTC] = config.enableRealTimeCollaboration
            it[KEY_ENABLE_DIST] = config.enableDistributedArchitecture
            it[KEY_MAX_AGENTS] = config.maxAgents
            it[KEY_LR] = config.learningRate
            it[KEY_LOG_LEVEL] = config.logLevel.name
        }
    }
}
