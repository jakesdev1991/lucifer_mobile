# BUILDOUT LOG — feature/lucifer-full-buildout
Started: 2026-11-01 ~08:25 EST. Brief: /tmp/lucifer_buildout_brief.md (14 items).
Baseline on branch entry: `./gradlew :composeApp:assembleDebug` BUILD SUCCESSFUL in 2m8s
(JAVA_HOME=/home/jake/android-build-tools/jdk-17, ANDROID_SDK_ROOT=/home/jake/android-build-tools/android-sdk).
`python3 tools/browser-core/spec_check.py` PASS (77 checks).

Recon findings that changed the plan:
- Item 1 (browser): the InteractiveBrowser (real WebView: JS, cookies, click/type/evaluate,
  confirmation-gated, egress/Tor policy in BrowserPolicy.availability) ALREADY EXISTS and is wired
  into BrowserControlPanel. The brief's premise ("browser is read-only, no JS/click/login") is
  stale vs the code. Remaining real work under item 1: verify it compiles (it does — baseline
  build includes it), and check for gaps vs the brief's ask (e.g. login flows = TYPE+CLICK with
  confirmation, which exists). No new WebView needed.
- Item 2 (native): uncommitted changes ALREADY wire System.loadLibrary + CMake path fixes.
  BUT native/src/lucifer_native.cpp is full of placeholder JNI stubs (wallet/browser/llm/sandbox
  return "{}" placeholders) and a KaiSandboxBridge socket path. Baseline build succeeded, so the
  JNI symbols match. Decision per brief ("wire properly or delete dead code"): the native lib has
  no real consumer — delete the placeholder JNI surface and keep only init/shutdown logging, OR
  delete the whole native module. Chosen: strip placeholders, keep a minimal honest native lib
  (init/shutdown only) so loadLibrary is not dead code. Wallet JNI is out of scope anyway
  (brief item 5: do not touch wallet).
- Item 3 (KaiBridge): real code exists (run-as com.inspiredandroid.kai exec). It is not a stub;
  the per-domain methods (wallet/browser/llm) shell into /tmp/omni_bridge python scripts that
  only exist inside the Kai sandbox. With wallet frozen, keep execute()/connect()/ping() (real),
  remove the dead wallet/browser/llm/rcod wrappers that reference nonexistent scripts, per
  "remove rather than ship a stub".
- Item 4 (stale trees): commonMain (12 files) and kotlin.backup (21 files) are NOT in the
  compiled source set (sourceSets only includes androidMain/kotlin). audit_locals.py references
  commonMain; audit_braces.py says --all includes them. All DIFF vs live tree (live is newer).
  No unique files of value (ONLY-BACKUP files are old prototype screens; SSHService, BrowserScreen
  etc. superseded). Plan: delete both trees, fix audit_locals.py path list.
- Item 6 (heartbeat): LuciferHeartbeatService + HeartbeatSessionEngine exist and match the
  spec's shape (30-min bounded sessions, read-only research, one local markdown draft,
  no execution). Spec v1.1 is 1097 lines of recovered-source + reconstructed spec. Gap vs
  brief: "producing executable workflow blueprints" — the engine produces markdown drafts
  (which can BE blueprints). Also item 14 (self-benchmark) touches CognitionScores.
- Item 7 (packet capture): AndroidPlatformDiagnostics line 52 says NOT IMPLEMENTED. Real
  implementation = VpnService. That is a big surface; plan a consent-gated, on-device-only
  VpnService that counts flows (no payload) — needs manifest + FOREGROUND_SERVICE type +
  runtime consent via VpnService.prepare(). Deferred after compile-green items; documented if
  blocked.
- Item 8 (notification listener): no NotificationListenerService exists. Implement
  NotificationListenerService, on-device only, toggle in Settings, BIND_NOTIFICATION_LISTENER_SERVICE.
- Item 9 (email): no email capability in the app at all. "Respect existing auth patterns" —
  the repo has credential patterns (credentials-core). Cheapest honest path: compose/read via
  Termux himalaya CLI through the existing Termux bridge? Check what auth patterns exist first.
- Item 10 (logcat): no READ_LOGS permission (needs root/Shizuku). Path: read own app's logs
  via `logcat --pid` through Shizuku shell (existing ShizukuTerminal), or own ring buffer.
  Ambient awareness = feed into ObservationBus as a new bounded sense.
- Item 11: CAPABILITIES.md:355 and APP_CAPABILITY_AUDIT.md:79 say commonMain/kotlin.backup are
  stale and README says "Compose layers have not been compiled by Gradle" — now FALSE (assembleDebug
  green since commit 88af5a9 + today's build). Update those docs. Python↔Kotlin mirror drift:
  tools/*-core/spec_check.py mirror Kotlin logic — find the drift-check harness.
- Item 12 (proposal loop): TacticianRunner.recordOutcome already feeds SkillSynthRuntime
  .learnProposalOutcome. Verify it's actually called on all APPLIED paths (it is, lines 162/180).
  Check learnProposalOutcome exists and does something real.
- Item 13 (lesson export): LessonExchange.kt already implements opt-in export/import with
  secret dropping and dedupe. "Signed" — check if there's a signature; brief wants signed.
  Add HMAC signature over the export bundle keyed by a device-held key? Check what exists.

Env: build via ./gradlew :composeApp:assembleDebug with JAVA_HOME=/home/jake/android-build-tools/jdk-17.
Load ~8-10 on 24 cores; builds take ~2min when warm.
