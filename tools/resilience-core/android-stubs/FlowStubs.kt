// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines.flow

interface StateFlow<out T> {
    val value: T
}

interface MutableStateFlow<T> : StateFlow<T> {
    override var value: T
}

fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this

fun <T> MutableStateFlow(initial: T): MutableStateFlow<T> = object : MutableStateFlow<T> {
    override var value: T = initial
}
