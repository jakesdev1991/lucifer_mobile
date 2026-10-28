// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.os

object Build {
    object VERSION {
        const val SDK_INT: Int = 34
    }

    object VERSION_CODES {
        const val Q = 29
        const val O = 26
    }
}

object BatteryManager {
    const val EXTRA_LEVEL = "level"
    const val EXTRA_SCALE = "scale"
    const val EXTRA_STATUS = "status"
    const val BATTERY_STATUS_CHARGING = 2
}

interface IBinder
