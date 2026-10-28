// Minimal Android/JVM surface used by Lucifer's privilege + world adapters.
// Compile-only: these are NOT the real framework classes and never ship.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle

open class Context {
    val packageName: String = "com.lucifer.mobile"
    val packageManager: PackageManager = PackageManager()
    val applicationContext: Context get() = this
    fun startActivity(intent: Intent) {}
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    fun <T> getSystemService(serviceClass: Class<T>): T? = null
    fun <T> getSystemService(name: String): T? = null
    companion object { const val MODE_PRIVATE = 0 }
}

class Intent(val action: String? = null) {
    constructor(context: Context, cls: Class<*>): this(null as String?)
    fun addFlags(flags: Int): Intent = this
    fun setClassName(pkg: String, cls: String): Intent = this
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}

class ComponentName(val pkg: String, val cls: Class<*>? = null) {
    constructor(context: Context, cls: Class<*>): this(context.packageName, cls)
}

open class SharedPreferences {
    fun getInt(key: String, def: Int): Int = def
    fun edit(): Editor = Editor()
    class Editor {
        fun putInt(key: String, value: Int): Editor = this
        fun remove(key: String): Editor = this
        fun apply() {}
    }
}

class CrossProfileApps {
    fun canInteractAcrossProfiles(): Boolean = false
    fun canRequestInteractAcrossProfiles(): Boolean = false
    fun requestInteractAcrossProfiles() {}
    fun startMainActivity(component: ComponentName, user: UserHandle) {}
    fun getTargetUserProfiles(): List<UserHandle> = emptyList()
}


