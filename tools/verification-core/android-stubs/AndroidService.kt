// The service, notification and binder surface the verifier service touches.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app

import android.content.Context
import android.content.Intent
import android.os.IBinder

open class Service : Context() {
    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    open fun onDestroy() {}
    open fun onBind(intent: Intent?): IBinder? = null
    fun startForeground(id: Int, notification: Notification) {}
    fun stopForeground(flags: Int) {}
    fun stopSelf() {}
    fun stopSelf(startId: Int) {}

    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}

class Notification {
    class Builder(val context: Context, val channelId: String) {
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setSmallIcon(icon: Int): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun build(): Notification = Notification()
    }
}

class NotificationChannel(
    val id: String,
    val name: CharSequence,
    val importance: Int
) {
    var description: String? = null
    fun setShowBadge(show: Boolean) {}
}

class NotificationManager {
    fun getNotificationChannel(id: String): NotificationChannel? = null
    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Notification) {}
    fun cancel(id: Int) {}

    companion object { const val IMPORTANCE_LOW = 2 }
}

class PendingIntent {
    companion object {
        const val FLAG_IMMUTABLE = 0x04000000
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent =
            PendingIntent()
    }
}
