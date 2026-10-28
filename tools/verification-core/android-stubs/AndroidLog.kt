@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

object Log {
    fun i(tag: String, message: String): Int = 0
    fun w(tag: String, message: String): Int = 0
    fun w(tag: String, message: String, throwable: Throwable): Int = 0
    fun e(tag: String, message: String): Int = 0
    fun e(tag: String, message: String, throwable: Throwable): Int = 0
    fun d(tag: String, message: String): Int = 0
}
