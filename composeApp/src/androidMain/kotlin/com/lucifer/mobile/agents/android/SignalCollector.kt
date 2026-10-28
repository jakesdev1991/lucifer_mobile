package com.lucifer.mobile.agents.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.lucifer.mobile.agents.AgentRegistry
import com.lucifer.mobile.resilience.LearningLedger
import com.lucifer.mobile.tactician.DetectionInput
import com.lucifer.mobile.tactician.HeartbeatSignals
import com.lucifer.mobile.tactician.Observation
import com.lucifer.mobile.tactician.ObservationKind
import com.lucifer.mobile.tactician.RecoveryDigest
import com.lucifer.mobile.tactician.RecoveryHistory
import com.lucifer.mobile.tactician.TacticianEngine
import com.lucifer.mobile.tactician.WorkflowSignals
import com.lucifer.mobile.workflows.WorkflowStepStatus
import com.lucifer.mobile.workflows.WorkflowTaskStatus
import com.lucifer.mobile.workflows.WorkflowTaskStore
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads what Lucifer already records and turns it into observations.
 *
 * Only append-only or Lucifer-owned stores are read. Nothing outside the app's private directory,
 * and no other app's data. Each source keeps a byte offset so a pass costs one small read instead of
 * re-ingesting history.
 */
class SignalCollector(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ingest new signal and return how many observations were added. */
    fun collect(): Int {
        var added = 0
        added += ingestAppEvents()
        added += ingestHeartbeatSessions()
        added += ingestDeviceState()
        ObservationBus.flush(appContext)
        return added
    }

    /** Everything the tactician needs for one pass. */
    fun detectionInput(
        store: PromptStore,
        now: Long = System.currentTimeMillis(),
        budget: com.lucifer.mobile.tactician.AutonomyBudget = defaultBudget()
    ): DetectionInput {
        val observations = ObservationBus.ledger(appContext).all()
        val registry = settleRegistry(store)
        val recovery = recoveryDigest()
        val runs = observations.filter { it.kind == ObservationKind.AGENT_RUN }
            .groupingBy { it.tags.firstOrNull() ?: "unknown" }
            .eachCount()
        return DetectionInput(
            observations = observations,
            agents = registry,
            recovery = recovery,
            agentRuns = runs,
            workflow = workflowSignals(),
            heartbeat = heartbeatSignals(store, observations, now),
            modelCallsToday = TacticianEngine.callCountToday(observations, now),
            budget = budget,
            proposalsPending = TacticianEngine.loadLedger(readLedger()).pending().size,
            lastRunAt = prefs.getLong(KEY_LAST_RUN_AT, 0L),
            now = now
        )
    }

    /** Seeded on first use so the roster is never empty in a fresh install. */
    fun settleRegistry(store: PromptStore): AgentRegistry {
        store.ensureDefaults()
        return store.registry()
    }

    fun recoveryDigest(): List<RecoveryDigest> = try {
        val file = File(appContext.filesDir, "resilience/lessons.json")
        if (!file.isFile || file.length() > 512_000L) emptyList()
        else RecoveryHistory.summarize(file.readText())
    } catch (error: Exception) {
        Log.w(TAG, "lesson read failed", error)
        emptyList()
    }

    fun workflowSignals(): WorkflowSignals = try {
        val tasks = WorkflowTaskStore(appContext).load()
        val now = System.currentTimeMillis()
        WorkflowSignals(
            total = tasks.size,
            active = tasks.count { it.status == WorkflowTaskStatus.ACTIVE },
            readyToComplete = tasks.count { it.status == WorkflowTaskStatus.READY_TO_COMPLETE },
            stalledOverDays = tasks.count { task ->
                task.status == WorkflowTaskStatus.ACTIVE &&
                    now - task.updatedAt > 48L * 60 * 60 * 1_000 &&
                    task.steps.any { it.status == WorkflowStepStatus.RUNNING || it.status == WorkflowStepStatus.REVIEW }
            }
        )
    } catch (error: Exception) {
        Log.w(TAG, "workflow signals unavailable", error)
        WorkflowSignals()
    }

    fun heartbeatSignals(
        store: PromptStore,
        observations: List<Observation>,
        now: Long = System.currentTimeMillis()
    ): HeartbeatSignals {
        val window = 14L * 24 * 60 * 60 * 1_000
        val heartbeat = observations.filter {
            it.kind == ObservationKind.HEARTBEAT && now - it.at in 0..window
        }
        val camera = observations.filter {
            it.kind == ObservationKind.CAMERA && now - it.at in 0..window
        }
        val lastAt = heartbeat.maxOfOrNull { it.at }
            ?: readLatestHeartbeatTimestamp()
        return HeartbeatSignals(
            enabled = com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService.isEnabled(appContext),
            cameraConsent = com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService.isCameraEnabled(appContext),
            sessionsInWindow = heartbeat.size,
            cameraSessionsInWindow = camera.size,
            lastSessionAt = lastAt,
            customDirective = store.heartbeatIsCustomized()
        )
    }

    // ── sources ──────────────────────────────────────────────────────

    private fun ingestAppEvents(): Int {
        val file = File(appContext.filesDir, "memory/app_events.log")
        var added = 0
        forEachNewLine(file, KEY_APP_OFFSET) { line ->
            val parts = line.trim().split(' ', limit = 4)
            if (parts.size < 4) return@forEachNewLine
            val verb = parts[2]
            val pkg = parts[3].take(80)
            if (pkg.isBlank()) return@forEachNewLine
            val kindLabel = when (verb) {
                "INSTALLED" -> "installed"
                "REMOVED" -> "removed"
                "UPDATED" -> "updated"
                else -> return@forEachNewLine
            }
            ObservationBus.record(
                appContext, ObservationKind.APP_EVENT, "app $kindLabel: $pkg",
                detail = "Package change recorded by Lucifer's launcher receiver.",
                source = "package-receiver", tags = listOf(pkg)
            )
            added++
        }
        return added
    }

    private fun ingestHeartbeatSessions(): Int {
        val file = File(appContext.filesDir, "heartbeat/sessions.jsonl")
        var added = 0
        forEachNewLine(file, KEY_HEARTBEAT_OFFSET) { line ->
            val record = try { JSONObject(line) } catch (_: Exception) { null } ?: return@forEachNewLine
            val status = record.optString("status")
            val title = record.optString("title").take(120)
            if (status == "ERROR") {
                ObservationBus.recordFailure(
                    appContext, ObservationKind.HEARTBEAT,
                    "heartbeat session failed",
                    record.optString("summary").take(300)
                )
            } else {
                ObservationBus.record(
                    appContext, ObservationKind.HEARTBEAT,
                    "heartbeat session: ${title.ifBlank { "untitled" }} (importance ${record.optInt("importance")})",
                    detail = record.optString("summary").take(300),
                    source = "heartbeat", tags = listOf(status.lowercase().ifBlank { "unknown" })
                )
                if (record.optBoolean("cameraIncluded", false)) {
                    ObservationBus.record(
                        appContext, ObservationKind.CAMERA,
                        "heartbeat used the camera for one still",
                        detail = "Image was sent for vision analysis and not persisted by Lucifer.",
                        source = "heartbeat", tags = listOf("consented")
                    )
                }
            }
            added++
        }
        return added
    }

    private fun ingestDeviceState(): Int {
        val now = System.currentTimeMillis()
        val bucket = now / (6 * 60 * 60 * 1_000L)
        if (prefs.getLong(KEY_DEVICE_BUCKET, -1L) == bucket) return 0
        val batteryIntent = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val summary = buildString {
            append("device snapshot")
            if (percent >= 0) append(" · battery $percent%")
            append(if (charging) " (charging)" else "")
            append(" · sdk ${android.os.Build.VERSION.SDK_INT}")
        }
        ObservationBus.record(
            appContext, ObservationKind.DEVICE, summary,
            detail = "Coarse state only; no location, identifiers, or app content.",
            source = "platform", tags = if (percent in 0..20 && !charging) listOf("low-battery") else emptyList()
        )
        prefs.edit().putLong(KEY_DEVICE_BUCKET, bucket).apply()
        return 1
    }

    // ── file tailing ─────────────────────────────────────────────────

    private inline fun forEachNewLine(file: File, offsetKey: String, action: (String) -> Unit) {
        if (!file.isFile || file.length() == 0L) return
        val stored = prefs.getLong(offsetKey, 0L)
        val start = if (stored in 0..file.length()) stored else 0L
        if (start == file.length()) return
        try {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(start)
                val bytes = ByteArray((file.length() - start).coerceAtMost(MAX_READ_BYTES).toInt())
                handle.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline < 0) return
                text.substring(0, lastNewline).lines()
                    .filter { it.isNotBlank() }
                    .takeLast(MAX_LINES)
                    .forEach(action)
                prefs.edit().putLong(offsetKey, start + lastNewline + 1).apply()
            }
        } catch (error: Exception) {
            Log.w(TAG, "tail failed for ${file.name}", error)
        }
    }

    private fun readLatestHeartbeatTimestamp(): Long = try {
        val file = File(appContext.filesDir, "heartbeat/latest.json")
        if (file.isFile && file.length() in 1..64_000L) JSONObject(file.readText()).optLong("timestamp") else 0L
    } catch (_: Exception) {
        0L
    }

    // ── ledger ───────────────────────────────────────────────────────

    fun readLedger(): String? = try {
        val file = File(appContext.filesDir, LEDGER_FILE)
        if (file.isFile && file.length() in 1..512_000L) file.readText() else null
    } catch (_: Exception) {
        null
    }

    fun writeLedger(raw: String) {
        try {
            val file = File(appContext.filesDir, LEDGER_FILE)
            file.parentFile?.mkdirs()
            file.writeText(raw)
        } catch (error: Exception) {
            Log.w(TAG, "ledger write failed", error)
        }
    }

    fun markRan(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_RUN_AT, at).apply()
    }

    fun lastRunAt(): Long = prefs.getLong(KEY_LAST_RUN_AT, 0L)

    fun defaultBudget(): com.lucifer.mobile.tactician.AutonomyBudget {
        val stored = prefs.getInt(KEY_MAX_CALLS, 40)
        val interval = prefs.getInt(KEY_INTERVAL_MINUTES, 20)
        return com.lucifer.mobile.tactician.AutonomyBudget(
            maxModelCallsPerDay = stored.coerceIn(0, 200),
            minMinutesBetweenRuns = interval.coerceIn(5, 240)
        )
    }

    fun saveBudget(maxCallsPerDay: Int, minMinutes: Int) {
        prefs.edit()
            .putInt(KEY_MAX_CALLS, maxCallsPerDay.coerceIn(0, 200))
            .putInt(KEY_INTERVAL_MINUTES, minMinutes.coerceIn(5, 240))
            .apply()
    }

    private companion object {
        const val TAG = "SignalCollector"
        const val PREFS = "lucifer_tactician_scan"
        const val KEY_APP_OFFSET = "app_events_offset"
        const val KEY_HEARTBEAT_OFFSET = "heartbeat_sessions_offset"
        const val KEY_DEVICE_BUCKET = "device_bucket"
        const val KEY_LAST_RUN_AT = "last_run_at"
        const val KEY_MAX_CALLS = "max_calls_per_day"
        const val KEY_INTERVAL_MINUTES = "min_minutes_between_runs"
        const val LEDGER_FILE = "tactician/proposals.json"
        const val MAX_READ_BYTES = 400_000L
        const val MAX_LINES = 80
    }
}
