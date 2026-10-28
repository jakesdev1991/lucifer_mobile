// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.content

object ContextCompat {
    fun startForegroundService(context: android.content.Context, intent: android.content.Intent) {}
    fun checkSelfPermission(context: android.content.Context, permission: String): Int = 0
}
