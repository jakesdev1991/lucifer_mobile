// Type-check-only stand-ins for the Lucifer collaborators ResilienceEngine touches.
// See AndroidStubs.kt for the caveat: this verifies types, not device behavior.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.launcher

enum class CommandTarget(val launcherIndex: Int, val displayName: String) {
    LOCAL(0, "LOCAL"),
    TERMUX(1, "TERMUX"),
    VM(2, "VM"),
    SHIZUKU(3, "SHIZUKU")
}
