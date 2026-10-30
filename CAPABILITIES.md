# What Lucifer can actually do

A capability inventory of the code as it stands on this branch. It is deliberately blunt about the
difference between *implemented*, *host-verified*, and *review-only*, because the app's own docs already
are — and because a capability list that flatters itself is worse than no list.

**Bottom line:** 125 Kotlin files, ~33,800 lines, one Android app (`com.lucifer.mobile`), minSdk 28,
targetSdk 34, compileSdk 35. It is a **replacement home screen** that is also an assistant with four
shell targets, a persistent memory, a self-adapting behaviour store, an opt-in background brainstormer,
a background observer that only ever proposes, a device-owner "world", a metered Lean-4 verification
server, and a small EVM wallet. **None of the Kotlin has been compiled in this workspace** (no JDK, no
Android SDK); the host-side models and audits are what have actually been run. See
[Verification status](#verification-status).

---

## 1. It is the home screen

`launcher/LauncherActivity` registers `CATEGORY_HOME`, so Lucifer *is* the launcher, not an app on top of
one. It lists every launchable package with real icons, journals installs/removals/updates, and offers a
dock for Termux, the Linux Terminal, Ashigaru, Lucifer itself and the dialer. `PackageEventReceiver`
records `PACKAGE_ADDED/REMOVED/REPLACED` into `files/memory/app_events.log`, which later becomes one of
the General's senses.

The launcher bar and chat are natural-language first: text is understood before anything happens. The
router (`launcher/NaturalLanguageRouter`) returns one of **eight** intents — `chat`, `execute`,
`clarify`, `open_app`, `open_settings`, `open_panel`, `recover`/`research`/`lessons`/`plan` (the
resilience family) — from a Nemotron call with a strict JSON contract, with a deterministic keyword
fallback when the model is unreachable. Tapping an app tile is still a direct launch; nothing else is.

**Android control:** it opens the real Settings pages — Wi-Fi, Bluetooth, location, display, sound,
notifications, battery, apps, Lucifer's own permissions. It never claims to flip a protected switch; it
opens the screen and hands you the toggle.

---

## 2. Eleven panels in the control app

| # | Panel | What it is |
|---|---|---|
| 0 | Terminal | multi-target shell + chat |
| 1 | Memory | local episodic memory, search, pause/clear |
| 2 | Skills | operational registry, 100-entry reference library, learned skills |
| 3 | Settings | 19 cards: privacy, soul, heartbeat, Shizuku, diagnostics, SSH ×4, Lean, NVIDIA key, wallet, Kai, knowledge, local memory, learned skills, Drive MCP, voice, status |
| 4 | Wallet | EVM address generation, balances |
| 5 | Browser | read-only page fetch |
| 6 | Heartbeat | opt-in idea engine |
| 7 | Work | workflow runner |
| 8 | Recover | failure episodes and moves |
| 9 | Agents | soul/heartbeat/agent files, the General |
| 10 | World | managed profile, owner actions, verifier |

The whole UI is a custom design system (`ui/theme/Morningstar`): deliberate crimson/gold palette, chamfered
`ShardShape` cards, a sigil clock, ember/burn interactions. Backdrop blur needs API 31 and the build ships
minSdk 28, so "glass" is layered gradients plus a 1 px hairline — a stated limitation, not a bug. A
browser-viewable mockup of the home screen lives at `design/morningstar-home.html`.

---

## 3. The mind

**Model.** `omni_bridge/llm/NemotronClient` is a pure-Kotlin OpenAI-compatible client for
`integrate.api.nvidia.com`, with a fallback chain (`nemotron-3-ultra` → `llama-3.1-nemotron-70b-instruct`),
a user-selectable override, and a vision path
(`nemotron-3-nano-omni-30b-a3b-reasoning`) that accepts one still image up to 2 MiB. The API key is
supplied by you, stored AES-GCM under an Android Keystore key, never shipped. If every route is dead it
says so instead of inventing an answer.

**The Four Horsemen** (`FourHorsemenOrchestrator`) is a real multi-pass council, not a metaphor: Conquest
(structural synthesis) → War (adversarial plan against Conquest) → Famine (compression/execution order) →
Death (invalid-assumption pruning), each a separate model call with its own persona prompt. Offline, each
degrades to a corpus-cited canned line rather than pretending.

**The Omega corpus is inside the APK.** `assets/toe/` is 944 KB: 54 Lean 4 volumes plus their text forms,
axioms, protocol, dimensional hierarchy, unified foundation, a layman's guide, a technical volume, the full
book, and `lucifer_super_reasoning.md` used as the system prompt. `EmbeddedToE` searches it offline with
zero network; `ToEReasoningEngine` runs PERCEIVE → UNDERSTAND → DECIDE → ACT against it. An optional
GitHub overlay (`toe_live/`) can be synced, and `OmegaKnowledgeClient` can additionally talk JSON-RPC to a
separate `omega-knowledge-mcp` server on loopback (port 8030) if one is running.

**Surface awareness.** Ten named surfaces (`launcher`, `core`, `terminal`, `overlay-termux`, `overlay-vm`,
`workflow`, `recover`, `agents`, `heartbeat`, `unknown`). Questions about himself — "what can you do
here", "can you see my screen" — are answered **deterministically from compiled surface data plus live
device state** (network switch, model key, Termux permission, VM host-key pin, Shizuku presence), never by
the model guessing. Every other turn carries the surface block in its prompt, so "here" is grounded. The
answer always states the boundary: he knows which surface he is on and does not see the screen.
`audit_surfaces.py --check` fails if any reasoning call site forgets to declare its surface.

**Memory.** App-private SQLite with FTS (`LocalConversationMemory`): compact, redacted digests of
exchanges, completed command results and heartbeat findings, importance-ranked, **no age-based expiry**,
2 GiB hard disk cap. Retrieved and inserted before model calls; local recall still works offline.
Separately, `DriveMemoryMcpClient` does read-only retrieval from a private `Lucifer_Memory.md` through a
token-protected **loopback** Termux MCP service written in C++17 (`mcp/cpp/drive_memory_mcp.cpp`), with
llama.cpp Q4 embeddings fused with BM25 and lexical fallback. The Drive file is never mirrored to disk.

**Learned skills** (`synth/`, `skills/SkillSynthRuntime`). Always on, no button. A correction scores the
skills that were in that turn's prompt as a loss and creates a `CORRECTION`; the same question asked again
within 10 minutes is a weak loss plus a `ROUTING` candidate; a recovery tactic that fixed a failing
command becomes a `PROCEDURE`; a compliment is a win; a plain directive is a `DIRECTIVE` live after one
confirmation. Laplace-smoothed confidence, promotion at 2, retirement at 3 losses under 0.45, revival,
stale drop, directive supersession. Caps: 64 skills, 5 lines / 1200 chars per prompt, one atomic file.
Injected as *habits, not permissions*, with a footer that says so. See [SKILL_SYNTHESIS.md](SKILL_SYNTHESIS.md).

**The reasoning loop** (`cognition/`, [COGNITION.md](COGNITION.md)). A message is triaged first: greetings,
short facts and anything with no tools available take the existing single-pass path. A multi-part question,
a reasoning shape ("design", "debug", "why does") with a second clause, or `/deep` enters a bounded
think-act-observe loop — 8 steps, 90 seconds, 24 000 characters of observation — where each step is one
model call, one tool, and one result. Seven tools, each with a declared side effect: corpus search, local
memory search, device facts (`DEVICE_READ`), clock, exact arithmetic (a real parser, not a guess), a page
read, and bounded web research — the last two through the egress gate, so they appear in the ledger and
ride Tor. Anything that writes, changes the device or sends outward requires the owner's confirmation and
**none ship**, so the loop reads and concludes and changes nothing. Before the answer is shown it is
audited: grounded in what a tool returned, no certainty it has not earned, no claim about this device
without a device read. One repair attempt on a blocker, then it says so. Confidence is a four-rung ladder
that is never HIGH without two observations. Tool output is treated as hostile: a fetched page cannot
speak the control format, because every observation is defanged before it reaches a prompt. Every run is
logged to `files/cognition/runs.jsonl` (names, counts, confidence — never what a tool returned), the last
run's step-by-step trace is in the Skills tab, and **CLEAR THINKING HISTORY** in Settings erases both that
log and the per-tool record. He also keeps score on his own tools: called, and came back with something.
Once a tool has been tried three times and succeeded less than half of them, one line joins the loop's
instructions naming it and saying not to retry it unchanged, and "how do you think?" answers with that
record rather than only with a description. A follow-up ("and why?") is fed the previous two turns; the
overlay and the launcher print each step while it runs, and a STOP control cancels a run mid-flight
without the cancellation being mistaken for a tool failure. `/deep` still forces the loop from any of
them.

**Reference library.** 100 reference-only skills in 10 packs (`assets/skills/skill_library.json`, 53 KB),
schema-validated and fail-closed, lexically retrieved into Four Horsemen and workflow prompts. Immutable,
`REFERENCE_ONLY`, never a tool.

---

## 4. The hands — four shell targets

| Target | Mechanism | Boundaries |
|---|---|---|
| **LOCAL** | `Runtime.exec` in the app sandbox | Not a root shell; Android's sandbox restrictions apply |
| **TERMUX** | `RUN_COMMAND` one-shot + a real interactive PTY attached to persistent `tmux` | Needs Termux, `allow-external-apps=true`, and the run-command grant |
| **VM** | SSH via JSch to a Debian/AVF guest | **Fails closed** without a pinned `known_hosts`; `StrictHostKeyChecking=yes`; default `8022`/`droid` |
| **SHIZUKU** | AIDL UserService bound through Shizuku | Reports whether it is ADB shell (UID 2000) or root (0); no direct PM/AppOps Binder wrappers |

Every target is reachable from chat, and every **non-read-only command requires exact-command
confirmation**. Confirmation is a consent gate, not a sandbox — confirming `rm` authorises the `rm`.

**Floating companion.** `FloatingChatService` is a `TYPE_APPLICATION_OVERLAY` foreground service: a
draggable chat over Termux or the Linux Terminal VM that types one confirmed command line into the same
tmux PTY, captures the visible pane, or polls it every 4 s on LIVE. Up to 2 KB of current output may be
sent to the model when you ask for terminal help. The AOSP Linux Terminal is AVF-managed with no public
shell API — Lucifer launches it and talks to it, and only reaches its shell if you enable `openssh-server`
in the guest and forward a port; it does not bypass AVF or use hidden APIs.

**Local sites.** STAGE STATIC / STAGE API / PAYMENT DEMO write starter projects under `~/lucifer-sites/` and
serve them in separate tmux sessions on **loopback only** — 8765 static, 8766 API, 8767 the no-money USDC
checkout demo, whose POST routes return 503 and cannot move money. Existing files are preserved; STOP
PREVIEWS stops them. These are local demos, never public deployments.

**Workflows.** Seven blueprints — software change, research brief, local website, *authorized* bug bounty,
Pixel 8a system/permission audit, recover-from-failure, formal verification. The runner plans, executes
one user-started step (or accepts an observation you type in), reflects, and proposes at most one next
step; completion is a separate user action. No background loop. Text is unencrypted app-private storage.

**Recovery.** Every finished command is classified into 18 failure classes. A failure opens an episode:
a **fixed 32-probe read-only catalog** (scoped per target) inspects the machine, the model may diagnose
with divergent hypotheses (heuristic fallback without one), and **read-only web research** (DuckDuckGo HTML
plus a couple of result pages, `robots.txt`-aware with longest-match Allow/Disallow and crawl-delay,
rate-limited, page-budgeted, `[S1]` citations) can ground it. Moves come from 14 kinds × the per-class
playbook **and** from locally recorded outcomes, so a failed tactic is not repeated and a working one is
promoted. Read-only moves run immediately; anything that changes the system goes back through the router
and confirmation.

**The world.** `World` tab, two deliberately separate backends:

- **Shizuku** gives a shell (ADB or root) for `pm`/`cmd`/`am`; dies on reboot.
- **Dhizuku** gives a borrowed **device-owner** identity over Binder; survives reboot; cannot run shell text.

`LocalOwnerOps` turns either into real device-owner calls: create a managed profile, `setApplicationHidden`,
`setPackagesSuspended`, `setPermissionGrantState`, `addUserRestriction`, `logoutUser`. A managed profile is
a separate Android user with its own data and processes — Lucifer can wake it (`am start-user`), freeze it
(`am stop-user -f`), launch apps into it via `CrossProfileApps`, and start the VM host inside it, where
everything freezes with the profile. `WorldCharter` marks every action non-autonomous and non-LOW risk
actions always show their literal command and wait. Nothing in this layer is reachable from chat, an
agent, a workflow or a background pass. He will not hide, suspend or uninstall himself, Shizuku, Dhizuku or
Permission Manager X, and PMX's grants stay read-only — `TerminalCommandPolicy` blocks recognisable
`pm grant`/`pm revoke`/`appops set` targeting PMX by name.

**The Lean verification server.** Lucifer hosts a Lean-4 proof-checking **MCP server** bound to
`127.0.0.1:8791` in his world profile. The phone is the control plane — endpoint, keys, scopes, quota,
metering, ledger, tunnel — and every elaboration runs on a remote Lean host, because a work profile cannot
build mathlib. Six tools and no shell: `lean.check` (1 credit, 120 s), `lean.sorry_scan` (2),
`lean.axioms` (3), `lean.build` (5, 900 s), `lean.gates` (6), `lean.verify` (8, 1800 s). `lean.gates` is
the point: kernel soundness is the floor, not the audit, so it runs four vault gates — zero
`sorry`/`admit`/`native_decide`/`axiom`, no vacuous or degenerate statement, no name asserting more than it
proves, declaration parity — and returns suspects with line numbers. Best possible outcome is
`GATES CLEAN — human validation still required`; it never says "validated". Requests pass four gates in
order (key+scope, tool allowlist, parameter planning with a source screen for
`#eval`/`unsafe`/`run_cmd`/`@[extern]`, then quota) so a refusal is free and never reaches the host. Keys
are `lvs_<id>_<secret>` with only SHA-256 stored; scopes `READ`/`VERIFY`/`BUILD`/`ADMIN`; plans `INTERNAL`
(unbounded, Lucifer's own), `FREE` 50, `METERED` 5,000 @ $0.012, `ENTERPRISE` 200,000 @ $0.008. Work is
charged **after** it happens, never when no host ran it. Publishing needs a tunnel the owner starts by hand
and confirms; the socket itself opens no route out.

**Verification in the guest:** the separate Settings path can prepare the public Omega Theory source at a
reviewed commit SHA, then run audit scripts and `lake build ToE` in a dedicated Debian tmux job (10-minute
prepare timeout, 2-hour verification deadline; a timeout is never a pass). It never invokes `apt`.

---

## 5. The Senses, the switch and the ledger

**Transport · Tor for his own traffic.** Settings → Privacy has a TRANSPORT switch. On `VIA TOR`,
every remote request Lucifer makes is tunnelled through a SOCKS5 proxy (Orbot, `127.0.0.1:9050`) with the
hostname sent **unresolved**, so the phone never performs the DNS lookup that would otherwise defeat the
proxy. TLS has hostname verification, `.onion` works only in this mode, and a dead proxy means the
request is **refused — never sent directly**. `TEST CIRCUIT` asks Tor Project whether the exit is a Tor
node. There is deliberately **no device-wide `VpnService`**: that needs a userspace TCP/IP stack, and a
half-built one reports "connected" while leaking. See [NETWORK_AND_TOR.md](NETWORK_AND_TOR.md).

**Egress ledger + kill switch.** Every outbound request is authorised by `privacy/NetworkGuard`
immediately before its socket opens, and `privacy/EgressHttp` is the only place in the app that can open
one — currently **9 gated call sites, 1 socket owner, 0 outside the gate**. Settings → Privacy offers
**GO DARK · NETWORK OFF**, which makes every non-local destination refuse to connect while Termux, the VM
bridge, the memory MCP and the Omega server keep working. Each attempt is recorded in a bounded local
ledger of **host and path only** — never a query string — with trigger, class
(LOCAL/MODEL/VISION/SEARCH/PAGE/RPC/CODE) and whether a person asked. It is transparency and a kill
switch, **not anonymity**: it does not touch the radio, the network path or attestation. `audit_egress.py
--check` fails on a socket opened outside `EgressHttp`, an ungated site, or any GMS/Firebase/play-services
dependency.

**Play-stack detection.** Settings reports Play Services vs official microG vs ReVanced GmsCore vs nothing,
then reports `FLAG_SYSTEM` — whether Play is a **system** app with reach you cannot revoke, or an ordinary
sandboxed one. Detection does not trust the package name (official microG installs as
`com.google.android.gms`); it scores label, version shape, installer and Play Store identity, needs two
signals before naming microG, and otherwise shows `UNKNOWN` with its evidence.

**Pixel/runtime diagnostics.** A local, read-only, never-uploaded snapshot: Pixel 8a (`akita`) identified
from runtime properties, API/build, process importance and background restriction, idle/power-save/thermal,
Usage Access *for Lucifer only*, active VPN transport. It does not read other apps' usage history and does
not capture traffic. Read-only Shizuku package audit (`id`, `dumpsys package`, `cmd appops get`) for one
package you type in, kept in memory and never sent to the model.

**The General (tactician).** An opt-in foreground service, notification with Stop, one bounded pass
roughly every 60 minutes, resumable after boot only if enabled. It reads only what the app already
recorded, as summaries: app installs, commands and outcomes (last 6 lines, redacted), recovery lessons,
workflow status counts, heartbeat session metadata, camera *use* (never the image), model call counts,
agent runs, and battery/charging/SDK once per 6 h. Deterministic detectors run first (exhausted recovery
signatures, missing instrumentation, repeated failing commands, permission denials, unused/overlapping or
invalid agents, heartbeat drift, camera consent with no camera use, stalled workflows, budget pressure,
proposal backlog); the model may add at most 3 interpretations and the digest is marked untrusted.
Budget: **40 model calls/day, 20-minute floor between passes, quiet hours 23:00–07:00**, checked before the
call, with the reason reported when a pass is skipped. Every proposal is `APPROVAL_REQUIRED` — there is
no path from proposal to execution. Explicitly out of scope: message bodies, browsing history, other apps'
data, keystrokes, contacts, location.

**Agents.** `soul.md`, `heartbeat.md` and one `agent-class.md` per agent are the source of truth, all
user-editable in the Agents panel, and every soul read goes through one validated store. Agents are
user-written or composed by Lucifer from a goal (bounded JSON contract, deterministic fallback; ids,
author, and the boundary block always come from the deterministic path). Roster caps at 24; mandates are
rejected if short, oversized, or credential-bearing (`sk-…`, `nvapi-…`, `AKIA…`, key blocks) because prompt
files are sent to the model on every call. `skills`/`triggers` are **routing hints only** — they never grant
a tool, a target or a permission.

**Heartbeat.** Opt-in foreground service: one session immediately, then ~30 minutes after each completed
session. Reads limited public GitHub Trending / Hacker News, asks the model to score an idea or continue a
project, and writes Markdown drafts plus a session ledger under private `files/heartbeat/`. It reads **one
still** from the camera per session (only if you separately enable camera and grant consent) and sends it
to NVIDIA's multimodal endpoint — the image is not saved. It never executes a generated command, posts,
submits, deploys, messages anyone, spends, or writes outside that private directory. Android may defer or
stop it, so cadence is approximate.

---

## 6. Wallet

Generates EVM addresses, stores private-key material AES-GCM under an Android Keystore key, lists and
copies addresses, and queries **native-token** balances over public JSON-RPC on Ethereum, Arbitrum,
Optimism, Base and Polygon. That is the whole of it.

- No recovery phrase or key export.
- No spending policy.
- **No transaction sending.** Legacy raw-hash signing is rejected because the request carries no
  chain/recipient/value/fees and therefore cannot be policy-checked.
- No WalletConnect, no token transfers, no Solana.

**Do not fund these wallets.** If the device or its encrypted app data is lost, they are gone.

---

## 7. The browser

Fetches HTTPS and extracts readable text. Redirects are re-checked, local/private/reserved address targets
are rejected, cookies are scoped to the requesting host. No JavaScript, no clicking, no typing, no
login, no form submission, no purchase, no screenshot, no session persistence. The DNS-rebinding
resistance is defence-in-depth, not a hardened boundary, and fetched text is untrusted.

---

## 8. What it will not do — by design, not by omission

- **No root, no Magisk, no LSPosed/Shamiko, no signature spoofing, no microG install.** The user cannot
  root the device; that is accepted, and none of it is proposed.
- **No IMEI/baseband spoofing** (illegal in the US/UK/EU, needs privileged telephony hooks, breaks modems
  and attestation), **no mock location** (no location permission is declared and no location API is
  called), **no MAC or identifier spoofing**.
- **No device-wide VPN.** Tor is implemented for *his own* traffic (`net/` + `privacy/TorRuntime`); a
  `VpnService` that would cover every app is not, because a userspace TCP/IP stack is a months-long
  project and a leaking VPN is worse than none. He *reports* whether an OS-level VPN is active. Identity
  hiding remains a ROM-level question, not an app-level one.
- **No Google.** Zero GMS/Firebase/play-services dependencies, enforced by the egress audit.
- **No PMX tampering**, no hiding or suspending itself or its tools.
- **No autonomous execution.** Recovery, the General, heartbeat and workflows all stop at "you confirm".
- **No public deployment, no real payments.** The checkout is a labelled demo whose POST returns 503.
- **No fake success.** Offline paths say they are offline; the verifier never says "validated"; the browser
  and heartbeat say they are read-only.

---

## 9. Declared but not connected

This section used to list eleven manifest permissions with no call site. That is now **zero**: the
manifest is audited by `tools/manifest-core/audit_manifest.py`, which fails the build for any permission
that is neither used by code nor granted outright by the platform, and `verify_all.sh` runs it on every
push.

What changed:

| Permission | Before | Now |
|---|---|---|
| `BLUETOOTH*` (4) | declared, nothing called it | **withdrawn.** Nothing to connect was ever built; radio toggling stays in Settings |
| `NFC` | declared, no `NfcAdapter` | **withdrawn.** No reader mode, no tag dispatch |
| `USE_FINGERPRINT` | deprecated alias | **withdrawn**, superseded by `USE_BIOMETRIC` |
| `READ/WRITE_EXTERNAL_STORAGE` | declared, unused | **withdrawn.** Export goes through the system file picker (SAF), which grants one URI and no permission |
| `USE_BIOMETRIC` | declared, unused | **wired** — `security/BiometricGate.kt` in front of owner calls and world changes |
| `RECORD_AUDIO` | absent | **added and wired** — press-to-talk `SpeechRecognizer`, destroyed after each answer. The rest of the voice work is in `BROWSER_AND_VOICE.md` |
| `CAMERA`, `ACCESS_LOCAL_NETWORK`, `FOREGROUND_SERVICE_*` | declared | unchanged, each with a call site |

**Dead or overstated modules.** The ten `omni_bridge` subpackages
(`gepa`, `taro`, `qstar`, `rcod`, `meta_cognitive`, `ring_buffer`, `atoms`, `appa`, `compression`,
`evidence_ledger`) are **deleted**, not re-advertised. They claimed capabilities this architecture cannot
have: TARo steers *token logits* and QSTAR routes on *model internals*, but Lucifer talks to a hosted
API over HTTPS and never sees a logit, a KV cache or a gradient. RCOD detects "phase transitions" in a
state vector that nothing produces. `evidence_ledger`'s BLAKE3 Merkle tree is superseded by the egress
ledger and the privilege ledger, which record real events. A delete is the honest fix; a stub would
have been a quieter lie. The `rcod` name survives only in `git log`.

Still genuinely absent: SMS/contacts/calendar/alarm integration (no such permissions), no notification
listener, no camera control outside the heartbeat, no wallet send/export, and no `VpnService` of its
own (Tor is Orbot's, reached over its SOCKS port). `native/lucifer_native.cpp` (197 lines, JNI + unix
sockets) exists but `System.loadLibrary` is commented out in the active source. `KaiBridge` is a ping
with stubs; no deployment is wired. The `commonMain` tree and `androidMain/kotlin.backup` are stale and
uncompiled.

---

## 10. Repository tooling (host-side, not shipped)

- **9 verification harnesses** (`scripts/verify_*_core.sh`) driven by `scripts/verify_all.sh`, plus **3
  static audits** (`audit_egress.py`, `audit_config.py`, `audit_surfaces.py`) and the skill-library
  validator. Two of the harnesses (`privacy`, `surface`, `synth`) also have executable **Python mirrors** of
  their specs.
- **~1,000 host assertions** across `tools/*/` Kotlin harnesses: agents 154, verification 348, privilege
  131, privacy 124, synth 110, resilience 100, design 72, surface 52. Each harness compiles the real logic
  against minimal Android stubs and must print `SKIPPED` (not fail) when no toolchain exists.
- **CI** (`.github/workflows/verify.yml`): a job that always runs the Python/static half, and an opt-in job
  (`RUN_KOTLIN_HARNESSES=true`) that installs JDK 17 + kotlinc and runs every harness in strict mode.
- **C++ MCP memory server** with CMake, Termux install scripts, runit persistence, and an official-protocol
  test client; **design mockup** with a local preview server; **deploy script** for a Pixel 8a.

---

## Verification status

Run in this workspace, just now:

| Check | Result |
|---|---|
| `python3 tools/privacy-core/spec_check.py` | **PASS · 92 checks** |
| `python3 tools/surface-core/spec_check.py` | **PASS · 72 checks** |
| `python3 tools/synth-core/spec_check.py` | **PASS · 128 checks** |
| `python3 tools/privacy-core/audit_egress.py` | 10 gated sites · 2 socket owners · 0 outside the gate · 0 Google references |
| `python3 tools/surface-core/audit_surfaces.py` | 6 reasoning sites · 0 undeclared · 10 surfaces · 0 undescribed |
| `python3 tools/config-core/audit_config.py` | every config path named once, every write atomic, no bypass writers |
| `python3 scripts/validate_skill_library.py` | 100 skills, 10 packs, 53,392 bytes, all `REFERENCE_ONLY` |
| `python3 tools/net-core/spec_check.py` | **PASS · 110 checks** |
| `python3 tools/cognition-core/spec_check.py` | **PASS · 204 checks** |
| `python3 tools/cognition-core/fuzz.py` | **FUZZ OK · 13 invariants** |
| `python3 tools/cognition-core/loop_sim.py` | **LOOP SIM OK · 7 invariants, scripted runs** |
| `./scripts/verify_all.sh --quiet` | **PASS 18 · SKIP 0 · FAIL 0** |
| `./scripts/verify_cognition_core.sh` | **PASS · 141 + 21 + 10 + 9 Kotlin checks** + 49 static claims |
| `./scripts/verify_synth_core.sh` | **PASS · 128 spec checks + 28 Kotlin checks** |
| `./scripts/verify_verification_core.sh` | **PASS · 32 + 23 Kotlin checks** + adapter type-check |

**Compiled and executed on the host, not on a device.** A JDK (`jdk4py`) and `kotlin-compiler` (npm)
were installed into this sandbox, so every harness now compiles the real logic and runs it: the counts
above are executed checks, not reviews, and the first compile found five defects that no static check
had seen — an unescaped-quote syntax error in `CognitionCore.kt`, a loop guard that skipped its own
observation check, a preference the synthesizer learned and then discarded, `div)` accepted as a CSS
selector, and three harness/stub signatures that had drifted from the code they stand in for.

**Still not verified anywhere:** there is no Gradle, Android SDK, emulator or phone in this workspace, so
no Gradle build has run, no lint or APK was produced, and nothing has been executed on a device — the
whole UI, the Shizuku DHCP path, VM SSH pinning, the overlay chat, the heartbeat camera, the Lean server's
service wrapper, the World actions, and any real Tor circuit. The single biggest open item (P0 in the
audit) is unchanged: give CI a JDK 17 + SDK and run `:composeApp:assembleDebug`.

Two smaller caveats worth carrying: the harnesses are **host logic + type-check**, so they prove the rules
and signatures, not behaviour; and the Python mirrors prove the *spec* the Kotlin is supposed to implement,
which is only as good as the drift check that keeps them aligned.
