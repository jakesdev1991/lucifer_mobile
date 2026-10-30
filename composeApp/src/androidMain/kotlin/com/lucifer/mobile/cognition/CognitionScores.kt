package com.lucifer.mobile.cognition

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The tools' record on this device, kept between runs.
 *
 * Two small facts per tool — how often it was called, how often it came back with something — are
 * enough for the loop to stop wasting its budget on a fetch that is not working today. The count lives
 * in one file, is rewritten after each deep run (a handful of lines), and is deliberately boring: it
 * cannot grow, cannot hold tool output, and cannot be edited by the model. Only the trace of a run that
 * actually happened can move it.
 *
 * The advice string is cached in memory because the planner reads it on every step and a file read per
 * step would be a silly cost for a line that changes once a turn.
 */
object CognitionScores {

    private const val TAG = "CognitionScores"
    private const val DIR = "cognition"
    private const val FILE = "tools.json"

    /** A single call outcome is noise; the file is capped so a long-lived install stays small. */
    private const val MAX_TOOLS = 24

    @Volatile
    private var cachedAdvice: String? = null

    private fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR).apply { mkdirs() }, FILE)

    private fun load(context: Context): List<ToolTally> = try {
        val target = file(context)
        if (!target.isFile) emptyList() else {
            val root = JSONObject(target.readText(Charsets.UTF_8))
            root.keys().asSequence().mapNotNull { id ->
                val pair = root.optJSONArray(id) ?: return@mapNotNull null
                val ok = pair.optInt(0, 0)
                val calls = pair.optInt(1, 0)
                if (calls <= 0) null else ToolTally(id, calls, ok.coerceIn(0, calls))
            }.toList()
        }
    } catch (error: Exception) {
        // A corrupt counter must not cost a turn: start counting again and say so in the log.
        Log.w(TAG, "tool record unreadable, restarting the count: ${error.javaClass.simpleName}")
        emptyList()
    }

    private fun save(context: Context, tallies: List<ToolTally>) {
        try {
            val root = JSONObject()
            tallies.sortedByDescending { it.calls }.take(MAX_TOOLS).forEach { tally ->
                root.put(tally.id, JSONArray().put(tally.ok).put(tally.calls))
            }
            file(context).writeText(root.toString(), Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "could not persist the tool record: ${error.javaClass.simpleName}")
        }
    }

    /** Fold one finished run into the record. Called with a trace, so only real calls count. */
    fun record(context: Context, trace: List<StepRecord>) {
        val outcomes = trace.mapNotNull { record ->
            val call = record.call ?: return@mapNotNull null
            val result = record.result ?: return@mapNotNull null
            ToolCallOutcome(call.tool, result.ok && result.output.isNotBlank())
        }
        if (outcomes.isEmpty()) return
        val tallies = ToolRecord.merge(load(context), outcomes)
        save(context, tallies)
        cachedAdvice = ToolRecord.advice(tallies)
    }

    /** The line the planner adds to its instructions, or empty. Never throws, never reads the disk twice. */
    fun adviceLine(context: Context): String {
        cachedAdvice?.let { return it }
        val advice = try {
            ToolRecord.advice(load(context))
        } catch (_: Exception) {
            ""
        }
        cachedAdvice = advice
        return advice
    }

    /** A table for the Skills card, e.g. `tools: page_read 4/5 · clock 2/2`. Empty when unused. */
    fun report(context: Context): String = try {
        ToolRecord.report(load(context))
    } catch (_: Exception) {
        ""
    }

    /**
     * What the Skills card shows: the totals, the last few runs, and how the tools have behaved.
     * Empty sections are omitted rather than printed as blanks.
     */
    fun panelLines(context: Context): String {
        val runs = CognitionLedger.recentLines(context, max = 5)
        return buildList {
            add(CognitionLedger.summary(context))
            if (runs.isNotEmpty()) {
                add("recent")
                addAll(runs)
            }
            add(report(context))
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
        cachedAdvice = null
    }
}
