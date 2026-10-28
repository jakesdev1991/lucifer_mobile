package com.lucifer.mobile.world

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.lucifer.mobile.privilege.LedgerEntry
import com.lucifer.mobile.privilege.LocalOwnerOps
import com.lucifer.mobile.privilege.PrivilegeLedger
import com.lucifer.mobile.privilege.PrivilegeOp
import com.lucifer.mobile.privilege.PrivilegeOutcome
import com.lucifer.mobile.privilege.PrivilegeRouter
import com.lucifer.mobile.privilege.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lucifer's world, on the device.
 *
 * The containment is Android's, not Lucifer's: a managed profile is a separate user with its own
 * package data, its own accounts and its own processes. Lucifer can be handed that user, launch into
 * it through the sanctioned [android.content.CrossProfileApps] API, and start the VM there — and when
 * the profile is stopped, everything inside it is frozen with it.
 *
 * Two things he cannot do, and says so: read the owner's data (cross-profile launches do not grant
 * data access), and act in the profile without the owner asking for that specific action.
 */
class WorldManager(private val context: Context) {

    private val router = PrivilegeRouter(context)
    private val prefs = context.getSharedPreferences("lucifer_world", Context.MODE_PRIVATE)

    private val userManager: UserManager? = context.getSystemService(UserManager::class.java)
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)
    private val crossProfileApps = if (Build.VERSION.SDK_INT >= 28) {
        context.getSystemService(android.content.CrossProfileApps::class.java)
    } else {
        null
    }

    /** `Process.myUid() / 100000` is the only fully public way to read this. */
    fun currentUserId(): Int = Process.myUid() / 100_000

    fun preferredWorldId(): Int? = prefs.getInt(KEY_WORLD_USER, -1).takeIf { it >= 0 }

    fun rememberWorld(userId: Int) {
        prefs.edit().putInt(KEY_WORLD_USER, userId).apply()
    }

    fun forgetWorld() {
        prefs.edit().remove(KEY_WORLD_USER).apply()
    }

    private fun managedProfiles(): List<AndroidUser> {
        val manager = userManager ?: return emptyList()
        return try {
            manager.userProfiles.mapNotNull { handle ->
                val info: UserInfo = runCatching { manager.getUserInfo(handle) }.getOrNull()
                    ?: return@mapNotNull null
                if (!info.isManagedProfile || info.id == currentUserId()) return@mapNotNull null
                AndroidUser(
                    id = info.id,
                    name = info.name ?: "profile ${info.id}",
                    flags = if (info.isManagedProfile) AndroidUser.FLAG_MANAGED_PROFILE else 0,
                    running = true
                )
            }.distinctBy { it.id }.sortedBy { it.id }
        } catch (error: Exception) {
            Log.w("LuciferWorld", "profile enumeration failed: ${error.message}")
            emptyList()
        }
    }

    private fun handleFor(userId: Int): UserHandle? {
        val manager = userManager ?: return null
        return manager.userProfiles.firstOrNull { handle ->
            runCatching { manager.getUserInfo(handle).id }.getOrNull() == userId
        }
    }

    fun crossProfileAllowed(): Boolean {
        val apps = crossProfileApps ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) apps.canInteractAcrossProfiles() else true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when Android would let Lucifer ask for cross-profile interaction. On API 30+ that needs
     * either a device/profile-owner relationship or the owner approving the request once.
     */
    fun canRequestCrossProfile(): Boolean {
        val apps = crossProfileApps ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) apps.canInteractAcrossProfiles()
            else apps.canRequestInteractAcrossProfiles()
        } catch (_: Exception) {
            false
        }
    }

    fun requestCrossProfile(): Boolean {
        val apps = crossProfileApps ?: return false
        return try {
            if (Build.VERSION.SDK_INT < 30) {
                apps.requestInteractAcrossProfiles()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun isInstalledIn(userId: Int, pkg: String): Boolean {
        val handle = handleFor(userId) ?: return false
        val apps = launcherApps ?: return false
        return try {
            apps.getApplicationInfo(pkg, 0, handle)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun packagesIn(userId: Int, limit: Int = 60): List<String> {
        val handle = handleFor(userId) ?: return emptyList()
        val apps = launcherApps ?: return emptyList()
        return try {
            apps.getActivityList(null, handle)
                .map { it.applicationInfo.packageName }
                .distinct()
                .sorted()
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun snapshot(): WorldSnapshot = withContext(Dispatchers.IO) {
        val profiles = managedProfiles()
        val world = UserListParser.resolveWorld(profiles, preferredWorldId())
        world?.let { rememberWorld(it.id) }
        WorldSnapshot(
            currentUserId = currentUserId(),
            users = profiles,
            world = world,
            crossProfileAllowed = crossProfileAllowed(),
            vmHostInstalledInWorld = world?.let { isInstalledIn(it.id, VM_HOST_PACKAGE) } ?: false,
            worldPackages = world?.let { packagesIn(it.id, 40) } ?: emptyList(),
            prepared = world != null,
            note = when {
                world == null -> "No managed profile on this device. Lucifer's world has not been created yet."
                !crossProfileAllowed() -> "Profile found, but Android has not allowed cross-profile launches yet."
                else -> ""
            }
        )
    }

    /**
     * Launches an app *inside* the world. Uses CrossProfileApps, so the launch is the sanctioned
     * cross-profile one: no data access, no shell, and the world's own permissions apply.
     */
    fun launchInWorld(pkg: String, userId: Int): PrivilegeOutcome {
        val handle = handleFor(userId)
            ?: return PrivilegeOutcome(false, Provider.NONE, "That profile is no longer present.")
        val apps = launcherApps
            ?: return PrivilegeOutcome(false, Provider.NONE, "LauncherApps is unavailable on this build.")
        val cross = crossProfileApps
            ?: return PrivilegeOutcome(false, Provider.NONE, "Cross-profile launches need Android 9 or newer.")
        val activity = try {
            apps.getActivityList(pkg, handle).firstOrNull()?.componentName
        } catch (error: Exception) {
            null
        } ?: return PrivilegeOutcome(
            false,
            Provider.NONE,
            "$pkg is not launchable inside the world. Install it in the profile first."
        )
        return try {
            cross.startMainActivity(activity, handle)
            PrivilegeLedger.record(
                LedgerEntry(System.currentTimeMillis(), "launch $pkg in world ${handle.hashCode()}", Provider.NONE, true)
            )
            PrivilegeOutcome(true, Provider.NONE, "Launched $pkg inside Lucifer's world.")
        } catch (error: Exception) {
            PrivilegeOutcome(
                false,
                Provider.NONE,
                "Cross-profile launch refused: ${error.message ?: error.javaClass.simpleName}. " +
                    "Android needs the owner relationship or a one-time approval for this."
            )
        }
    }

    /** Starts the VM host app inside the world, falling back to the owner's session with a note. */
    fun startVm(userId: Int?): PrivilegeOutcome {
        val target = userId ?: preferredWorldId()
        if (target != null && isInstalledIn(target, VM_HOST_PACKAGE)) {
            return launchInWorld(VM_HOST_PACKAGE, target)
        }
        val ownSession = try {
            val intent = context.packageManager.getLaunchIntentForPackage(VM_HOST_PACKAGE)
            if (intent == null) false else {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }
        } catch (_: Exception) {
            false
        }
        return if (ownSession) {
            PrivilegeOutcome(
                true,
                Provider.NONE,
                "The VM host is not installed inside the world, so Lucifer started the one in your session."
            )
        } else {
            PrivilegeOutcome(
                false,
                Provider.NONE,
                "The Android VM host ($VM_HOST_PACKAGE) is not installed anywhere Lucifer can see."
            )
        }
    }

    /**
     * Creates the world. When Lucifer is the device owner this is a direct DevicePolicyManager call;
     * otherwise the profile is created through the confirmed shell path (Shizuku), which is the same
     * thing the work-profile apps do under the hood.
     */
    suspend fun createWorld(name: String = WorldCommands.DEFAULT_PROFILE_NAME): PrivilegeOutcome =
        withContext(Dispatchers.IO) {
            if (LocalOwnerOps.isDeviceOwner(context)) {
                val (result, _) = LocalOwnerOps.createManagedProfile(context, name)
                if (result.ok) {
                    val created = resolveNewestProfile()
                    if (created != null) rememberWorld(created)
                    PrivilegeLedger.record(
                        LedgerEntry(System.currentTimeMillis(), "create managed profile \"$name\"", Provider.LUCIFER_OWNER, true)
                    )
                    return@withContext PrivilegeOutcome(
                        true, Provider.LUCIFER_OWNER,
                        "Created Lucifer's world as device owner. ${if (created != null) "User ${created} is ready." else "Start it to wake it."}"
                    )
                }
                if (!result.ok) {
                    return@withContext PrivilegeOutcome(false, Provider.LUCIFER_OWNER, result.message)
                }
            }
            val outcome = router.run(PrivilegeOp.CreateManagedProfile(name), confirmed = true)
            if (outcome.ok) {
                // The command prints "Status: ok\nUserId: 10" style output on most builds.
                newestProfileId(outcome.detail)?.let { rememberWorld(it) }
            }
            outcome.copy(
                headline = if (outcome.ok) {
                    "${outcome.headline} Start the new profile from this panel to wake it."
                } else {
                    outcome.headline
                }
            )
        }

    suspend fun wakeWorld(userId: Int): PrivilegeOutcome = withContext(Dispatchers.IO) {
        router.run(PrivilegeOp.StartUser(userId), confirmed = true)
    }

    suspend fun sleepWorld(userId: Int): PrivilegeOutcome = withContext(Dispatchers.IO) {
        router.run(PrivilegeOp.StopUser(userId), confirmed = true)
    }

    fun openWorkProfileSettings(): Boolean = try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGED_PROFILE_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    fun openProfileApp(pkg: String): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private fun resolveNewestProfile(): Int? = managedProfiles().maxByOrNull { it.id }?.id

    private fun newestProfileId(output: List<String>): Int? {
        val match = Regex("User\\s*id\\s*[:=]\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
        return output.firstNotNullOfOrNull { line -> match.find(line)?.groupValues?.get(1)?.toIntOrNull() }
    }

    companion object {
        const val VM_HOST_PACKAGE = "com.android.virtualization.terminal"
        const val WORK_PROFILE_APP_PACKAGE = "net.typeblog.shelter"
        private const val KEY_WORLD_USER = "world_user_id"

        fun vmInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(VM_HOST_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }

        /** Work-profile apps Lucifer recognises by package, so the panel can offer to open them. */
        val PROFILE_TOOLS = listOf(
            "net.typeblog.shelter" to "Shelter",
            "com.oasisfeng.island" to "Island",
            "com.oasisfeng.island.fdroid" to "Insular",
            "com.pdp.deviceowner" to "Test DPC",
            "com.rosan.dhizuku" to "Dhizuku"
        )

        fun installedProfileTools(context: Context): List<Pair<String, String>> = PROFILE_TOOLS.filter { (pkg, _) ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
