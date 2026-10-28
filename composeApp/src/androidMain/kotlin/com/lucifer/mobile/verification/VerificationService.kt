package com.lucifer.mobile.verification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Holds the verifier's loopback socket open while the owner has switched the server on.
 *
 * This is the only component that keeps the endpoint alive, and it is deliberately dumb: it starts a
 * listener on 127.0.0.1, shows a notification with the port and the metered total, and stops when told
 * to. It opens no tunnel, runs no Lean of its own, and cannot be started by a client — only by the
 * owner (or by Lucifer, in the foreground, with the panel visible).
 */
class VerificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        startForeground(NOTIFICATION_ID, notification())
        val bound = VerifierController.startEndpoint(applicationContext)
        isRunning = bound > 0
        updateNotification(
            if (bound > 0) "Listening on 127.0.0.1:$bound · clients need a bearer key."
            else "Port ${VerifierController.PORT} is already in use; the endpoint did not start."
        )
        if (bound <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        VerifierController.stopEndpoint()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        VerifierController.stopEndpoint()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lean verification server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Lucifer's proof-checking endpoint is running."
                setShowBadge(false)
            }
        )
    }

    private fun notification(
        text: String = "127.0.0.1:${VerifierController.PORT} · bearer key required"
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "com.lucifer.mobile.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lean verifier running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
            .onFailure { Log.w(TAG, "could not update notification", it) }
    }

    companion object {
        private const val TAG = "LuciferVerifier"
        private const val CHANNEL_ID = "lucifer_verifier"
        private const val NOTIFICATION_ID = 0x4C56

        const val ACTION_START = "com.lucifer.mobile.verification.START"
        const val ACTION_STOP = "com.lucifer.mobile.verification.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, VerificationService::class.java)
                    .setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, VerificationService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }
}
