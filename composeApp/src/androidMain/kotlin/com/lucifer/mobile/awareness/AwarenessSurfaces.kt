package com.lucifer.mobile.awareness

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Honest awareness surfaces, all limited to Lucifer's OWN UID and OWN data.
 *
 * The standing rule (CAPABILITIES.md): a surface may report what the app can actually
 * see without root, and must say plainly what it cannot see. Nothing here reads another
 * app's logs, notifications, traffic, or messages.
 */
object AwarenessSurfaces {

    const val OWN_PACKAGE = "com.lucifer.mobile"

    // ── 1. SYSTEM LOG ────────────────────────────────────────────────────────────
    // Without root, READ_LOGS is not grantable on API 26+: logcat shows only the
    // caller's own UID. With Shizuku granted, the shell runs as ADB shell (2000) or
    // root, which CAN read the whole buffer — so we filter to our own UID/package
    // and say so. Either way the honest statement is: other apps' logs are not shown.
    const val SYSTEM_LOG_HONESTY_PREFIX = "Shows Lucifer's own log lines only (filtered to our UID). "
    val systemLogHonesty: String
        get() = SYSTEM_LOG_HONESTY_PREFIX +
            "READ_LOGS is not grantable to apps on API 26+, so other apps' logs are NOT readable and are not shown. Full multi-app logcat needs root."

    /** Shizuku-side own-UID logcat dump. Returns null when Shizuku is not ready. */
    suspend fun ownLogcatViaShizuku(context: Context, maxLines: Int = 200): List<String>? {
        val uid = Process.myUid()
        // --uid= filters the dump to our own UID at the source; we do not read other UIDs.
        val command = "logcat -d --uid=$uid -v brief | tail -n $maxLines"
        val out = com.lucifer.mobile.shizuku.ShizukuTerminal.execute(context, command)
        val error = out.firstOrNull { it.startsWith("[ERROR]") || it.startsWith("[SETUP]") || it.startsWith("[BLOCKED]") }
        return if (error != null) null else out
    }

    // ── 2. NOTIFICATION LISTENER ─────────────────────────────────────────────────
    // BIND_NOTIFICATION_LISTENER_SERVICE is a special app access the USER enables in
    // system settings; the app can only open that screen and report honestly whether
    // it is enabled. Stored records are package + title + timestamp only.
    fun notificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { component ->
            component.substringBefore('/').equals(context.packageName, ignoreCase = true)
        }
    }

    fun openNotificationListenerSettings(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) { false }

    // ── 3. OWN-UID TRAFFIC ───────────────────────────────────────────────────────
    // TrafficStats.getUidRxBytes/getUidTxBytes for our own UID need no permission and
    // no VPN. They are byte counters since boot, not a packet capture. Full pcap needs
    // a root VPN service and is NOT attempted.
    data class OwnTraffic(val rxBytes: Long, val txBytes: Long) {
        fun render(): String = buildString {
            appendLine("Lucifer own-UID traffic (since boot, all networks):")
            appendLine("  received: ${human(rxBytes)}")
            appendLine("  sent:     ${human(txBytes)}")
            append("Counters come from TrafficStats for UID ${Process.myUid()} only. " +
                "Full packet capture (pcap) needs root and is NOT attempted.")
        }
    }

    fun ownTraffic(): OwnTraffic = try {
        OwnTraffic(
            android.net.TrafficStats.getUidRxBytes(Process.myUid()),
            android.net.TrafficStats.getUidTxBytes(Process.myUid())
        )
    } catch (_: Exception) {
        OwnTraffic(-1L, -1L)
    }

    private fun human(bytes: Long): String = when {
        bytes < 0 -> "unavailable"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ── 4. EMAIL ─────────────────────────────────────────────────────────────────
    // mailto: hands the compose screen to the system default mail app. Lucifer stores
    // no mail credentials and talks to no SMTP/IMAP server.
    data class MailtoResult(val launched: Boolean, val note: String)

    fun composeEmail(context: Context, to: String = "", subject: String = "", body: String = ""): MailtoResult {
        val uri = buildString {
            append("mailto:")
            append(Uri.encode(to))
            val params = mutableListOf<String>()
            if (subject.isNotBlank()) params += "subject=${Uri.encode(subject)}"
            if (body.isNotBlank()) params += "body=${Uri.encode(body)}"
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }.toUri()
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            MailtoResult(true, "Opened the system default mail app via mailto:. No credentials are stored by Lucifer.")
        } catch (_: Exception) {
            MailtoResult(false, "NOT CONFIGURED — no mail app answered the mailto: intent on this device. Lucifer stores no mail credentials.")
        }
    }
}
