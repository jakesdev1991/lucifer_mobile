@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

/** Every log line the loop writes is captured, so a harness can assert on the refusal it logged. */
object Log {
    val lines = mutableListOf<String>()

    fun i(tag: String, message: String): Int { lines += "I/$tag: $message"; return 0 }
    fun w(tag: String, message: String): Int { lines += "W/$tag: $message"; return 0 }
    fun e(tag: String, message: String): Int { lines += "E/$tag: $message"; return 0 }
    fun e(tag: String, message: String, error: Throwable): Int { lines += "E/$tag: $message (${error.javaClass.simpleName})"; return 0 }
    fun d(tag: String, message: String): Int { lines += "D/$tag: $message"; return 0 }

    fun clear() = lines.clear()
    fun contains(fragment: String): Boolean = lines.any { it.contains(fragment) }
}
