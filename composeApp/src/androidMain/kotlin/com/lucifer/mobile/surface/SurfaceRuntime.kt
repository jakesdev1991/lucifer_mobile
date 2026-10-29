package com.lucifer.mobile.surface

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.lucifer.mobile.privacy.NetworkGuard
import java.io.File

/**
 * Reads [SurfaceFacts] from the device so surface answers are current instead of aspirational.
 *
 * Every read is guarded and degrades to a pessimistic value: "not installed", "not configured",
 * "not pinned". A capability answer that overstates is worse than one that undersells, because the user
 * may act on it.
 */
object SurfaceRuntime {

    private const val TAG = "SurfaceRuntime"

    /** The surface this process most recently declared. Used by panels that cannot pass it down. */
    @Volatile
    var current: Surface = Surface.UNKNOWN
        private set

    fun enter(surface: Surface) {
        current = surface
    }

    fun facts(context: Context, selectedTarget: String = "LOCAL"): SurfaceFacts {
        val app = context.applicationContext
        return SurfaceFacts(
            selectedTarget = selectedTarget,
            termuxInstalled = isInstalled(app, "com.termux"),
            termuxRunCommandGranted = permissionGranted(app, "com.termux.permission.RUN_COMMAND"),
            overlayPermission = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false),
            vmConfigured = nonEmpty(app, "config/vm_host.txt"),
            vmHostKeyPinned = nonEmpty(app, "config/known_hosts"),
            shizukuAvailable = isInstalled(app, "moe.shizuku.privileged.api"),
            shizukuIdentity = "",
            networkOnline = NetworkGuard.isOnline(app),
            modelKeyConfigured = nonEmpty(app, "config/nvidia_key.txt"),
            memoryMcpConfigured = nonEmpty(app, "config/drive_memory_mcp_url.txt"),
            pendingConfirmation = false
        )
    }

    /**
     * The whole interception: a prompt-free answer when the question is about the interface, otherwise
     * null and the normal reasoning path continues with the surface block attached.
     */
    fun answerIfAboutSurface(
        context: Context,
        text: String,
        surface: Surface,
        selectedTarget: String = "LOCAL"
    ): String? {
        if (SurfaceQuestions.classify(text) == SurfaceQuestion.NONE) return null
        return try {
            SurfaceAnswers.answer(text, surface, facts(context, selectedTarget))
        } catch (error: Exception) {
            Log.w(TAG, "surface answer failed, falling through to the model", error)
            null
        }
    }

    private fun isInstalled(context: Context, name: String): Boolean = try {
        context.packageManager.getPackageInfo(name, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun permissionGranted(context: Context, permission: String): Boolean = try {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun nonEmpty(context: Context, relative: String): Boolean = try {
        val file = File(context.filesDir, relative)
        file.isFile && file.length() > 0L
    } catch (_: Exception) {
        false
    }
}
