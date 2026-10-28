// Stand-in for Lucifer's real Shizuku entry points, so the privilege router can be type-checked
// without the rikka.shizuku AIDL toolchain.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.shizuku

import android.content.Context

object ShizukuAccess {
    data class Status(
        val binderAvailable: Boolean,
        val permissionGranted: Boolean,
        val uid: Int?,
        val message: String
    ) {
        val ready: Boolean get() = binderAvailable && permissionGranted
    }

    fun status(): Status = Status(false, false, null, "stub")
    fun requestPermission(): Boolean = false
}

object ShizukuTerminal {
    suspend fun execute(context: Context, command: String): List<String> = emptyList()
}
