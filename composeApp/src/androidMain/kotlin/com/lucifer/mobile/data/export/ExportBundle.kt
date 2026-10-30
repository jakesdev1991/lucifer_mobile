package com.lucifer.mobile.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.privacy.NetworkGuard
import com.lucifer.mobile.skills.SkillSynthRuntime
import com.lucifer.mobile.synth.SkillStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Getting your data *out* — the one thing `WRITE_EXTERNAL_STORAGE` was declared for and never did.
 *
 * Everything Lucifer stores lives in app-private storage, which is the right default and a trap: if the
 * app is uninstalled, or the phone is lost, it is gone. This writes a single reviewable JSON document
 * to a location *you* pick through the system file picker (SAF), so the app never holds a storage
 * permission and never writes anywhere you did not point at.
 *
 * What it will not do, by construction:
 *
 *  - **No secrets.** The NVIDIA key, the VM SSH password and the memory-MCP token are excluded by name.
 *    The export records only whether each is *set*, which is what a support question actually needs.
 *  - **No transcripts.** Memory is summarised by count and size, not by dumping rows.
 *  - **No upload.** It writes a file and stops. There is no code path from here to a socket.
 */
object ExportBundle {

    private const val TAG = "ExportBundle"

    /** The document, bounded: a few hundred kilobytes at most. */
    fun build(context: Context): String {
        val root = JSONObject()
        root.put("generatedAt", System.currentTimeMillis())
        root.put("app", "com.lucifer.mobile")

        // ── what is configured, never the value of a secret ──
        val settings = JSONObject()
        settings.put("vmHost", AppConfig.readText(context, AppConfig.VM_HOST)?.let { "set" } ?: "unset")
        settings.put("vmUser", AppConfig.readText(context, AppConfig.VM_USER) ?: "unset")
        settings.put("knownHostsPinned", AppConfig.readText(context, AppConfig.KNOWN_HOSTS) != null)
        settings.put("nvidiaKey", AppConfig.readText(context, AppConfig.NVIDIA_KEY)?.let { "set" } ?: "unset")
        settings.put("vmSshPassword", AppConfig.readText(context, AppConfig.VM_SSH_PASSWORD)?.let { "set" } ?: "unset")
        settings.put("memoryMcpToken", AppConfig.readText(context, AppConfig.MEMORY_MCP_TOKEN)?.let { "set" } ?: "unset")
        settings.put("egressTransport", AppConfig.readText(context, AppConfig.EGRESS_TRANSPORT) ?: "direct")
        settings.put("skillSynthEnabled", AppConfig.readFlag(context, AppConfig.SKILL_SYNTH_ENABLED, default = true))
        root.put("settings", settings)

        // ── what he has learned, in full, because it is his and yours ──
        val skills = JSONArray()
        for (skill in SkillSynthRuntime.skills(context)) {
            skills.put(
                JSONObject().apply {
                    put("id", skill.id)
                    put("kind", skill.kind.name)
                    put("status", skill.status.name)
                    put("trigger", skill.trigger)
                    put("instruction", skill.instruction)
                    put("wins", skill.wins)
                    put("losses", skill.losses)
                    put("exposures", skill.hits)
                    put("confidence", (skill.confidence * 1000).toInt() / 1000.0)
                    put("createdAt", skill.createdAt)
                    put("updatedAt", skill.updatedAt)
                    if (skill.status == SkillStatus.BLOCKED || skill.status == SkillStatus.RETIRED) {
                        put("note", skill.note)
                    }
                }
            )
        }
        root.put("learnedSkills", skills)
        root.put("learnedSkillCount", skills.length())

        // ── the egress ledger: hosts and paths, which are already query-string free ──
        root.put("egressSummary", NetworkGuard.summary(context))
        root.put("egressRecent", NetworkGuard.recent(context, max = 60))

        // ── counts, not contents ──
        root.put("memoryEntries", countLines(File(context.filesDir, "memory")))
        root.put("heartbeatSessions", countLines(File(context.filesDir, "heartbeat")))
        root.put("resilienceLessonBytes", File(context.filesDir, "resilience/lessons.json").let { if (it.isFile) it.length() else 0L })

        root.put(
            "note",
            "Secrets are never included: only whether each one is set. This file was written by you, " +
                "to a place you chose, and Lucifer did not send it anywhere."
        )
        return root.toString(2)
    }

    /** Write the bundle to the URI the system file picker returned. Never throws at the caller. */
    fun write(context: Context, uri: Uri): Result<String> = try {
        val body = build(context)
        val stream = context.contentResolver.openOutputStream(uri)
            ?: return Result.failure(IllegalStateException("The picker returned a location Lucifer cannot write to."))
        stream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        Log.i(TAG, "exported ${body.length} characters to the chosen location")
        Result.success("Exported ${body.length} characters. Nothing was uploaded.")
    } catch (error: Exception) {
        Log.w(TAG, "export failed: ${error.javaClass.simpleName}")
        Result.failure(error)
    }

    /** Count records in a directory of JSONL/log files without loading them. */
    private fun countLines(dir: File): Int {
        if (!dir.isDirectory) return 0
        return try {
            dir.listFiles().orEmpty().filter { it.isFile }.sumOf { file ->
                file.useLines { lines -> lines.count { it.isNotBlank() } }
            }
        } catch (_: Exception) {
            0
        }
    }
}
