// Compile-only coroutine surface (the toolchain here has no coroutines artifact).
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

typealias CancellationException = java.util.concurrent.CancellationException

fun <T> runBlocking(block: suspend () -> T): T =
    throw UnsupportedOperationException("compile-only stub")
