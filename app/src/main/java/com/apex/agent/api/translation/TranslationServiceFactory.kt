package com.apex.agent.api.translation

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.apex.agent.api.translation.LanguageInfo

class TranslationServiceFactory private constructor(
    private val context: Context
) {
    private var currentService: TranslationService? = null
    private var currentProvider: TranslationProvider = TranslationProvider.OPENAI

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    companion object {
        private const val TAG = "TranslationServiceFactory"
        private var instance: TranslationServiceFactory? = null

        fun getInstance(context: Context): TranslationServiceFactory {
            return instance ?: synchronized(this) {
                instance ?: TranslationServiceFactory(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun getService(): TranslationService? = currentService

    suspend fun createService(provider: TranslationProvider, config: TranslationConfig? = null): Boolean {
        try {
            currentService?.shutdown()

            currentService = when (provider) {
                TranslationProvider.GOOGLE -> GoogleTranslationService(context, config)
                TranslationProvider.DEEPL -> DeepLTranslationService(context, config)
                TranslationProvider.OPENAI -> OpenAITranslationService(context, config)
                TranslationProvider.MICROSOFT -> MicrosoftTranslationService(context, config)
                TranslationProvider.CUSTOM -> CustomTranslationService(context, config)
            }

            currentProvider = provider
            val success = currentService?.initialize() ?: false
            _isInitialized.value = success

            if (success) {
                AppLogger.d(TAG, "Translation service created: ${provider}")
            } else {
                AppLogger.e(TAG, "Failed to initialize translation service: ${provider}")
            }

            return success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error creating translation service: ${provider}", e)
            _isInitialized.value = false
            return false
        }
    }

    fun getCurrentProvider(): TranslationProvider = currentProvider

    suspend fun switchProvider(provider: TranslationProvider, config: TranslationConfig? = null): Boolean {
        return createService(provider, config)
    }
}

data class TranslationConfig(
    val apiKey: String = "",
    val endpoint: String = "",
    val timeout: Int = 30,
    val customHeaders: Map<String, String> = emptyMap(),
    val defaultSourceLanguage: String? = null,
    val defaultTargetLanguage: String = "en",
    val useStream: Boolean = false
)

private class GoogleTranslationService(
    private val context: Context,
    private val config: TranslationConfig?
) : TranslationService {

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized
    override val isTranslating: Boolean = false

    override suspend fun initialize(): Boolean {
        _isInitialized = true
        return true
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "[Google] ${text}",
            sourceLanguage = sourceLanguage ?: "auto",
            targetLanguage = targetLanguage
        )
    }

    override fun translateStream(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ) = kotlinx.coroutines.flow.flowOf(translate(text, sourceLanguage, targetLanguage))

    override suspend fun detectLanguage(text: String): String? = "zh"

    override suspend fun getSupportedLanguages() = emptyList<LanguageInfo>()

    override fun shutdown() { _isInitialized = false }
}

private class DeepLTranslationService(
    private val context: Context,
    private val config: TranslationConfig?
) : TranslationService {

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized
    override val isTranslating: Boolean = false

    override suspend fun initialize(): Boolean {
        _isInitialized = true
        return true
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "[DeepL] ${text}",
            sourceLanguage = sourceLanguage ?: "auto",
            targetLanguage = targetLanguage
        )
    }

    override fun translateStream(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ) = kotlinx.coroutines.flow.flowOf(translate(text, sourceLanguage, targetLanguage))

    override suspend fun detectLanguage(text: String): String? = "zh"

    override suspend fun getSupportedLanguages() = emptyList<LanguageInfo>()

    override fun shutdown() { _isInitialized = false }
}

private class OpenAITranslationService(
    private val context: Context,
    private val config: TranslationConfig?
) : TranslationService {

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized
    override val isTranslating: Boolean = false

    override suspend fun initialize(): Boolean {
        _isInitialized = true
        return true
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "[OpenAI] ${text}",
            sourceLanguage = sourceLanguage ?: "auto",
            targetLanguage = targetLanguage
        )
    }

    override fun translateStream(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ) = kotlinx.coroutines.flow.flowOf(translate(text, sourceLanguage, targetLanguage))

    override suspend fun detectLanguage(text: String): String? = "zh"

    override suspend fun getSupportedLanguages() = listOf(
        LanguageInfo("en", "English", "English"),
        LanguageInfo("zh", "Chinese", "中文"),
        LanguageInfo("ja", "Japanese", "日本?),
        LanguageInfo("ko", "Korean", "한국?),
        LanguageInfo("fr", "French", "Français"),
        LanguageInfo("de", "German", "Deutsch"),
        LanguageInfo("es", "Spanish", "Español"),
        LanguageInfo("ru", "Russian", "Русский")
    )

    override fun shutdown() { _isInitialized = false }
}

private class MicrosoftTranslationService(
    private val context: Context,
    private val config: TranslationConfig?
) : TranslationService {

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized
    override val isTranslating: Boolean = false

    override suspend fun initialize(): Boolean {
        _isInitialized = true
        return true
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "[Microsoft] ${text}",
            sourceLanguage = sourceLanguage ?: "auto",
            targetLanguage = targetLanguage
        )
    }

    override fun translateStream(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ) = kotlinx.coroutines.flow.flowOf(translate(text, sourceLanguage, targetLanguage))

    override suspend fun detectLanguage(text: String): String? = "zh"

    override suspend fun getSupportedLanguages() = emptyList<LanguageInfo>()

    override fun shutdown() { _isInitialized = false }
}

private class CustomTranslationService(
    private val context: Context,
    private val config: TranslationConfig?
) : TranslationService {

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized
    override val isTranslating: Boolean = false

    override suspend fun initialize(): Boolean {
        _isInitialized = config?.endpoint?.isNotBlank() == true
        return _isInitialized
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "[Custom] ${text}",
            sourceLanguage = sourceLanguage ?: "auto",
            targetLanguage = targetLanguage
        )
    }

    override fun translateStream(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ) = kotlinx.coroutines.flow.flowOf(translate(text, sourceLanguage, targetLanguage))

    override suspend fun detectLanguage(text: String): String? = "zh"

    override suspend fun getSupportedLanguages() = emptyList<LanguageInfo>()

    override fun shutdown() { _isInitialized = false }
}
