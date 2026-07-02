package com.apex.agent.core.multiagent

import android.content.Context
import com.apex.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

class PersistenceManager(private val context: Context) {

    companion object {
        private const val TAG = "PersistenceManager"
    }

    private val gson = Gson()
    private val dataDir = File(context.filesDir, "multi_agent")

    init {
        // 确保数据目录存在
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
    }

    fun saveAgents(agents: List<Agent>) {
        try {
            val file = File(dataDir, "agents.json")
            FileWriter(file).use { writer ->
                gson.toJson(agents, writer)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save agents", e)
        }
    }

    fun loadAgents(): List<Agent> {
        try {
            val file = File(dataDir, "agents.json")
            if (!file.exists()) {
                return emptyList()
            }
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<Agent>>() {}.type
                return gson.fromJson<List<Agent>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load agents", e)
            return emptyList()
        }
    }

    fun saveTasks(tasks: List<CollaborationTask>) {
        try {
            val file = File(dataDir, "tasks.json")
            FileWriter(file).use { writer ->
                gson.toJson(tasks, writer)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save tasks", e)
        }
    }

    fun loadTasks(): List<CollaborationTask> {
        try {
            val file = File(dataDir, "tasks.json")
            if (!file.exists()) {
                return emptyList()
            }
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<CollaborationTask>>() {}.type
                return gson.fromJson<List<CollaborationTask>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load tasks", e)
            return emptyList()
        }
    }

    fun saveSessions(sessions: List<AgentSession>) {
        try {
            val file = File(dataDir, "sessions.json")
            FileWriter(file).use { writer ->
                gson.toJson(sessions, writer)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save sessions", e)
        }
    }

    fun loadSessions(): List<AgentSession> {
        try {
            val file = File(dataDir, "sessions.json")
            if (!file.exists()) {
                return emptyList()
            }
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<AgentSession>>() {}.type
                return gson.fromJson<List<AgentSession>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load sessions", e)
            return emptyList()
        }
    }

    fun saveTemplates(templates: List<AgentTemplate>) {
        try {
            val file = File(dataDir, "templates.json")
            FileWriter(file).use { writer ->
                gson.toJson(templates, writer)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save templates", e)
        }
    }

    fun loadTemplates(): List<AgentTemplate> {
        try {
            val file = File(dataDir, "templates.json")
            if (!file.exists()) {
                return emptyList()
            }
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<AgentTemplate>>() {}.type
                return gson.fromJson<List<AgentTemplate>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load templates", e)
            return emptyList()
        }
    }

    fun clearAll() {
        try {
            val files = dataDir.listFiles()
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear all data", e)
        }
    }
}
