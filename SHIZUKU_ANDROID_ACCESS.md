# Shizuku and Android system access: Lucifer's operating model

This guide describes the upstream Shizuku model and what Lucifer actually implements. It is an engineering reference, not a promise that a command/API works on every Android version or OEM build.

## Shizuku in one sentence

Shizuku is a user-started broker that lets an app ask Android system services to perform operations under the server's ADB-shell or root identity. It is not a root exploit, a blanket permission grant, or an API that makes every protected Android operation available.

Android framework managers (for example `PackageManager`) normally make Binder IPC calls to system services. Shizuku transports an authorized request through its server so the system service sees the Shizuku-side UID/PID and applies its own permission and policy checks. The result returns over Binder. ADB-shell access is limited, and its permissions vary by Android release; root mode is more privileged but is still subject to device policy, SELinux, vendor behavior, and API correctness.

## The two developer paths

1. **Wrapped system-service Binder:** obtain a service Binder and wrap it with `ShizukuBinderWrapper`, then call a compatible system AIDL interface. This can avoid spawning a shell for suitable operations, but many interfaces are hidden/non-SDK APIs, transaction details change across releases, and every operation still needs validation and the required server identity.
2. **UserService (the path Lucifer currently uses):** Shizuku starts Lucifer's own AIDL service in a separate process under the configured shell/root UID. Lucifer binds to that service and invokes typed AIDL methods. This is more flexible than parsing command text from a generic process launcher, but the UserService is not a normal Android app process: a supplied `Context` is limited, and APIs that depend on normal app lifecycle, receivers, or `ContentResolver` may not work there.

Lucifer does **not** currently use `ShizukuBinderWrapper` to call PackageManager/AppOps Binder interfaces. Its UserService executes the one requested command with `ProcessBuilder("sh", "-c", command)`. Therefore, its actual capabilities are those of that shell/root identity plus the invoked command and the device's Android/SELinux policy—not a custom set of direct framework API integrations.

## Calls used by Lucifer

- `composeApp/build.gradle.kts` currently pins Shizuku API/provider `13.1.5`. The manifest declares `moe.shizuku.manager.permission.API_V23` and exports `rikka.shizuku.ShizukuProvider` at `${applicationId}.shizuku`, protected by `INTERACT_ACROSS_USERS_FULL`. The user still starts the server and grants Lucifer access in Shizuku.
- `ShizukuAccess.status()` calls `pingBinder()`, checks `isPreV11()`, calls `checkSelfPermission()`, and then `getUid()`. Lucifer labels UID `2000` as **ADB shell**, UID `0` as **root**, and other/unknown values separately.
- `ShizukuAccess.requestPermission()` calls `requestPermission(REQUEST_CODE)` only when the server is present and API v11+ is available. MainActivity listens for the matching permission result and refreshes status.
- `ShizukuTerminal.execute()` checks the server grant and `getVersion()`, constructs `UserServiceArgs` for `ShizukuCommandUserService`, then calls `bindUserService()`. Its `ServiceConnection` converts the Binder to `IShizukuCommandService`, invokes `execute(command, 60)`, and unbinds with removal in `finally`.
- `ShizukuCommandUserService.execute()` rejects empty/oversized/NUL-containing requests, runs `sh -c`, drains combined output on a reader thread, caps captured output at 32 KB, and enforces a maximum 60-second process timeout. `destroy()` exits the short-lived user-service process when Shizuku removes it.
- Natural-language requests reach this route through `TerminalExecutor` and the existing exact-command confirmation for non-read-only commands. The Settings **TEST SHIZUKU IDENTITY** action explicitly runs `id`.

The relevant implementation is in `composeApp/src/androidMain/kotlin/com/lucifer/mobile/shizuku/`, its AIDL is in `composeApp/src/androidMain/aidl/`, and provider/permission declarations are in `AndroidManifest.xml`.

Settings also offers an explicit read-only package inspection using `dumpsys package <validated.package>` and `cmd appops get <validated.package>`. It does not write permission/AppOps state, output is shown in memory only (not persisted or sent to the model), and command errors are surfaced rather than replaced with broader access. This is a point-in-time report, not an audit log. Device/build, process, power, usage-access-for-Lucifer, and active-VPN status are available in the local Pixel/Android diagnostics card; see [PIXEL8A_ANDROID_SYSTEMS.md](PIXEL8A_ANDROID_SYSTEMS.md).

## What identity means

| Shizuku UID | Practical interpretation | Important limit |
| --- | --- | --- |
| `2000` | Android ADB shell identity | Not root. Shell's allowed permissions and commands change across Android versions; it normally cannot read another app's private `/data/user/0/<package>` files. SELinux also applies. |
| `0` | Root identity, if Shizuku was started through a root backend | Much broader authority, but not a guarantee that every operation succeeds; SELinux, mount state, OEM changes, and API semantics still matter. |
| anything else / unavailable | Report exactly what was observed | Do not infer root or claim a capability. |

Checking the UID is only an identity diagnostic. Before adding a new operation, check the specific API/command, the server's permission, Android API level, target package/state, and actual result. A binder being alive does not prove a requested operation is authorized.

## Boundaries for Lucifer

- Lucifer may observe Shizuku server/grant status and use the identity the owner configured. **It must not grant, revoke, or change Permission Manager X's permissions.** The owner manages those grants outside Lucifer. The terminal boundary blocks recognizable direct `pm grant/revoke/set-permission-flags`, `cmd package` grant/revoke, and `appops set/reset` commands that name PMX; this is a targeted policy check, not a general shell sandbox or protection against every obfuscated equivalent.
- The general Shizuku terminal remains a user-authorized command surface, not an app sandbox. Do not claim an ADB-shell session is root. Do not silently add commands, broaden targets, bypass a confirmation, or treat model-generated text as authorization.
- Prefer narrow, typed AIDL operations over new arbitrary shell features. For each proposed system feature, specify the API/command, target Android versions, required identity/permission, read/write classification, expected output, timeout, and rollback/manual recovery before implementation.
- Do not bypass hidden-API restrictions as a generic workaround. Use stable public APIs where possible; if a feature truly needs a non-SDK interface, isolate it behind a versioned adapter, document the device/API constraints, and test fail-closed behavior.
- A Shizuku grant does not itself expose device traffic. Device-wide network filtering/telemetry is a separate `VpnService` design requiring Android's VPN consent and its own privacy controls. VPN routing can expose packet metadata, but a VPN alone does not decrypt HTTPS; TLS interception would be a separate, high-impact design. Do not promise payload visibility or treat Shizuku as a network-monitor permission.
- Permission inspection is not permission mutation. If a future feature should inspect app-op/permission state, scope it to named packages, use read-only operations, and return an explicit unsupported/denied result rather than falling back to a broader command.

## Android lifecycle and troubleshooting

Shizuku's wireless-debugging server is owner-started and can stop after reboot, debugging changes, or OEM process management. Lucifer currently refreshes its Settings status while that tab is visible and binds a non-daemon UserService per invocation. A failed bind, missing grant, wrong API version, command timeout, or shell denial is a visible failure—not evidence that a permission should be changed automatically.

For diagnosis, check in order: (1) the Shizuku app says its server is running, (2) Lucifer is approved in Shizuku, (3) Lucifer reports the actual UID, (4) `id` succeeds, (5) the specific read-only command/API is supported on this OS build. Keep the exact output and API/build context when reporting a failure.

## Telemetry and OS controls are separate surfaces

| What Lucifer wants to observe/control | Relevant Android surface | What Shizuku does not guarantee |
| --- | --- | --- |
| Per-app network access / packet metadata | A consented `VpnService` can route traffic through a local TUN interface; a firewall such as NetGuard parses packets and applies UID/rule policy. | Shizuku alone does not see all device packets, and a VPN alone does not decrypt HTTPS; TLS interception would be a separate, high-impact design. A VPN feature needs its own explicit enable/stop control, disclosure, data-retention policy, and conflict handling with other VPNs. Lucifer does not currently run a local VPN. |
| Network byte/usage totals | Android network-statistics APIs have their own access and version limits. | A privileged Binder identity is not proof that every UID's statistics are readable. Check the precise API result and permission on the device. |
| App usage / process diagnostics | Usage access, DUMP/AppOps and ActivityManager surfaces are distinct, often special-access or version-dependent. Termux's phantom-process utilities check for DUMP and PACKAGE_USAGE_STATS before reading the ActivityManager phantom-process limit. | Do not infer app-usage or log access from Shizuku being connected. Lucifer currently has no general telemetry dashboard. |
| Logs, sensors, and settings | Each API/permission has its own Android gate; camera/microphone use must retain runtime consent, foreground-service requirements, and Android indicators. | Shizuku does not replace runtime consent, notification disclosure, user-controlled toggles, or system privacy indicators. |
| Long-running processes | Android may defer background work, enforce Doze/standby policy, or kill cached/phantom processes; OEM process management adds variance. Use supported lifecycle APIs and truthful status. | Shizuku is not a process-survival or battery-optimization bypass. Lucifer must not silently edit DeviceConfig or power settings to keep itself alive. |

The NetGuard source is a useful study of a TUN-based local firewall (Java policy/rule state plus native packet parsers). Termux's `PhantomProcessUtils` is a concrete example of checking Android process limits and required diagnostic access. Those projects are references, not capabilities already present in Lucifer.

## Sources

- [Shizuku project README: architecture, privilege limits, and system Binder broker](https://github.com/RikkaApps/Shizuku/blob/master/README.md)
- [Shizuku API developer guide: Binder wrapper, UserService lifecycle, UID differences, and API changelog](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [Shizuku API sample project](https://github.com/RikkaApps/Shizuku-API/tree/master/demo)
- [NetGuard: local VPN firewall implementation](https://github.com/M66B/NetGuard)
- [Termux PhantomProcessUtils: Android phantom-process diagnostics](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/android/PhantomProcessUtils.java)
- Lucifer's current integration: `ShizukuAccess.kt`, `ShizukuTerminal.kt`, `ShizukuCommandUserService.kt`, and `IShizukuCommandService.aidl`.

The upstream guide reviewed here explains that ADB and root privileges differ, that UserService is not a normal app process, and that system Binder APIs can be version-sensitive. Lucifer's current integration has not been compiled or device-tested in this workspace; verify against the installed Shizuku version and the actual AOSP/OEM build before relying on a capability.
