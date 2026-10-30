package com.lucifer.mobile.net

/**
 * "Is my traffic actually in a Tor circuit?" — asked of Tor itself, not assumed.
 *
 * A SOCKS port that accepts a connection proves nothing: a local port could be anything, and a
 * misconfigured Orbot will happily relay a request out of a normal exit while the UI still says TOR.
 * So the check goes through the same tunnel the app uses, to Tor Project's own endpoint, and reads
 * `IsTor` out of the answer.
 *
 * The verdict wording matters as much as the check. `NOT_TOR` is a refusal to claim privacy, not a
 * failure to connect, and the UI must not blur the two.
 */
object TorCheck {

    /** Tor Project's own check endpoint. Fixed, and named here so the ledger cannot hide it. */
    const val ENDPOINT = "https://check.torproject.org/api/ip"

    /** A usable proxy answers in seconds; anything slower is treated as down, not as "busy". */
    const val TIMEOUT_MS = 12_000

    sealed class Verdict {
        /** The circuit is real and the exit is a Tor node. */
        data class InCircuit(val exitAddress: String) : Verdict()

        /** The proxy answered but the request left through an ordinary connection. */
        data class NotInCircuit(val observedAddress: String) : Verdict()

        /** The proxy could not be reached or refused the request. */
        data class Unreachable(val reason: String) : Verdict()

        /** The endpoint answered with something this parser does not recognise. */
        data class Inconclusive(val reason: String) : Verdict()
    }

    fun describe(verdict: Verdict): String = when (verdict) {
        is Verdict.InCircuit ->
            "IN CIRCUIT · exit ${verdict.exitAddress.ifBlank { "unknown" }} · DNS is resolved by the proxy"
        is Verdict.NotInCircuit ->
            "NOT IN CIRCUIT · this traffic left as ${verdict.observedAddress.ifBlank { "unknown" }}. " +
                "The proxy is up but is not Tor, so nothing here is anonymised."
        is Verdict.Unreachable ->
            "UNREACHABLE · ${verdict.reason} · nothing was sent"
        is Verdict.Inconclusive ->
            "UNVERIFIED · ${verdict.reason} · do not assume a circuit"
    }

    /**
     * Parse the JSON body without a JSON dependency, because this object is also compiled by the
     * host-side harness, which has no `org.json`.
     */
    fun parse(body: String): Verdict {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Verdict.Inconclusive("empty answer from the check endpoint")
        val isTor = boolField(trimmed, "IsTor")
            ?: return Verdict.Inconclusive("the answer has no IsTor field")
        val ip = stringField(trimmed, "IP").orEmpty()
        return if (isTor) Verdict.InCircuit(ip) else Verdict.NotInCircuit(ip)
    }

    /** `{"IsTor": true, …}` — accepts `true`/`false`, quoted or bare, with any spacing. */
    fun boolField(json: String, name: String): Boolean? {
        val match = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"?(true|false)\"?", RegexOption.IGNORE_CASE)
            .find(json) ?: return null
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

    fun stringField(json: String, name: String): String? =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    /** The line the panel shows before any check has run. */
    const val UNCHECKED = "NOT VERIFIED · run TEST CIRCUIT before trusting this mode"
}
