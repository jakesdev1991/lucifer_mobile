// Host-side verification for the privileged layer and Lucifer's world.
//
// The Android adapters cannot be compiled here (no Android SDK), but the decisions they execute can:
// which backend to pick, what exact command an op becomes, what is refused before any command exists,
// how `pm list users` is parsed, and what the world charter allows. Those are the parts where a bug
// means a wrong command reached a phone.
//
// Not part of the Android build. Run with scripts/verify_privilege_core.sh.
package com.lucifer.mobile.privilege.harness

import com.lucifer.mobile.privilege.Capability
import com.lucifer.mobile.privilege.CommandPlan
import com.lucifer.mobile.privilege.LedgerEntry
import com.lucifer.mobile.privilege.PrivilegeCommands
import com.lucifer.mobile.privilege.PrivilegeLedger
import com.lucifer.mobile.privilege.PrivilegeOp
import com.lucifer.mobile.privilege.Provider
import com.lucifer.mobile.privilege.ProviderPlan
import com.lucifer.mobile.privilege.ProviderState
import com.lucifer.mobile.privilege.Risk
import com.lucifer.mobile.world.AndroidUser
import com.lucifer.mobile.world.UserListParser
import com.lucifer.mobile.world.WorldAction
import com.lucifer.mobile.world.WorldCharter
import com.lucifer.mobile.world.WorldCommands
import com.lucifer.mobile.world.WorldSnapshot

private var passed = 0
private val failures = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  ok  $name")
    } catch (error: Throwable) {
        failures.add("$name: ${error.message ?: error.javaClass.simpleName}")
        println("FAIL  $name -> ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun expectCommand(plan: CommandPlan, expected: String) {
    expect(plan.ok, "expected a command, got refusal: ${plan.refusal}")
    expect(plan.command == expected, "expected \"$expected\" but was \"${plan.command}\"")
}

private fun expectRefused(plan: CommandPlan, why: String = "") {
    expect(!plan.ok, "expected a refusal but got command: ${plan.command}")
    expect(!plan.refusal.isNullOrBlank(), "refusal without a reason $why")
}

private val allGood = listOf(
    ProviderState(Provider.DHIZUKU, true, "connected"),
    ProviderState(Provider.LUCIFER_OWNER, false, "not owner"),
    ProviderState(Provider.SHIZUKU_ROOT, false, "not root"),
    ProviderState(Provider.SHIZUKU_SHELL, true, "connected")
)

fun main() {
    println("Lucifer privilege + world core · verification harness")

    // ── provider selection ───────────────────────────────────────────
    test("shell work goes to shell, owner work goes to the owner") {
        expect(ProviderPlan.select(Capability.SHELL, allGood) == Provider.SHIZUKU_SHELL, "shell")
        expect(ProviderPlan.select(Capability.DEVICE_OWNER, allGood) == Provider.DHIZUKU, "owner")
        expect(ProviderPlan.select(Capability.CROSS_PROFILE, allGood) == Provider.NONE, "cross-profile is not a backend")
    }

    test("root outranks the ADB shell, and Dhizuku outranks a local owner") {
        val both = listOf(
            ProviderState(Provider.SHIZUKU_SHELL, true, ""),
            ProviderState(Provider.SHIZUKU_ROOT, true, ""),
            ProviderState(Provider.LUCIFER_OWNER, true, ""),
            ProviderState(Provider.DHIZUKU, true, "")
        )
        expect(ProviderPlan.select(Capability.SHELL, both) == Provider.SHIZUKU_ROOT, "root first")
        expect(ProviderPlan.select(Capability.DEVICE_OWNER, both) == Provider.DHIZUKU, "Dhizuku first")
    }

    test("a shell backend is never used for owner work, or the reverse") {
        val shellOnly = listOf(ProviderState(Provider.SHIZUKU_SHELL, true, ""))
        val ownerOnly = listOf(ProviderState(Provider.DHIZUKU, true, ""))
        expect(ProviderPlan.select(Capability.DEVICE_OWNER, shellOnly) == null, "shell cannot own")
        expect(ProviderPlan.select(Capability.SHELL, ownerOnly) == null, "owner cannot shell")
    }

    test("the summary says what is missing instead of implying full power") {
        expect(ProviderPlan.summary(allGood).contains("Shell via Shizuku"), ProviderPlan.summary(allGood))
        val shellOnly = listOf(ProviderState(Provider.SHIZUKU_SHELL, true, ""))
        expect(ProviderPlan.summary(shellOnly).contains("No device owner"), ProviderPlan.summary(shellOnly))
        val ownerOnly = listOf(ProviderState(Provider.DHIZUKU, true, ""))
        expect(ProviderPlan.summary(ownerOnly).contains("policy calls only"), ProviderPlan.summary(ownerOnly))
        expect(ProviderPlan.summary(emptyList()).contains("No privilege backend"), "nothing at all")
    }

    // ── commands that must be exact ──────────────────────────────────
    test("grants and revokes are fully specified") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.GrantRuntimePermission("com.example.app", "android.permission.CAMERA", 10)),
            "pm grant --user 10 com.example.app android.permission.CAMERA"
        )
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.RevokeRuntimePermission("com.example.app", "android.permission.CAMERA")),
            "pm revoke --user 0 com.example.app android.permission.CAMERA"
        )
    }

    test("app-ops carry a validated op and mode") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.SetAppOp("com.example.app", "RUN_IN_BACKGROUND", "ignore", 0)),
            "cmd appops set --user 0 com.example.app RUN_IN_BACKGROUND ignore"
        )
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetAppOp("com.example.app", "RUN_IN_BACKGROUND", "always")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetAppOp("com.example.app", "run_in_background", "allow")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetAppOp("com.example.app", "OP", "allow")))
    }

    test("hide, suspend and uninstall name the package and the user") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.SetHidden("com.example.app", true, 0)),
            "pm hide --user 0 com.example.app"
        )
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.SetSuspended("com.example.app", false, 10)),
            "pm unsuspend --user 10 com.example.app"
        )
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.Uninstall("com.example.app", 10)),
            "pm uninstall --user 10 com.example.app"
        )
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.Uninstall("com.example.app")),
            "pm uninstall com.example.app"
        )
    }

    test("installs require an absolute apk path with no traversal") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.Install("/data/user/0/com.lucifer.mobile/files/lucifer.apk", 10)),
            "pm install -r --user 10 /data/user/0/com.lucifer.mobile/files/lucifer.apk"
        )
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.Install("relative/path.apk")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.Install("/data/../../etc/passwd.apk")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.Install("/data/app/file.txt")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.Install("/data/app/file.apk; rm -rf /")))
    }

    test("malformed identifiers never reach a command") {
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.GrantRuntimePermission("com.example; rm -rf /", "android.permission.CAMERA")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.GrantRuntimePermission("nodots", "android.permission.CAMERA")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.GrantRuntimePermission("com.example.app", "CAMERA; reboot")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetHidden("com.example.app", true, 1000)))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetHidden("com.example.app", true, -1)))
        expect(!PrivilegeCommands.validPackage("com..app"), "empty segment")
        expect(!PrivilegeCommands.validPackage(""), "empty package")
    }

    test("Lucifer's own tooling is off limits, whoever asks") {
        for (pkg in PrivilegeCommands.protectedPackages) {
            expectRefused(PrivilegeCommands.plan(PrivilegeOp.GrantRuntimePermission(pkg, "android.permission.CAMERA")))
            expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetHidden(pkg, true)))
            expectRefused(PrivilegeCommands.plan(PrivilegeOp.SetSuspended(pkg, true)))
            expectRefused(PrivilegeCommands.plan(PrivilegeOp.Uninstall(pkg)))
        }
        expect(PrivilegeCommands.protectedPackages.contains("com.mirfatif.permissionmanagerx"), "PMX protected")
    }

    test("users: 0 stays the owner's, ids stay in range") {
        expectCommand(PrivilegeCommands.plan(PrivilegeOp.StartUser(10)), "am start-user 10")
        expectCommand(PrivilegeCommands.plan(PrivilegeOp.StopUser(10)), "am stop-user -f 10")
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.StopUser(0)))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.StartUser(1000)))
        expect(!PrivilegeCommands.validUser(-3), "negative user")
    }

    test("profile names are constrained and quoted") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile("Lucifer")),
            "pm create-user --profileOf 0 --managed \"Lucifer\""
        )
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile("  Lucifer   world  ")),
            "pm create-user --profileOf 0 --managed \"Lucifer world\""
        )
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile("evil\"; reboot; \"")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile("")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.CreateManagedProfile("x".repeat(40))))
    }

    test("inspection is read-only and cannot be smuggled") {
        expectCommand(
            PrivilegeCommands.plan(PrivilegeOp.ReadOnly("pm list users")),
            "pm list users"
        )
        expect(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("dumpsys package com.example.app")).ok, "dumpsys allowed")
        expect(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("cmd appops get com.example.app")).ok, "appops allowed")
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("pm grant com.example.app android.permission.CAMERA")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("dumpsys package x; reboot")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("rm -rf /data")))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.ReadOnly("")))
    }

    test("owner ops are validated, then carried over Binder rather than as text") {
        val hide = PrivilegeCommands.plan(PrivilegeOp.OwnerHidden("com.example.app", true, 10))
        expect(hide.ok && hide.ownerCall, "owner plan")
        expect(hide.command == null, "owner ops must not become shell text")
        expect(hide.capability == Capability.DEVICE_OWNER, "owner capability")
        expect(PrivilegeCommands.plan(PrivilegeOp.OwnerSuspended("com.example.app", true)).ownerCall, "suspend")
        expect(PrivilegeCommands.plan(PrivilegeOp.OwnerPermission("com.example.app", "android.permission.CAMERA", true)).ownerCall, "grant")
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.OwnerHidden("com.rosan.dhizuku", true)))
        expectRefused(PrivilegeCommands.plan(PrivilegeOp.OwnerPermission("com.example.app", "bad perm", true)))
    }

    test("every state change is at least medium risk, inspection is low") {
        expect(PrivilegeOp.ReadOnly("pm list users").risk == Risk.LOW, "inspect")
        val mutations = listOf(
            PrivilegeOp.GrantRuntimePermission("com.example.app", "android.permission.CAMERA"),
            PrivilegeOp.SetHidden("com.example.app", true),
            PrivilegeOp.SetSuspended("com.example.app", true),
            PrivilegeOp.StartUser(10),
            PrivilegeOp.StopUser(10),
            PrivilegeOp.Install("/data/local/lucifer.apk"),
            PrivilegeOp.Uninstall("com.example.app"),
            PrivilegeOp.CreateManagedProfile("Lucifer"),
            PrivilegeOp.OwnerHidden("com.example.app", true)
        )
        for (op in mutations) {
            expect(op.risk != Risk.LOW, "${op.label} must not be low risk")
        }
    }

    // ── audit trail ──────────────────────────────────────────────────
    test("the ledger is bounded and summarises honestly") {
        PrivilegeLedger.clear()
        expect(PrivilegeLedger.summary().contains("No privileged actions"), PrivilegeLedger.summary())
        repeat(PrivilegeLedger.MAX_ENTRIES + 25) { index ->
            PrivilegeLedger.record(
                LedgerEntry(index.toLong(), "op $index", Provider.SHIZUKU_SHELL, index % 5 != 0)
            )
        }
        expect(PrivilegeLedger.snapshot().size == PrivilegeLedger.MAX_ENTRIES, "bounded")
        expect(PrivilegeLedger.snapshot().first().label == "op 25", "oldest dropped")
        expect(PrivilegeLedger.summary().contains("failed"), PrivilegeLedger.summary())
        PrivilegeLedger.clear()
    }

    // ── the world ────────────────────────────────────────────────────
    val sampleUsers = """
        Users:
        	UserInfo{0:Owner:c13} running
        	UserInfo{10:Work profile:1030} running
        	UserInfo{11:Lucifer:1030} running
        	UserInfo{12:Guest:402} running
        	UserInfo{13:Disabled:1030}
    """.trimIndent()

    test("pm list users is parsed into ids, names, managed state and liveness") {
        val users = UserListParser.parse(sampleUsers)
        expect(users.map { it.id } == listOf(0, 10, 11, 12, 13), "ids ${users.map { it.id }}")
        expect(users[1].name == "Work profile", "name ${users[1].name}")
        expect(users[1].isManagedProfile, "0x1030 is a managed profile")
        expect(!users[0].isManagedProfile, "owner is not a profile")
        expect(!users[3].isManagedProfile && users[3].isFullUser, "guest is a full user")
        expect(users[0].running && !users[4].running, "running flags")
    }

    test("only real managed profiles become candidate worlds") {
        val users = UserListParser.parse(sampleUsers)
        val profiles = UserListParser.managedProfiles(users)
        expect(profiles.map { it.id } == listOf(10, 11, 13), "profiles ${profiles.map { it.id }}")
        expect(UserListParser.resolveWorld(users, 11)?.id == 11, "remembered world wins")
        expect(UserListParser.resolveWorld(users, 99)?.id == 10, "stale memory falls back to the first profile")
        expect(UserListParser.resolveWorld(UserListParser.parse("Users:\n\tUserInfo{0:Owner:c13} running"), null) == null, "no profile, no world")
    }

    test("garbage never invents a user") {
        expect(UserListParser.parse("").isEmpty(), "empty")
        expect(UserListParser.parse("Users:\n nothing here").isEmpty(), "noise")
        expect(UserListParser.parse("UserInfo{1000:Too high:1030} running").isEmpty(), "out of range")
        expect(UserListParser.parse("UserInfo{abc:Not a number:1030}").isEmpty(), "not a number")
    }

    test("the charter never lets the world run unattended") {
        for (action in WorldAction.entries) {
            expect(!WorldCharter.mayRunAutonomously(action), "${action.label} must not be autonomous")
        }
        expect(!WorldCharter.requiresConfirmation(WorldAction.INSPECT), "inspection is free")
        expect(!WorldCharter.requiresConfirmation(WorldAction.OPEN_SETTINGS), "settings are free")
        for (action in listOf(WorldAction.OPEN_WORLD, WorldAction.CLOSE_WORLD, WorldAction.START_VM, WorldAction.CREATE_WORLD)) {
            expect(WorldCharter.requiresConfirmation(action), "${action.label} needs confirmation")
        }
        expect(WorldCharter.capabilityFor(WorldAction.OPEN_WORLD) == Capability.SHELL, "waking needs a shell")
        expect(WorldCharter.capabilityFor(WorldAction.START_VM) == Capability.CROSS_PROFILE, "VM launch needs no privilege")
    }

    test("world commands reuse the same guards as every other privileged action") {
        expectCommand(WorldCommands.startUser(10), "am start-user 10")
        expectCommand(WorldCommands.stopUser(10), "am stop-user -f 10")
        expectCommand(
            WorldCommands.createManagedProfile(),
            "pm create-user --profileOf 0 --managed \"Lucifer\""
        )
        expectRefused(WorldCommands.startUser(0))
        expectRefused(WorldCommands.stopUser(0))
        expectCommand(
            WorldCommands.installIntoWorld("/data/user/0/com.lucifer.mobile/files/thing.apk", 10),
            "pm install -r --user 10 /data/user/0/com.lucifer.mobile/files/thing.apk"
        )
        expectCommand(
            WorldCommands.uninstallFromWorld("com.example.app", 10),
            "pm uninstall --user 10 com.example.app"
        )
        expect(WorldCommands.inspectUsers().command == "pm list users", "inspect delegates")
    }

    test("the snapshot describes an absent world without pretending") {
        val empty = WorldSnapshot()
        val lines = empty.lines()
        expect(lines.any { it.contains("No managed profile") }, lines.toString())
        expect(!empty.hasWorld, "no world")
        val present = WorldSnapshot(
            currentUserId = 0,
            users = UserListParser.parse(sampleUsers),
            world = AndroidUser(10, "Work profile", AndroidUser.FLAG_MANAGED_PROFILE, true),
            crossProfileAllowed = true,
            vmHostInstalledInWorld = true,
            worldPackages = listOf("com.android.chrome", "com.termux")
        )
        val described = present.lines()
        expect(described.any { it.contains("awake") }, described.toString())
        expect(described.any { it.contains("Apps in the world: 2") }, described.toString())
        expect(described.any { it.contains("VM host: installed") }, described.toString())
    }

    println()
    if (failures.isEmpty()) {
        println("PASS · $passed checks")
    } else {
        println("FAILED · $passed passed, ${failures.size} failed")
        failures.forEach { println(" - $it") }
        kotlin.system.exitProcess(1)
    }
}
