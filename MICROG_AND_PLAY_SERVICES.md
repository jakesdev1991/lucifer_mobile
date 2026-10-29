# microG, Play Services, and what "not tracked by Google" can actually mean

Status: **documentation plus an environment probe.** `privacy/PlayEnvironment.kt` reports which Play
stack the device is running; its classifier is host-verified, and the Compose block that shows it has
not been compiled by Gradle or run on a device.

This is the answer to: *"I want Play Services for some things, but I don't want this build tracked by
Google — that's why I wanted microG."*

## First, a correction to what I said earlier

I previously waved microG away as "a non-issue." That was true in a narrow sense and misleading in the
one that matters to you:

- **True:** this APK has zero Google dependencies (`composeApp/build.gradle.kts` — no `play-services`,
  no `firebase`, no `gms`). Lucifer does not care which Play stack is installed.
- **Misleading:** microG is a **device-wide** decision about every *other* app on the phone. That is a
  legitimate and well-established choice, and it is the right thing to evaluate if you want Play APIs
  without Google's client. My earlier framing answered the wrong question.

And the question you are actually asking has a hard constraint in it, which needs saying plainly:

> **microG removes Google's client. It does not remove Google from the network path.**

## What microG really buys you

| Area | Stock Play Services | microG | Notes |
| --- | --- | --- | --- |
| System privileges | Runs as a privileged system app | Ordinary app + signature spoofing | microG gets far less reach into the OS |
| Telemetry | Constant, opaque, Google-directed | None of Google's client telemetry | This is the real win |
| App compatibility | Full | Partial — stubs, not implementations | Expect specific apps to break |
| Push (FCM) | Google | microG reimplements the *client* | **Messages still arrive from Google's servers** |
| Location | Google fused provider | GPS + third-party backend (beaconDB or a local GSM database) | Mozilla's MLS shut down in June 2024; the old UnifiedNlp "Mozilla" backends are dead |
| Play Integrity | Google verdict | Not expected to pass | Banking, DRM, some games will refuse |
| Play Store | Google | microG Companion shims `com.android.vending`, or use Aurora/F-Droid | Some apps check the installer |

### The push caveat, concretely

This is the part that most "deGoogle" guides gloss over. To receive a push notification, microG still
performs a **check-in with `android.clients.google.com`** to obtain an Android ID and auth token, then
holds a persistent connection to **`mtalk.google.com`**; app registration goes through
`firebaseinstallations.googleapis.com`. Google therefore still sees:

- that *this* Android ID is online,
- your IP address and the timing of that connection,
- which app packages are receiving messages, because FCM messages are addressed per package.

You avoid Google's Play Services *client* and its device telemetry. You do not avoid Google knowing
your device talks to it. If a Google account is added to microG for sign-in, the link becomes explicit.

### Where microG is genuinely strong

- No privileged system access, no telemetry pipeline, open source you can read.
- Fine-grained control: you can enable push but not location, or run with no account at all.
- Perfect when you need *specific* apps (a banking app you rarely use, a transit app) and can tolerate
  the ones that break.

## The alternative worth knowing about — because your phone is a Pixel 8a

`PIXEL8A_ANDROID_SYSTEMS.md` and the launcher target `akita`. That matters: **GrapheneOS is a
first-class option on exactly this hardware**, and its model is usually a better fit for "I want Play
Services for some things":

| | GrapheneOS + sandboxed Play | LineageOS/CalyxOS + microG |
| --- | --- | --- |
| What Google gets | The *real* Play Services, but as an ordinary app in a sandbox you choose (often a separate user profile) | No Google client at all |
| Network access | Per-app, revocable; can be confined to one profile | Whatever microG's components reach |
| App compatibility | High — real implementation, so push/maps/sign-in mostly work | Medium — stubs and gaps |
| Play Integrity | Fails `STRONG`/`DEVICE`; many banking apps still work | Fails; fewer banking apps work |
| Security patching | Same-day/next-day upstream Pixel patches | Days to weeks behind |
| Privilege | Play Services gets **no** system-level access | Signature spoofing for GMS identity |
| Effort | Low (web installer) | Moderate |

Both are defensible. The honest summary: **microG minimises what Google's client can do on your
device; GrapheneOS minimises what Google's services can do while still letting them work.** If the goal
is "Play Services for some things, Google tracking near zero," sandboxed Play in a separate profile
usually wins on both compatibility and containment — and you can turn its network access off entirely
when you are not using those apps.

What neither fixes: LTE, the SIM, and the fact that your carrier knows where the phone is. That is the
layer table in [PRIVACY_AND_IDENTITY.md](PRIVACY_AND_IDENTITY.md).

## What this repository does about it

Three things, all enforced rather than promised:

1. **Lucifer stays degoogled by policy, and the build fails if that slips.**
   `tools/privacy-core/audit_egress.py --check` now fails on any `com.google.android.gms`,
   `play-services` or `firebase` dependency, and on any `import com.google.android.gms`.
   Current state: **0 references**. If you ever add a Play API to Lucifer, that is a deliberate commit
   that breaks the check, not a silent drift.

2. **Lucifer reports the environment instead of assuming one.** `privacy/PlayEnvironment.kt` classifies
   the device as `GOOGLE PLAY SERVICES`, `MICROG`, `REVANCED MICROG`, `NO PLAY SERVICES`, or `UNKNOWN`,
   and shows it in Settings → Privacy with the integrity/push/location consequence of each.

   Detection deliberately does **not** trust the package name, because official microG installs as
   `com.google.android.gms` — the same identifier Google uses — so apps keep binding to it. The
   classifier scores independent signals (application label, version shape, installer, whether the Play
   Store is microG Companion) and needs two before it says MICROG. One weak signal reports `UNKNOWN`
   and shows the evidence rather than guessing. The signing certificate SHA-256 is displayed so you can
   compare it yourself.

3. **Nothing in this app requires any of it.** Every Lucifer feature — chat, terminal, memory, wallet,
   workflows, the ticker — works identically with no Play Services at all. If you switch ROMs, the only
   thing that changes is what the probe prints.

## Practical recommendation for this user

1. Keep the GMS question **out of the app**; keep it a ROM decision. This build is now checked to be
   incapable of becoming a Google client by accident.
2. On a Pixel 8a, **try GrapheneOS with sandboxed Play in a separate profile** before committing to
   microG. You get push and maps working *and* tighter containment than microG's model, and you can
   revoke its network access when idle.
3. If you go microG anyway: expect **no Play Integrity**, plan alternatives for banking/DRM, use
   beaconDB or a local GSM database for network location, and understand that **push still reaches
   Google** — budget for that instead of being surprised by it.
4. Read the `PLAY ENVIRONMENT` block in Settings after any ROM change. It is four lines and it tells you
   what you are actually running.

## Verification

```bash
python3 tools/privacy-core/spec_check.py            # 83 checks, including the 11 Play-stack cases
python3 tools/privacy-core/audit_egress.py --check  # fails on an unguarded socket OR a Google dependency
./scripts/verify_privacy_core.sh                    # both, plus the Kotlin harness when a JVM exists
```

Not verified: `PlayEnvironment.kt` has never run on a device or been compiled by AGP, so the classifier
is proven against constructed facts, not against a real microG install. The `UNKNOWN` state exists
precisely because the real world will produce combinations these tests do not enumerate.

Sources for the factual claims above: microG's own issue tracker on MLS retirement
(github.com/microg/GmsCore#2237), the GmsCore push/checkin architecture documentation, and the
GrapheneOS attestation-compatibility notes. Re-check them if you are making a decision months from now
— this area moves.
