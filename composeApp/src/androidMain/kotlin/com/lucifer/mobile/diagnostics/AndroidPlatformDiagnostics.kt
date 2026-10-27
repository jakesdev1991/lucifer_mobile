package com.lucifer.mobile.diagnostics

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process

/** Read-only, in-memory snapshot of Android build, power, process, and special-access state. */
object AndroidPlatformDiagnostics {
    fun snapshot(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)

        val isPixel8a = Build.DEVICE.equals("akita", ignoreCase = true) ||
            (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 8a", ignoreCase = true))
        val deviceName = if (isPixel8a) "Google Pixel 8a (akita)" else "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        val usageAccess = runCatching {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        val activeVpn = runCatching {
            val network = connectivity.activeNetwork
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { activity.isBackgroundRestricted }.getOrNull()?.toString() ?: "unavailable"
        } else "unsupported (requires API 28+)"
        val idleMode = runCatching { power.isDeviceIdleMode }.getOrDefault(false)
        val powerSave = runCatching { power.isPowerSaveMode }.getOrDefault(false)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { thermalLabel(power.currentThermalStatus) }.getOrDefault("unavailable")
        } else "unsupported (requires API 29+)"
        val processImportance = importanceLabel(processState.importance)

        return buildString {
            appendLine("Device: $deviceName")
            appendLine("Android: ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · security patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Build: ${Build.DISPLAY} · type=${Build.TYPE}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("Kernel: ${System.getProperty("os.version") ?: "unknown"}")
            appendLine("Lucifer process: pid=${Process.myPid()} · importance=$processImportance · backgroundRestricted=$backgroundRestricted")
            appendLine("Power: idleMode=$idleMode · powerSave=$powerSave · thermal=$thermal")
            appendLine("Lucifer Usage Access: $usageAccess (status only; no other-app usage history read)")
            appendLine("System VPN active: $activeVpn")
            appendLine("Lucifer packet VPN/traffic capture: NOT IMPLEMENTED")
            append("Scope: local read-only snapshot; not persisted or sent to an AI service. OS/OEM behavior may vary.")
        }
    }

    private fun importanceLabel(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground-service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "importance-$importance"
    }

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "status-$status"
    }
}
