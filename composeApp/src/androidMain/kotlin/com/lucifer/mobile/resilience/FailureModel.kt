package com.lucifer.mobile.resilience

/**
 * Failure model for Lucifer's recovery loop.
 *
 * This file is deliberately pure Kotlin (no Android, no org.json, no coroutines) so the
 * classification logic can be compiled and exercised by the host-side harness in
 * `tools/resilience-core`. The Android layer only supplies command output.
 */

/** Terminal destination a probe or recovery action may use. Mirrors the launcher targets. */
enum class ResilienceTarget(val displayName: String) {
    LOCAL("LOCAL"),
    TERMUX("TERMUX"),
    VM("VM"),
    SHIZUKU("SHIZUKU");

    companion object {
        /** Best-effort mapping from the launcher's target names; never throws. */
        fun fromName(raw: String?): ResilienceTarget? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** A coarse, actionable category for a failure. Ordering matters for the UI. */
enum class FailureClass(val label: String, val transient: Boolean) {
    NONE("no failure", false),
    COMMAND_NOT_FOUND("missing command", false),
    MISSING_DEPENDENCY("missing dependency", false),
    PERMISSION_DENIED("permission denied", false),
    AUTH("authentication rejected", false),
    NETWORK("network unreachable", true),
    DNS("name resolution failed", true),
    TIMEOUT("timed out", true),
    RESOURCE_LIMIT("resource exhausted", true),
    DISK_FULL("disk full", false),
    PORT_IN_USE("port already bound", false),
    SYNTAX("syntax / usage error", false),
    PATH_MISSING("path not found", false),
    BUILD_FAILED("build failed", false),
    TESTS_FAILED("tests failed", false),
    MODEL_UNAVAILABLE("model route unavailable", true),
    TERMINAL_SETUP("terminal not configured", false),
    UNKNOWN("unclassified failure", false)
}

/** Redaction shared by every new resilience surface. Conservative and idempotent. */
object ResilienceRedactor {
    private val patterns = listOf(
        Regex("(?i)(authorization\\s*:\\s*bearer\\s+)\\S+") to "$1<redacted>",
        Regex("(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|token)\\b\\s*[:=]\\s*[^\\s,;'\"]+") to "$1=<redacted>",
        Regex("\\b[A-Za-z0-9_\\-]{24,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{20,}\\b") to "<redacted-token>",
        Regex("\\b[0-9a-fA-F]{64}\\b") to "<redacted-64-hex>",
        Regex("\\b0x[0-9a-fA-F]{40,}\\b") to "<redacted-address>",
        Regex("(?i)\\b([a-z0-9._%+\\-]+)@([a-z0-9.\\-]+\\.[a-z]{2,})\\b") to "<redacted-email>",
        Regex("(?i)\\b(password|passphrase)\\s+for\\s+[^:]+:") to "<redacted-prompt>"
    )

    fun redact(raw: String, maxChars: Int = 4_000): String {
        var out = raw
        for ((pattern, replacement) in patterns) out = pattern.replace(out, replacement)
        return if (out.length > maxChars) out.take(maxChars) + "…" else out
    }
}

/** Text helpers used for signature building and evidence quoting. */
object ResilienceText {
    private val ansi = Regex("\\u001B\\[[0-9;?]*[A-Za-z]")
    private val hex = Regex("\\b[0-9a-fA-F]{8,}\\b")
    private val numbers = Regex("\\b\\d+\\b")
    private val urls = Regex("(?i)\\b(?:https?|ftp)://\\S+")
    private val paths = Regex("(?:^|[\\s\"'=(])(?:~|\\.{1,2})?/[\\w.@%+\\-/]{2,}")
    private val ips = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")

    fun stripAnsi(raw: String): String = ansi.replace(raw, "")

    /** Lowercase, strip volatile tokens, and collapse whitespace into a stable comparison key. */
    fun normalizeForSignature(raw: String): String {
        var text = stripAnsi(raw).lowercase()
        text = urls.replace(text, "<url>")
        text = ips.replace(text, "<ip>")
        text = hex.replace(text, "<hex>")
        text = paths.replace(text, " <path>")
        text = numbers.replace(text, "<n>")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.trim(' ', ':', '-', '.', ',', ';').take(160)
    }

    /** First line that looks like an error, so signatures stay meaningful. */
    fun firstErrorLine(lines: List<String>): String {
        val cleaned = lines.asSequence().map { stripAnsi(it).trim() }.filter { it.isNotEmpty() }
        val errorish = cleaned.firstOrNull { line ->
            Regex("(?i)(error|failed|failure|denied|refused|not found|no such|traceback|exception|panic|fatal|cannot|unable|timed out|not installed)").containsMatchIn(line) &&
                !line.startsWith("[INFO]") && !line.startsWith("[SETUP]")
        }
        return (errorish ?: cleaned.firstOrNull() ?: "").take(400)
    }

    fun lastMeaningfulLine(lines: List<String>): String =
        lines.asSequence().map { stripAnsi(it).trim() }.lastOrNull { it.isNotEmpty() }?.take(400) ?: ""

    /** Tool name from a command line, used to keep signatures useful across projects. */
    fun toolOf(command: String): String {
        val first = command.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val base = first.substringAfterLast('/')
        return if (base.isBlank()) "unknown" else base.take(32)
    }

    /** FNV-1a 64-bit: stable, dependency-free, good enough for a local lesson key. */
    fun stableKey(parts: List<String>, maxChars: Int = 24): String {
        var hash = -0x340d631b7bdddcdbL
        for (part in parts) {
            val text = part.lowercase()
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            hash = hash xor 0x1fL
            hash *= 0x100000001b3L
        }
        val hexText = java.lang.Long.toHexString(hash)
        return hexText.takeLast(maxChars)
    }
}

/** One finished terminal/chat action and everything known about its result. */
data class CommandOutcome(
    val target: ResilienceTarget,
    val command: String,
    val lines: List<String>,
    val exitCode: Int? = null,
    val executedAt: Long = System.currentTimeMillis()
) {
    val tool: String get() = ResilienceText.toolOf(command)

    /** Explicit "[exit N]" markers produced by the terminal executor win over guessing. */
    val effectiveExitCode: Int?
        get() = exitCode ?: Regex("\\[exit\\s+(-?\\d+)]")
            .find(lines.asReversed().joinToString("\n"))
            ?.groupValues?.get(1)?.toIntOrNull()

    val joined: String get() = lines.joinToString("\n")
}

/** A classified failure. [key] is the stable value used to look up learned lessons. */
data class FailureSignal(
    val failureClass: FailureClass,
    val key: String,
    val tool: String,
    val errorLine: String,
    val target: ResilienceTarget,
    val command: String,
    val exitCode: Int?,
    val rawLines: List<String> = emptyList(),
    val detectedAt: Long = System.currentTimeMillis()
) {
    val summary: String
        get() = "${failureClass.label} · ${tool} · ${ResilienceRedactor.redact(errorLine, 220)}"
}

/**
 * Deterministic failure classifier.
 *
 * Rules are ordered from specific to generic so that, for example, a missing Python module is
 * reported as MISSING_DEPENDENCY rather than PATH_MISSING because the interpreter printed
 * "No such file or directory".
 */
object FailureClassifier {

    private data class Rule(val failureClass: FailureClass, val pattern: Regex)

    private val rules: List<Rule> = listOf(
        Rule(FailureClass.TERMINAL_SETUP, Regex("(?i)\\[setup]|allow-external-apps|run commands in termux|known_hosts|host-key pin|install termux")),
        Rule(FailureClass.MODEL_UNAVAILABLE, Regex("(?i)\\[llm]|no nvidia api key|all routes failed|model .* unavailable|http 429|rate.?limit")),
        Rule(FailureClass.MISSING_DEPENDENCY, Regex("(?i)(cannot find module|module not found|no module named|modulenotfounderror|importerror|command .* requires|is not installed|missing dependency|unable to locate package|package .* (?:was )?not found|npm err! code e404|e: package .* has no installation candidate|nothing provides|library not found|shared object file.*cannot open|no such package|could not find package|requirements? (?:not satisfied|missing))")),
        Rule(FailureClass.PERMISSION_DENIED, Regex("(?i)(permission denied|operation not permitted|eacces|eperm|not authorized|access denied|sudo: .* not allowed)")),
        Rule(FailureClass.AUTH, Regex("(?i)(http 401|http 403|unauthorized|authentication failed|invalid api key|bad credentials|publickey|access token .* (?:expired|invalid)|403 forbidden)")),
        Rule(FailureClass.DNS, Regex("(?i)(could not resolve host|temporary failure in name resolution|name or service not known|nodename nor servname|getaddrinfo|dns (?:lookup )?fail)")),
        Rule(FailureClass.NETWORK, Regex("(?i)(network is unreachable|connection refused|connection reset|connection closed|failed to connect|no route to host|ssl(?:error| handshake)|tls handshake|econnrefused|etimedout|socket hang up|proxy error)")),
        Rule(FailureClass.TIMEOUT, Regex("(?i)(timed out|timeout|deadline exceeded|took too long)")),
        Rule(FailureClass.DISK_FULL, Regex("(?i)(no space left on device|disk quota exceeded|insufficient storage|enospc)")),
        Rule(FailureClass.PORT_IN_USE, Regex("(?i)(address already in use|eaddrinuse|port .* (?:is )?already (?:in use|bound)|bind: .* in use)")),
        Rule(FailureClass.RESOURCE_LIMIT, Regex("(?i)(out of memory|cannot allocate memory|oom|killed process|killed by signal|memory limit|too many open files|resource temporarily unavailable)")),
        Rule(FailureClass.BUILD_FAILED, Regex("(?i)(build failed|compilation (?:error|failed)|could not compile|gradle .*failed|make: \\*\\*\\*|tsc: error|error ts\\d+|fatal error: .*\\.h: no such file)")),
        Rule(FailureClass.TESTS_FAILED, Regex("(?i)(\\d+ (?:failed|failing)|tests? failed|test suite failed|assertion(?:error| failed)|expected .* (?:but|to be|got))")),
        Rule(FailureClass.SYNTAX, Regex("(?i)(syntax error|unexpected token|invalid syntax|parse error|unexpected argument|usage: |illegal option|unknown option|bad substitution)")),
        Rule(FailureClass.PATH_MISSING, Regex("(?i)(no such file or directory|cannot access .*: no such|does not exist|not a directory|cannot stat)"))
    )

    private val commandNotFound = Regex(
        "(?i)(command not found|: not found$|is not recognized as an internal or external command|unknown command|executable file not found|no such command|command '.*' not found)"
    )

    private val genericError = Regex("(?i)^\\s*(\\[error]|error\\b|fatal\\b|panic\\b|traceback \\(most recent call last\\)|exception\\b|failed\\b|\\[blocked])")

    private val exitCodeMap = mapOf(
        124 to FailureClass.TIMEOUT,
        126 to FailureClass.PERMISSION_DENIED,
        127 to FailureClass.COMMAND_NOT_FOUND,
        137 to FailureClass.RESOURCE_LIMIT,
        143 to FailureClass.TIMEOUT
    )

    /** True when the output contains an unmistakable failure marker. */
    fun looksLikeFailure(outcome: CommandOutcome): Boolean {
        val exit = outcome.effectiveExitCode
        if (exit != null && exit != 0) return true
        return outcome.lines.any { line ->
            val text = ResilienceText.stripAnsi(line)
            commandNotFound.containsMatchIn(text) || rules.any { it.pattern.containsMatchIn(text) } ||
                genericError.containsMatchIn(text)
        }
    }

    /**
     * Returns null when the output shows no failure, so callers can keep successful runs out
     * of the lesson ledger entirely.
     */
    fun classify(outcome: CommandOutcome): FailureSignal? {
        if (!looksLikeFailure(outcome)) return null
        val exit = outcome.effectiveExitCode
        val errorLine = ResilienceText.firstErrorLine(outcome.lines)
        val text = outcome.joined
        val failureClass = when {
            commandNotFound.containsMatchIn(text) -> FailureClass.COMMAND_NOT_FOUND
            else -> rules.firstOrNull { it.pattern.containsMatchIn(text) }?.failureClass
                ?: exit?.let { exitCodeMap[it] }
                ?: if (genericError.containsMatchIn(errorLine)) FailureClass.UNKNOWN else FailureClass.NONE
        }
        if (failureClass == FailureClass.NONE) return null
        val key = ResilienceText.stableKey(
            listOf(
                failureClass.name,
                ResilienceText.toolOf(outcome.command),
                ResilienceText.normalizeForSignature(errorLine),
                outcome.target.name
            )
        )
        return FailureSignal(
            failureClass = failureClass,
            key = key,
            tool = outcome.tool,
            errorLine = errorLine.ifBlank { "exit ${exit ?: "unknown"}" },
            target = outcome.target,
            command = outcome.command,
            exitCode = exit,
            rawLines = outcome.lines.takeLast(120),
            detectedAt = outcome.executedAt
        )
    }

    /** Lesson key for a *successful* command, used to learn from what worked. */
    fun successKey(outcome: CommandOutcome): String = ResilienceText.stableKey(
        listOf(
            FailureClass.NONE.name,
            ResilienceText.toolOf(outcome.command),
            ResilienceText.normalizeForSignature(outcome.command),
            outcome.target.name
        )
    )
}
