package com.lucifer.mobile.agents.android

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

/**
 * The General's always-on loop.
 *
 * A user-enabled foreground service that wakes on an interval, ingests what Lucifer already
 * recorded, and produces a bounded tactical brief with proposals. It has no execution path: it
 * cannot run a command, change a permission, or edit a prompt on its own. Approval happens in the
 * Agents panel, in the foreground, by the user.
 */
class TacticianService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                loopJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_BOOT_RESUME -> prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .apply()
            ACTION_RUN_NOW -> prefs.edit().putLong(KEY_NEXT_RUN_AT, 0L).apply()
            null -> if (!prefs.getBoolean(KEY_ENABLED, false)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startTacticianForeground("Watching device signals and preparing proposals.")
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                val runner = TacticianRunner(applicationContext)
                while (isActive && prefs.getBoolean(KEY_ENABLED, false)) {
                    val dueAt = prefs.getLong(KEY_NEXT_RUN_AT, 0L)
                    val waitMs = dueAt - System.currentTimeMillis()
                    if (waitMs > 0L) delay(waitMs)
                    if (!isActive || !prefs.getBoolean(KEY_ENABLED, false)) break
                    try {
                        val outcome = runner.runOnce("background")
                        Log.i(TAG, outcome.message)
                        updateNotification(outcome.message.take(160))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "tactician pass failed", error)
                        updateNotification("Pass error: ${error.message ?: "unknown"}")
                    }
                    prefs.edit().putLong(KEY_NEXT_RUN_AT, System.currentTimeMillis() + PASS_INTERVAL_MS).apply()
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTacticianForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
    }

    private fun updateNotification(message: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
        } catch (error: Exception) {
            Log.w(TAG, "notification update failed", error)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, TacticianService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("The General is watching")
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
                CHANNEL_ID, "Tactician", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status and stop control for the background tactician. It proposes; it never executes."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.lucifer.mobile.tactician.START"
        const val ACTION_STOP = "com.lucifer.mobile.tactician.STOP"
        const val ACTION_BOOT_RESUME = "com.lucifer.mobile.tactician.BOOT_RESUME"
        const val ACTION_RUN_NOW = "com.lucifer.mobile.tactician.RUN_NOW"
        const val PREFS = "lucifer_tactician"
        const val KEY_ENABLED = "enabled"
        private const val KEY_NEXT_RUN_AT = "next_run_at"
        const val PASS_INTERVAL_MS = 60L * 60L * 1_000L
        private const val TAG = "TacticianService"
        private const val CHANNEL_ID = "lucifer_tactician"
        private const val NOTIFICATION_ID = 4811
        private const val REQUEST_OPEN = 4812
        private const val REQUEST_STOP = 4813

        @Volatile private var running: Boolean = false

        fun isRunning(): Boolean = running

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Only call after POST_NOTIFICATIONS is granted (or on API < 33). */
        fun start(context: Context) {
            val intent = Intent(context, TacticianService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resumeAfterBoot(context: Context) {
            val intent = Intent(context, TacticianService::class.java).setAction(ACTION_BOOT_RESUME)
            ContextCompat.startForegroundService(context, intent)
        }

        fun runNow(context: Context) {
            val intent = Intent(context, TacticianService::class.java).setAction(ACTION_RUN_NOW)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TacticianService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (error: Exception) {
                Log.w(TAG, "stop failed", error)
            }
        }
    }
}
