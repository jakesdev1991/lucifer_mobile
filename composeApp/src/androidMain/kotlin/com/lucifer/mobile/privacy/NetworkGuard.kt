package com.lucifer.mobile.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File

/**
 * The one place every outbound request asks permission.
 *
 * [authorize] is called immediately before a socket opens. It decides, records the attempt, and only
 * then does the caller connect. The switch it reads is a single file in app-private storage — no
 * service, no broadcast receiver, nothing that can be killed by the OS and quietly fail open.
 *
 * Failure mode is deliberate and documented: if the process never called [install] (only
 * `LuciferApplication.onCreate` does), egress is unguarded, because a guard that blocks everything on
 * a wiring mistake would brick an app whose whole interface is remote model calls. The Privacy panel
 * reports `GUARD INSTALLED` so the state is visible instead of assumed.
 */
object NetworkGuard {

    private const val TAG = "NetworkGuard"
    private const val CONFIG_DIR = "config"
    private const val CONFIG_FILE = "network_enabled.txt"
    private const val LEDGER_DIR = "privacy"
    private const val LEDGER_FILE = "egress.tsv"

    /** Cached switch. Read once per process, written on change; the file is the source of truth. */
    @Volatile
    private var cachedOnline: Boolean? = null

    @Volatile
    private var appContext: Context? = null

    private val log = EgressLog(EgressLog.DEFAULT_CAPACITY)
    private val lock = Any()

    /** Called from `LuciferApplication.onCreate` — the only writer of [appContext]. */
    fun install(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val installed: Boolean get() = appContext != null

    // ── the switch ───────────────────────────────────────────────────

    /** Default is ON: the app worked this way before the switch existed. Off is the explicit choice. */
    fun isOnline(context: Context): Boolean {
        cachedOnline?.let { return it }
        val fromDisk = try {
            val file = File(context.filesDir, "$CONFIG_DIR/$CONFIG_FILE")
            if (!file.exists()) true else file.readText().trim().lowercase() != "false"
        } catch (error: Exception) {
            Log.w(TAG, "network switch unreadable, treating as ON", error)
            true
        }
        cachedOnline = fromDisk
        return fromDisk
    }

    fun setOnline(context: Context, online: Boolean) {
        cachedOnline = online
        try {
            val dir = File(context.filesDir, CONFIG_DIR)
            dir.mkdirs()
            File(dir, CONFIG_FILE).writeText(if (online) "true" else "false")
        } catch (error: Exception) {
            Log.w(TAG, "network switch could not be persisted", error)
        }
        // Turning the network off is a statement, not just a setting: say it in the ledger.
        synchronized(lock) {
            val entry = EgressRecord(
                epochMillis = System.currentTimeMillis(),
                host = "switch",
                route = if (online) "/online" else "/offline",
                kind = EgressClass.LOCAL,
                userInitiated = true,
                outcome = EgressOutcome.ALLOWED,
                hadQueryString = false
            )
            log.record(entry)
            append(context, entry)
        }
    }

    /** Whether the OS currently has a usable network. Advisory only; the switch is the real gate. */
    fun deviceHasNetwork(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (error: Exception) {
        Log.i(TAG, "connectivity lookup failed: ${error.javaClass.simpleName}")
        false
    }

    // ── the gate ─────────────────────────────────────────────────────

    /**
     * Ask before sending. Always returns a decision; always records it when the guard is installed.
     *
     * @param url the exact destination about to be opened
     * @param userInitiated true when a person asked for this, false for heartbeat/recovery/etc.
     * @param expected the caller's own classification. Pass [EgressClass.VISION] for image calls so
     *   the ledger says a picture left the device, not a prompt.
     */
    fun authorize(
        url: String,
        userInitiated: Boolean,
        expected: EgressClass? = null
    ): EgressDecision {
        val context = appContext
        val online = if (context == null) true else isOnline(context)
        val decision = EgressPolicy.decide(url, online, userInitiated, expected)
        if (context == null) return decision
        synchronized(lock) {
            val entry = EgressPolicy.record(
                url = url,
                decision = decision,
                epochMillis = System.currentTimeMillis(),
                userInitiated = userInitiated
            )
            log.record(entry)
            append(context, entry)
        }
        if (decision.blocked) Log.i(TAG, "blocked ${decision.kind.label} to ${decision.host}: ${decision.reason}")
        return decision
    }

    /** The line a caller returns instead of pretending it worked. */
    fun refusalMessage(decision: EgressDecision, what: String): String = when (decision.outcome) {
        EgressOutcome.BLOCKED_OFFLINE ->
            "[BLOCKED] Network is OFF (Settings → Privacy). Nothing was sent for $what."
        EgressOutcome.BLOCKED_POLICY ->
            "[BLOCKED] Refused $what: ${decision.reason.ifBlank { "destination not identifiable" }}"
        EgressOutcome.ALLOWED -> ""
    }

    // ── reading the ledger ───────────────────────────────────────────

    fun summary(context: Context): String = synchronized(lock) {
        load(context)
        log.summary()
    }

    fun recent(context: Context, max: Int = 30): String = synchronized(lock) {
        load(context)
        log.render(max)
    }

    fun countsByClass(context: Context): Map<EgressClass, Int> = synchronized(lock) {
        load(context)
        log.countsByClass()
    }

    fun clear(context: Context) = synchronized(lock) {
        log.clear()
        persist(context)
    }

    // ── storage ──────────────────────────────────────────────────────

    private var loaded = false

    /** Lines believed to be on disk, so an append can decide when a compaction is due. */
    private var diskRecords = 0

    /** Rewrite threshold: twice the ring, so compaction is amortised rather than per request. */
    private const val COMPACT_AT = EgressLog.DEFAULT_CAPACITY * 2

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(context.filesDir, "$LEDGER_DIR/$LEDGER_FILE")
            if (!file.isFile) return
            val lines = file.readLines().filter { it.isNotBlank() }
            log.loadLines(lines.takeLast(EgressLog.DEFAULT_CAPACITY))
            diskRecords = lines.size
        } catch (error: Exception) {
            Log.w(TAG, "ledger unreadable", error)
        }
    }

    /**
     * One record, one appended line.
     *
     * The old code rewrote all 400 lines of the ledger on every outbound request, which made an
     * ordinary chat turn do far more disk work than the request it was recording. Appending is
     * O(1); when the file grows past twice the ring it is rewritten once, compacted.
     */
    private fun append(context: Context, record: EgressRecord) {
        try {
            val dir = File(context.filesDir, LEDGER_DIR)
            dir.mkdirs()
            val file = File(dir, LEDGER_FILE)
            if (diskRecords >= COMPACT_AT || !file.exists()) {
                persist(context)
                return
            }
            file.appendText(EgressCodec.encode(record) + "\n")
            diskRecords++
        } catch (error: Exception) {
            Log.w(TAG, "ledger append failed, rewriting instead", error)
            persist(context)
        }
    }

    private fun persist(context: Context) {
        try {
            val dir = File(context.filesDir, LEDGER_DIR)
            dir.mkdirs()
            val file = File(dir, LEDGER_FILE)
            val lines = log.toLines()
            val body = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
            // Atomic replace: a crash mid-write must not leave a truncated ledger behind.
            val temp = File(dir, "$LEDGER_FILE.tmp")
            temp.writeText(body)
            if (!temp.renameTo(file)) {
                file.writeText(body)
                temp.delete()
            }
            diskRecords = lines.size
        } catch (error: Exception) {
            Log.w(TAG, "ledger could not be written", error)
        }
    }

    /** Test seam: forget the cache so a host test can re-read the file it just wrote. */
    fun resetForTest() {
        cachedOnline = null
        loaded = false
        diskRecords = 0
        log.clear()
    }
}
