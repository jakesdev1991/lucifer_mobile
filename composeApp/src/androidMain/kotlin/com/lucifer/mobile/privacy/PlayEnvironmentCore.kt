package com.lucifer.mobile.privacy

/**
 * WHICH PLAY STACK IS THIS DEVICE RUNNING? — the pure half.
 *
 * Lucifer has no Google dependencies, so it behaves identically on stock Android, on GrapheneOS with
 * sandboxed Play, and on LineageOS/CalyxOS with microG. But the *device* does not behave identically,
 * and the user deserves to see which world they are in from inside the app that claims to be their
 * shell: push delivery, location, and Play Integrity all differ, and none of them fail loudly.
 *
 * Detection cannot use the package name. Official microG installs as `com.google.android.gms` — the
 * same identifier Google uses — because apps bind to that name. The distinguishing evidence is the
 * label, the version shape, the installer, and the signing certificate. [PlayClassifier.classify] uses
 * those as scored heuristics and [PlayFacts] carries the raw evidence so the panel can show it instead
 * of asserting a verdict the app cannot actually prove.
 *
 * **This file has no Android imports on purpose.** It is the part a host can execute, and until it was
 * split out, `tools/privacy-core/EgressCoreTests.kt` referenced these types through a file that imports
 * `Context` — so that harness could not compile and had never run.
 */

enum class PlayFlavor {
    /** Google-signed Play Services, however it got installed. */
    GOOGLE,
    /** Official microG (`com.google.android.gms`, microG-signed) or its preview build. */
    MICROG,
    /** ReVanced GmsCore (`app.revanced.android.gms`): microG minus most of the API surface. */
    REVANCED_MICROG,
    /** No Play Services at all. */
    ABSENT,
    /** Something is installed, but it cannot be told apart. Shown as UNKNOWN, never guessed. */
    UNKNOWN;

    val label: String
        get() = when (this) {
            GOOGLE -> "GOOGLE PLAY SERVICES"
            MICROG -> "MICROG"
            REVANCED_MICROG -> "REVANCED MICROG"
            ABSENT -> "NO PLAY SERVICES"
            UNKNOWN -> "UNKNOWN"
        }
}

/** Everything the probe could learn, with no interpretation applied. */
data class PlayFacts(
    val gmsInstalled: Boolean = false,
    val gmsLabel: String = "",
    val gmsVersionName: String = "",
    val gmsInstaller: String = "",
    val gmsSignatureSha256: String = "",
    /** `com.android.vending` — the Play Store, which official microG ships as "microG Companion". */
    val vendingInstalled: Boolean = false,
    val vendingLabel: String = "",
    /** `org.microg.gms` — the microG project's own preview/pre-release package. */
    val microgPreviewInstalled: Boolean = false,
    /** `app.revanced.android.gms` — ReVanced's GmsCore fork. */
    val revenancedGmsInstalled: Boolean = false,
    /**
     * True when GMS was installed to /system (or /system/priv-app) rather than as a user app. This is
     * the difference the user actually cares about: a system Play Services holds reach a normal app
     * cannot, and cannot be revoked the way an ordinary app's permissions can.
     */
    val gmsIsSystemApp: Boolean = false
) {
    /** How many independent signals say "this is microG pretending to be GMS". */
    fun microgSignals(): List<String> {
        val signals = ArrayList<String>(4)
        if (gmsLabel.lowercase().contains("microg")) signals.add("GMS label says microG")
        if (gmsVersionName.matches(Regex("^0\\.(2|3)\\.[0-9.]+"))) signals.add("GMS version has microG's shape")
        if (vendingInstalled && vendingLabel.lowercase().contains("microg")) signals.add("Play Store is microG Companion")
        if (gmsInstalled && gmsInstaller.isNotBlank() && !gmsInstaller.startsWith("com.android.vending")) {
            signals.add("GMS was not installed by the Play Store")
        }
        return signals
    }
}

object PlayClassifier {

    /** Two independent signals, or the unmistakable package names, before calling it microG. */
    const val MICROG_SIGNAL_THRESHOLD = 2

    fun classify(facts: PlayFacts): PlayFlavor {
        if (facts.revenancedGmsInstalled) return PlayFlavor.REVANCED_MICROG
        if (!facts.gmsInstalled) {
            return if (facts.microgPreviewInstalled) PlayFlavor.MICROG else PlayFlavor.ABSENT
        }
        if (facts.microgPreviewInstalled) return PlayFlavor.MICROG
        return when {
            facts.microgSignals().size >= MICROG_SIGNAL_THRESHOLD -> PlayFlavor.MICROG
            facts.microgSignals().isEmpty() -> PlayFlavor.GOOGLE
            else -> PlayFlavor.UNKNOWN
        }
    }

    fun integrityVerdict(flavor: PlayFlavor): String = when (flavor) {
        PlayFlavor.GOOGLE -> "INTEGRITY: available — but an unlocked or uncertified device can still fail"
        PlayFlavor.MICROG, PlayFlavor.REVANCED_MICROG ->
            "INTEGRITY: not expected to pass — apps demanding a DEVICE/STRONG verdict will refuse"
        PlayFlavor.ABSENT -> "INTEGRITY: unavailable — no attestation path at all"
        PlayFlavor.UNKNOWN -> "INTEGRITY: cannot determine without knowing the signer"
    }

    /**
     * The caveat that matters for "Play Services without Google tracking": push is not local.
     * microG reimplements the client, not the service — the messages still arrive from Google's
     * servers over a connection Google can attribute to an IP.
     */
    fun pushVerdict(flavor: PlayFlavor): String = when (flavor) {
        PlayFlavor.GOOGLE -> "PUSH: Google delivers it — a device token, your IP and your timing"
        PlayFlavor.MICROG, PlayFlavor.REVANCED_MICROG ->
            "PUSH: still Google — microG checks in with android.clients.google.com and holds a socket to mtalk.google.com"
        PlayFlavor.ABSENT -> "PUSH: only apps with their own socket (Signal, UnifiedPush) will notify you"
        PlayFlavor.UNKNOWN -> "PUSH: unknown until the Play stack is identified"
    }

    /**
     * "Power over the phone" is not a feeling, it is a flag: whether Play Services is a system app.
     * A sandboxed install is revocable — network, sensors, background, storage, all per-app switches.
     * A system install is not, and flashing microG into /system/priv-app reproduces exactly the
     * arrangement this user is trying to avoid, open source or not.
     */
    fun privilegeVerdict(facts: PlayFacts): String {
        val flavor = classify(facts)
        return when {
            flavor == PlayFlavor.ABSENT -> "PRIVILEGE: no Play Services installed"
            facts.gmsIsSystemApp && flavor == PlayFlavor.GOOGLE ->
                "PRIVILEGE: SYSTEM APP — platform-level reach that cannot be revoked like a normal app"
            // microG/ReVanced/unknown builds fall through to here so the source of the privilege is
            // named: a FLOSS Play stack flashed into /system is exactly as privileged as Google's.
            facts.gmsIsSystemApp ->
                "PRIVILEGE: SYSTEM APP — even a microG build flashed into /system keeps that reach"
            else ->
                "PRIVILEGE: ordinary app — same sandbox as everything else; permissions and network are yours to revoke"
        }
    }

    fun locationVerdict(flavor: PlayFlavor): String = when (flavor) {
        PlayFlavor.GOOGLE -> "LOCATION: fused provider, Google-assisted"
        PlayFlavor.MICROG, PlayFlavor.REVANCED_MICROG ->
            "LOCATION: GPS plus a third-party backend (beaconDB or a local GSM database); Mozilla's MLs is gone"
        PlayFlavor.ABSENT -> "LOCATION: GPS only — slower first fix, poor indoors"
        PlayFlavor.UNKNOWN -> "LOCATION: unknown"
    }

    /** Four lines the Settings panel can print without a second lookup. */
    fun report(facts: PlayFacts): String {
        val flavor = classify(facts)
        return buildString {
            appendLine("STACK: ${flavor.label}" + if (facts.gmsVersionName.isNotBlank()) " · ${facts.gmsVersionName}" else "")
            appendLine(integrityVerdict(flavor))
            appendLine(pushVerdict(flavor))
            appendLine(locationVerdict(flavor))
            append(privilegeVerdict(facts))
            if (facts.gmsSignatureSha256.isNotBlank()) {
                appendLine()
                append("SIGNER: ${facts.gmsSignatureSha256.take(16)}…")
            }
            if (flavor == PlayFlavor.UNKNOWN) {
                appendLine()
                append("Signals: " + facts.microgSignals().joinToString("; ").ifBlank { "none affirmative" })
            }
        }
    }
}
