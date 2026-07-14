package com.apex.agent.api.speech

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.wakeWordDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "wake_word_preferences")

@Serializable
data class WakeWord(
    val id: String,
    val phrase: String,
    val isRegex: Boolean = false,
    val sensitivity: Float = 0.7f,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class WakeWordConfig(
    val wakeWords: List<WakeWord> = emptyList(),
    val globalEnabled: Boolean = true,
    val globalSensitivity: Float = 0.7f,
    val useOffline: Boolean = true,
    val useOnline: Boolean = true,
    val detectionThreshold: Float = 0.6f
)

class WakeWordManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val dataStore = context.wakeWordDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(WakeWordConfig())
    val config: StateFlow<WakeWordConfig> = _config.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastDetectedWakeWord = MutableStateFlow<WakeWord?>(null)
    val lastDetectedWakeWord: StateFlow<WakeWord?> = _lastDetectedWakeWord.asStateFlow()

    private var onWakeWordDetected: ((WakeWord) -> Unit)? = null

    companion object {
        private val WAKE_WORDS_CONFIG = stringPreferencesKey("wake_words_config")
        private val GLOBAL_ENABLED = booleanPreferencesKey("global_enabled")
        private val GLOBAL_SENSITIVITY = floatPreferencesKey("global_sensitivity")
        private val USE_OFFLINE = booleanPreferencesKey("use_offline")
        private val USE_ONLINE = booleanPreferencesKey("use_online")
        private val DETECTION_THRESHOLD = floatPreferencesKey("detection_threshold")
        private val CUSTOM_WAKE_WORDS = stringSetPreferencesKey("custom_wake_words")

        const val DEFAULT_WAKE_WORD_PHRASE = "hey assistant"
        const val DEFAULT_SENSITIVITY = 0.7f
        const val MIN_SENSITIVITY = 0.3f
        const val MAX_SENSITIVITY = 1.0f
        const val MAX_WAKE_WORDS = 5
    }

    init {
        loadConfig()
    }

    private fun loadConfig() {
        coroutineScope.launch {
            dataStore.data.collect { prefs ->
                val configJson = prefs[WAKE_WORDS_CONFIG]
                val wakeWordsJson = prefs[CUSTOM_WAKE_WORDS]

                val wakeWords = if (configJson != null) {
                    try {
                        json.decodeFromString<WakeWordConfig>(configJson).wakeWords
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to parse wake words config", e)
                        emptyList()
                    }
                } else if (wakeWordsJson != null) {
                    wakeWordsJson.map { phrase ->
                        WakeWord(
                            id = phrase.hashCode().toString(),
                            phrase = phrase,
                            sensitivity = prefs[GLOBAL_SENSITIVITY] ?: DEFAULT_SENSITIVITY
                        )
                    }
                } else {
                    listOf(
                        WakeWord(
                            id = "default",
                            phrase = DEFAULT_WAKE_WORD_PHRASE,
                            sensitivity = DEFAULT_SENSITIVITY
                        )
                    )
                }

                _config.value = WakeWordConfig(
                    wakeWords = wakeWords,
                    globalEnabled = prefs[GLOBAL_ENABLED] ?: true,
                    globalSensitivity = prefs[GLOBAL_SENSITIVITY] ?: DEFAULT_SENSITIVITY,
                    useOffline = prefs[USE_OFFLINE] ?: true,
                    useOnline = prefs[USE_ONLINE] ?: true,
                    detectionThreshold = prefs[DETECTION_THRESHOLD] ?: 0.6f
                )
            }
        }
    }

    fun setOnWakeWordDetected(callback: (WakeWord) -> Unit) {
        onWakeWordDetected = callback
    }

    suspend fun addWakeWord(phrase: String, isRegex: Boolean = false): Boolean {
        if (_config.value.wakeWords.size >= MAX_WAKE_WORDS) {
            AppLogger.w(TAG, "Maximum number of wake words reached")
            return false
        }

        val newWakeWord = WakeWord(
            id = System.currentTimeMillis().toString(),
            phrase = phrase.trim(),
            isRegex = isRegex,
            sensitivity = _config.value.globalSensitivity
        )

        val updatedWakeWords = _config.value.wakeWords + newWakeWord
        saveWakeWords(updatedWakeWords)
        return true
    }

    suspend fun removeWakeWord(id: String) {
        val updatedWakeWords = _config.value.wakeWords.filter { it.id != id }
        saveWakeWords(updatedWakeWords)
    }

    suspend fun updateWakeWord(id: String, phrase: String? = null, isRegex: Boolean? = null, sensitivity: Float? = null, isEnabled: Boolean? = null) {
        val updatedWakeWords = _config.value.wakeWords.map { ww ->
            if (ww.id == id) {
                ww.copy(
                    phrase = phrase ?: ww.phrase,
                    isRegex = isRegex ?: ww.isRegex,
                    sensitivity = sensitivity?.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) ?: ww.sensitivity,
                    isEnabled = isEnabled ?: ww.isEnabled
                )
            } else {
                ww
            }
        }
        saveWakeWords(updatedWakeWords)
    }

    private suspend fun saveWakeWords(wakeWords: List<WakeWord>) {
        val updatedConfig = _config.value.copy(wakeWords = wakeWords)
        _config.value = updatedConfig

        dataStore.edit { prefs ->
            prefs[WAKE_WORDS_CONFIG] = json.encodeToString(updatedConfig)
        }
    }

    suspend fun setGlobalEnabled(enabled: Boolean) {
        _config.value = _config.value.copy(globalEnabled = enabled)
        dataStore.edit { prefs ->
            prefs[GLOBAL_ENABLED] = enabled
        }
    }

    suspend fun setGlobalSensitivity(sensitivity: Float) {
        val clampedSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        _config.value = _config.value.copy(globalSensitivity = clampedSensitivity)
        dataStore.edit { prefs ->
            prefs[GLOBAL_SENSITIVITY] = clampedSensitivity
        }
    }

    suspend fun setUseOffline(enabled: Boolean) {
        _config.value = _config.value.copy(useOffline = enabled)
        dataStore.edit { prefs ->
            prefs[USE_OFFLINE] = enabled
        }
    }

    suspend fun setUseOnline(enabled: Boolean) {
        _config.value = _config.value.copy(useOnline = enabled)
        dataStore.edit { prefs ->
            prefs[USE_ONLINE] = enabled
        }
    }

    suspend fun setDetectionThreshold(threshold: Float) {
        _config.value = _config.value.copy(detectionThreshold = threshold.coerceIn(0.3f, 0.9f))
        dataStore.edit { prefs ->
            prefs[DETECTION_THRESHOLD] = threshold
        }
    }

    fun detectWakeWord(text: String): WakeWord? {
        if (!_config.value.globalEnabled) return null

        for (wakeWord in _config.value.wakeWords) {
            if (!wakeWord.isEnabled) continue

            val match = if (wakeWord.isRegex) {
                try {
                    Regex(wakeWord.phrase).containsMatchIn(text.lowercase())
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Invalid regex: ${wakeWord.phrase}", e)
                    false
                }
            } else {
                text.lowercase().contains(wakeWord.phrase.lowercase())
            }

            if (match) {
                _lastDetectedWakeWord.value = wakeWord
                onWakeWordDetected?.invoke(wakeWord)
                return wakeWord
            }
        }
        return null
    }

    fun startListening() {
        _isListening.value = true
    }

    fun stopListening() {
        _isListening.value = false
    }

    fun resetLastDetectedWakeWord() {
        _lastDetectedWakeWord.value = null
    }
}

private const val TAG = "WakeWordManager"
