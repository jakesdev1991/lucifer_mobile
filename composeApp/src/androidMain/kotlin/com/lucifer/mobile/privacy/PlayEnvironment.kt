package com.lucifer.mobile.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * Reads the Play stack from the platform. The classification itself lives in PlayEnvironmentCore.kt,
 * which is pure Kotlin and therefore testable on a host: this half only gathers evidence.
 */

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
            revenancedGmsInstalled = isInstalled(pm, REVANCED_GMS_PACKAGE),
            gmsIsSystemApp = gms?.isSystemApp ?: false
        )
    }

    private data class PackageFacts(
        val label: String,
        val versionName: String,
        val installer: String,
        val signatureSha256: String,
        val isSystemApp: Boolean
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
        val applicationInfo = info.applicationInfo
        val isSystemApp = applicationInfo != null &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        PackageFacts(
            label = label,
            versionName = info.versionName.orEmpty(),
            installer = installer,
            signatureSha256 = signer,
            isSystemApp = isSystemApp
        )
    } catch (_: Exception) {
        null
    }
}
