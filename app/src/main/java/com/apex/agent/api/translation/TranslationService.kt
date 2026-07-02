package com.apex.agent.api.translation

import kotlinx.coroutines.flow.Flow

interface TranslationService {
    val isInitialized: Boolean
    val isTranslating: Boolean

    suspend fun initialize(): Boolean

    suspend fun translate(
        text: String,
        sourceLanguage: String? = null,
        targetLanguage: String
    ): TranslationResult

    fun translateStream(
        text: String,
        sourceLanguage: String? = null,
        targetLanguage: String
    ): Flow<TranslationResult>

    suspend fun detectLanguage(text: String): String?

    suspend fun getSupportedLanguages(): List<LanguageInfo>

    fun shutdown()
}

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val confidence: Float = 1.0f,
    val isFinal: Boolean = true,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null && translatedText.isNotBlank()
}

data class LanguageInfo(
    val code: String,
    val name: String,
    val nativeName: String,
    val isSupported: Boolean = true
)

class TranslationException(
    message: String,
    val errorCode: Int = 0
) : Exception(message)

enum class TranslationProvider {
    GOOGLE,
    DEEPL,
    OPENAI,
    MICROSOFT,
    CUSTOM
}
