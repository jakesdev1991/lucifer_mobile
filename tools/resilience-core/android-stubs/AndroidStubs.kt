// Type-check-only stand-ins for the Android/JVM APIs used by ResilienceEngine.
//
// These are NOT shipped and are never compiled into the app. They exist so the host harness can
// type-check the Android adapter (ResilienceEngine.kt) without an Android SDK. Runtime behavior
// and Compose UI remain unverified on this host.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: java.io.File get() = java.io.File("/tmp/lucifer-files")
}
