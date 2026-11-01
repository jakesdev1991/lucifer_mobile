// android.app.admin, as far as Lucifer's local-owner path touches it.
// Signatures mirror the API 35 SDK jar; see the note at the top of AndroidCore.kt.
//
// The old stub gave `logoutUser` a second `UserHandle` parameter the platform does not have and typed
// `createAndManageUser`'s extras as `Bundle` instead of `PersistableBundle`. Both are fixed here:
// a stub that is easier to call than the real API is a stub that hides a build break.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app.admin

import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.UserHandle

open class DeviceAdminReceiver

class DevicePolicyManager {
    fun isDeviceOwnerApp(pkg: String): Boolean = false
    fun isProfileOwnerApp(pkg: String): Boolean = false
    fun setApplicationHidden(admin: ComponentName, pkg: String, hidden: Boolean): Boolean = true
    fun setPackagesSuspended(admin: ComponentName, pkgs: Array<String>, suspended: Boolean): Array<String> = pkgs
    fun setPermissionGrantState(admin: ComponentName, pkg: String, permission: String, state: Int): Boolean = true
    fun addUserRestriction(admin: ComponentName, key: String) {}
    fun clearUserRestriction(admin: ComponentName, key: String) {}

    /** `logoutUser(ComponentName): int` — the target user is the caller's, not an argument. */
    fun logoutUser(admin: ComponentName): Int = 0

    fun createAndManageUser(
        admin: ComponentName,
        name: String,
        profileOwner: ComponentName,
        extras: PersistableBundle?,
        flags: Int
    ): UserHandle = UserHandle(10)

    companion object {
        const val SKIP_SETUP_WIZARD = 0x0001
        const val MAKE_USER_EPHEMERAL = 0x0002
        // Real values, read out of the API 35 jar. The old stub had DENIED = 16 and GRANTED = 0, which
        // are not the platform's numbers.
        const val PERMISSION_GRANT_STATE_DEFAULT = 0
        const val PERMISSION_GRANT_STATE_GRANTED = 1
        const val PERMISSION_GRANT_STATE_DENIED = 2
    }
}
