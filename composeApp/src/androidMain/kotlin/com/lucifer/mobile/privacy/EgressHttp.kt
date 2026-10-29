package com.lucifer.mobile.privacy

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE ONLY WAY OUT.
 *
 * [NetworkGuard] used to be a convention: every call site was expected to ask it before calling
 * `openConnection()`, and an audit script failed the build if one forgot. That works, but it means the
 * guard is only as good as the next person's memory — and an audit can only see the call sites it
 * knows how to look for.
 *
 * This object inverts that. Opening a socket goes through [open], which authorises first and returns a
 * grant or a refusal. Application code no longer sees `openConnection()` at all, and
 * `tools/privacy-core/audit_egress.py --check` fails if it reappears outside this file.
 *
 * It also owns the timeouts, because eleven call sites each inventing their own is how a request ends
 * up hanging for two minutes with no record of why.
 */
object EgressHttp {

    private const val TAG = "EgressHttp"

    /** Defaults used when a caller has no opinion. Both are clamped, never zero, never infinite. */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 20_000

    /**
     * The outcome of an authorisation: either a connection you may use, or the decision that refused
     * it. Never both. [connection] is null exactly when [decision].blocked is true.
     */
    data class Grant(
        val decision: EgressDecision,
        val connection: HttpURLConnection?
    ) {
        val blocked: Boolean get() = connection == null

        /** The honest line to return instead of pretending the call happened. */
        fun refusal(what: String): String = NetworkGuard.refusalMessage(decision, what)
    }

    /**
     * Authorise [url] and open a connection to it.
     *
     * @param userInitiated true when a person asked for this, false for heartbeat/memory/recovery.
     * @param expected the caller's classification, e.g. [EgressClass.VISION] for an image call.
     */
    fun open(
        url: String,
        userInitiated: Boolean,
        expected: EgressClass? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        followRedirects: Boolean = false
    ): Grant {
        val decision = NetworkGuard.authorize(url, userInitiated, expected)
        if (decision.blocked) return Grant(decision, null)
        return Grant(decision, connect(url, connectTimeoutMs, readTimeoutMs, followRedirects))
    }

    /**
     * Open under a decision the caller already holds.
     *
     * Used when one user action legitimately makes several attempts: a model turn retries across
     * models and keys, and every retry is a real socket. Authorising once per turn keeps the ledger
     * readable ("the user asked; N attempts") instead of writing N identical decisions.
     * Never call this with a decision from a different action.
     */
    fun openApproved(
        url: String,
        decision: EgressDecision,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        followRedirects: Boolean = false
    ): Grant {
        if (decision.blocked) return Grant(decision, null)
        return Grant(decision, connect(url, connectTimeoutMs, readTimeoutMs, followRedirects))
    }

    /** Authorisation only, for callers that need a yes/no without a connection (probes, previews). */
    fun allowed(url: String, userInitiated: Boolean): Boolean =
        !NetworkGuard.authorize(url, userInitiated).blocked

    private fun connect(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean
    ): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = clampTimeout(connectTimeoutMs)
            readTimeout = clampTimeout(readTimeoutMs)
            // Never follow a redirect by default: a 302 would carry credentials or a payload to a host
            // this phone never chose. Callers that follow redirects do it one hop at a time, so every
            // new destination is authorised and recorded.
            instanceFollowRedirects = followRedirects
        }
    } catch (error: Exception) {
        // A malformed URL is a policy outcome, not a crash: report it as refused.
        Log.w(TAG, "could not open ${url.take(64)}: ${error.javaClass.simpleName}")
        null
    }

    private fun clampTimeout(value: Int): Int = value.coerceIn(500, 120_000)
}
