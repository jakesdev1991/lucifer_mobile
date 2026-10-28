// Minimal Android/JVM surface used by the verifier's Android adapters.
// Compile-only: these are NOT the real framework classes and never ship.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import java.io.File

open class Context {
    val packageName: String = "com.lucifer.mobile"
    val applicationContext: Context get() = this
    val filesDir: File = File("/data/user/0/com.lucifer.mobile/files")
    fun startForegroundService(intent: Intent) {}
    fun startService(intent: Intent) {}
    fun <T> getSystemService(serviceClass: Class<T>): T? = null
    fun <T> getSystemService(name: String): T? = null
}

class Intent(val action: String? = null) {
    constructor(context: Context, cls: Class<*>): this(null as String?)
    fun setAction(action: String?): Intent = this
    fun setClassName(pkg: String, cls: String): Intent = this
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
