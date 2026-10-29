package com.lucifer.mobile.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * WHICH PLAY STACK IS THIS DEVICE RUNNING?
 *
 * Lucifer has no Google dependencies, so it behaves identically on stock Android, on GrapheneOS with
 * sandboxed Play, and on LineageOS/CalyxOS with microG. But the *device* does not behave identically,
 * and the user deserves to see which world they are in from inside the app that claims to be their
 * shell: push delivery, location, and Play Integrity all differ, and none of them fail loudly.
 *
 * Detection cannot use the package name. Official microG installs as `com.google.android.gms` — the
 * same identifier Google uses — because apps bind to that name. The distinguishing evidence is the
 * label, the version shape, the installer, and the signing certificate. [classify] uses those as
 * scored heuristics and [PlayFacts] carries the raw evidence so the panel can show it instead of
 * asserting a verdict the app cannot actually prove.
 *
 * The classification itself is pure, so it is exercised on a host (tools/privacy-core).
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
    val revenancedGmsInstalled: Boolean = false
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
            append(locationVerdict(flavor))
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

/** Reads [PlayFacts] from the platform. Every call is guarded: a launcher must not crash over this. */
class PlayEnvironmentProbe(private val context: Context) {

    companion object {
        private const val TAG = "PlayEnvironment"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val MICROG_PREVIEW_PACKAGE = "org.microg.gms"
        const val REVANCED_GMS_PACKAGE = "app.revanced.android.gms"
    }

    fun read(): PlayFacts {
        val pm = context.packageManager
        val gms = describe(pm, GMS_PACKAGE)
        val vending = describe(pm, VENDING_PACKAGE)
        return PlayFacts(
            gmsInstalled = gms != null,
            gmsLabel = gms?.label.orEmpty(),
            gmsVersionName = gms?.versionName.orEmpty(),
            gmsInstaller = gms?.installer.orEmpty(),
            gmsSignatureSha256 = gms?.signatureSha256.orEmpty(),
            vendingInstalled = vending != null,
            vendingLabel = vending?.label.orEmpty(),
            microgPreviewInstalled = isInstalled(pm, MICROG_PREVIEW_PACKAGE),
            revenancedGmsInstalled = isInstalled(pm, REVANCED_GMS_PACKAGE)
        )
    }

    private data class PackageFacts(
        val label: String,
        val versionName: String,
        val installer: String,
        val signatureSha256: String
    )

    private fun isInstalled(pm: PackageManager, name: String): Boolean = try {
        pm.getPackageInfo(name, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun describe(pm: PackageManager, name: String): PackageFacts? = try {
        val info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        val label = try {
            val applicationInfo = info.applicationInfo
            if (applicationInfo == null) "" else pm.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            ""
        }
        // A byte can be negative; mask it so the fingerprint is always two hex characters per byte.
        val signer = runCatching {
            val signingInfo = info.signingInfo
            if (signingInfo == null) {
                ""
            } else {
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                val first = signers?.firstOrNull()
                if (first == null) {
                    ""
                } else {
                    MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                }
            }
        }.getOrElse { error ->
            Log.i(TAG, "signature unreadable for $name: ${error.javaClass.simpleName}")
            ""
        }
        val installer = try {
            pm.getInstallSourceInfo(name).installingPackageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        PackageFacts(
            label = label,
            versionName = info.versionName.orEmpty(),
            installer = installer,
            signatureSha256 = signer
        )
    } catch (_: Exception) {
        null
    }
}
