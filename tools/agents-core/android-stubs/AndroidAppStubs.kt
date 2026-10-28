// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app

class Notification

abstract class Service : android.content.Context() {
    open fun onCreate() {}
    open fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int = 0
    open fun onDestroy() {}
    open fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
    fun startForeground(id: Int, notification: Notification) {}
    fun startForeground(id: Int, notification: Notification, type: Int) {}
    fun stopForeground(flags: Int) {}
    fun stopSelf() {}
    fun stopSelf(startId: Int) {}

    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}

class NotificationChannel(id: String, name: String, importance: Int) {
    var description: String = ""
}

class NotificationManager {
    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Notification) {}

    companion object {
        const val IMPORTANCE_LOW = 2
    }
}

class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT = 0x08000000
        const val FLAG_IMMUTABLE = 0x04000000
        fun getActivity(context: android.content.Context, requestCode: Int, intent: android.content.Intent, flags: Int): PendingIntent = PendingIntent()
        fun getService(context: android.content.Context, requestCode: Int, intent: android.content.Intent, flags: Int): PendingIntent = PendingIntent()
    }
}
