package com.apex.agent.orchestration.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityManager @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "SecurityManager"
        private const val KEYSTORE_ALIAS = "orchestration_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "orchestration_secure_prefs"
        private const val ENCRYPTED_KEYS_KEY = "encrypted_api_keys"
        private const val AUDIT_LOG_KEY = "audit_log"
        private const val AES_GCM_TAG_LENGTH = 128
        private const val IV_SIZE = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val securePrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val auditLog = mutableListOf<AuditEntry>()

    init {
        ensureKeyExists()
        loadAuditLog()
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey() = (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    fun encryptAndStoreApiKey(agentId: String, apiKey: String): Boolean {
        return try {
            val encrypted = encrypt(apiKey)
            val json = org.json.JSONObject(securePrefs.getString(ENCRYPTED_KEYS_KEY, "{}"))
            json.put(agentId, encrypted)
            securePrefs.edit().putString(ENCRYPTED_KEYS_KEY, json.toString()).apply()
            logAuditEvent(AuditAction.API_KEY_STORED, agentId, "API key stored")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt API key", e)
            false
        }
    }

    fun retrieveApiKey(agentId: String): String? {
        return try {
            val json = org.json.JSONObject(securePrefs.getString(ENCRYPTED_KEYS_KEY, "{}"))
            val encrypted = json.optString(agentId, null) ?: return null
            decrypt(encrypted).also {
                logAuditEvent(AuditAction.API_KEY_RETRIEVED, agentId, "API key retrieved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve API key", e)
            null
        }
    }

    fun deleteApiKey(agentId: String): Boolean {
        return try {
            val json = org.json.JSONObject(securePrefs.getString(ENCRYPTED_KEYS_KEY, "{}"))
            if (!json.has(agentId)) return false
            json.remove(agentId)
            securePrefs.edit().putString(ENCRYPTED_KEYS_KEY, json.toString()).apply()
            logAuditEvent(AuditAction.API_KEY_DELETED, agentId, "API key deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API key", e)
            false
        }
    }

    fun hasApiKey(agentId: String): Boolean {
        return org.json.JSONObject(securePrefs.getString(ENCRYPTED_KEYS_KEY, "{}")).has(agentId)
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getSecretKey()) }
        val iv = ByteArray(IV_SIZE).apply { SecureRandom().nextBytes(this) }
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encrypted = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    fun logAuditEvent(action: AuditAction, targetId: String, details: String) {
        val entry = AuditEntry(
            id = "audit_${System.currentTimeMillis()}",
            action = action,
            targetId = targetId,
            details = details,
            timestamp = System.currentTimeMillis()
        )
        auditLog.add(entry)
        persistAuditLog()
    }

    fun getAuditLog(): List<AuditEntry> = auditLog.sortedByDescending { it.timestamp }

    private fun loadAuditLog() {
        try {
            securePrefs.getString(AUDIT_LOG_KEY, null)?.let { jsonString ->
                val array = org.json.JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    auditLog.add(AuditEntry.fromJson(array.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audit log", e)
        }
    }

    private fun persistAuditLog() {
        try {
            val array = org.json.JSONArray()
            auditLog.takeLast(1000).forEach { array.put(it.toJson()) }
            securePrefs.edit().putString(AUDIT_LOG_KEY, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist audit log", e)
        }
    }

    fun clearAuditLog() {
        auditLog.clear()
        persistAuditLog()
        logAuditEvent(AuditAction.AUDIT_LOG_CLEARED, "system", "Audit log cleared")
    }
}

enum class AuditAction {
    API_KEY_STORED,
    API_KEY_RETRIEVED,
    API_KEY_DELETED,
    TASK_CREATED,
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_FAILED,
    AGENT_ADDED,
    AGENT_REMOVED,
    SECURITY_EVENT,
    AUDIT_LOG_CLEARED
}

data class AuditEntry(
    val id: String,
    val action: AuditAction,
    val targetId: String,
    val details: String,
    val timestamp: Long
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("action", action.name)
            put("targetId", targetId)
            put("details", details)
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): AuditEntry {
            return AuditEntry(
                id = json.getString("id"),
                action = AuditAction.valueOf(json.getString("action")),
                targetId = json.getString("targetId"),
                details = json.getString("details"),
                timestamp = json.getLong("timestamp")
            )
        }
    }
}
