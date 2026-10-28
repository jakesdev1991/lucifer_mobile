// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.app

class NotificationCompat {
    class Builder(context: android.content.Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: String?): Builder = this
        fun setContentText(text: String?): Builder = this
        fun setStyle(style: Style): Builder = this
        fun setContentIntent(intent: android.app.PendingIntent?): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun addAction(icon: Int, title: String?, intent: android.app.PendingIntent?): Builder = this
        fun build(): android.app.Notification = android.app.Notification()
    }

    open class Style
    class BigTextStyle : Style() {
        fun bigText(text: String?): BigTextStyle = this
    }
}
