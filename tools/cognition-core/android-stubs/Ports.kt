// Type-check-only stand-ins for the collaborators the runner is wired to on a device.
//
// Each one mirrors the real signature exactly (the harness fails to compile if that drifts) and each
// one returns "nothing" rather than pretending. The loop under test is given its own planner and
// toolbox, so none of these are executed by the assertions.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.cognition

import android.content.Context

/** The device's tool tally is read from a file; the harness passes its own record instead. */
object CognitionScores {
    fun adviceLine(context: Context): String = ""
    fun report(context: Context): String = ""
    fun record(context: Context, call: ToolCall, ok: Boolean) = Unit
}
