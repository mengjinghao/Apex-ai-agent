package com.apex.gepa

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class GepaConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var minSuccessRateForMatch: Float
        get() = prefs.getFloat(KEY_MIN_SUCCESS_RATE, 0.5f)
        set(value) = prefs.edit { putFloat(KEY_MIN_SUCCESS_RATE, value.coerceIn(0f, 1f)) }

    var minExecutionsForHighQuality: Int
        get() = prefs.getInt(KEY_MIN_EXECUTIONS, 3)
        set(value) = prefs.edit { putInt(KEY_MIN_EXECUTIONS, value.coerceAtLeast(1)) }

    var autoExtractOnSuccess: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXTRACT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_EXTRACT, value) }

    var maxStoredSkills: Int
        get() = prefs.getInt(KEY_MAX_STORED_SKILLS, 1000)
        set(value) = prefs.edit { putInt(KEY_MAX_STORED_SKILLS, value.coerceAtLeast(10)) }

    var cleanupThresholdDays: Int
        get() = prefs.getInt(KEY_CLEANUP_DAYS, 30)
        set(value) = prefs.edit { putInt(KEY_CLEANUP_DAYS, value.coerceAtLeast(7)) }

    var matchConfidenceThreshold: Float
        get() = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.5f)
        set(value) = prefs.edit { putFloat(KEY_CONFIDENCE_THRESHOLD, value.coerceIn(0f, 1f)) }

    var enableParallelExecution: Boolean
        get() = prefs.getBoolean(KEY_PARALLEL_EXECUTION, true)
        set(value) = prefs.edit { putBoolean(KEY_PARALLEL_EXECUTION, value) }

    var maxConcurrentTasks: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT, 5)
        set(value) = prefs.edit { putInt(KEY_MAX_CONCURRENT, value.coerceAtLeast(1)) }

    var taskTimeoutMs: Long
        get() = prefs.getLong(KEY_TASK_TIMEOUT, 300000L)
        set(value) = prefs.edit { putLong(KEY_TASK_TIMEOUT, value.coerceAtLeast(10000)) }

    var enableSkillVersioning: Boolean
        get() = prefs.getBoolean(KEY_VERSIONING, true)
        set(value) = prefs.edit { putBoolean(KEY_VERSIONING, value) }

    var autoMergeSimilarSkills: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MERGE, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_MERGE, value) }

    var skillSimilarityThreshold: Float
        get() = prefs.getFloat(KEY_SIMILARITY_THRESHOLD, 0.85f)
        set(value) = prefs.edit { putFloat(KEY_SIMILARITY_THRESHOLD, value.coerceIn(0f, 1f)) }

    fun getMatchConfidenceForMinRate(): MatchConfidence {
        return when {
            minSuccessRateForMatch >= 0.8f -> MatchConfidence.HIGH
            minSuccessRateForMatch >= 0.6f -> MatchConfidence.MEDIUM
            minSuccessRateForMatch >= 0.4f -> MatchConfidence.LOW
            else -> MatchConfidence.VERY_LOW
        }
    }

    fun reset() {
        prefs.edit { clear() }
    }

    fun export(): Map<String, Any> {
        return mapOf(
            KEY_ENABLED to isEnabled,
            KEY_MIN_SUCCESS_RATE to minSuccessRateForMatch,
            KEY_MIN_EXECUTIONS to minExecutionsForHighQuality,
            KEY_AUTO_EXTRACT to autoExtractOnSuccess,
            KEY_MAX_STORED_SKILLS to maxStoredSkills,
            KEY_CLEANUP_DAYS to cleanupThresholdDays,
            KEY_CONFIDENCE_THRESHOLD to matchConfidenceThreshold,
            KEY_PARALLEL_EXECUTION to enableParallelExecution,
            KEY_MAX_CONCURRENT to maxConcurrentTasks,
            KEY_TASK_TIMEOUT to taskTimeoutMs,
            KEY_VERSIONING to enableSkillVersioning,
            KEY_AUTO_MERGE to autoMergeSimilarSkills,
            KEY_SIMILARITY_THRESHOLD to skillSimilarityThreshold
        )
    }

    fun import(config: Map<String, Any>) {
        prefs.edit {
            config.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "gepa_config"
        private const val KEY_ENABLED = "gepa_enabled"
        private const val KEY_MIN_SUCCESS_RATE = "min_success_rate"
        private const val KEY_MIN_EXECUTIONS = "min_executions"
        private const val KEY_AUTO_EXTRACT = "auto_extract"
        private const val KEY_MAX_STORED_SKILLS = "max_stored_skills"
        private const val KEY_CLEANUP_DAYS = "cleanup_days"
        private const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"
        private const val KEY_PARALLEL_EXECUTION = "parallel_execution"
        private const val KEY_MAX_CONCURRENT = "max_concurrent"
        private const val KEY_TASK_TIMEOUT = "task_timeout"
        private const val KEY_VERSIONING = "versioning"
        private const val KEY_AUTO_MERGE = "auto_merge"
        private const val KEY_SIMILARITY_THRESHOLD = "similarity_threshold"

        @Volatile
        private var INSTANCE: GepaConfig? = null

        fun getInstance(context: Context): GepaConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GepaConfig(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
