@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app.admin

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserHandle

open class DeviceAdminReceiver

class DevicePolicyManager {
    fun isDeviceOwnerApp(pkg: String): Boolean = false
    fun isProfileOwnerApp(pkg: String): Boolean = false
    fun setApplicationHidden(admin: ComponentName, pkg: String, hidden: Boolean): Boolean = true
    fun setPackagesSuspended(admin: ComponentName, pkgs: Array<String>, suspended: Boolean): Array<String> = pkgs
    fun setPermissionGrantState(admin: ComponentName, pkg: String, permission: String, state: Int): Boolean = true
    fun addUserRestriction(admin: ComponentName, key: String) {}
    fun logoutUser(admin: ComponentName, user: UserHandle): Int = 0
    fun createAndManageUser(
        admin: ComponentName,
        name: String,
        profileOwner: ComponentName,
        extras: Bundle?,
        flags: Int
    ): UserHandle = UserHandle(10)

    companion object {
        const val SKIP_SETUP_WIZARD = 0x0001
        const val MAKE_USER_EPHEMERAL = 0x0002
        const val PERMISSION_GRANT_STATE_GRANTED = 0
        const val PERMISSION_GRANT_STATE_DENIED = 16
    }
}
