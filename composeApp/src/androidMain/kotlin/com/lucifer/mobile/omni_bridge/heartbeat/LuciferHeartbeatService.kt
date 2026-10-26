package com.lucifer.mobile.omni_bridge.heartbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lucifer.mobile.MainActivity
import com.lucifer.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Explicitly enabled foreground service. Runs one bounded planning/drafting turn,
 * then waits 30 minutes after completion before the next turn. */
class LuciferHeartbeatService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            sessionJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_START -> preferences.edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_CAMERA_ENABLED, false)
                .putLong(KEY_NEXT_RUN_AT, 0L)
                .apply()
            ACTION_BOOT_RESUME -> preferences.edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_CAMERA_ENABLED, false)
                .apply()
            ACTION_ENABLE_CAMERA -> preferences.edit().putBoolean(KEY_CAMERA_ENABLED, true).apply()
            null -> {
                if (!preferences.getBoolean(KEY_ENABLED, false)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // A sticky restart may occur while the app is backgrounded. Camera FGS
                // access is while-in-use restricted, so resume text-only until the user
                // foregrounds the app and explicitly enables camera again.
                preferences.edit().putBoolean(KEY_CAMERA_ENABLED, false).apply()
            }
        }
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val cameraEnabled = preferences.getBoolean(KEY_CAMERA_ENABLED, false)
        startHeartbeatForeground(cameraEnabled)
        if (sessionJob?.isActive != true) {
            sessionJob = serviceScope.launch {
                val engine = HeartbeatSessionEngine(applicationContext)
                val camera = HeartbeatCameraCapture(applicationContext)
                while (isActive && preferences.getBoolean(KEY_ENABLED, false)) {
                    val dueAt = preferences.getLong(KEY_NEXT_RUN_AT, 0L)
                    val waitMs = dueAt - System.currentTimeMillis()
                    if (waitMs > 0L) delay(waitMs)
                    if (!isActive || !preferences.getBoolean(KEY_ENABLED, false)) break
                    try {
                        val cameraJpeg = if (preferences.getBoolean(KEY_CAMERA_ENABLED, false)) camera.captureJpeg() else null
                        val result = try {
                            engine.runSession(cameraJpeg)
                        } finally {
                            cameraJpeg?.fill(0)
                        }
                        Log.i(TAG, result)
                        updateNotification(result.take(160))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "session failed", error)
                        updateNotification("Session error: ${error.message ?: "unknown"}")
                    }
                    preferences.edit().putLong(KEY_NEXT_RUN_AT, System.currentTimeMillis() + SESSION_INTERVAL_MS).apply()
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startHeartbeatForeground(cameraEnabled: Boolean) {
        val notification = buildNotification(
            if (cameraEnabled) "Camera may be used during idea/project sessions. Stop to end access."
            else "Active in text-only mode. Open the app to re-enable camera access."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                (if (cameraEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, LuciferHeartbeatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lucifer heartbeat is active")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopService)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Heartbeat sessions", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Visible status and stop control for opted-in 30-minute sessions." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false
        sessionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.lucifer.mobile.heartbeat.START"
        const val ACTION_STOP = "com.lucifer.mobile.heartbeat.STOP"
        const val ACTION_BOOT_RESUME = "com.lucifer.mobile.heartbeat.BOOT_RESUME"
        const val ACTION_ENABLE_CAMERA = "com.lucifer.mobile.heartbeat.ENABLE_CAMERA"
        const val PREFS = "lucifer_heartbeat"
        const val KEY_ENABLED = "enabled"
        private const val KEY_CAMERA_ENABLED = "camera_enabled"
        private const val KEY_NEXT_RUN_AT = "next_run_at"
        const val SESSION_INTERVAL_MS = 30L * 60L * 1_000L
        private const val TAG = "LuciferHeartbeat"
        private const val CHANNEL_ID = "lucifer_heartbeat"
        private const val NOTIFICATION_ID = 4801
        private const val REQUEST_OPEN = 4802
        private const val REQUEST_STOP = 4803
        @Volatile private var running: Boolean = false

        fun isRunning(): Boolean = running

        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

        fun isCameraEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAMERA_ENABLED, false)

        fun start(context: Context) {
            val intent = Intent(context, LuciferHeartbeatService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun enableCamera(context: Context) {
            val intent = Intent(context, LuciferHeartbeatService::class.java).setAction(ACTION_ENABLE_CAMERA)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resumeAfterBoot(context: Context) {
            val intent = Intent(context, LuciferHeartbeatService::class.java).setAction(ACTION_BOOT_RESUME)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LuciferHeartbeatService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
