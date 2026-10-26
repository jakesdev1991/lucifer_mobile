package com.lucifer.mobile.omni_bridge.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import androidx.core.content.ContextCompat

/** Restarts only a heartbeat the user explicitly enabled earlier. Camera remains
 * disabled until the app is opened in the foreground, per Android's camera-FGS rules. */
class HeartbeatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!LuciferHeartbeatService.isEnabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            LuciferHeartbeatService.resumeAfterBoot(context)
        } catch (error: Exception) {
            Log.w(TAG, "Could not resume opted-in heartbeat after reboot", error)
        }
    }

    private companion object {
        const val TAG = "HeartbeatBootReceiver"
    }
}
