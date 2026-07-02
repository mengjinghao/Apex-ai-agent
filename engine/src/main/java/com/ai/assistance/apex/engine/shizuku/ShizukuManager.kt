package com.ai.assistance.apex.engine.shizuku

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ShizukuManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private var instance: ShizukuManager? = null

        fun getInstance(context: Context): ShizukuManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var isShizukuAvailable = false
    private var shizukuVersion = -1

    init {
        checkShizukuAvailability()
    }

    private fun checkShizukuAvailability() {
        try {
            shizukuVersion = Shizuku.getVersion()
            isShizukuAvailable = shizukuVersion > 0
        } catch (e: Exception) {
            isShizukuAvailable = false
        }
    }

    fun isAvailable(): Boolean = isShizukuAvailable

    fun getVersion(): Int = shizukuVersion

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    fun executeCommand(command: String): CommandResult {
        if (!isAvailable()) {
            return CommandResult(-1, "", "Shizuku is not available")
        }

        if (!isPermissionGranted()) {
            return CommandResult(-1, "", "Shizuku permission not granted")
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = if (process.waitFor(30, TimeUnit.SECONDS)) 0 else { process.destroyForcibly(); -1 }
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()

            CommandResult(exitCode, output, error)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Shizuku command execution failed")
        }
    }

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}