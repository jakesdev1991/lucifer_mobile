package com.lucifer.mobile.privilege

/**
 * The privileged layer, in pure Kotlin: which backend to use, what command to send, and what Lucifer
 * refuses to touch.
 *
 * Nothing here touches Android. The adapters only execute what this file decides, which is what the
 * host harness verifies — including the refusals, because a refusal that silently becomes a command
 * is the whole risk.
 *
 * Two backends, two shapes of power:
 *
 *  - **Shizuku** hands Lucifer a *shell* (ADB, UID 2000) or root: `pm`, `cmd`, `am`, `settings`.
 *    It stops when the phone reboots.
 *  - **Dhizuku** hands Lucifer the *device owner* identity over Binder. It cannot run shell commands
 *    at all; it calls DevicePolicyManager, which is what owns profiles, permission policy and
 *    package policy. It survives reboots.
 *
 * So a capability is not a provider: `SHELL` work goes to Shizuku, `DEVICE_OWNER` work to Dhizuku,
 * and when neither exists Lucifer says so instead of pretending.
 */
enum class Capability {
    /** Run a shell command as ADB shell or root. Shizuku only. */
    SHELL,

    /** Call DevicePolicyManager as the device owner. Dhizuku, or Lucifer if it owns the device. */
    DEVICE_OWNER,

    /** Launch and reason about the managed profile as a peer app. No privilege at all. */
    CROSS_PROFILE
}

enum class Provider(val label: String) {
    DHIZUKU("Dhizuku · device owner"),
    LUCIFER_OWNER("Lucifer · device owner"),
    SHIZUKU_ROOT("Shizuku · root"),
    SHIZUKU_SHELL("Shizuku · ADB shell"),
    NONE("none");

    val isShell: Boolean get() = this == SHIZUKU_ROOT || this == SHIZUKU_SHELL || this == LUCIFER_OWNER
    val isOwner: Boolean get() = this == DHIZUKU || this == LUCIFER_OWNER
}

data class ProviderState(
    val provider: Provider,
    val available: Boolean,
    val detail: String
)

object ProviderPlan {

    /** Owner backends outrank shell backends for owner work, and the reverse. Never mix the two. */
    private val ownerRank = listOf(Provider.DHIZUKU, Provider.LUCIFER_OWNER)
    private val shellRank = listOf(Provider.SHIZUKU_ROOT, Provider.SHIZUKU_SHELL, Provider.LUCIFER_OWNER)

    fun select(capability: Capability, states: List<ProviderState>): Provider? {
        if (capability == Capability.CROSS_PROFILE) return Provider.NONE
        val rank = if (capability == Capability.SHELL) shellRank else ownerRank
        val usable = states.filter { it.available }.map { it.provider }.toSet()
        return rank.firstOrNull { it in usable }
    }

    /** One honest sentence about what is actually wired up right now. */
    fun summary(states: List<ProviderState>): String {
        val shell = select(Capability.SHELL, states)
        val owner = select(Capability.DEVICE_OWNER, states)
        return when {
            shell != null && owner != null ->
                "Shell via ${shell.label}; device-owner via ${owner.label}."
            shell != null ->
                "Shell via ${shell.label}. No device owner: profile and permission policy work is unavailable."
            owner != null ->
                "Device-owner via ${owner.label}. No shell: policy calls only, no free-form commands."
            else ->
                "No privilege backend. Lucifer can only do what a normal app can do."
        }
    }
}

enum class Risk { LOW, MEDIUM, HIGH }

/**
 * A privileged request. `LOW` is reserved for read-only inspection; anything that changes device
 * state is at least `MEDIUM` and needs the owner to confirm that exact action.
 */
sealed class PrivilegeOp(val risk: Risk, val label: String) {

    data class GrantRuntimePermission(val pkg: String, val permission: String, val userId: Int = 0) :
        PrivilegeOp(Risk.HIGH, "grant $permission to $pkg")

    data class RevokeRuntimePermission(val pkg: String, val permission: String, val userId: Int = 0) :
        PrivilegeOp(Risk.HIGH, "revoke $permission from $pkg")

    data class SetAppOp(val pkg: String, val op: String, val mode: String, val userId: Int = 0) :
        PrivilegeOp(Risk.HIGH, "set app-op $op=$mode for $pkg")

    data class SetHidden(val pkg: String, val hidden: Boolean, val userId: Int = 0) :
        PrivilegeOp(Risk.MEDIUM, "${if (hidden) "hide" else "unhide"} $pkg")

    data class SetSuspended(val pkg: String, val suspended: Boolean, val userId: Int = 0) :
        PrivilegeOp(Risk.MEDIUM, "${if (suspended) "suspend" else "unsuspend"} $pkg")

    data class Uninstall(val pkg: String, val userId: Int? = null) :
        PrivilegeOp(Risk.HIGH, "uninstall $pkg")

    data class Install(val apkPath: String, val userId: Int? = null) :
        PrivilegeOp(Risk.HIGH, "install $apkPath")

    data class StartUser(val userId: Int) : PrivilegeOp(Risk.MEDIUM, "start user $userId")

    data class StopUser(val userId: Int) : PrivilegeOp(Risk.MEDIUM, "stop user $userId")

    data class CreateManagedProfile(val name: String) :
        PrivilegeOp(Risk.HIGH, "create managed profile \"$name\"")

    /** Device-owner variants: same intent as the shell ops, but carried over Dhizuku's Binder. */
    data class OwnerHidden(val pkg: String, val hidden: Boolean, val userId: Int = 0) :
        PrivilegeOp(Risk.MEDIUM, "${if (hidden) "hide" else "unhide"} $pkg (owner)")

    data class OwnerSuspended(val pkg: String, val suspended: Boolean, val userId: Int = 0) :
        PrivilegeOp(Risk.MEDIUM, "${if (suspended) "suspend" else "unsuspend"} $pkg (owner)")

    data class OwnerPermission(
        val pkg: String,
        val permission: String,
        val granted: Boolean,
        val userId: Int = 0
    ) : PrivilegeOp(Risk.HIGH, "${if (granted) "grant" else "deny"} $permission for $pkg (owner)")

    data class ReadOnly(val command: String) : PrivilegeOp(Risk.LOW, "inspect: $command")
}

data class CommandPlan(
    val capability: Capability,
    val command: String? = null,
    val refusal: String? = null,
    /** True when the plan is a validated device-owner request that travels over Binder, not text. */
    val ownerCall: Boolean = false
) {
    val ok: Boolean get() = refusal == null && (command != null || ownerCall)

    companion object {
        fun refuse(reason: String, capability: Capability = Capability.SHELL) =
            CommandPlan(capability, refusal = reason)

        fun owner() = CommandPlan(Capability.DEVICE_OWNER, ownerCall = true)
    }
}

/**
 * Turns an op into one exact shell command, or a refusal with the reason.
 *
 * Every command is fully specified — package, permission, user id — and every identifier is
 * validated against a strict pattern first, so nothing the user types can turn into shell syntax.
 * There is no "run this string" entry point: the router may only execute plans built here.
 */
object PrivilegeCommands {

    const val USER_FLOOR = 0
    const val USER_CEILING = 999

    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val permissionPattern = Regex("^[A-Za-z][A-Za-z0-9_.]{2,100}$")
    private val appOpPattern = Regex("^[A-Z][A-Z0-9_]{2,60}$")
    private val profileNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,30}$")
    private val apkPathPattern = Regex("^/[A-Za-z0-9_./-]{3,380}\\.apk$")
    private val inspectPattern = Regex(
        "^(dumpsys [A-Za-z0-9_.]{1,60}( [A-Za-z0-9_.]{1,200}){0,2}" +
            "|cmd appops get [A-Za-z0-9_.]{1,200}" +
            "|pm list (packages|users|permissions)" +
            "|pm path [A-Za-z0-9_.]{1,200}" +
            "|am get-current-user)$"
    )

    private val appOpModes = setOf("allow", "deny", "ignore", "default", "foreground", "ask")

    /**
     * Packages Lucifer will never hide, suspend, or uninstall, whoever asks: both privilege
     * backends, the permission manager the owner audits with, and Lucifer itself. Losing any of them
     * costs the owner their own tooling, so the answer is no before any confirmation dialog appears.
     */
    val protectedPackages = setOf(
        "com.lucifer.mobile",
        "moe.shizuku.privileged.api",
        "com.rosan.dhizuku",
        "com.mirfatif.permissionmanagerx"
    )

    fun isProtected(pkg: String): Boolean = pkg.trim().lowercase() in protectedPackages

    fun validPackage(pkg: String): Boolean =
        pkg.length in 3..255 && packagePattern.matches(pkg)

    fun validPermission(permission: String): Boolean = permissionPattern.matches(permission.trim())

    fun validAppOp(op: String): Boolean = appOpPattern.matches(op.trim())

    fun validUser(userId: Int): Boolean = userId in USER_FLOOR..USER_CEILING

    fun validProfileName(name: String): Boolean = profileNamePattern.matches(name.trim())

    fun plan(op: PrivilegeOp): CommandPlan = when (op) {
        is PrivilegeOp.ReadOnly -> {
            val command = op.command.trim()
            if (inspectPattern.matches(command)) CommandPlan(Capability.SHELL, command = command)
            else CommandPlan.refuse("Inspection is limited to dumpsys, pm list/path, am get-current-user, and cmd appops get.")
        }

        is PrivilegeOp.GrantRuntimePermission -> permissionPlan(op.pkg, op.permission, op.userId) { pkg, perm, user ->
            "pm grant --user $user $pkg $perm"
        }

        is PrivilegeOp.RevokeRuntimePermission -> permissionPlan(op.pkg, op.permission, op.userId) { pkg, perm, user ->
            "pm revoke --user $user $pkg $perm"
        }

        is PrivilegeOp.SetAppOp -> {
            when {
                !validPackage(op.pkg.trim()) -> CommandPlan.refuse("Enter a valid Android package name.")
                isProtected(op.pkg) ->
                    CommandPlan.refuse("${op.pkg.trim()} is protected; Lucifer will not change its app-ops.")
                !validAppOp(op.op) -> CommandPlan.refuse("That app-op name is not a valid Android identifier.")
                op.mode.trim().lowercase() !in appOpModes ->
                    CommandPlan.refuse("App-op mode must be one of ${appOpModes.sorted().joinToString(", ")}.")
                !validUser(op.userId) -> CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
                else -> CommandPlan(
                    Capability.SHELL,
                    command = "cmd appops set --user ${op.userId} ${op.pkg.trim()} ${op.op.trim()} ${op.mode.trim().lowercase()}"
                )
            }
        }

        is PrivilegeOp.SetHidden -> packagePlan(op.pkg, "hide") { pkg ->
            if (!validUser(op.userId)) CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            else CommandPlan(Capability.SHELL, command = "pm ${if (op.hidden) "hide" else "unhide"} --user ${op.userId} $pkg")
        }

        is PrivilegeOp.SetSuspended -> packagePlan(op.pkg, "suspend") { pkg ->
            if (!validUser(op.userId)) CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            else CommandPlan(
                Capability.SHELL,
                command = "pm ${if (op.suspended) "suspend" else "unsuspend"} --user ${op.userId} $pkg"
            )
        }

        is PrivilegeOp.Uninstall -> packagePlan(op.pkg, "uninstall") { pkg ->
            val requested = op.userId
            if (requested != null && !validUser(requested)) {
                CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            } else {
                val userPart = requested?.let { " --user $it" } ?: ""
                CommandPlan(Capability.SHELL, command = "pm uninstall$userPart $pkg")
            }
        }

        is PrivilegeOp.Install -> {
            val path = op.apkPath.trim()
            when {
                !apkPathPattern.matches(path) || path.contains("..") ->
                    CommandPlan.refuse("Install path must be an absolute .apk path with no traversal.")
                op.userId != null && !validUser(op.userId) ->
                    CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
                else -> {
                    val userPart = op.userId?.let { " --user $it" } ?: ""
                    CommandPlan(Capability.SHELL, command = "pm install -r$userPart $path")
                }
            }
        }

        is PrivilegeOp.StartUser -> userPlan(op.userId, "am start-user", requireCeiling = false)
        is PrivilegeOp.StopUser -> userPlan(op.userId, "am stop-user -f", requireCeiling = false)

        is PrivilegeOp.CreateManagedProfile -> {
            val name = op.name.trim().replace(Regex("\\s+"), " ")
            if (!profileNamePattern.matches(name)) {
                CommandPlan.refuse("Profile name must be 1–31 characters of letters, digits, space, - or _.")
            } else {
                CommandPlan(Capability.SHELL, command = "pm create-user --profileOf 0 --managed \"$name\"")
            }
        }

        // Owner ops never become shell text. They are still validated here so a protected package or
        // a malformed identifier is refused before any Binder call is attempted.
        is PrivilegeOp.OwnerHidden -> packagePlan(op.pkg, "hide") {
            if (!validUser(op.userId)) CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            else CommandPlan.owner()
        }
        is PrivilegeOp.OwnerSuspended -> packagePlan(op.pkg, "suspend") {
            if (!validUser(op.userId)) CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            else CommandPlan.owner()
        }
        is PrivilegeOp.OwnerPermission -> {
            val clean = op.pkg.trim()
            when {
                !validPackage(clean) -> CommandPlan.refuse("Enter a valid Android package name.", Capability.DEVICE_OWNER)
                isProtected(clean) -> CommandPlan.refuse(
                    "$clean is protected; Lucifer will not change its permission state.",
                    Capability.DEVICE_OWNER
                )
                !validPermission(op.permission) -> CommandPlan.refuse(
                    "That permission name is not a valid Android identifier.",
                    Capability.DEVICE_OWNER
                )
                !validUser(op.userId) -> CommandPlan.refuse(
                    "User id must be between $USER_FLOOR and $USER_CEILING.",
                    Capability.DEVICE_OWNER
                )
                else -> CommandPlan.owner()
            }
        }
    }

    private fun permissionPlan(
        pkg: String,
        permission: String,
        userId: Int,
        build: (String, String, Int) -> String
    ): CommandPlan {
        val clean = pkg.trim()
        return when {
            !validPackage(clean) -> CommandPlan.refuse("Enter a valid Android package name.")
            isProtected(clean) -> CommandPlan.refuse("$clean is protected; Lucifer will not change its permission state.")
            !validPermission(permission) -> CommandPlan.refuse("That permission name is not a valid Android identifier.")
            !validUser(userId) -> CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
            else -> CommandPlan(Capability.SHELL, command = build(clean, permission.trim(), userId))
        }
    }

    private fun packagePlan(pkg: String, what: String, build: (String) -> CommandPlan): CommandPlan {
        val clean = pkg.trim()
        return when {
            !validPackage(clean) -> CommandPlan.refuse("Enter a valid Android package name.")
            isProtected(clean) -> CommandPlan.refuse("$clean is protected; Lucifer will not $what it.")
            else -> build(clean)
        }
    }

    private fun userPlan(userId: Int, verb: String, requireCeiling: Boolean): CommandPlan {
        if (!validUser(userId)) return CommandPlan.refuse("User id must be between $USER_FLOOR and $USER_CEILING.")
        if (userId == 0) return CommandPlan.refuse("User 0 is the owner's own session; Lucifer will not start or stop it.")
        return CommandPlan(Capability.SHELL, command = "$verb $userId")
    }
}

/** What actually happened, in words the panel can show without spin. */
data class PrivilegeOutcome(
    val ok: Boolean,
    val provider: Provider,
    val headline: String,
    val detail: List<String> = emptyList()
)

/** One line of the audit trail. Bounded by [PrivilegeLedger.MAX_ENTRIES]. */
data class LedgerEntry(
    val at: Long,
    val label: String,
    val provider: Provider,
    val ok: Boolean,
    val note: String = ""
)

/**
 * An in-memory record of every privileged action Lucifer took. Never contains secrets: only the
 * op label (package, permission, user) and whether it succeeded. The adapter persists the tail.
 */
object PrivilegeLedger {
    const val MAX_ENTRIES = 200

    private val entries = ArrayDeque<LedgerEntry>()

    fun record(entry: LedgerEntry): List<LedgerEntry> {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        return snapshot()
    }

    fun snapshot(): List<LedgerEntry> = entries.toList()

    fun clear() = entries.clear()

    fun summary(): String {
        val all = snapshot()
        if (all.isEmpty()) return "No privileged actions have been taken."
        val failures = all.count { !it.ok }
        return "${all.size} privileged action${if (all.size == 1) "" else "s"} recorded" +
            if (failures == 0) "." else ", $failures failed."
    }
}
