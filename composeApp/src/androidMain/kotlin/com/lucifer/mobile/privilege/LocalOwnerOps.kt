package com.lucifer.mobile.privilege

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserHandle
import android.os.UserManager

/**
 * Lucifer as the device or profile owner in its own right.
 *
 * This is the one privileged path that needs no external app and no hidden API: every call below is a
 * public `DevicePolicyManager` method, so if the owner provisions Lucifer with `dpm set-device-owner`
 * (or transfers ownership from Dhizuku), the whole world feature works with no reflection at all.
 * Dhizuku exists for owners who want to keep that identity in a separate app; this exists so the
 * feature does not depend on a third-party package staying installed.
 */
class LuciferDeviceAdmin : DeviceAdminReceiver()

object LocalOwnerOps {

    data class Result(val ok: Boolean, val message: String)

    fun admin(context: Context): ComponentName = ComponentName(context, LuciferDeviceAdmin::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true
    } catch (_: Exception) {
        false
    }

    fun isProfileOwner(context: Context): Boolean = try {
        dpm(context)?.isProfileOwnerApp(context.packageName) == true
    } catch (_: Exception) {
        false
    }

    fun isOwner(context: Context): Boolean = isDeviceOwner(context) || isProfileOwner(context)

    fun setApplicationHidden(context: Context, pkg: String, hidden: Boolean, userId: Int = 0): Result {
        if (!PrivilegeCommands.validPackage(pkg)) return Result(false, "Enter a valid Android package name.")
        if (PrivilegeCommands.isProtected(pkg)) return Result(false, "$pkg is protected; Lucifer will not hide it.")
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.")
        return try {
            val changed = manager.setApplicationHidden(admin(context), pkg, hidden)
            Result(true, "${if (hidden) "Hidden" else "Restored"} $pkg${if (changed) "" else " (state already matched)"}.")
        } catch (error: Exception) {
            Result(false, "setApplicationHidden refused: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun setPackagesSuspended(context: Context, pkg: String, suspended: Boolean, userId: Int = 0): Result {
        if (!PrivilegeCommands.validPackage(pkg)) return Result(false, "Enter a valid Android package name.")
        if (PrivilegeCommands.isProtected(pkg)) return Result(false, "$pkg is protected; Lucifer will not suspend it.")
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.")
        return try {
            manager.setPackagesSuspended(admin(context), arrayOf(pkg), suspended)
            Result(true, "${if (suspended) "Suspended" else "Unsuspended"} $pkg.")
        } catch (error: Exception) {
            Result(false, "setPackagesSuspended refused: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun setPermissionGrantState(context: Context, pkg: String, permission: String, granted: Boolean): Result {
        if (!PrivilegeCommands.validPackage(pkg)) return Result(false, "Enter a valid Android package name.")
        if (PrivilegeCommands.isProtected(pkg)) {
            return Result(false, "$pkg is protected; Lucifer will not change its permission state.")
        }
        if (!PrivilegeCommands.validPermission(permission)) return Result(false, "That permission name is not valid.")
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.")
        return try {
            val state = if (granted) {
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            } else {
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            }
            val applied = manager.setPermissionGrantState(admin(context), pkg, permission, state)
            Result(applied, if (applied) "$permission ${if (granted) "granted to" else "denied for"} $pkg." else "The platform declined that grant change.")
        } catch (error: Exception) {
            Result(false, "setPermissionGrantState refused: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun addUserRestriction(context: Context, key: String): Result {
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.")
        return try {
            manager.addUserRestriction(admin(context), key)
            Result(true, "Restriction $key applied.")
        } catch (error: Exception) {
            Result(false, "addUserRestriction refused: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun logoutUser(context: Context, user: UserHandle): Result {
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.")
        return try {
            manager.logoutUser(admin(context), user)
            Result(true, "Logged that user out.")
        } catch (error: Exception) {
            Result(false, "logoutUser refused: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Creates the managed profile directly, when Lucifer *is* the owner. This is the no-shell path to
     * Lucifer's world: DevicePolicyManager makes the user, Lucifer becomes its profile owner, and the
     * cross-profile calls in [com.lucifer.mobile.world.WorldManager] can start launching into it.
     */
    fun createManagedProfile(context: Context, name: String): Pair<Result, UserHandle?> {
        val manager = dpm(context) ?: return Result(false, "DevicePolicyManager is unavailable on this build.") to null
        if (!isDeviceOwner(context)) {
            return Result(false, "Creating a profile directly needs Lucifer to be device owner; use Shizuku or Dhizuku instead.") to null
        }
        return try {
            val handle = manager.createAndManageUser(
                admin(context),
                name,
                admin(context),
                null,
                DevicePolicyManager.SKIP_SETUP_WIZARD
            )
            Result(true, "Created managed profile \"$name\".") to handle
        } catch (error: Exception) {
            Result(false, "createAndManageUser refused: ${error.message ?: error.javaClass.simpleName}") to null
        }
    }

    fun userRestrictionKeys(context: Context): List<String> = try {
        val userManager = context.getSystemService(UserManager::class.java)
        userManager?.userRestrictions?.keySet()?.sorted() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
