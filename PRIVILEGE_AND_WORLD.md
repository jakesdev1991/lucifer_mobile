# Privilege and the World

Lucifer has two very different ways to be more than an app, and one place to use either of them.

Status: **implemented, core host-verified (22 assertions + adapter type-check), not compiled by Gradle
and not run on a device.** Every claim below is about what the code does; nothing here has been
exercised against a real Shizuku server, a real Dhizuku install, or a real managed profile.

## Two backends, two shapes of power

| | Shizuku | Dhizuku |
| --- | --- | --- |
| Identity | ADB shell (UID 2000) or root (UID 0) | Device owner, delegated |
| What it can do | any `pm` / `cmd` / `am` / `dumpsys` command | `IDevicePolicyManager` calls only |
| What it cannot do | DevicePolicyManager policy, profile ownership | run a shell command, read a file, install an APK |
| Survives reboot | no (restart Shizuku) | yes |
| Needs | Wireless debugging, Lucifer granted in Shizuku | Dhizuku provisioned, Lucifer approved in it |

They are not interchangeable, so the code never pretends they are: `ProviderPlan` refuses to send
owner work to a shell, and refuses to send shell text to an owner. A capability is a property of the
*operation*, not of whichever backend happens to be connected.

There is a third route that needs neither: if the owner provisions Lucifer itself —

```bash
adb shell dpm set-device-owner com.lucifer.mobile/.privilege.LuciferDeviceAdmin
```

— then `LocalOwnerOps` reaches the same DevicePolicyManager through fully public APIs, with no
reflection, no hidden API, and no third-party app. Dhizuku is for owners who would rather keep that
identity in a separate, uninstallable app.

## The rules that did not change

- **One exact action, confirmed.** `PrivilegeRouter.run()` refuses anything above read-only unless
  the caller passes `confirmed = true`, and the UI only passes it after showing the literal command
  (`pm grant --user 10 com.example.app android.permission.CAMERA`) in a dialog.
- **Refusals happen before confirmation.** Bad identifiers, traversal in an APK path, a user id of 0,
  a protected package: all rejected by `PrivilegeCommands` before any backend is selected, so there is
  nothing to confirm.
- **Nothing autonomous.** No agent, heartbeat session, workflow step, recovery move or chat instruction
  can reach this layer. The panel is the only caller, and `WorldCharter.mayRunAutonomously()` returns
  `false` for every action, including the harmless ones.
- **Self-protection.** Lucifer will not hide, suspend, uninstall or change the permission state of:

  | Package | Why |
  | --- | --- |
  | `com.lucifer.mobile` | Lucifer |
  | `moe.shizuku.privileged.api` | the shell backend |
  | `com.rosan.dhizuku` | the owner backend |
  | `com.mirfatif.permissionmanagerx` | the manager the owner audits with |

  That last one is also why `TerminalCommandPolicy` still blocks PMX permission mutations typed into
  any terminal: Permission Manager X stays the owner's instrument, and Lucifer inspects grants
  read-only.
- **Every action is logged.** `PrivilegeLedger` keeps the last 200 `(label, backend, ok, note)` entries
  and the World panel shows them. It stores no secrets — only which package, permission and user.
- **The wallet boundary is untouched.** Nothing here signs, sends, or unseals a wallet key.

## The world

A managed profile is a separate Android user: its own package data, its own accounts, its own running
processes, its own copy of the VM host. Lucifer can be given that user as a room to act in, and when
the profile is stopped everything in it freezes with it — which is exactly the containment that makes
"let him do what he wants in there" a reasonable sentence.

| Action | Backend | Needs confirmation |
| --- | --- | --- |
| Inspect the world (`pm list users`) | shell | no |
| Create the world | owner (direct) or shell (`pm create-user --profileOf 0 --managed "Lucifer"`) | yes |
| Wake it (`am start-user 10`) | shell | yes |
| Sleep it (`am stop-user -f 10`) | shell | yes |
| Launch an app inside it | none — `CrossProfileApps.startMainActivity` | yes |
| Start the VM inside it | none — cross-profile launch of `com.android.virtualization.terminal` | yes |
| Open managed-profile settings | none — `Settings.ACTION_MANAGED_PROFILE_SETTINGS` | no |

What the world does **not** give Lucifer: access to the owner's data in that profile (cross-profile
launches are launches, not data access), the owner's accounts, or a way to act without being asked.
If the VM host is not installed in the profile, Lucifer says so and offers the one in the owner's own
session instead of pretending.

### Three ways to get a world

1. **Lucifer as device owner.** `dpm set-device-owner` as above, then *World → Create Lucifer's world*.
   `DevicePolicyManager.createAndManageUser` makes the profile and Lucifer becomes its profile owner.
   Public APIs all the way.
2. **Dhizuku + Shizuku.** Approve Lucifer in Dhizuku for owner calls, restart Shizuku if it is not
   running, then *Create Lucifer's world* — the profile is made with `pm create-user --managed`.
3. **An existing work profile.** If the device already has one (Shelter, Island/Insular, a work
   account), the World panel finds it, remembers its user id, and can launch into it once Android
   permits cross-profile interaction. On Android 11+ that permission comes from the owner or profile
   owner relationship; the panel reports which of the two you are missing.

## Dhizuku specifics

- The client library is `io.github.iamr0s:Dhizuku-API:2.5.4` (Maven Central), added as a dependency so
  the class exists at runtime — but every call goes through reflection, so a version that renames a
  method degrades to "connection refused: <reason>" rather than a broken build.
- `Dhizuku.init(context)` → `isPermissionGranted()` → `binderWrapper(...)` gives a Binder for
  `device_policy`; Lucifer wraps it with `IDevicePolicyManager.Stub.asInterface`.
- Calls are an **allowlist**: `setApplicationHidden`, `setPackagesSuspended`,
  `setPermissionGrantState`, `installExistingPackage`, `setUserRestriction`, `clearUserRestriction`,
  `logoutUser`, `setLockTaskPackages`, `wipeData`. Anything else is refused before Binder.
- `wipeData` is on the allowlist because Dhizuku owners legitimately use it, but nothing in Lucifer's
  UI calls it today; there is no button, and no path from chat or an agent.
- **Hidden-API limit, stated plainly:** `IDevicePolicyManager` is a non-SDK interface. Lucifer attempts
  an exemption through `HiddenApiBypass` and then `VMRuntime`, and reports which failed. On builds that
  refuse it, every owner call returns a named failure such as
  `setApplicationHidden refused: Accessing hidden method ...` and nothing is silently skipped. That is
  the honest behaviour, and it is why route 1 (Lucifer as device owner) exists as a fallback that does
  not depend on hidden API at all.

## Verification

```bash
./scripts/verify_privilege_core.sh
```

22 assertions over the parts that decide outcomes: backend selection (root over shell, Dhizuku over a
local owner, never crossing capabilities), command construction for every op, refusals for malformed
identifiers / traversal / user 0 / protected packages, the read-only inspection allowlist and its
smuggling attempts, the owner-call allowlist, risk classification, the bounded ledger, `pm list users`
parsing (including managed-profile flags and garbage), world resolution with a stale remembered id, the
charter, and the honest snapshot text. Then it type-checks `DhizukuBridge`, `LocalOwnerOps`,
`PrivilegeRouter` and `WorldManager` against Android stubs.

Not verified: whether a real Dhizuku install answers the Binder, whether `pm create-user --managed`
succeeds on a given OEM, whether Android grants the cross-profile relationship on a particular build,
and every pixel of the World panel.

## What is still off

- No arbitrary command execution from the privileged layer. Either an op exists in
  `PrivilegeCommands`, or nothing runs.
- No permission changes on Lucifer's own enrolment path (`INTERACT_ACROSS_USERS`, `MANAGE_USERS`, and
  similar are not requested; Dhizuku or `dpm` carry that identity instead).
- No PMX grant edits, no wallet signing, no autonomous execution, no background privileged loop.
- The work profile is not a sandbox against a malicious Lucifer — it is containment of *his* actions,
  not a security boundary against your own device owner.
