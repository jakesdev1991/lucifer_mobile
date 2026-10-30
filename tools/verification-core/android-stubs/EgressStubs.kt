// Type-check-only stand-in. See AndroidCore.kt.
//
// AuditVault reaches the vault through the one egress door. That door pulls in sockets, the Tor
// transport and the ledger, none of which this harness is about, so the shape of the door is stubbed
// and the call site is still checked for the fields it passes.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.privacy

class EgressDecision(val allowed: Boolean = true, val reason: String = "") {
    val blocked: Boolean get() = !allowed
}

object EgressHttp {

    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 20_000

    data class Request(
        val url: String,
        val userInitiated: Boolean,
        val expected: Any? = null,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
        val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        val maxBodyBytes: Int = 1 shl 20,
        val decision: EgressDecision? = null
    )

    data class Reply(
        val decision: EgressDecision,
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val error: String = ""
    ) {
        fun text(): String = String(body, Charsets.UTF_8)
        fun refusal(what: String): String = "[BLOCKED] $what: $error"
    }

    fun exchange(request: Request): Reply = Reply(EgressDecision(allowed = false), 0, error = "stub")
}
