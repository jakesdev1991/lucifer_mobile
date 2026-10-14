package com.lucifer.mobile.launcher

import java.util.concurrent.CopyOnWriteArrayList

/**
 * LuciferState — the shared consciousness bus between the agent engine and the Launcher.
 * The HeartbeatService publishes its phase/cycle here; the LauncherActivity renders it
 * on the home screen. Same process (one APK) = zero IPC, always fresh.
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
