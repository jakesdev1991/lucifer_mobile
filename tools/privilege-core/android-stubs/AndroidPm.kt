@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content.pm

import android.content.Intent
import android.os.UserHandle

class PackageManager {
    fun getPackageInfo(pkg: String, flags: Int): PackageInfo = PackageInfo(pkg)
    fun getLaunchIntentForPackage(pkg: String): Intent? = null
    class NameNotFoundException : Exception()

    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
    }
}

class PackageInfo(val packageName: String)

class ApplicationInfo(val packageName: String)

class LauncherActivityInfo(val componentName: android.content.ComponentName, val applicationInfo: ApplicationInfo)

class LauncherApps {
    fun getApplicationInfo(pkg: String, flags: Int, user: UserHandle): ApplicationInfo = ApplicationInfo(pkg)
    fun getActivityList(pkg: String?, user: UserHandle): List<LauncherActivityInfo> = emptyList()
}

class UserInfo(val id: Int, val name: String?, val isManagedProfile: Boolean)

