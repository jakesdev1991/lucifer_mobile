// Enough of kotlinx.coroutines to type-check Lucifer's adapters on the host. Not the real library.
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

interface CoroutineContext

object Dispatchers {
    val IO: CoroutineContext = object : CoroutineContext {}
    val Main: CoroutineContext = object : CoroutineContext {}
    val Default: CoroutineContext = object : CoroutineContext {}
}

suspend fun <T> withContext(context: CoroutineContext, block: suspend () -> T): T = block()

fun CoroutineScope.isActive(): Boolean = true

interface CoroutineScope

suspend fun delay(millis: Long) {}
