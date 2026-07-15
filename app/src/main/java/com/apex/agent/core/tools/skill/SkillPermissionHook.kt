package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.core.tools.PackagePermission
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.apex.agent.core.tools.defaultTool.standard.name

data class SkillPermissionRequest(
    val skillName: String,
    val permissions: List<PackagePermission>,
    val onResult: (Boolean) -> Unit
)

class SkillPermissionHook private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillPermissionHook"

        @Volatile private var INSTANCE: SkillPermissionHook? = null

        fun getInstance(context: Context): SkillPermissionHook {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPermissionHook(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val skillPermissionManager = SkillPermissionManager.getInstance(context)
        private val _permissionRequest = MutableStateFlow<SkillPermissionRequest?>(null)
        val permissionRequest: MutableStateFlow<SkillPermissionRequest?> = _permissionRequest

    private val _pendingSkillPermissions = MutableStateFlow<Map<String, List<PackagePermission>>>(emptyMap())
        val pendingSkillPermissions = _pendingSkillPermissions.asStateFlow()
        private var onPermissionRequestListener: ((String, List<PackagePermission>, (Boolean) -> Unit) -> Unit)? = null

    fun registerPermissionRequestListener(
        listener: (String, List<PackagePermission>, (Boolean) -> Unit) -> Unit
    ) {
        onPermissionRequestListener = listener
    }
        fun unregisterPermissionRequestListener() {
        onPermissionRequestListener = null
    }
        fun setSkillPermissions(skillName: String, permissions: List<PackagePermission>) {
        _pendingSkillPermissions.value = _pendingSkillPermissions.value.toMutableMap().apply {
            put(skillName, permissions)
        }
    }
        fun clearSkillPermissions(skillName: String) {
        _pendingSkillPermissions.value = _pendingSkillPermissions.value.toMutableMap().apply {
            remove(skillName)
        }
    }
        fun getSkillPermissions(skillName: String): List<PackagePermission> {
        return _pendingSkillPermissions.value[skillName] ?: emptyList()
    }
        fun hasSkillPermissions(skillName: String): Boolean {
        val permissions = _pendingSkillPermissions.value[skillName]
        return !permissions.isNullOrEmpty()
    }

    suspend fun checkAndRequestPermission(
        skillName: String,
        permissions: List<PackagePermission>
    ): Boolean {
        if (permissions.isEmpty()) {
            return true
        }
        val result = skillPermissionManager.checkSkillPermissions(skillName, permissions)
        if (result.allGranted) {
            AppLogger.d(TAG, "All permissions granted for skill: ${skillName}")
        return true
        }
        val missingRequired = result.missingRequired
        if (missingRequired.isEmpty()) {
            return true
        }

        AppLogger.d(TAG, "Missing required permissions for skill ${skillName}: ${missingRequired.map { it.name }}")
        val granted = requestPermissions(skillName, permissions)
        return granted
    }
        private suspend fun requestPermissions(
        skillName: String,
        permissions: List<PackagePermission>
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val request = SkillPermissionRequest(skillName, permissions) { granted ->
                scope.launch {
                    if (granted) {
                        skillPermissionManager.setSkillAlwaysAllow(skillName, false)
                    }
                }
                continuation.resume(granted)
            }

            _permissionRequest.value = request

            onPermissionRequestListener?.invoke(skillName, permissions) { granted ->
                scope.launch {
                    if (granted) {
                        permissions.forEach { perm ->
                            skillPermissionManager.savePermissionGrant(skillName, perm.name)
                        }
                    } else {
                        permissions.filter { it.required }.forEach { perm ->
                            skillPermissionManager.savePermissionDenial(skillName, perm.name)
                        }
                    }
                }
                _permissionRequest.value = null
                request.onResult(granted)
            }
        }
    }
        fun cancelPermissionRequest() {
        _permissionRequest.value?.let { request ->
            request.onResult(false)
            _permissionRequest.value = null
        }
    }
        fun hasPendingRequest(): Boolean {
        return _permissionRequest.value != null
    }
        fun getPendingRequest(): SkillPermissionRequest? {
        return _permissionRequest.value
    }

    suspend fun isSkillAlwaysAllowed(skillName: String): Boolean {
        return skillPermissionManager.isSkillAlwaysAllowed(skillName)
    }

    suspend fun setSkillAlwaysAllowed(skillName: String, alwaysAllow: Boolean) {
        skillPermissionManager.setSkillAlwaysAllow(skillName, alwaysAllow)
    }

    suspend fun clearSkillPermission(skillName: String) {
        val permissions = _pendingSkillPermissions.value[skillName] ?: emptyList()
        skillPermissionManager.clearAllPermissions(skillName, permissions)
        clearSkillPermissions(skillName)
    }
}