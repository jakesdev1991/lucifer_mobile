package com.lucifer.mobile.agents.android

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.lucifer.mobile.tactician.Observation
import com.lucifer.mobile.tactician.ObservationKind
import com.lucifer.mobile.tactician.ObservationLedger
import java.io.File

/**
 * Process-wide sink for the General's observations.
 *
 * Every part of Lucifer that already does something observable calls [record]. The ledger is
 * bounded in memory and on disk, redacted at construction time, and never leaves the device unless
 * the user runs a tactician pass (which sends a bounded digest to the model).
 */
object ObservationBus {

    private const val TAG = "ObservationBus"
    private const val FILE = "tactician/observations.json"
    private const val MAX_BYTES = 1_500_000L
    private const val WRITE_INTERVAL_MS = 4_000L

    @Volatile private var cached: ObservationLedger? = null
    private var lastWriteAt = 0L
    private val lock = Any()

    fun ledger(context: Context): ObservationLedger = synchronized(lock) {
        cached ?: load(context.applicationContext).also { cached = it }
    }

    private fun load(context: Context): ObservationLedger {
        val file = File(context.filesDir, FILE)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) return ObservationLedger()
        return try {
            ObservationLedger.decode(file.readText()) ?: ObservationLedger()
        } catch (error: Exception) {
            Log.w(TAG, "observation ledger unreadable", error)
            ObservationLedger()
        }
    }

    /** Record one event. Never throws: sensing must not break the feature that produced it. */
    fun record(context: Context, observation: Observation): Observation? = try {
        val appContext = context.applicationContext
        val ledger = ledger(appContext)
        val stored = ledger.add(observation)
        persist(appContext, ledger)
        stored
    } catch (error: Exception) {
        Log.w(TAG, "record failed", error)
        null
    }

    fun record(
        context: Context,
        kind: ObservationKind,
        summary: String,
        detail: String = "",
        source: String = "app",
        severity: Int = 0,
        tags: List<String> = emptyList()
    ): Observation? = record(
        context,
        Observation.of(kind, summary, detail, System.currentTimeMillis(), source, severity, tags)
    )

    fun recordFailure(context: Context, kind: ObservationKind, summary: String, detail: String = "") {
        record(context, kind, summary, detail, source = "app", severity = 2)
    }

    fun clear(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        val empty = ObservationLedger()
        cached = empty
        persistNow(appContext, empty)
    }

    private fun persist(context: Context, ledger: ObservationLedger) {
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < WRITE_INTERVAL_MS) return
        lastWriteAt = now
        persistNow(context, ledger)
    }

    private fun persistNow(context: Context, ledger: ObservationLedger) {
        try {
            val target = File(context.filesDir, FILE)
            target.parentFile?.mkdirs()
            val json = ledger.encode()
            if (json.length > MAX_BYTES) return
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(json.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            Log.w(TAG, "persist failed", error)
        }
    }

    /** Flush the in-memory ledger to disk immediately (used before a tactician pass). */
    fun flush(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        cached?.let { persistNow(appContext, it) }
    }
}
