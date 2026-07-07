package com.apex.agent.core.tools.system.shell

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.apex.agent.util.AppLogger
import com.apex.agent.core.tools.system.AndroidPermissionLevel
import com.apex.agent.core.tools.system.ShellIdentity
import com.apex.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 基于设备管理员的Shell命令执行。实现ADMIN权限级别的命令执�?/
class AdminShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val TAG = "AdminShellExecutor"
        private var adminComponentName: ComponentName? = null

        /**
         * 设置设备管理员组件名�?        * @param componentName 设备管理员组件名�?        */
        fun setAdminComponentName(componentName: ComponentName) {
            adminComponentName = componentName
        }
    }

    private val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ADMIN

    override fun isAvailable(): Boolean {
        return adminComponentName != null && isDeviceAdminActive()
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        if (adminComponentName == null) {
            return ShellExecutor.PermissionStatus.denied("Device admin component name not set")
        }

        return if (isDeviceAdminActive()) {
            ShellExecutor.PermissionStatus.granted()
        } else {
            ShellExecutor.PermissionStatus.denied("Device admin is not active for this app")
        }
    }

    override fun initialize() {
        // 设备管理员初始化由系统控制，此处无需额外操作
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (isAvailable()) {
            onResult(true)
            return
        }

        if (adminComponentName == null) {
            AppLogger.e(TAG, "Admin component name not set")
            onResult(false)
            return
        }

        // 引导用户激活设备管理员
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_shell_requires_permission))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            // 由于无法知道用户是否激活了管理员，返回false，让调用者自行处理后续检�?           onResult(false)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error opening device admin settings", e)
            onResult(false)
        }
    }

    /** 检查设备管理员是否已激�?/
    private fun isDeviceAdminActive(): Boolean {
        return try {
            adminComponentName?.let { devicePolicyManager.isAdminActive(it) } ?: false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking device admin status", e)
            false
        }
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult =
            withContext(Dispatchers.IO) {
                val permStatus = hasPermission()
                if (!permStatus.granted) {
                    return@withContext ShellExecutor.CommandResult(false, "", permStatus.reason, -1)
                }

                AppLogger.d(TAG, "Executing command via device admin: ${command}")

                // 设备管理员API不能直接执行shell命令，但可以执行一些系统操�?               // 这里实现将根据实际可用的管理员API而定

                try {
                    when {
                        command.startsWith("lockscreen") -> {
                            devicePolicyManager.lockNow()
                            return@withContext ShellExecutor.CommandResult(
                                    true,
                                    "Screen locked",
                                    "",
                                    0
                            )
                        }
                        command.startsWith("wipe") -> {
                            devicePolicyManager.wipeData(0)
                            return@withContext ShellExecutor.CommandResult(
                                    true,
                                    "Device wipe initiated",
                                    "",
                                    0
                            )
                        }
                        // 可以添加更多设备管理员API支持的操�?                       else -> {
                            return@withContext ShellExecutor.CommandResult(
                                    false,
                                    "",
                                    "Unsupported command for device admin: ${command}",
                                    -1
                            )
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error executing admin command", e)
                    return@withContext ShellExecutor.CommandResult(
                            false,
                            "",
                            "Error: ${e.message}",
                            -1
                    )
                }
            }

    override suspend fun startProcess(command: String): ShellProcess {
        return AdminShellProcess(command, this)
    }
}

/**
 * 设备管理员的 ShellProcess 实现
 */
private class AdminShellProcess(
    private val command: String,
    private val executor: AdminShellExecutor
) : ShellProcess {
    private var completed = false
    private var exitCode = -1
    private var result: ShellExecutor.CommandResult? = null
    
    init {
        // 异步执行命令
        CoroutineScope(Dispatchers.IO).launch {
            result = executor.executeCommand(command, ShellIdentity.DEFAULT)
            exitCode = result?.exitCode ?: -1
            completed = true
        }
    }
    
    override val stdout: Flow<String> = callbackFlow {
        while (!completed) {
            kotlinx.coroutines.delay(10)
        }
        result?.stdout?.let { output ->
            if (output.isNotEmpty()) {
                trySend(output)
            }
        }
        close()
        awaitClose { }
    }

    override val stderr: Flow<String> = callbackFlow {
        while (!completed) {
            kotlinx.coroutines.delay(10)
        }
        result?.stderr?.let { error ->
            if (error.isNotEmpty()) {
                trySend(error)
            }
        }
        close()
        awaitClose { }
    }

    override val isAlive: Boolean
        get() = !completed

    override fun destroy() {
        completed = true
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        while (!completed) {
            kotlinx.coroutines.delay(10)
        }
        exitCode
            }
}
