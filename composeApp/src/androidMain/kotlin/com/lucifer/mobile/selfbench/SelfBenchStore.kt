package com.lucifer.mobile.selfbench

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The Android half of the self-benchmark: where the eval set and history live, and the one entry
 * point the heartbeat calls.
 *
 * Offline and budget-free by construction: [run] never touches NemotronClient, never opens a
 * socket, and never records an ObservationKind.MODEL observation, so the General's daily model
 * budget (which counts MODEL observations) is untouched.
 */
object SelfBench {

    private const val TAG = "SelfBench"
    private const val DIR = "selfbench"
    private const val HISTORY = "history.jsonl"
    private const val ASSET = "selfbench.json"
    private const val MAX_HISTORY_LINES = 60
    /** One run per 7 days at most. */
    const val WEEK_MS = 7L * 24L * 60L * 60L * 1_000L

    private fun historyFile(context: Context): File = File(File(context.filesDir, DIR), HISTORY)

    /** The shipped eval set, decoded; empty when the asset is missing or malformed. */
    fun loadCases(context: Context): List<BenchCase> = try {
        SelfBenchCases.decode(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    } catch (error: Exception) {
        Log.w(TAG, "selfbench asset unreadable", error)
        emptyList()
    }

    fun history(context: Context): List<BenchRun> = try {
        val file = historyFile(context.applicationContext)
        if (!file.isFile) emptyList() else file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { SelfBenchCodec.decodeLine(it) }
    } catch (error: Exception) {
        Log.w(TAG, "selfbench history unreadable", error)
        emptyList()
    }

    /** True when no run exists or the newest run is older than a week. */
    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = history(context).maxOfOrNull { it.at } ?: return true
        return now - last >= WEEK_MS
    }

    /**
     * Run the eval set through [answer] (the no-tool cognition answer stage) and append the result
     * to history. [answer] is injected so the runner stays testable without a model.
     */
    fun run(context: Context, answer: (String) -> String, now: Long = System.currentTimeMillis()): BenchRun? {
        val app = context.applicationContext
        val cases = loadCases(app)
        if (cases.isEmpty()) {
            Log.w(TAG, "no eval cases; self-bench skipped")
            return null
        }
        val results = cases.map { case ->
            val reply = try { answer(case.prompt) } catch (error: Exception) {
                Log.w(TAG, "answer stage failed for ${case.id}", error)
                ""
            }
            SelfBenchScorer.score(case, reply)
        }
        val run = BenchRun(now, results.size, results.count { it.passed }, results)
        appendHistory(app, run)
        Log.i(TAG, "self-bench: ${run.passed}/${run.total} passed")
        return run
    }

    private fun appendHistory(context: Context, run: BenchRun) {
        try {
            val file = historyFile(context)
            file.parentFile?.mkdirs()
            file.appendText(SelfBenchCodec.encode(run) + "\n")
            // Bound the history file.
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size > MAX_HISTORY_LINES) {
                file.writeText(lines.takeLast(MAX_HISTORY_LINES).joinToString("\n") + "\n")
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not append self-bench history", error)
        }
    }

    /** One-line summary for the Settings row. */
    fun summaryLine(context: Context): String {
        val runs = history(context)
        if (runs.isEmpty()) return "no runs yet"
        val last = runs.last()
        return "last run ${last.passed}/${last.total} (${(last.score * 100).toInt()}%) · ${runs.size} run(s) logged"
    }

    /** Last-4-weeks pass-rate sparkline, oldest first: ▁▃▅█ … */
    fun sparkline(context: Context, weeks: Int = 4, now: Long = System.currentTimeMillis()): String {
        val runs = history(context)
        if (runs.isEmpty()) return ""
        val bars = "▁▂▃▄▅▆▇█"
        val windowMs = weeks * WEEK_MS
        val recent = runs.filter { now - it.at <= windowMs }
        if (recent.isEmpty()) return ""
        return recent.takeLast(weeks).joinToString(" ") { run ->
            val idx = (run.score * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)
            bars[idx].toString()
        }
    }
}
