# Pixel 8a Android systems plan and current implementation

## Device targeting rule

Pixel 8a's device codename is `akita`. Lucifer must not assume a fixed Android release from that model name: stock AOSP/Pixel builds, beta builds, and custom AOSP-derived builds can differ. The Settings snapshot reads the running device/build values (`Build.DEVICE`, release/API, security patch, build fingerprint, kernel, power/process state) at runtime and labels the device as Pixel 8a only when the reported model/codename matches.

This checkout currently compiles and targets Android API 34 (`compileSdk = 34`, `targetSdk = 34`, `minSdk = 28`). Runtime diagnostics can report a newer OS, but that does not mean this binary uses newer APIs or that newer-OS behavior has been tested. Do not infer current Pixel firmware or Shizuku capability from the hardware model alone.

## Delivered read-only surfaces

### 1. Package permission and AppOps inspection

Settings can inspect one package, entered by the user, through Shizuku using only:

- `dumpsys package <validated.package.name>`
- `cmd appops get <validated.package.name>`

The package string is constrained to Android package-name characters before command construction. The actions are read-only; results have bounded output, are displayed in Settings memory, and are not saved or sent to the model. A failed/unsupported shell command is shown as a failure. The report is not a complete audit log and does not imply that an AppOp can replace an Android runtime permission.

This path does not add `QUERY_ALL_PACKAGES`, does not write permissions/AppOps, and does not use hidden `IAppOpsService` interfaces. The existing terminal policy blocks recognizable direct `pm`/`appops` permission mutations targeting Permission Manager X, while acknowledging that the general shell is not a security sandbox against deliberately obfuscated commands.

### 2. Pixel and process-lifecycle snapshot

The Settings diagnostics card reads and displays, locally:

- detected model/codename, Android release/API, security patch, build/fingerprint, and kernel string;
- Lucifer's process importance and Android's background-restricted flag where available;
- current idle/power-save and thermal state where the platform exposes them;
- whether Lucifer itself has Usage Access (status only; no other-app history is queried);
- whether Android currently reports a VPN transport, and that Lucifer has no packet-capture VPN implementation.

These are point-in-time observations, not guarantees that Android will keep a process alive. Doze, cached/phantom process policies, system resource pressure, thermal management, and OEM policy can affect a Pixel 8a differently across builds. Lucifer must not silently change DeviceConfig, power settings, or app grants to evade those policies.

### 3. Network telemetry / VPN status

Lucifer currently only reports whether the active Android network has VPN transport. It does **not** declare or start a `VpnService`, establish a TUN, collect packet metadata, or forward/filter traffic. This is deliberate: an incomplete TUN reader could black-hole connectivity. A working VPN needs a complete TCP/UDP/IPv4/IPv6 data plane, loop prevention, route/DNS policy, lifecycle/foreground-notification handling, cleanup, and explicit Android consent before any packets are captured.

Before implementing a Pixel 8a VPN, validate on the actual build: Wi-Fi↔cellular handoff, IPv4 and IPv6, DNS and encrypted-DNS behavior, QUIC/HTTP/3, VPN conflict/always-on/lockdown states, stop/revoke behavior, and failure recovery. Disclose exactly which metadata is retained and keep payload capture/TLS interception out of scope unless the user separately requests and approves it. No traffic should be routed into a non-forwarding prototype.

## Shizuku specifics

The existing Shizuku bridge uses `UserService` plus Lucifer AIDL and a bounded shell command, not direct `ShizukuBinderWrapper` calls to PackageManager/AppOps. UID 2000 means ADB shell, not root; UID 0 is root when the owner started a root backend. Permissions and SELinux enforcement are still build/operation dependent. The package audit above is opt-in, read-only, and requires the user-started Shizuku server and Lucifer grant.

## Pixel 8a verification checklist

1. Open Settings → **PIXEL / ANDROID PROCESS DIAGNOSTICS** and record the displayed device code, Android API/release, security patch, and build fingerprint. This is the source of truth for the installed OS.
2. Start Shizuku through the owner's Wireless debugging flow; request/approve Lucifer in Shizuku; verify the reported UID and the `id` test.
3. Run the read-only package report for a known installed package. Confirm both sections show either data or an explicit permission/unsupported error, and confirm no setting changes.
4. Lock/unlock the screen and revisit diagnostics to observe process/power state. Do not treat one sample as a background-execution guarantee.
5. For VPN work, first implement and test a proper packet forwarder on a dedicated test build/device. Until then, keep Lucifer VPN capture disabled and use an existing VPN only if the user chooses it.

## Reference projects

- [Pixel 8a (`akita`) device source example](https://github.com/GrapheneOS-Archive/device_google_akita)
- [Shizuku API and UserService guide](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [NetGuard's local VPN firewall](https://github.com/M66B/NetGuard)
- [Termux phantom-process diagnostics](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/android/PhantomProcessUtils.java)
- [AppOpsNext: recent Shizuku-based AppOps research](https://github.com/1zumiii/AppOpsNext) — GPL-3.0; reference the design and documented limitations, do not copy its implementation into Lucifer.
