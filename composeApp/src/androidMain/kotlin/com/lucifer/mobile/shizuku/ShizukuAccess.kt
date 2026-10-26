package com.lucifer.mobile.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Status and explicit entry points for the Shizuku server and Permission Manager X.
 * Lucifer observes the grants made by the owner; it does not grant/revoke PMX permissions. */
object ShizukuAccess {
    const val REQUEST_CODE = 0x5348
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_MANAGER_X_PACKAGE = "com.mirfatif.permissionmanagerx"

    data class Status(
        val binderAvailable: Boolean,
        val permissionGranted: Boolean,
        val uid: Int?,
        val message: String
    ) {
        val ready: Boolean get() = binderAvailable && permissionGranted
    }

    fun status(): Status {
        val alive = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        if (!alive) return Status(false, false, null, "Shizuku server not running")
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            return Status(true, false, null, "Shizuku API v11+ required")
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        if (!granted) return Status(true, false, null, "Shizuku access not granted to Lucifer")
        val uid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
        val identity = when (uid) {
            0 -> "root"
            2000 -> "ADB shell"
            -1 -> "unknown Shizuku identity"
            else -> "UID $uid"
        }
        return Status(true, true, uid, "Connected · $identity")
    }

    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) false
        else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    } catch (_: Exception) { false }

    fun openShizuku(context: Context): Boolean = openPackage(context, SHIZUKU_PACKAGE)

    fun openPermissionManagerX(context: Context): Boolean = openPackage(context, PERMISSION_MANAGER_X_PACKAGE)

    fun isPermissionManagerXInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PERMISSION_MANAGER_X_PACKAGE, 0)
        true
    } catch (_: Exception) { false }

    private fun openPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
}
