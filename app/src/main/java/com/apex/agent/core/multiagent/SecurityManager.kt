package com.apex.agent.core.multiagent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityManager(private val context: Context) {

    companion object {
        private const val TAG = "SecurityManager"
        private const val KEYSTORE_ALIAS = "multi_agent_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "multi_agent_secure_prefs"
        private const val ENCRYPTED_API_KEYS_KEY = "encrypted_api_keys"
        private const val AUDIT_LOG_KEY = "audit_log"
        private const val AES_GCM_TAG_LENGTH = 128
        private const val IV_SIZE = 12
    }
        private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }
        private val securePrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val auditLog = mutableListOf<AuditEntry>()

    init {
        ensureKeyExists()
        loadAuditLog()
    }
        private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }
        private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
        fun encryptAndStoreApiKey(agentId: String, apiKey: String): Boolean {
        return try {
            val encryptedData = encrypt(apiKey)
        val encryptedKeysJson = JSONObject(securePrefs.getString(ENCRYPTED_API_KEYS_KEY, "{}"))
            encryptedKeysJson.put(agentId, encryptedData)

            securePrefs.edit()
                .putString(ENCRYPTED_API_KEYS_KEY, encryptedKeysJson.toString())
                .apply()

            logAuditEvent(AuditAction.API_KEY_STORED, agentId, "API key stored successfully")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and store API key", e)
            logAuditEvent(AuditAction.API_KEY_STORE_FAILED, agentId, "Failed: ${e.message}")
            false
        }
    }
        fun retrieveApiKey(agentId: String): String? {
        return try {
            val encryptedKeysJson = JSONObject(securePrefs.getString(ENCRYPTED_API_KEYS_KEY, "{}"))
        val encryptedData = encryptedKeysJson.optString(agentId, null) ?: return null

            val decrypted = decrypt(encryptedData)

            logAuditEvent(AuditAction.API_KEY_RETRIEVED, agentId, "API key retrieved successfully")

            decrypted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve API key", e)
            logAuditEvent(AuditAction.API_KEY_RETRIEVE_FAILED, agentId, "Failed: ${e.message}")
            null
        }
    }
        fun deleteApiKey(agentId: String): Boolean {
        return try {
            val encryptedKeysJson = JSONObject(securePrefs.getString(ENCRYPTED_API_KEYS_KEY, "{}"))
        if (!encryptedKeysJson.has(agentId)) {
                return false
            }

            encryptedKeysJson.remove(agentId)

            securePrefs.edit()
                .putString(ENCRYPTED_API_KEYS_KEY, encryptedKeysJson.toString())
                .apply()

            logAuditEvent(AuditAction.API_KEY_DELETED, agentId, "API key deleted successfully")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API key", e)
            logAuditEvent(AuditAction.API_KEY_DELETE_FAILED, agentId, "Failed: ${e.message}")
            false
        }
    }
        fun hasApiKey(agentId: String): Boolean {
        val encryptedKeysJson = JSONObject(securePrefs.getString(ENCRYPTED_API_KEYS_KEY, "{}"))
        return encryptedKeysJson.has(agentId)
    }
        private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }
        private fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val iv = ByteArray(IV_SIZE)
        System.arraycopy(combined, 0, iv, 0, IV_SIZE)
        val encryptedBytes = ByteArray(combined.size - IV_SIZE)
        System.arraycopy(combined, IV_SIZE, encryptedBytes, 0, encryptedBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }
        fun logAuditEvent(action: AuditAction, targetId: String, details: String) {
        val entry = AuditEntry(
            id = "audit_${System.currentTimeMillis()}",
            action = action,
            targetId = targetId,
            details = details,
            timestamp = System.currentTimeMillis(),
            userId = "system"
        )

        auditLog.add(entry)

        persistAuditLog()
    }
        fun getAuditLog(filter: AuditLogFilter? = null): List<AuditEntry> {
        var filteredLog = auditLog

        filter?.let { f ->
            filteredLog = filteredLog.filter { entry ->
                val matchesAction = f.actions.isEmpty() || entry.action in f.actions
                val matchesTarget = f.targetId.isEmpty() || entry.targetId == f.targetId
                val matchesStartTime = f.startTime == null || entry.timestamp >= f.startTime
                val matchesEndTime = f.endTime == null || entry.timestamp <= f.endTime

                matchesAction && matchesTarget && matchesStartTime && matchesEndTime
            }
        }
        return filteredLog.sortedByDescending { it.timestamp }
    }
        fun exportAuditLog(): String {
        val jsonArray = org.json.JSONArray()
        auditLog.forEach { entry ->
            jsonArray.put(entry.toJson())
        }
        return jsonArray.toString(2)
    }
        private fun loadAuditLog() {
        try {
            val auditLogJson = securePrefs.getString(AUDIT_LOG_KEY, null)
        if (auditLogJson != null) {
                val jsonArray = org.json.JSONArray(auditLogJson)
        for (i in 0 until jsonArray.length()) {
                    val entryJson = jsonArray.getJSONObject(i)
                    auditLog.add(AuditEntry.fromJson(entryJson))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audit log", e)
        }
    }
        private fun persistAuditLog() {
        try {
            val jsonArray = org.json.JSONArray()
            auditLog.takeLast(1000).forEach { entry ->
                jsonArray.put(entry.toJson())
            }

            securePrefs.edit()
                .putString(AUDIT_LOG_KEY, jsonArray.toString())
                .apply()
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
    API_KEY_STORE_FAILED,
    API_KEY_RETRIEVED,
    API_KEY_RETRIEVE_FAILED,
    API_KEY_DELETED,
    API_KEY_DELETE_FAILED,
    TASK_CREATED,
    TASK_STARTED,
    TASK_PAUSED,
    TASK_RESUMED,
    TASK_STOPPED,
    TASK_COMPLETED,
    TASK_FAILED,
    AGENT_ADDED,
    AGENT_REMOVED,
    AGENT_STATUS_CHANGED,
    COLLABORATION_MODE_CHANGED,
    RESOURCE_ALLOCATED,
    RESOURCE_RELEASED,
    SECURITY_EVENT,
    AUDIT_LOG_CLEARED
}

data class AuditEntry(
    val id: String,
    val action: AuditAction,
    val targetId: String,
    val details: String,
    val timestamp: Long,
    val userId: String
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("action", action.name)
            put("targetId", targetId)
            put("details", details)
            put("timestamp", timestamp)
            put("userId", userId)
        }
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): AuditEntry {
            return AuditEntry(
                id = json.getString("id"),
                action = AuditAction.valueOf(json.getString("action")),
                targetId = json.getString("targetId"),
                details = json.getString("details"),
                timestamp = json.getLong("timestamp"),
                userId = json.getString("userId")
            )
        }
    }
}

data class AuditLogFilter(
    val actions: List<AuditAction> = emptyList(),
    val targetId: String = "",
    val startTime: Long? = null,
    val endTime: Long? = null
)
