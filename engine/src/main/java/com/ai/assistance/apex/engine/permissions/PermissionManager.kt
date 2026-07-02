package com.ai.assistance.apex.engine.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
        private const val REQUEST_CODE_BASE = 1000
    }

    fun checkPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(permission: String): Boolean {
        Log.w(TAG, "requestPermission() requires an Activity context to actually show the system dialog. Use requestPermission(activity, permission) instead, or the permission may already be granted.")
        return checkPermission(permission)
    }

    fun requestPermission(activity: Activity, permission: String) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(permission),
            REQUEST_CODE_BASE + permission.hashCode()
        )
    }

    fun requestPermissions(activity: Activity, vararg permissions: String) {
        ActivityCompat.requestPermissions(
            activity,
            permissions,
            REQUEST_CODE_BASE + permissions.contentHashCode()
        )
    }

    fun checkAllPermissions(vararg permissions: String): Boolean {
        return permissions.all { checkPermission(it) }
    }

    fun getRequiredPermissions(): List<String> {
        return buildList {
            add(android.Manifest.permission.INTERNET)
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(android.Manifest.permission.ACCESS_NETWORK_STATE)
            add(android.Manifest.permission.ACCESS_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun getDangerousPermissions(): List<String> {
        return buildList {
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}