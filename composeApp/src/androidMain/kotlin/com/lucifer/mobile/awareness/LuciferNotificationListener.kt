package com.lucifer.mobile.awareness

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Opt-in notification listener. The user must enable it for com.lucifer.mobile in
 * Settings > Special app access > Notification access — the app cannot enable itself.
 *
 * Stored records are MINIMAL BY DEFAULT: package name + notification title + timestamp.
 * No text body, no extras beyond what the UI shows, nothing persisted to disk unless the
 * user clears/acks in the NOTIFICATIONS panel.
 */
class LuciferNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val entry = NotificationStore.Entry(
            packageName = sbn.packageName ?: "",
            title = n.notification.extras.getString(android.app.Notification.EXTRA_TITLE) ?: "",
            timestamp = sbn.postTime
        )
        NotificationStore.add(entry)
    }

    override fun onListenerConnected() {
        NotificationStore.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        NotificationStore.setListenerConnected(false)
    }
}

/** In-memory, app-private record of seen notifications. Bounded; clearable from the UI. */
object NotificationStore {
    private const val CAP = 200

    data class Entry(val packageName: String, val title: String, val timestamp: Long)

    @Volatile
    var listenerConnected: Boolean = false

    internal fun setListenerConnected(connected: Boolean) {
        listenerConnected = connected
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun add(entry: Entry) = synchronized(lock) {
        entries.addFirst(entry)
        while (entries.size > CAP) entries.removeLast()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    fun ack(timestamp: Long) = synchronized(lock) { entries.removeAll { it.timestamp == timestamp } }

    fun statusLine(context: Context): String {
        val enabled = AwarenessSurfaces.notificationListenerEnabled(context)
        val count = snapshot().size
        return when {
            !enabled -> "OFF — enable Lucifer in Settings > Notification access (the app cannot enable itself). Records kept: $count"
            else -> "ON (system-granted) · $count recorded · package+title+timestamp only"
        }
    }
}
