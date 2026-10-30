package com.lucifer.mobile.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import java.util.concurrent.Executor

/**
 * "Are you the owner?" — asked before the things a stolen unlocked phone should not do.
 *
 * `USE_BIOMETRIC` was declared in the manifest and wired to nothing, which this codebase treats as a
 * lie in the permission list. So it has exactly one job now: a second factor in front of the two
 * actions that can change the device or another app on it — the World panel's owner calls (hide,
 * suspend, grant, deny a package) and the world's own state changes.
 *
 * Three honest behaviours, none of them silent:
 *
 *  - **No sensor, no lock** → the prompt cannot be shown at all. The gate reports that and lets the
 *    existing confirmation dialog be the only gate, rather than locking the owner out of their own
 *    device-owner powers.
 *  - **Cancelled or rejected** → the action does not happen, and the caller is told.
 *  - **`onAuthenticationFailed`** (a finger that did not match) is *not* a cancellation: the prompt is
 *    still up and the user may try again. Treating a failed read as a denial is the classic way to make
 *    biometric gates feel broken.
 *
 * Uses `android.hardware.biometrics.BiometricPrompt` (API 28+) rather than the AndroidX wrapper so no
 * new dependency is added for one dialog; the trade-off is that the caller must pass a real `Activity`.
 */
object BiometricGate {

    private const val TAG = "BiometricGate"

    /** What this device can actually do, in words worth showing. */
    data class Capability(val available: Boolean, val detail: String)

    fun capability(context: Context): Capability {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secure = try {
            keyguard?.isDeviceSecure == true
        } catch (_: Exception) {
            false
        }
        val features = context.packageManager
        val sensor = try {
            features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE)) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_IRIS))
        } catch (_: Exception) {
            false
        }
        return when {
            !sensor && !secure -> Capability(
                false,
                "no biometric sensor and no device lock · the confirmation dialog is the only gate"
            )
            !sensor -> Capability(
                false,
                "device lock present but no biometric sensor · the confirmation dialog is the only gate"
            )
            else -> Capability(
                true,
                if (secure) "biometric or device credential required" else "biometric required"
            )
        }
    }

    /**
     * Show the prompt. Returns true when one was actually shown; false means the caller should proceed
     * on the strength of its own confirmation, because this device cannot ask.
     *
     * Must be called from an Activity on the main thread: `BiometricPrompt` takes the activity's task.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    fun require(
        activity: Activity,
        title: String,
        subtitle: String,
        onDenied: (String) -> Unit,
        onConfirmed: () -> Unit
    ): Boolean {
        val capability = capability(activity)
        if (!capability.available) {
            Log.i(TAG, "biometric gate unavailable: ${capability.detail}")
            return false
        }
        val executor: Executor = activity.mainExecutor
        val prompt = try {
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .apply {
                    // A PIN/pattern fallback needs `setAllowedAuthenticators`, which is API 30. Without
                    // it, a wet finger means no action.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                    } else {
                        setNegativeButton("Cancel", executor) { _, _ -> }
                    }
                }
                .build()
        } catch (error: Exception) {
            Log.w(TAG, "could not build the biometric prompt: ${error.javaClass.simpleName}")
            return false
        }

        try {
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        Log.i(TAG, "owner confirmed by the platform")
                        onConfirmed()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        // 13 = ERROR_CANCELED, 10 = ERROR_USER_CANCELED, 7 = ERROR_LOCKOUT …
                        val reason = errString?.toString()?.takeIf { it.isNotBlank() }
                            ?: "authentication was not completed ($errorCode)"
                        Log.i(TAG, "owner not confirmed: $reason")
                        onDenied(reason)
                    }

                    // A finger that did not match is not a refusal: the prompt stays up.
                    override fun onAuthenticationFailed() {
                        Log.i(TAG, "authentication attempt did not match; prompt still open")
                    }
                }
            )
        } catch (error: Exception) {
            Log.w(TAG, "biometric prompt failed: ${error.javaClass.simpleName}")
            onDenied("the biometric prompt could not be shown: ${error.message ?: error.javaClass.simpleName}")
            return true
        }
        return true
    }
}
