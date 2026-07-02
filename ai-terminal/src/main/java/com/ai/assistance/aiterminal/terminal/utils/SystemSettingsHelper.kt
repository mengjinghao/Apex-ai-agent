package com.ai.assistance.aiterminal.terminal.utils

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.provider.Settings

object SystemSettingsHelper {
    fun setBrightness(contentResolver: ContentResolver, brightness: Int): Boolean {
        return try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness.coerceIn(0, 255)
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setMusicVolume(context: Context, volume: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (volume.toFloat() / 15 * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setScreenTimeout(contentResolver: ContentResolver, timeout: Int): Boolean {
        return try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                timeout.coerceIn(1000, 1800000)
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setBluetoothEnabled(contentResolver: ContentResolver, enabled: Boolean): Boolean {
        return try {
            Settings.System.putInt(
                contentResolver,
                Settings.Global.BLUETOOTH_ON,
                if (enabled) 1 else 0
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setAirplaneModeEnabled(contentResolver: ContentResolver, enabled: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enabled) 1 else 0
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setWiFiEnabled(contentResolver: ContentResolver, enabled: Boolean): Boolean {
        return try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.WIFI_ON,
                if (enabled) 1 else 0
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBrightness(contentResolver: ContentResolver): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            128
        }
    }

    fun getMusicVolume(context: Context): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            (currentVolume.toFloat() / maxVolume * 15).toInt()
        } catch (e: Exception) {
            7
        }
    }

    fun getScreenTimeout(contentResolver: ContentResolver): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
        } catch (e: Exception) {
            30000
        }
    }
}
