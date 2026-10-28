package com.lucifer.mobile.launcher

import android.content.BroadcastReceiver
import com.lucifer.mobile.agents.android.ObservationBus
import com.lucifer.mobile.tactician.ObservationKind
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PackageEventReceiver — Lucifer's senses.
 * Registered for PACKAGE_ADDED / PACKAGE_REMOVED / PACKAGE_REPLACED so the agent
 * perceives every install/uninstall/update on the device and journals it into
 * his persistent memory. Also the hook for accessibility-driven perception later.
 */
class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        val verb = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> "INSTALLED"
            Intent.ACTION_PACKAGE_REMOVED -> "REMOVED"
            Intent.ACTION_PACKAGE_REPLACED -> "UPDATED"
            else -> return
        }
        LuciferState.log("[$verb] $pkg")
        try {
            ObservationBus.record(
                context, ObservationKind.APP_EVENT,
                "app ${verb.lowercase()}: $pkg",
                detail = "Package change recorded by Lucifer's launcher receiver.",
                source = "package-receiver", tags = listOf(pkg)
            )
        } catch (_: Exception) {
            // Sensing must never break the receiver.
        }
        try {
            val dir = File(context.filesDir, "memory")
            dir.mkdirs()
            File(dir, "app_events.log").appendText(
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} $verb $pkg\n"
            )
        } catch (e: Exception) {
            Log.w("LuciferLauncher", "journal failed", e)
        }
    }
}
