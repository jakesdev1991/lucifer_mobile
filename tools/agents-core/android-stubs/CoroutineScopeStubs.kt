// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

import kotlin.coroutines.CoroutineContext

interface Job {
    val isActive: Boolean
    fun cancel()
}

class SupervisorJob : Job, CoroutineContext {
    override val isActive: Boolean get() = true
    override fun cancel() {}
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = null
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = initial
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = this
}

class CoroutineScope(val coroutineContext: CoroutineContext) {
    val isActive: Boolean get() = true
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = SupervisorJob()
}

fun CoroutineScope.cancel() {}

object Dispatchers {
    val IO: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
    val Main: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
}

class CancellationException(message: String? = null) : Exception(message)

suspend fun delay(millis: Long) {}

suspend fun <T> withContext(context: CoroutineContext, block: suspend () -> T): T = block()

// Extension forms so `import kotlinx.coroutines.isActive` / `.launch` resolve like the real library.
val CoroutineScope.isActive: Boolean get() = true

fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = SupervisorJob()

suspend fun kotlin.coroutines.CoroutineContext.ensureActive() {}

suspend fun currentCoroutineContext(): kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
