// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

object Log {
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
}

/** Mirrors the subset of android.util.AtomicFile used by the lesson store. */
class AtomicFile(private val base: java.io.File) {
    fun openRead(): java.io.FileInputStream = java.io.FileInputStream(base)
    fun startWrite(): java.io.FileOutputStream = java.io.FileOutputStream(base)
    fun finishWrite(stream: java.io.FileOutputStream) {
        stream.close()
    }

    fun failWrite(stream: java.io.FileOutputStream) {
        stream.close()
    }
}
