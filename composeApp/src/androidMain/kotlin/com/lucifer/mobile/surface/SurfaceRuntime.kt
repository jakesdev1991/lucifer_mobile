package com.lucifer.mobile.surface

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.privacy.NetworkGuard

/**
 * Reads [SurfaceFacts] from the device so surface answers are current instead of aspirational.
 *
 * Every read is guarded and degrades to a pessimistic value: "not installed", "not configured",
 * "not pinned". A capability answer that overstates is worse than one that undersells, because the user
 * may act on it.
 */
object SurfaceRuntime {

    private const val TAG = "SurfaceRuntime"

    // Deliberately no "current surface" global. The surface is a parameter on every call, so a screen
    // cannot inherit a stale one from whatever ran last; an ambient variable would only make that
    // possible. (There was one here, unused by everything, and it was deleted rather than wired.)

    /**
     * Facts are read on every model turn (for the prompt block) and on every surface question. Each
     * read is several PackageManager lookups plus file stats, and they cannot meaningfully change
     * twice inside a second, so the result is cached briefly. A capability toggle in Settings is
     * still reflected on the next turn.
     */
    private const val FACTS_TTL_MS = 3_000L
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var cached: SurfaceFacts? = null

    fun invalidate() {
        cachedAt = 0L
        cached = null
    }

    fun facts(context: Context, selectedTarget: String = "LOCAL"): SurfaceFacts {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < FACTS_TTL_MS && it.selectedTarget == selectedTarget) return it }
        return read(context, selectedTarget).also {
            cached = it
            cachedAt = now
        }
    }

    private fun read(context: Context, selectedTarget: String): SurfaceFacts {
        val app = context.applicationContext
        return SurfaceFacts(
            selectedTarget = selectedTarget,
            termuxInstalled = isInstalled(app, "com.termux"),
            termuxRunCommandGranted = permissionGranted(app, "com.termux.permission.RUN_COMMAND"),
            overlayPermission = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false),
            vmConfigured = AppConfig.isSet(app, AppConfig.VM_HOST),
            vmHostKeyPinned = AppConfig.isSet(app, AppConfig.KNOWN_HOSTS),
            shizukuAvailable = isInstalled(app, "moe.shizuku.privileged.api"),
            shizukuIdentity = "",
            networkOnline = NetworkGuard.isOnline(app),
            modelKeyConfigured = AppConfig.isSet(app, AppConfig.NVIDIA_KEY),
            memoryMcpConfigured = AppConfig.isSet(app, AppConfig.MEMORY_MCP_URL),
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
            SurfaceAnswers.answer(
                text,
                surface,
                facts(context, selectedTarget),
                // Counts only, read off the main thread's critical path? It is one small file, and this
                // branch only runs when the question is about the interface itself.
                toolRecord = com.lucifer.mobile.cognition.CognitionScores.report(context)
            )
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

}
