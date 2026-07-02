package com.apex.agent.core.tools.skill

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apex.agent.core.tools.LocalizedText
import com.apex.agent.core.tools.PackagePermission
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val Context.skillPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "skill_permissions")

enum class SkillPermissionState {
    GRANTED,
    DENIED,
    UNKNOWN
}

data class SkillPermission(
    val skillName: String,
    val permission: PackagePermission,
    val state: SkillPermissionState = SkillPermissionState.UNKNOWN
)

data class SkillPermissionCheckResult(
    val skillName: String,
    val allGranted: Boolean,
    val permissions: List<SkillPermission>,
    val missingRequired: List<PackagePermission> = emptyList(),
    val error: String? = null
)

class SkillPermissionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillPermissionManager"
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 60000L
        private const val PERMISSION_PREFIX = "skill_perm_"
        private const val STATE_SUFFIX = "_state"

        @Volatile private var INSTANCE: SkillPermissionManager? = null

        fun getInstance(context: Context): SkillPermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPermissionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _permissionCheckResults = MutableStateFlow<Map<String, SkillPermissionCheckResult>>(emptyMap())
    val permissionCheckResults: Flow<Map<String, SkillPermissionCheckResult>> = _permissionCheckResults.asStateFlow()

    private val _pendingPermissionRequests = MutableStateFlow<List<Pair<String, PackagePermission>>>(emptyList())
    val pendingPermissionRequests: Flow<List<Pair<String, PackagePermission>>> = _pendingPermissionRequests.asStateFlow()

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var currentSkillName: String? = null

    private val alwaysAllowSkills = mutableSetOf<String>()

    private fun skillPermissionKey(skillName: String, permissionName: String) =
        stringPreferencesKey("${PERMISSION_PREFIX}${skillName}_${permissionName}${STATE_SUFFIX}")

    private fun skillAlwaysAllowKey(skillName: String) =
        booleanPreferencesKey("${PERMISSION_PREFIX}${skillName}_always_allow")

    suspend fun checkSkillPermissions(skillName: String, permissions: List<PackagePermission>): SkillPermissionCheckResult {
        if (permissions.isEmpty()) {
            return SkillPermissionCheckResult(
                skillName = skillName,
                allGranted = true,
                permissions = emptyList()
            )
        }

        val skillPermissions = permissions.map { perm ->
            val state = getPermissionStateInternal(skillName, perm.name)
            SkillPermission(skillName, perm, state)
        }

        val missingRequired = skillPermissions
            .filter { it.state == SkillPermissionState.DENIED && it.permission.required }
            .map { it.permission }

        val result = SkillPermissionCheckResult(
            skillName = skillName,
            allGranted = missingRequired.isEmpty(),
            permissions = skillPermissions,
            missingRequired = missingRequired
        )

        _permissionCheckResults.value = _permissionCheckResults.value.toMutableMap().apply {
            put(skillName, result)
        }

        return result
    }

    private suspend fun getPermissionStateInternal(skillName: String, permissionName: String): SkillPermissionState {
        val preferences = context.skillPermissionsDataStore.data.first()
        val key = skillPermissionKey(skillName, permissionName)
        val storedState = preferences[key]

        if (storedState != null) {
            return when (storedState.lowercase()) {
                "granted" -> SkillPermissionState.GRANTED
                "denied" -> SkillPermissionState.DENIED
                else -> checkAndroidPermission(permissionName)
            }
        }

        if (alwaysAllowSkills.contains(skillName)) {
            return SkillPermissionState.GRANTED
        }

        return checkAndroidPermission(permissionName)
    }

    private fun checkAndroidPermission(permissionName: String): SkillPermissionState {
        return try {
            val granted = ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
            if (granted) SkillPermissionState.GRANTED else SkillPermissionState.DENIED
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unknown permission: ${permissionName}", e)
            SkillPermissionState.UNKNOWN
        }
    }

    suspend fun requestPermission(skillName: String, permission: PackagePermission): Boolean {
        currentSkillName = skillName

        val result = withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                permissionCallback = { granted ->
                    scope.launch {
                        if (granted) {
                            savePermissionGrant(skillName, permission.name)
                        }
                        continuation.resume(granted)
                    }
                }

                _pendingPermissionRequests.value = _pendingPermissionRequests.value.toMutableList().apply {
                    add(Pair(skillName, permission))
                }

                notifyPermissionRequest(skillName, permission)
            }
        } ?: run {
            AppLogger.d(TAG, "Permission request timed out for ${skillName}:${permission.name}")
            _pendingPermissionRequests.value = _pendingPermissionRequests.value.filter { it.first != skillName || it.second.name != permission.name }
            false
        }

        currentSkillName = null
        permissionCallback = null
        return result ?: false
    }

    private fun notifyPermissionRequest(skillName: String, permission: PackagePermission) {
        AppLogger.d(TAG, "Permission request notification: ${skillName}:${permission.name}")
    }

    fun handlePermissionResult(granted: Boolean) {
        permissionCallback?.invoke(granted)
        _pendingPermissionRequests.value = _pendingPermissionRequests.value.filter { it.first != currentSkillName }
    }

    suspend fun savePermissionGrant(skillName: String, permissionName: String) {
        context.skillPermissionsDataStore.edit { preferences ->
            val key = skillPermissionKey(skillName, permissionName)
            preferences[key] = "granted"
        }
    }

    suspend fun savePermissionDenial(skillName: String, permissionName: String) {
        context.skillPermissionsDataStore.edit { preferences ->
            val key = skillPermissionKey(skillName, permissionName)
            preferences[key] = "denied"
        }
    }

    suspend fun setSkillAlwaysAllow(skillName: String, alwaysAllow: Boolean) {
        context.skillPermissionsDataStore.edit { preferences ->
            val key = skillAlwaysAllowKey(skillName)
            preferences[key] = alwaysAllow
        }
        if (alwaysAllow) {
            alwaysAllowSkills.add(skillName)
        } else {
            alwaysAllowSkills.remove(skillName)
        }
    }

    suspend fun isSkillAlwaysAllowed(skillName: String): Boolean {
        if (alwaysAllowSkills.contains(skillName)) return true

        val preferences = context.skillPermissionsDataStore.data.first()
        val key = skillAlwaysAllowKey(skillName)
        val alwaysAllow = preferences[key] ?: false

        if (alwaysAllow) {
            alwaysAllowSkills.add(skillName)
        }

        return alwaysAllow
    }

    suspend fun getPermissionState(skillName: String, permissionName: String): SkillPermissionState {
        return getPermissionStateInternal(skillName, permissionName)
    }

    suspend fun clearPermission(skillName: String, permissionName: String) {
        context.skillPermissionsDataStore.edit { preferences ->
            val key = skillPermissionKey(skillName, permissionName)
            preferences.remove(key)
        }
    }

    suspend fun clearAllPermissions(skillName: String, permissions: List<PackagePermission>) {
        context.skillPermissionsDataStore.edit { preferences ->
            permissions.forEach { perm ->
                val key = skillPermissionKey(skillName, perm.name)
                preferences.remove(key)
            }
            val alwaysAllowKey = skillAlwaysAllowKey(skillName)
            preferences.remove(alwaysAllowKey)
        }
        alwaysAllowSkills.remove(skillName)
    }

    suspend fun getSkillPermissionStatus(skillName: String, permissions: List<PackagePermission>): Map<String, SkillPermissionState> {
        return permissions.associate { perm ->
            perm.name to getPermissionStateInternal(skillName, perm.name)
        }
    }

    fun hasPendingRequest(): Boolean {
        return _pendingPermissionRequests.value.isNotEmpty()
    }

    fun getCurrentPendingRequest(): Pair<String, PackagePermission>? {
        return _pendingPermissionRequests.value.firstOrNull()
    }

    fun getPermissionDescription(permission: PackagePermission, language: String = "zh"): String {
        return permission.description.resolve(language)
    }
}