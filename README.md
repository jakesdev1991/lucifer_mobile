# Lucifer Mobile

Lucifer Mobile is an Android launcher and assistant with natural-language chat, a multi-target terminal, local activity logs, voice output, and limited device/web controls. The actively configured app is Android-only: `composeApp/build.gradle.kts` applies the Android application and Kotlin Android plugins and compiles `src/androidMain/kotlin`.

## What currently works

- **Natural-language-first input:** launcher chat/bar and the MainActivity terminal route text before action. Requests can be answered, clarified, routed to LOCAL/TERMUX/VM, used to open an installed app, or used to open a supported Android Settings page. Tapping an app tile remains an explicit direct launch.
- **Terminal:** LOCAL app shell, Termux commands, configured VM SSH, Shizuku UserService, and a Termux interactive PTY mode. Termux can run a persistent tmux session with Lucifer's draggable chat overlay; chat commands enter that same session after any required confirmation, and the visible pane can be inspected. In TERMUX, **STAGE STATIC** and **STAGE API** create starter projects under `~/lucifer-sites/` and launch loopback-only previews on ports 8765/8766; **STOP PREVIEWS** stops them. These are local demos, not public deployments. Existing site files are preserved instead of overwritten. The optional toolkit installer is explicit and runs visibly in Termux. Non-read-only commands require confirmation; confirmation is not a sandbox.
- **Android control:** open Wi-Fi, Bluetooth, location, display, sound, notifications, battery, app, or Lucifer permission settings. Android keeps protected toggles user-controlled; Lucifer opens the relevant settings screen rather than claiming to change the switch.
- **Shizuku bridge:** checks server/grant/version and reports whether the authorized process is ADB shell (UID 2000) or root (UID 0), then binds Lucifer's bounded AIDL UserService. This build currently executes shell commands rather than direct system-service Binder wrappers; each platform operation remains version/SELinux dependent. Lucifer will not change Permission Manager X grants; recognizable PM/AppOps permission mutations targeting PMX are blocked. See [SHIZUKU_ANDROID_ACCESS.md](SHIZUKU_ANDROID_ACCESS.md).
- **Wallet panel:** create EVM addresses, list/copy addresses, and query native-token balances on Ethereum, Arbitrum, Optimism, Base, or Polygon. Private-key material is stored encrypted with Android Keystore-backed AES-GCM. Legacy raw-hash signing requests are rejected; there is no configured spending policy or transaction-send flow.
- **Read-only browser panel:** fetch HTTPS pages and extract text. It does not execute JavaScript, click, type, sign in, submit forms, or purchase; local/private/reserved hosts are rejected as defense-in-depth.
- **Memory and skills panels:** search recent local app/terminal logs and inspect an honest capability/limitation catalog.
- **Workflow blueprints and runner (implemented, not yet compiled/device-tested):** choose a software-change, research, local-site, or formal-verification recipe; Lucifer plans, runs one user-started terminal/chat step or accepts a user-entered observation from another panel, reflects, and proposes at most one next step. The task target is fixed, non-read-only terminal commands still use the existing exact-command confirmation, and completion requires a separate user action. There is no background workflow loop. Task goals and observations may be sent to the configured NVIDIA model; saved task text is in app-private storage but is not encrypted. Common token/password patterns are redacted from captured output, not every possible secret format. See [WORKFLOW_BLUEPRINTS.md](WORKFLOW_BLUEPRINTS.md).
- **Credential storage:** user-provided NVIDIA and VM secrets are stored encrypted, not embedded in the current source.

### Important limitations

- **Do not fund generated wallets.** There is no recovery phrase/key export, configured spending policy, or transaction-send flow. Raw transaction signing is disabled because the legacy bridge receives only a hash and address and cannot validate recipient, value, fees, or limits. If the device or encrypted app data is lost, those wallets cannot be recovered.
- VM SSH now requires JSch `StrictHostKeyChecking=yes` and a saved `known_hosts` entry. Host-key setup has not yet been verified on a device; independently verify the fingerprint before saving it.
- Termux requires Termux itself, `allow-external-apps=true`, and Lucifer's Termux run-command permission. The interactive mode additionally requires Android's **Display over other apps** permission and installs/uses `tmux`; the optional curated toolkit is installed only after a visible confirmation and package installs are displayed in Termux. Lucifer's small floating chat can submit a single command line to the same tmux PTY, refresh a pane snapshot, or poll it every four seconds when **LIVE** is enabled. When you ask about terminal help, up to 2 KB of current output may be sent to the configured assistant service.
- Android's built-in Linux Terminal is an AVF-managed Debian VM and has no documented third-party shell-control API. Lucifer can launch it with the same floating chat, but direct command control requires enabling `openssh-server` in the guest, configuring Terminal's port-forwarding UI, and entering the forwarded host/port/user/password plus independently verified SSH host key in Settings. Without that bridge, the overlay is chat-only over that app; Lucifer does not bypass AVF or use hidden system APIs.
- VM commands require the configured hostname/IP, reachable SSH on port 8022 as user `droid`, the SSH password saved in Settings, and a `known_hosts` entry whose SHA-256 fingerprint you independently verified. Lucifer refuses VM execution when no matching host-key entry is present.
- **On-device Lean verification is implemented but unvalidated.** In Settings, prepare the public Omega Theory source first; review its exact commit SHA and pinned `lean-toolchain`; then explicitly confirm before audit scripts and `lake build ToE` run in a dedicated Debian tmux job. Status and logs persist in the guest and can be refreshed/stopped. The local VM host must be `127.0.0.1` or `localhost` through the configured forwarded SSH port. Preparation has a 10-minute timeout; verification has a two-hour deadline, and a timeout is never a pass. Requires Git, tmux, coreutils `timeout`, Python 3, elan/Lean/Lake, and network/storage for pinned dependencies; after confirmation, elan/Lake may download the pinned compiler/dependencies, but Lucifer does not invoke apt. This verifies that repository revision only—not the app's non-identical bundled corpus, physical interpretations, or an Android release. No app build or VM execution has yet validated the integration.
- Kai sandbox deployment is not implemented. Settings offers a ping/health check only; bridge operations depend on an installed Kai package and external sandbox files and remain incomplete.
- Camera and its foreground-service type are connected to the opt-in heartbeat. Notification permission is requested before starting on Android 13+; camera permission is requested only when the user separately enables camera context. Storage, Bluetooth, NFC, biometric, and local-network declarations are restored, but those integrations are not yet connected; declarations alone do not grant runtime access.
- **Heartbeat:** an explicit opt-in control in the Heartbeat tab starts a visible foreground service, runs one idea/project session immediately, then waits about 30 minutes after each completed session. The model assigns its own importance score, continues or parks backlog work, reads limited public GitHub Trending/Hacker News pages, and writes Markdown drafts plus a session ledger under private `files/heartbeat/`. A configured NVIDIA API key is required. Camera use is optional and enabled separately in the Heartbeat tab. When enabled and CAMERA consent is granted, the service captures one still per session and sends it with public trend text to NVIDIA's multimodal Nemotron endpoint; the image is not saved by Lucifer. The service never executes generated commands, posts, submits forms, deploys, or writes outside that private directory. Android may defer or stop background work, so cadence is approximate. An opted-in service attempts to resume after reboot or a service restart in text-only mode; camera must be re-enabled from the foreground app. API usage may incur cost.
- No public website deployment or real USDC checkout is implemented. **PAYMENT DEMO** creates a clearly labeled local-only prototype; quote and checkout requests return disabled responses and cannot move money. The requested payment direction is crypto plus card/bank checkout, with USDC settling on Arbitrum into a separate receiving wallet; provider, supported source assets, wallet recovery, and payment verification are still to be designed. Do not use the current generated wallets for funds. Browser automation and web writes are not implemented; the Browser tab and heartbeat research are read-only. Generated work is local draft writing, not writing to shared Documents or publishing to a website.
- Browser URL/DNS checks are not a hardened defense against DNS rebinding. Treat fetched web content as untrusted.
- See [APP_CAPABILITY_AUDIT.md](APP_CAPABILITY_AUDIT.md) for the broader capability map, risks, and prioritized follow-up list. See [SECURITY.md](SECURITY.md) for credential-handling notes.

## Using chat and the terminal

Open **CHAT** from the launcher or type into the launcher bar. The request is understood before routing. Examples:

- “Show disk space on my VM”
- “List files in Termux”
- “Open Chrome”
- “Open wallet,” “Open the browser panel,” or “Open workflows”
- “Open Bluetooth settings” (Android will ask you to make protected changes there)
- “What is entropy?”
- “Run `git status` on the VM”

Choose LOCAL, TERMUX, VM, or SHIZUKU as the default destination, or name a target in the request. Familiar read-only diagnostics can run directly. Other terminal commands display the exact command and target and wait for explicit confirmation. Questions and hypotheticals are chat, not shell commands. In the Terminal tab, select TERMUX and tap **OPEN PTY + CHAT** for a persistent interactive tmux shell with the floating companion; **INSTALL TOOLKIT** offers the curated Termux package set with a package-install confirmation first. **STAGE STATIC** and **STAGE API** create a starter site and a read-only sample API under Termux home and run each only on `127.0.0.1`; use the on-device browser at the displayed URL to preview. **PAYMENT DEMO** runs a no-money prototype for the selected Arbitrum USDC + crypto/card-bank direction; it has no wallet connection, quote provider, card processor, recipient address, or payment endpoint enabled. **STOP PREVIEWS** stops all local preview processes. Select VM and tap **OPEN ANDROID LINUX + CHAT** to launch the built-in Linux Terminal app when present.

For Termux, install/open it once, set `allow-external-apps=true` in `~/.termux/termux.properties`, run `termux-reload-settings`, and grant Lucifer Android’s **Run commands in Termux environment** permission.

For VM commands, configure the host/IP, SSH user, port, password, and `known_hosts` entry in Settings. For the Android Linux Terminal bridge, install `openssh-server` and `tmux` in the Debian guest, start SSH, and map a host-side port to guest port 22 in the Terminal app; use host `127.0.0.1` and that forwarded host port in Lucifer. In the Terminal app, start the shared shell with `tmux new-session -A -s lucifer`. Obtain a host-key line (for example, with `ssh-keyscan -p PORT HOST`), but **do not trust `ssh-keyscan` by itself**: compare the SHA-256 fingerprint against the VM console or another trusted channel before saving the line. Lucifer refuses VM execution if the host key is missing or changed. With this setup, both the Linux Terminal UI and Lucifer's overlay operate on that same tmux PTY; without it, the overlay cannot inspect/control the AVF guest.

## Build

Requirements: JDK 17, Android SDK 34, Android Gradle Plugin 8.3.0, Kotlin 1.9.10, and Android Studio/Gradle dependencies available.

```bash
./gradlew :composeApp:assembleDebug
```

The repository also includes native build/deploy scripts; use them only if your target workflow needs those components. They have not been validated as part of this audit. The current audit environment has no Java runtime, so Gradle compilation and tests were not run.

## Active project layout

```text
composeApp/
  build.gradle.kts                    # Android application plugin/source configuration
  src/androidMain/AndroidManifest.xml
  src/androidMain/kotlin/com/lucifer/mobile/
    MainActivity.kt                   # Terminal, memory, skills, settings, wallet, browser, heartbeat, workflow tabs
    launcher/                         # Home launcher, chat, natural-language routing, voice
    workflows/                        # Bounded workflow plans, task persistence, and state transitions
    terminal/                         # Local, Termux PTY/overlay, VM, and Shizuku execution
    data/wallet/                      # Native EVM wallet and encrypted persistence
    data/browser/                     # Read-only HTTPS fetch/extract
    security/                         # Android Keystore-backed secret storage
    omni_bridge/                      # LLM, Kai sandbox, reasoning, and opt-in heartbeat service
    ui/components/                    # Active Compose UI components
```

The repository also contains dormant `commonMain` and `kotlin.backup` prototype sources. They are not included by the active Android source-set configuration and should not be assumed to reflect the shipped app.
