// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object Dispatchers {
    val IO: CoroutineContext = EmptyCoroutineContext
}

suspend fun delay(millis: Long) {}

suspend fun <T> withContext(context: CoroutineContext, block: suspend () -> T): T = block()
