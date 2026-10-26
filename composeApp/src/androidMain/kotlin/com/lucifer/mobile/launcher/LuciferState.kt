package com.lucifer.mobile.launcher

import java.util.concurrent.CopyOnWriteArrayList

/**
 * LuciferState — shared launcher diagnostics. Heartbeat sessions are opt-in from the main app;
 * service state remains dormant and is not a live background-service indicator.
 */
object LuciferState {
    @Volatile var phase: String = "DORMANT"
    @Volatile var cycle: Int = 0
    @Volatile var lastBeatAt: Long = 0L
    @Volatile var serviceAlive: Boolean = false

    private val lines = CopyOnWriteArrayList<String>()

    fun log(line: String) {
        lines.add(line)
        while (lines.size > 20) lines.removeAt(0)
    }

    fun recent(): List<String> = lines.toList()
}
