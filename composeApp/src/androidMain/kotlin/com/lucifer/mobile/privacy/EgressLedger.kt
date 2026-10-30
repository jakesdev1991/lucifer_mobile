package com.lucifer.mobile.privacy

/**
 * THE EGRESS LEDGER — a pure record of what this device tells the world.
 *
 * Lucifer makes outbound requests to a handful of places: NVIDIA's model API, DuckDuckGo for
 * research, GitHub for the knowledge corpus, public JSON-RPC nodes for balances, and whatever page
 * the read-only browser was pointed at. Every one of those is observable by somebody else, and none
 * of them needs a device identifier to be interesting — an IP address, a timing pattern, and the
 * words in a prompt are enough.
 *
 * This file is the *policy and the record*, deliberately free of Android so the rules can be
 * exercised on a host. [com.lucifer.mobile.privacy.NetworkGuard] owns the switch and the file.
 *
 * Rules that are enforced here, not described:
 *  - A URL with no parseable host is refused: data is never posted to an unknown destination.
 *  - Device-local destinations (loopback, RFC1918, link-local, `.local`) are always allowed. Turning
 *    the network off must not break Termux, the VM bridge, the memory MCP or the Omega knowledge
 *    server — those never leave the phone.
 *  - With the network switched off, every non-local destination is refused before a socket opens.
 *  - The ledger stores the host and the *path*, never the query string. A search query is the one
 *    thing that would turn this log into a transcript of what the user was thinking about.
 *  - The log is a fixed-capacity ring. It cannot grow without bound, and it never leaves the device.
 */

/** What kind of destination a request was aimed at. Ordinal order is the UI's display order. */
enum class EgressClass {
    /** Loopback / private LAN. Never leaves the device or its own network. */
    LOCAL,
    /** A hosted language model — prompts, and optionally a camera still. */
    MODEL,
    /** A hosted vision model — carries image bytes. */
    VISION,
    /** A search engine — carries the query in the URL. */
    SEARCH,
    /** An ordinary public web page. */
    PAGE,
    /** A public blockchain RPC node — carries a wallet address. */
    RPC,
    /** A code host — repository listings and raw files. */
    CODE,
    /** Not recognised. Recorded so an unknown destination cannot hide. */
    UNKNOWN;

    val label: String
        get() = when (this) {
            LOCAL -> "LOCAL"
            MODEL -> "MODEL"
            VISION -> "VISION"
            SEARCH -> "SEARCH"
            PAGE -> "PAGE"
            RPC -> "RPC"
            CODE -> "CODE"
            UNKNOWN -> "UNKNOWN"
        }
}

/** Why a request did or did not leave the device. */
enum class EgressOutcome {
    ALLOWED,
    /** The network switch was off. Nothing was sent. */
    BLOCKED_OFFLINE,
    /** The destination could not be identified. Nothing was sent. */
    BLOCKED_POLICY;

    val label: String
        get() = when (this) {
            ALLOWED -> "ALLOWED"
            BLOCKED_OFFLINE -> "BLOCKED"
            BLOCKED_POLICY -> "REFUSED"
        }
}

/** One line in the ledger. Immutable; no field ever holds a query string or a credential. */
data class EgressRecord(
    val epochMillis: Long,
    val host: String,
    val route: String,
    val kind: EgressClass,
    val userInitiated: Boolean,
    val outcome: EgressOutcome,
    val hadQueryString: Boolean
) {
    /**
     * `14:02 MODEL integrate.api.nvidia.com/v1/chat/completions`
     *
     * A line Lucifer started is marked, because the difference between "I asked for this" and "he
     * asked on his own" is the whole reason a person reads an egress log.
     */
    fun summary(): String {
        val flag = if (outcome == EgressOutcome.ALLOWED) "" else " · ${outcome.label}"
        val who = if (userInitiated) "" else " · on his own"
        return "${kind.label} $host$route$flag$who"
    }
}

/** The verdict for one outbound attempt. Callers must check [allowed] before opening a socket. */
data class EgressDecision(
    val allowed: Boolean,
    val kind: EgressClass,
    val outcome: EgressOutcome,
    val host: String,
    val reason: String = ""
) {
    val blocked: Boolean get() = !allowed
}

object EgressPolicy {

    /** Longest path kept in the ledger. Longer paths are truncated, not dropped. */
    const val MAX_ROUTE_CHARS = 48

    /** Hosts recognised as device-local. Anything here is allowed even with the network off. */
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home")
    private val LOCAL_EXACT = setOf(
        "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "10.0.2.2"
    )

    /** Classification is by suffix so a subdomain cannot dodge the rule. */
    private val SUFFIX_RULES = listOf(
        "api.nvidia.com" to EgressClass.MODEL,
        "nvidia.com" to EgressClass.MODEL,
        "duckduckgo.com" to EgressClass.SEARCH,
        "github.com" to EgressClass.CODE,
        "githubusercontent.com" to EgressClass.CODE,
        "publicnode.com" to EgressClass.RPC,
        "arbitrum.io" to EgressClass.RPC,
        "llamarpc.com" to EgressClass.RPC,
        "ycombinator.com" to EgressClass.PAGE
    )

    /** Split a URL into (host, path-only route). Returns null when there is no usable host. */
    fun parse(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 4_096) return null
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = trimmed.substring(schemeEnd + 3)
        if (rest.isEmpty()) return null

        // Authority ends at the first / ? or #.
        var authorityEnd = rest.length
        for (marker in listOf('/', '?', '#')) {
            val at = rest.indexOf(marker)
            if (at in 0 until authorityEnd) authorityEnd = at
        }
        var authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty()) return null

        // Drop credentials: everything up to the last '@' in the authority is not a host.
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.isEmpty()) return null

        // Strip the port. IPv6 literals keep their brackets and their colons.
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            val colon = authority.indexOf(':')
            if (colon >= 0) authority.substring(0, colon) else authority
        }.lowercase()

        if (!isPlausibleHost(host)) return null

        var route = rest.substring(authorityEnd)
        for (marker in listOf('?', '#')) {
            val cut = route.indexOf(marker)
            if (cut >= 0) route = route.substring(0, cut)
        }
        if (route.isEmpty()) route = "/"
        return host to truncateRoute(route)
    }

    /** A host must look like a host: letters, digits, dots, hyphens, or a bracketed IPv6 literal. */
    private fun isPlausibleHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith("[")) return host.endsWith("]") && host.length > 2
        for (character in host) {
            val ok = character.isLetterOrDigit() || character == '.' || character == '-' || character == '_'
            if (!ok) return false
        }
        return host.contains('.') || host == "localhost"
    }

    fun truncateRoute(route: String): String =
        if (route.length <= MAX_ROUTE_CHARS) route
        else route.substring(0, MAX_ROUTE_CHARS - 1) + "…"

    /** True for loopback, RFC1918, link-local, CGNAT and mDNS names. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h in LOCAL_EXACT || host.lowercase() in LOCAL_EXACT) return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (h == "localhost") return true
        if (h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    fun classify(host: String): EgressClass {
        val h = host.lowercase()
        if (isLocal(h)) return EgressClass.LOCAL
        for ((suffix, kind) in SUFFIX_RULES) {
            if (h == suffix || h.endsWith(".$suffix")) return kind
        }
        return EgressClass.PAGE
    }

    fun hasQueryString(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.contains('?')
        return url.substring(schemeEnd + 3).contains('?')
    }

    /**
     * The whole rule, in one function. [userInitiated] is false for anything Lucifer does on its own
     * initiative — a heartbeat session, a recovery research pass — and true when a person asked.
     */
    fun decide(
        url: String,
        online: Boolean,
        userInitiated: Boolean,
        expected: EgressClass? = null
    ): EgressDecision {
        val parsed = parse(url)
            ?: return EgressDecision(
                allowed = false,
                kind = EgressClass.UNKNOWN,
                outcome = EgressOutcome.BLOCKED_POLICY,
                host = "",
                reason = "Destination has no identifiable host."
            )
        val (host, _) = parsed
        if (isLocal(host)) {
            return EgressDecision(
                allowed = true,
                kind = EgressClass.LOCAL,
                outcome = EgressOutcome.ALLOWED,
                host = host,
                reason = "Device-local."
            )
        }
        if (!online) {
            return EgressDecision(
                allowed = false,
                kind = expected ?: classify(host),
                outcome = EgressOutcome.BLOCKED_OFFLINE,
                host = host,
                reason = "Network switch is OFF."
            )
        }
        val kind = expected ?: classify(host)
        return EgressDecision(
            allowed = true,
            kind = kind,
            outcome = EgressOutcome.ALLOWED,
            host = host,
            reason = if (kind == EgressClass.PAGE && expected == null) "Unrecognised public host." else ""
        )
    }

    /** Build a record for [url] with the query string already removed. */
    fun record(
        url: String,
        decision: EgressDecision,
        epochMillis: Long,
        userInitiated: Boolean
    ): EgressRecord {
        val parsed = parse(url)
        return EgressRecord(
            epochMillis = epochMillis,
            host = parsed?.first ?: decision.host,
            route = parsed?.second ?: "",
            kind = decision.kind,
            userInitiated = userInitiated,
            outcome = decision.outcome,
            hadQueryString = hasQueryString(url)
        )
    }
}

/**
 * TSV instead of JSON on purpose: a corrupt line is dropped by a field-count check, so a half-written
 * record can never stop the app from reading the rest of its own ledger.
 */
object EgressCodec {

    const val FIELD_COUNT = 7

    fun encode(record: EgressRecord): String = listOf(
        record.epochMillis.toString(),
        clean(record.host),
        clean(record.route),
        record.kind.name,
        record.userInitiated.toString(),
        record.outcome.name,
        record.hadQueryString.toString()
    ).joinToString("\t")

    fun decode(line: String): EgressRecord? {
        if (line.isEmpty()) return null
        val parts = line.split('\t')
        if (parts.size != FIELD_COUNT) return null
        val epoch = parts[0].toLongOrNull() ?: return null
        val kind = enumOr(parts[3], EgressClass.UNKNOWN)
        val outcome = enumOr(parts[5], EgressOutcome.BLOCKED_POLICY)
        return EgressRecord(
            epochMillis = epoch,
            host = parts[1],
            route = parts[2],
            kind = kind,
            userInitiated = parts[4].toBooleanStrictOrNull() ?: false,
            outcome = outcome,
            hadQueryString = parts[6].toBooleanStrictOrNull() ?: false
        )
    }

    private fun clean(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(256)

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

/** A bounded, in-memory ring of egress records. The file on disk is NetworkGuard's business. */
class EgressLog(val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 400
    }

    private val entries = ArrayList<EgressRecord>(minOf(capacity, 64))

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun record(entry: EgressRecord) {
        entries.add(entry)
        while (entries.size > capacity) entries.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<EgressRecord> = entries.toList()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun blockedCount(): Int = entries.count { it.outcome != EgressOutcome.ALLOWED }

    @Synchronized
    fun countsByClass(): Map<EgressClass, Int> {
        val counts = LinkedHashMap<EgressClass, Int>()
        for (kind in EgressClass.values()) {
            val n = entries.count { it.kind == kind }
            if (n > 0) counts[kind] = n
        }
        return counts
    }

    /** `23 attempts · 21 allowed · 2 blocked · MODEL 6 · SEARCH 4` */
    @Synchronized
    fun summary(): String {
        if (entries.isEmpty()) return "No outbound requests recorded."
        val blocked = blockedCount()
        val parts = ArrayList<String>(4)
        parts.add("${entries.size} attempt" + (if (entries.size == 1) "" else "s"))
        parts.add("${entries.size - blocked} allowed")
        parts.add("$blocked blocked")
        for ((kind, n) in countsByClass()) parts.add("${kind.label} $n")
        return parts.joinToString(" · ")
    }

    /** Newest last, one line each. Used by the Privacy panel. */
    @Synchronized
    fun render(max: Int = 30): String {
        if (entries.isEmpty()) return "No outbound requests recorded."
        val tail = if (entries.size <= max) entries else entries.subList(entries.size - max, entries.size)
        return buildString {
            for (entry in tail) {
                appendLine(entry.summary())
            }
            append(summary())
        }
    }

    @Synchronized
    fun toLines(): List<String> = entries.map { EgressCodec.encode(it) }

    /** Replaces the ring with whatever parses. Returns how many lines were understood. */
    @Synchronized
    fun loadLines(lines: List<String>): Int {
        entries.clear()
        for (line in lines) {
            val record = EgressCodec.decode(line) ?: continue
            entries.add(record)
        }
        while (entries.size > capacity) entries.removeAt(0)
        return entries.size
    }
}
