# Browser, voice, and the rest of the list

**Date:** 2026-10-30
**State:** every item on the four-bullet list is either wired or withdrawn. Nothing in this document has
been compiled or run on a device — there is no JDK, kotlinc, Gradle or Android SDK in this workspace, so
**no Kotlin here has been compiled, no APK exists, and no WebView has rendered a page.** What did run is
the host-side verification, and it is named per section.

---

## 1. The four bullets

| What did not work | What it does now |
|---|---|
| Browser could only read text, never sign in or click | **Two browsers.** Read-only fetcher stays the default and stays in the egress ledger; an interactive WebView is available when the transport makes it honest, with every mutating action confirmed first |
| Bluetooth, NFC, biometric, storage declared but unwired | **Bluetooth and NFC withdrawn**, deprecated `USE_FINGERPRINT` withdrawn, storage permissions withdrawn; **biometric wired** as an owner gate, **export wired** through the system file picker |
| No voice input | **Press-to-talk** `SpeechRecognizer` in the launcher, destroyed after each answer |
| Ten dead `omni_bridge` modules | **Deleted.** Their names claimed model-internals access this app cannot have |

Verification: `./scripts/verify_browser_core.sh` → **PASS** (77 spec checks + 17 static assertions; the
Kotlin half prints SKIPPED here). `./scripts/verify_voice_core.sh` → **PASS** (12 static properties).
`python3 tools/manifest-core/audit_manifest.py` → **16 permissions declared · 16 justified · 0 findings**.
`./scripts/verify_all.sh --quiet` → **PASS 5 · SKIP 10 · FAIL 0**.

---

## 2. Browser: two doors, and why the second one is narrower

### 2.1 Read-only, still the default

`data/browser/LuciferBrowser.kt` is unchanged in behaviour: fetch a public page, extract text, no
JavaScript, no cookies you can carry, no forms. It goes through `privacy/EgressHttp`, so it appears in the
ledger and **rides the Tor tunnel** when the transport is TOR. Opening the Browser tab puts you here.

### 2.2 Interactive, and the fact that decides everything

`browser/InteractiveBrowser.kt` is a real `WebView`: JavaScript, DOM storage, first-party cookies,
sign-in. The cost is not a bug to be fixed; it is structural:

> **A `WebView` opens its own sockets. It never asks `EgressHttp`. It never appears in the ledger.**

So the interaction is offered only where that cannot silently defeat the privacy plane
(`BrowserPolicy.availability`):

| Transport | System VPN | Interactive browser |
|---|---|---|
| DIRECT | — | allowed (its traffic is direct, and the panel says so) |
| TOR | none | **refused**, with the reason printed on screen |
| TOR | Orbot VPN mode / WireGuard | allowed — the traffic is captured at the IP layer, so Tor still covers it |

The refusal names the fix rather than a rule: run a system VPN, or switch the transport to DIRECT and
accept that these page requests are direct.

### 2.3 Every action classified

`BrowserPolicy.decide(action, target)` — pure Kotlin, no Android imports, mirrored in
`tools/browser-core/spec_check.py`:

| Action | Allowed | Confirmation | Why |
|---|---|---|---|
| `NAVIGATE` (http/https only) | yes | no | a GET with no side effect on this device |
| `READ` | yes | no | extracts text |
| `SCREENSHOT` | yes | no | local pixels only |
| `CLICK` | yes | **yes** | "a click is not a read: it can post, message, buy or delete" |
| `TYPE` | yes | **yes** | text in a page is data handed to that page |
| `EVALUATE` | yes | **yes** | a script can do anything the page can do |

Refused outright: `javascript:`, `data:`, `file:`, `blob:` URLs; blank targets; URLs over 2 048
characters; selectors over 300; typed text over 2 000; scripts over 4 000. The confirmation card
(`CONFIRM ACTION` → `DO IT` / `CANCEL`) prints `BrowserPolicy.describe(...)`, so it names the selector,
the character count, or the script length — not "an action". The policy is re-checked when `DO IT` is
pressed, because the field could have changed between opening the sheet and confirming it.

### 2.4 What the WebView is not allowed to be

Set explicitly, and asserted by `verify_browser_core.sh` so a future edit cannot quietly drop one:
`allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`,
`allowUniversalAccessFromFileURLs = false`, geolocation off, multiple windows off,
`javaScriptCanOpenWindowsAutomatically = false`, `MIXED_CONTENT_NEVER_ALLOW`, third-party cookies off,
a non-default user agent. `shouldOverrideUrlLoading` re-checks every navigation, including redirects a
page triggers itself. The WebView is built from the **application** context (never the Activity),
destroyed when the panel leaves the screen, and `CLEAR` removes cookies, cache and form data.

A `javascript:` URL is never handed to `loadUrl`; only the escaping-clean scripts `BrowserPolicy`
generates. `jsString` escapes `\`, `'`, `\n`, `\r`, `<`, `>` (the last two as `\u003c`/`\u003e`), which
the harness proves with a breakout payload that must stay inside one literal.

Screenshots are JPEG q80 and dropped above 900 KB — bytes that large would otherwise be shipped to the
model, and a page can be made arbitrarily heavy.

---

## 3. Voice: input, with the truth attached

`launcher/LuciferVoice.kt` gained the other half of the loop. It was TextToSpeech only; it now also
listens.

- **Press-to-talk, not ambient.** A `MIC` control in the launcher bar. Nothing records until it is
  pressed. The recogniser is created **per press** and destroyed on result or error — no session lingers,
  no hotword, no wake lock.
- **Permission on first use.** `RECORD_AUDIO` is requested at runtime from the launcher, only when MIC is
  pressed. A refusal says so plainly and points at Settings.
- **Errors in sentences.** `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT`, `ERROR_NETWORK`, busy recogniser and
  missing service each map to a plain sentence rather than a code.
- **`shutdown()` stops listening first**, then releases TTS.

**The fact the UI states, and this document repeats:** the platform `SpeechRecognizer` delegates to the
device's speech service. On a stock phone that is usually Google's, and it **streams the audio off the
device**. Lucifer's transport policy, Tor tunnel and egress ledger do **not** cover it, because Lucifer
does not own that socket. Press-to-talk is therefore *input capability*, not *private input*. If that
matters, type instead — or install a local/offline speech engine and set it as the default recogniser,
which the app cannot do for you.

---

## 4. Biometric: one job, honestly bounded

`security/BiometricGate.kt` uses `android.hardware.biometrics.BiometricPrompt` directly (API 28+, no new
dependency) and is called from exactly two places in `MainActivity`:

- `runOwnerOp` — hide / unhide / suspend / grant / deny a package through Dhizuku or Shizuku;
- `runWorldAction` — create, wake, sleep a world, start the VM (`WorldCharter.requiresConfirmation`).

Three behaviours, none silent:

1. **Sensor present** → prompt with a device-credential fallback on API 29+. A finger that does not match
   keeps the prompt open (`onAuthenticationFailed` is not a denial — treating it as one is why biometric
   gates feel broken).
2. **Cancelled or rejected** → the action does not happen and the reason is shown. No fallback path.
3. **No sensor and no lock** → the gate reports it cannot ask, says so once, and the existing
   confirmation dialog stands alone. Locking an owner out of their own device-owner powers on a phone
   without a fingerprint reader would be worse than the gate's absence.

The Settings tab shows the gate's capability line verbatim (`biometricStatus`).

---

## 5. Storage: export, and the permission nobody needed

`data/export/ExportBundle.kt` writes one JSON document through `ActivityResultContracts.CreateDocument`
(the system file picker, SAF). Because the picker grants exactly the URI the user chose, **no storage
permission is held at all** — which is why `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` are gone
rather than "used".

Contents: settings **presence** (never values — the NVIDIA key, VM SSH password and memory-MCP token are
recorded as `set`/`unset` only), every learned skill with its win/loss/confidence, the egress summary and
latest 60 entries, and record counts for memory / heartbeat / resilience. No transcripts, no secrets, no
upload: there is no code path from this object to a socket.

---

## 6. Deleted: the ten `omni_bridge` modules

| Module | Claim | Why it is gone |
|---|---|---|
| `taro` | steers token-level logits | Lucifer calls a hosted API over HTTPS; it never receives a logit |
| `qstar` | routes on model confidence internals; cites a July 2026 paper | no internals access; the citation is not verifiable |
| `rcod` | detects phase transitions in a state vector | nothing produces the state vector |
| `meta_cognitive` | taxonomy routing from a frozen prefix | a prompt-shaping toy with no caller |
| `gepa` | evolves prompt architecture from failure traces | the always-on skill synthesizer already does reflection, with bounded, reversible state |
| `compression` | embed → rerank → summarise context | no embedding model; memory is FTS over SQLite, which is real |
| `evidence_ledger` | BLAKE3 Merkle tree of routing decisions | superseded by the egress ledger and privilege ledger, which log events that happen |
| `ring_buffer` | mirrored-memory SPSC buffer for SIMD parsing | no such consumer; ordinary queues suffice |
| `atoms` | seven-state psychological profile | unmeasurable, and would be presented as fact |
| `appa` | algebraic taint policy | `TransportPolicy` + `PrivilegeRouter` already refuse by construction |

Each was 100–250 lines of plausible-looking simulation. None had a caller. Deleting them removes a
category of falsehood from the repository; a stub would only have made the lie smaller.

---

## 7. Verification, and the honest gaps

```
python3 tools/browser-core/spec_check.py           PASS · 77 checks
./scripts/verify_browser_core.sh                   PASS  (Kotlin step SKIPPED — no kotlinc)
./scripts/verify_voice_core.sh                     PASS  (static only: 12 properties)
python3 tools/manifest-core/audit_manifest.py       16 declared · 16 justified · 0 findings
./scripts/verify_all.sh --quiet                     PASS 5 · SKIP 10 · FAIL 0
```

Unproven here, and labelled as such:

- **Nothing compiled.** `BrowserCoreTests.kt`, `InteractiveBrowser.kt`, `BiometricGate.kt`,
  `ExportBundle.kt`, the rewritten panel and every `LauncherActivity` edit are review-only. The Kotlin
  half of `verify_browser_core.sh` has never run.
- **No WebView has loaded a page.** No sign-in, no click, no screenshot on a real device.
- **No microphone has been opened.** The permission flow and the recogniser mapping are untested.
- **No biometric prompt has appeared.** `BiometricPrompt.Builder` behaviour differs across OEMs.
- **No SAF write has happened.** The picker path is standard, but untested here.
- **Still open from earlier work:** `exchange` sends `userInitiated = true` unconditionally (the
  ambient-ledger gap), and Tor is unproven until `TEST CIRCUIT` reports `IN CIRCUIT`.
