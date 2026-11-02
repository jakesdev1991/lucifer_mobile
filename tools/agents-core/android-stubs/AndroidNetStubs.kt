// Type-check-only stand-ins for android.net, used by the lesson-transfer adapter's SAF picker.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.net

class Uri private constructor() {
    override fun toString(): String = "content://stub"
}
