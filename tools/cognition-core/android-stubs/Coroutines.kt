// A very small coroutines stand-in.
//
// The point of this harness is the loop, not the scheduler. kotlinx-coroutines publishes no JVM
// artefact to the registries reachable from this sandbox, so these few calls are reimplemented
// honestly and narrowly: `withContext` runs the block on the calling thread, `delay` sleeps, and
// `withTimeout` runs the block on a daemon thread and gives up on it when the deadline passes.
//
// What that does NOT prove: real structured concurrency, cooperative cancellation inside a suspended
// call, or that a cancelled coroutine unwinds the way Android's dispatcher does. Those are named in
// COGNITION.md as still unverified rather than quietly assumed.
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

open class CancellationException(message: String? = null) : java.util.concurrent.CancellationException(message)

class TimeoutCancellationException(message: String) : CancellationException(message)

object Dispatchers {
    object Default
    object IO
    object Main
    object Unconfined
}

/**
 * Runs the block on the caller's thread. Nothing in the loop actually suspends under this shim, so a
 * direct call is a faithful execution of the loop's own logic — and it keeps failures on the stack.
 */
fun <T> runBlocking(block: suspend () -> T): T = drive(block)

suspend fun <T> withContext(context: Any?, block: suspend () -> T): T = block()

suspend fun delay(millis: Long) {
    if (millis > 0) Thread.sleep(millis)
}

suspend fun <T> withTimeout(timeMillis: Long, block: suspend () -> T): T {
    val box = Box<T>()
    val thread = Thread {
        try {
            box.value = drive(block)
        } catch (error: Throwable) {
            box.error = error
        }
    }
    thread.isDaemon = true
    thread.start()
    thread.join(timeMillis.coerceAtLeast(1))
    if (thread.isAlive) {
        thread.interrupt()
        throw TimeoutCancellationException("Timed out waiting for $timeMillis ms")
    }
    box.error?.let { throw it }
    return box.value as T
}

/**
 * Start the block and take its result. Every function in this shim completes without suspending, so
 * the continuation is resumed before `startCoroutine` returns; if that ever stops being true the null
 * check fires loudly instead of silently returning a half-run loop.
 */
private fun <T> drive(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    val continuation = object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    }
    block.startCoroutine(continuation)
    val settled = outcome ?: error("the coroutine shim only supports blocks that complete without suspending")
    return settled.getOrThrow()
}

private class Box<T> {
    @Volatile var value: T? = null
    @Volatile var error: Throwable? = null
}
