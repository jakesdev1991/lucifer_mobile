package com.lucifer.mobile.cognition

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * A record of every deep run, on the device, for the owner to read.
 *
 * The loop makes decisions a person did not watch: which tool it chose, what it read, why it stopped, how
 * confident it ended up. Without a record those decisions are unauditable — the answer arrives, the
 * reasoning evaporates. This app already keeps an egress ledger for the same reason; this is the same
 * idea for thinking.
 *
 * What it stores, per run, is deliberately small: the goal (truncated), the tools used *by name*, the
 * step count, the elapsed time, the termination, the confidence, and the audit codes. **It does not
 * store tool output.** A page Lucifer read is not persisted here; the ledger answers "what did he do",
 * not "what did he see", because the second would turn a debug log into a copy of the web.
 *
 * Rotates at [MAX_BYTES]: the newest runs are what a person needs, and an unbounded log on a phone
 * eventually costs someone their storage.
 */
object CognitionLedger {

    private const val TAG = "CognitionLedger"
    private const val DIR = "cognition"
    private const val FILE = "runs.jsonl"

    /** 256 KB of JSON lines is roughly a thousand runs — enough history, small enough to mail. */
    const val MAX_BYTES = 256 * 1024L

    private fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR).apply { mkdirs() }, FILE)

    /** Append one line. Never throws: a ledger failure must not break a turn that already succeeded. */
    fun record(context: Context, outcome: CognitionOutcome, goal: String) {
        try {
            val line = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("goal", goal.take(160))
                put("steps", outcome.steps)
                put("toolCalls", outcome.toolCalls)
                put("tools", outcome.trace.mapNotNull { it.call?.tool }.distinct())
                put("elapsedMs", outcome.elapsedMs)
                put("termination", outcome.stop.termination.name)
                put("stopDetail", outcome.stop.detail.take(140))
                put("concluded", outcome.concluded)
                put("confidence", outcome.audit.confidence.name)
                put("findings", outcome.audit.findings.map { it.code })
                put("memory", outcome.memorySummary)
            }.toString()

            val target = file(context)
            if (target.isFile && target.length() > MAX_BYTES) rotate(target)
            target.appendText(line + "\n", Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "could not record the run: ${error.javaClass.simpleName}")
        }
    }

    /**
     * A run the owner stopped. It never produces a [CognitionOutcome] — a cancelled coroutine cannot
     * return one — so without this line the history would quietly omit every turn a person interrupted,
     * and "12 runs, 11 concluded" would read as one failure rather than one decision.
     */
    fun recordInterrupted(context: Context, goal: String, steps: Int, elapsedMs: Long) {
        try {
            val line = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("goal", goal.take(160))
                put("steps", steps)
                put("toolCalls", steps)
                put("tools", org.json.JSONArray())
                put("elapsedMs", elapsedMs)
                put("termination", Termination.CANCELLED.name)
                put("stopDetail", "stopped by the owner after ${elapsedMs / 1000}s")
                put("concluded", false)
                put("confidence", AnswerAudit.Confidence.NONE.name)
                put("findings", org.json.JSONArray())
                put("memory", "")
            }.toString()
            val target = file(context)
            if (target.isFile && target.length() > MAX_BYTES) rotate(target)
            target.appendText(line + "\n", Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "could not record the interruption: ${error.javaClass.simpleName}")
        }
    }

    /** Keep the newest half when the file grows past the cap. Cheaper than rewriting on every append. */
    private fun rotate(target: File) {
        try {
            val lines = target.readLines(Charsets.UTF_8)
            val keep = lines.takeLast((lines.size / 2).coerceAtLeast(50))
            target.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "rotation failed, truncating instead: ${error.javaClass.simpleName}")
            target.delete()
        }
    }

    /** The most recent [max] runs, newest last, for a status panel. */
    fun recent(context: Context, max: Int = 20): List<String> = try {
        val target = file(context)
        if (!target.isFile) emptyList() else target.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .takeLast(max)
    } catch (_: Exception) {
        emptyList()
    }

    /** One line for a card: how many runs, how many concluded, how many needed a caveat. */
    fun summary(context: Context): String {
        val runs = recent(context, max = 500).mapNotNull {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }
        if (runs.isEmpty()) return "no deep runs recorded yet"
        val concluded = runs.count { it.optBoolean("concluded") }
        // Parenthesised on purpose: `a ?: 0 > 0` parses as `a ?: (0 > 0)`, which does not compile.
        val withFindings = runs.count { (it.optJSONArray("findings")?.length() ?: 0) > 0 }
        val avgSteps = runs.map { it.optInt("steps") }.average().let { (it * 10).toInt() / 10.0 }
        val interrupted = runs.count { it.optString("termination") == Termination.CANCELLED.name }
        return "${runs.size} run(s) · $concluded concluded · $withFindings with caveats · " +
            (if (interrupted > 0) "$interrupted stopped by you · " else "") +
            "${avgSteps} steps on average"
    }

    fun count(context: Context): Int = recent(context, max = 10_000).size

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }
}
