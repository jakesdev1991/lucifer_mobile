// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.omni_bridge.heartbeat

object LuciferHeartbeatService {
    fun isEnabled(context: android.content.Context): Boolean = false
    fun isCameraEnabled(context: android.content.Context): Boolean = false
    fun isRunning(): Boolean = false
}
