package com.lucifer.mobile.omni_bridge.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import androidx.core.content.ContextCompat
import com.lucifer.mobile.agents.android.TacticianService

/** Restarts only a heartbeat the user explicitly enabled earlier. Camera remains
 * disabled until the app is opened in the foreground, per Android's camera-FGS rules. */
class HeartbeatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val heartbeatWanted = LuciferHeartbeatService.isEnabled(context)
        val tacticianWanted = TacticianService.isEnabled(context)
        if (!heartbeatWanted && !tacticianWanted) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (heartbeatWanted) {
            try {
                LuciferHeartbeatService.resumeAfterBoot(context)
            } catch (error: Exception) {
                Log.w(TAG, "Could not resume opted-in heartbeat after reboot", error)
            }
        }
        if (tacticianWanted) {
            try {
                TacticianService.resumeAfterBoot(context)
            } catch (error: Exception) {
                Log.w(TAG, "Could not resume opted-in tactician after reboot", error)
            }
        }
    }

    private companion object {
        const val TAG = "HeartbeatBootReceiver"
    }
}
