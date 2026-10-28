// Type-check-only stand-ins for the Android/JVM APIs used by the agent-ecosystem adapters.
//
// These are NOT shipped and never compiled into the app. They exist so the host harness can
// type-check the adapters without an Android SDK. Runtime behaviour, notifications, foreground
// services, and Compose UI remain unverified on this host.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import java.io.File
import java.io.FileInputStream

open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File("/tmp/lucifer-files")
    val assets: AssetManager get() = AssetManager()

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
    fun <T> getSystemService(clazz: Class<T>): T = throw UnsupportedOperationException("stub")
    fun startService(intent: Intent) {}

    companion object {
        const val MODE_PRIVATE = 0
    }
}

class AssetManager {
    fun open(path: String): FileInputStream = FileInputStream(File("/tmp/lucifer-assets/$path"))
}

class SharedPreferences {
    fun getBoolean(key: String, default: Boolean): Boolean = default
    fun getLong(key: String, default: Long): Long = default
    fun getInt(key: String, default: Int): Int = default
    fun getString(key: String, default: String?): String? = default
    fun edit(): Editor = Editor()

    class Editor {
        fun putBoolean(key: String, value: Boolean): Editor = this
        fun putLong(key: String, value: Long): Editor = this
        fun putInt(key: String, value: Int): Editor = this
        fun putString(key: String, value: String?): Editor = this
        fun apply() {}
        fun commit(): Boolean = true
    }
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent?)
}

class Intent {
    constructor(action: String)
    constructor(context: Context, cls: Class<*>)
    var action: String? = null
    fun setAction(action: String?): Intent = this
    fun addFlags(flags: Int): Intent = this
    fun getIntExtra(name: String, defaultValue: Int): Int = defaultValue
    fun getStringExtra(name: String): String? = null

    companion object {
        const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
    }
}

class IntentFilter(action: String? = null)
