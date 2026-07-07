package com.apex.agent.core.permissions.rbac

import android.content.Context
import kotlinx.coroutines.runBlocking

class RbacGuard(private val context: Context) {

    private val checker: PermissionChecker by lazy { PermissionChecker(context) }

    fun require(userId: Long, permissionName: String): RbacGuardScope =
        RbacGuardScope(userId, permissionName, checker)

    class RbacGuardScope(
        private val userId: Long,
        private val permissionName: String,
        private val checker: PermissionChecker
    ) {
        fun orElse(onDenied: () -> Unit) {
            runBlocking {
                val result = checker.check(userId, permissionName)
                if (result is PermissionResult.Denied) {
                    onDenied()
                }
            }
        }

        suspend fun awaitOrElse(onDenied: suspend () -> Unit): Boolean {
            val result = checker.check(userId, permissionName)
            return when (result) {
                is PermissionResult.Granted -> true
                is PermissionResult.Denied -> {
                    onDenied()
                    false
                }
            }
        }

        suspend fun throwIfDenied() {
            checker.require(userId, permissionName)
        }
    }
}
