package com.lucifer.mobile.world

import com.lucifer.mobile.privilege.Capability
import com.lucifer.mobile.privilege.CommandPlan
import com.lucifer.mobile.privilege.PrivilegeCommands
import com.lucifer.mobile.privilege.PrivilegeOp
import com.lucifer.mobile.privilege.Risk

/**
 * Lucifer's world: the managed (work) profile as a place he can actually act in.
 *
 * The profile is a separate Android user with its own app data, its own accounts, its own running
 * processes and its own copy of the VM host. Lucifer can launch things there, keep his own notes
 * there, and start the VM inside it — and when the profile is stopped, everything he did there is
 * frozen with it. That containment is the point: it is a room he can be given without handing him
 * the owner's own session.
 *
 * Pure Kotlin, like [PrivilegeCommands]: the Android adapter parses real `pm list users` output into
 * [AndroidUser]s and executes what this file decides.
 */
data class AndroidUser(
    val id: Int,
    val name: String,
    /** Raw flags as printed by `pm list users`, parsed as hexadecimal. */
    val flags: Int,
    val running: Boolean
) {
    /** UserInfo.FLAG_MANAGED_PROFILE — the bit a work profile sets. */
    val isManagedProfile: Boolean get() = (flags and FLAG_MANAGED_PROFILE) != 0

    /** UserInfo.FLAG_FULL — a real secondary user rather than a profile. */
    val isFullUser: Boolean get() = (flags and FLAG_FULL) != 0

    val isOwner: Boolean get() = id == 0

    companion object {
        const val FLAG_MANAGED_PROFILE = 0x20
        const val FLAG_FULL = 0x400
        const val FLAG_SYSTEM = 0x1000
    }
}

object UserListParser {

    private val pattern = Regex("UserInfo\\{(\\d+):([^:}]*):([^}]*)\\}([^\\n]*)")

    /**
     * Parses `pm list users`, which looks like:
     *
     *     Users:
     *         UserInfo{0:Owner:c13} running
     *         UserInfo{10:Work profile:1030} running
     *
     * Anything unrecognised is ignored rather than guessed at: a wrong user id is a wrong target.
     */
    fun parse(output: String): List<AndroidUser> =
        pattern.findAll(output).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val name = match.groupValues[2].trim()
            val flags = match.groupValues[3].trim()
                .removePrefix("0x")
                .toIntOrNull(16) ?: 0
            val tail = match.groupValues[4]
            if (id !in 0..999) return@mapNotNull null
            AndroidUser(
                id = id,
                name = name.ifBlank { "user $id" },
                flags = flags,
                running = tail.contains("running")
            )
        }.distinctBy { it.id }.sortedBy { it.id }.toList()

    /** Managed profiles only, owner first by id so the choice is stable across reboots. */
    fun managedProfiles(users: List<AndroidUser>): List<AndroidUser> =
        users.filter { it.isManagedProfile && !it.isOwner }.sortedBy { it.id }

    /** The profile Lucifer should treat as his world: the remembered one if it still exists. */
    fun resolveWorld(users: List<AndroidUser>, preferredId: Int?): AndroidUser? {
        val profiles = managedProfiles(users)
        if (profiles.isEmpty()) return null
        return preferredId?.let { id -> profiles.firstOrNull { it.id == id } } ?: profiles.first()
    }
}

/** What a world action costs. Read-only inspection is the only thing that may run unattended. */
enum class WorldAction(val label: String, val risk: Risk, val needsShell: Boolean) {
    INSPECT("Inspect the world", Risk.LOW, true),
    OPEN_WORLD("Wake Lucifer's world", Risk.MEDIUM, true),
    CLOSE_WORLD("Put the world to sleep", Risk.MEDIUM, true),
    CREATE_WORLD("Create Lucifer's world", Risk.HIGH, true),
    START_VM("Start the VM inside the world", Risk.MEDIUM, false),
    LAUNCH_APP("Launch an app inside the world", Risk.MEDIUM, false),
    OPEN_SETTINGS("Open work-profile settings", Risk.LOW, false)
}

/**
 * The charter: what Lucifer may do in his world, and what still needs the owner's hand.
 *
 * His world is not a licence to act alone. Waking or freezing the profile, launching an app or
 * starting the VM there are all things the owner asks for by name; the charter records that they are
 * world-scoped, and refuses to let any of them be classified as autonomous.
 */
object WorldCharter {

    fun requiresConfirmation(action: WorldAction): Boolean = action.risk != Risk.LOW

    fun mayRunAutonomously(action: WorldAction): Boolean = false

    fun capabilityFor(action: WorldAction): Capability =
        if (action.needsShell) Capability.SHELL else Capability.CROSS_PROFILE

    fun describe(): String =
        "Lucifer's world is a managed profile: a separate user with its own app data, accounts and " +
            "processes. He can be given it, launch things inside it, and start the VM there; when it is " +
            "asleep everything in it is frozen. Waking or freezing it, launching an app in it, and " +
            "starting the VM are all requested by you by name — none of it runs on its own, and none of " +
            "it touches your own session, your accounts, or your wallet."
}

object WorldCommands {

    fun inspectUsers(): CommandPlan = PrivilegeCommands.plan(PrivilegeOp.ReadOnly("pm list users"))

    fun startUser(userId: Int): CommandPlan = PrivilegeCommands.plan(PrivilegeOp.StartUser(userId))

    fun stopUser(userId: Int): CommandPlan = PrivilegeCommands.plan(PrivilegeOp.StopUser(userId))

    fun createManagedProfile(name: String = DEFAULT_PROFILE_NAME): CommandPlan =
        PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile(name))

    fun installIntoWorld(apkPath: String, userId: Int): CommandPlan =
        PrivilegeCommands.plan(PrivilegeOp.Install(apkPath, userId))

    fun uninstallFromWorld(pkg: String, userId: Int): CommandPlan =
        PrivilegeCommands.plan(PrivilegeOp.Uninstall(pkg, userId))

    const val DEFAULT_PROFILE_NAME = "Lucifer"

    /** Packages Lucifer will look for inside the world when it opens. */
    val worldSeeds = listOf(
        "com.android.virtualization.terminal",
        "com.termux",
        "com.android.chrome",
        "com.google.android.apps.docs"
    )
}

/** Everything the panel needs to describe the world honestly, assembled by the adapter. */
data class WorldSnapshot(
    val currentUserId: Int = 0,
    val users: List<AndroidUser> = emptyList(),
    val world: AndroidUser? = null,
    val crossProfileAllowed: Boolean = false,
    val vmHostInstalledInWorld: Boolean = false,
    val worldPackages: List<String> = emptyList(),
    val prepared: Boolean = false,
    val note: String = ""
) {
    val hasWorld: Boolean get() = world != null

    fun lines(): List<String> = buildList {
        add("Your session: user $currentUserId")
        when {
            world == null -> add("No managed profile yet — Lucifer has no world to act in.")
            else -> {
                add("World: user ${world.id} · \"${world.name}\" · ${if (world.running) "awake" else "asleep"}")
                add("Cross-profile launches: ${if (crossProfileAllowed) "allowed" else "not allowed yet"}")
                add(
                    if (vmHostInstalledInWorld) "VM host: installed inside the world"
                    else "VM host: not installed inside the world"
                )
                if (worldPackages.isNotEmpty()) add("Apps in the world: ${worldPackages.size}")
            }
        }
        if (note.isNotBlank()) add(note)
    }
}
