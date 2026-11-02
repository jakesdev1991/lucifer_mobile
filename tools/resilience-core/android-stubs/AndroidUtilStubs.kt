// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

object Log {
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String, error: Throwable): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, error: Throwable): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, error: Throwable): Int = 0
    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = false
}

/** Mirrors android.util.AtomicFile (API 35): readFully, delete and the write triple. */
class AtomicFile(private val base: java.io.File) {
    fun getBaseFile(): java.io.File = base
    fun openRead(): java.io.FileInputStream = java.io.FileInputStream(base)
    fun readFully(): ByteArray = base.readBytes()
    fun startWrite(): java.io.FileOutputStream = java.io.FileOutputStream(base)
    fun finishWrite(stream: java.io.FileOutputStream) {
        stream.close()
    }

    fun failWrite(stream: java.io.FileOutputStream) {
        stream.close()
    }

    fun delete() {
        base.delete()
    }
}
