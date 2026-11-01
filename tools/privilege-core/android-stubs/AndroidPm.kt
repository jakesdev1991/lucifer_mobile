// android.content.pm, as far as Lucifer's world touches it.
// Signatures mirror the API 35 SDK jar; see the note at the top of AndroidCore.kt.
//
// `android.content.pm.UserInfo` is deliberately absent: the class is `@hide` in the platform and is not
// in android.jar at all, so the stub had no business declaring it. Lucifer reads user state from
// `pm list users` text (see world/WorldCore.kt) and from UserManager, both of which are public.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content.pm

import android.content.ComponentName
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

// The real LauncherActivityInfo has no public constructor — the system makes them. Stub-only, for the
// harnesses that need an instance to hand back.
class LauncherActivityInfo(val componentName: ComponentName, val applicationInfo: ApplicationInfo)

class LauncherApps {
    fun getApplicationInfo(pkg: String, flags: Int, user: UserHandle): ApplicationInfo = ApplicationInfo(pkg)
    fun getActivityList(pkg: String?, user: UserHandle): List<LauncherActivityInfo> = emptyList()
    fun isPackageEnabled(pkg: String, user: UserHandle): Boolean = true
    fun isActivityEnabled(component: ComponentName, user: UserHandle): Boolean = true
    fun getProfiles(): List<UserHandle> = emptyList()
}

/**
 * The sanctioned cross-profile channel. Every method below exists in android.jar (API 35), so a call
 * that compiles here compiles under AGP:
 *
 *   startMainActivity(ComponentName, UserHandle): void
 *   getTargetUserProfiles(): List<UserHandle>
 *   canRequestInteractAcrossProfiles(): boolean
 *   canInteractAcrossProfiles(): boolean
 *   createRequestInteractAcrossProfilesIntent(): Intent
 *   isManagedProfile(UserHandle): boolean
 *   isProfile(UserHandle): boolean
 */
class CrossProfileApps {
    fun startMainActivity(component: ComponentName, targetUser: UserHandle) {}
    fun getTargetUserProfiles(): List<UserHandle> = emptyList()
    fun canRequestInteractAcrossProfiles(): Boolean = false
    fun canInteractAcrossProfiles(): Boolean = false
    fun createRequestInteractAcrossProfilesIntent(): Intent = Intent()
    fun isManagedProfile(userHandle: UserHandle): Boolean = false
    fun isProfile(userHandle: UserHandle): Boolean = false
}
