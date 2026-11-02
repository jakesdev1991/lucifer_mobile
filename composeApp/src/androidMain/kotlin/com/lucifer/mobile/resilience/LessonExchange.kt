package com.lucifer.mobile.resilience

// No Android import here on purpose: this is signing, redaction and dedupe over JSON — pure logic, and
// pure logic has to stay compilable and executable on a host or the harness cannot check it. It carried
// an unused `android.util.Log` import, which moved it out of the pure-core compile and broke
// AgentCoreTests, which tests exactly this file.
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.bool
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Opt-in, offline exchange of resilience lessons between Lucifer installs.
 *
 * The user explicitly picks an export location through the system SAF picker and an import file
 * through the file picker. Nothing here opens a socket, and nothing is ever exported automatically:
 * the Settings toggle "allow lesson export" gates the export path, and import only runs when the
 * user picks a file.
 *
 * Privacy rules, enforced in the core so they hold on any host:
 *
 *  - **Credential rejection.** Any lesson whose text matches the same secret patterns the mandate
 *    rejects ([secretPatterns], mirrored from `agents/PromptFiles.kt`) is dropped from an export,
 *    and a dropped lesson is counted so the user can see it happened rather than wondering where a
 *    lesson went. The broader [ResilienceRedactor] also runs over every note.
 *  - **Dedupe by fingerprint.** A lesson's fingerprint is a stable key over its
 *    (signatureKey, failureClass, kind, target, tool, succeeded, note) fields; importing the same
 *    lesson twice — or from two exports — adds it once.
 *  - **Fail closed.** A payload that is not a lesson bundle this build understands is refused
 *    wholesale, never half-read.
 *  - **Signed bundles.** Schema 2 exports carry `sig` = hex(HMAC-SHA256(device key, canonical
 *    payload)). Import verifies the signature when present and rejects a bundle whose signature
 *    does not match, so a hand-edited export cannot inject lessons. Schema 1 bundles (unsigned)
 *    are still accepted for backward compatibility.
 *
 * The signing key is supplied by [signer] (the Android adapter injects a KeyStore-backed key; the
 * host harness injects a fixed test key). This keeps the file pure Kotlin and host-testable.
 */
object LessonExchange {

    const val SCHEMA_VERSION = 2
    const val MAX_EXPORT_LESSONS = LearningLedger.MAX_LESSONS
    const val MAX_IMPORT_BYTES = 2_000_000

    /**
     * HMAC-SHA256 hex signer. Set by the Android adapter (security/LessonSigner) at app start;
     * the host test harness sets a deterministic one. Null means "cannot sign" — export then
     * emits an unsigned schema-1 bundle and import of a schema-2 bundle fails closed.
     */
    @Volatile
    var signer: ((String) -> String?)? = null

    /**
     * The mandate's credential patterns, mirrored here so lesson text is held to exactly the same
     * standard as a prompt file. Duplicated rather than imported because `agents/` depends on
     * nothing in `resilience/` and this package must stay pure and host-testable.
     */
    private val secretPatterns = listOf(
        Regex("(?i)\\b(nvapi-[A-Za-z0-9_-]{12,})"),
        Regex("(?i)\\bsk-[A-Za-z0-9]{20,}"),
        Regex("(?i)\\bghp_[A-Za-z0-9]{20,}"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("(?i)\\b(password|passwd|api[_-]?key|secret|token)\\s*[:=]\\s*[^\\s\"']{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    )

    fun looksLikeSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    /** Stable fingerprint for one lesson: same content, same fingerprint, on any install. */
    fun fingerprint(lesson: Lesson): String = ResilienceText.stableKey(
        listOf(
            lesson.signatureKey,
            lesson.failureClass.name,
            lesson.kind.name,
            lesson.target.name,
            lesson.tool,
            lesson.succeeded.toString(),
            lesson.note
        ),
        maxChars = 32
    )

    data class ExportResult(val json: String, val exported: Int, val droppedForSecret: Int, val signed: Boolean)

    /**
     * Build an export bundle from a ledger. Lessons whose note or tool looks like a credential are
     * excluded (and counted), and every note is additionally passed through [ResilienceRedactor].
     *
     * The bundle is signed with the device key when [signer] is set (schema 2); otherwise an
     * unsigned schema-1 bundle is emitted for backward compatibility.
     */
    fun export(ledger: LearningLedger): ExportResult {
        var dropped = 0
        val kept = mutableListOf<Lesson>()
        for (lesson in ledger.lessons()) {
            val haystack = "${lesson.note}\n${lesson.tool}\n${lesson.episodeId}"
            if (looksLikeSecret(haystack)) {
                dropped++
                continue
            }
            kept += lesson.copy(note = ResilienceRedactor.redact(lesson.note, LearningLedger.MAX_NOTE_CHARS))
        }
        val array = kept.takeLast(MAX_EXPORT_LESSONS).map { lesson ->
            linkedMapOf<String, Any?>(
                "k" to lesson.signatureKey,
                "c" to lesson.failureClass.name,
                "m" to lesson.kind.name,
                "t" to lesson.target.name,
                "x" to lesson.tool,
                "s" to lesson.succeeded,
                "n" to lesson.note,
                "at" to lesson.createdAt,
                "e" to lesson.episodeId,
                "fp" to fingerprint(lesson)
            )
        }
        val sign = signer
        return if (sign != null) {
            val payload = linkedMapOf<String, Any?>(
                "schema" to SCHEMA_VERSION,
                "app" to "com.lucifer.mobile",
                "kind" to "lucifer-resilience-lessons",
                "exportedAt" to System.currentTimeMillis(),
                "droppedForSecret" to dropped,
                "lessons" to array
            )
            val canonical = JsonLite.stringify(payload)
            val sig = sign(canonical)
            if (sig != null) {
                ExportResult(JsonLite.stringify(payload + ("sig" to sig)), array.size, dropped, signed = true)
            } else {
                // Could not sign: fall back to unsigned schema 1 rather than emit a broken sig.
                ExportResult(exportSchema1(array, dropped), array.size, dropped, signed = false)
            }
        } else {
            ExportResult(exportSchema1(array, dropped), array.size, dropped, signed = false)
        }
    }

    private fun exportSchema1(array: List<Map<String, Any?>>, dropped: Int): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "schema" to 1,
            "app" to "com.lucifer.mobile",
            "kind" to "lucifer-resilience-lessons",
            "exportedAt" to System.currentTimeMillis(),
            "droppedForSecret" to dropped,
            "lessons" to array
        )
    )

    data class ImportResult(val added: Int, val duplicates: Int, val rejected: Boolean, val reason: String = "")

    /**
     * Merge an import bundle into a ledger. Dedupe is by the bundle's fingerprint when present, else
     * by the recomputed fingerprint. Anything malformed is rejected wholesale (fail closed); a
     * lesson that fails validation individually is skipped and counted in [rejectedLessons].
     *
     * Schema 2 bundles must carry a `sig` that verifies under the device key; a missing or
     * mismatched signature rejects the whole bundle.
     */
    fun importInto(raw: String?, ledger: LearningLedger, now: Long = System.currentTimeMillis()): ImportResult {
        if (raw.isNullOrBlank()) return ImportResult(0, 0, rejected = true, "empty file")
        if (raw.length > MAX_IMPORT_BYTES) return ImportResult(0, 0, rejected = true, "file too large")
        val root = JsonLite.parseObject(raw) ?: return ImportResult(0, 0, rejected = true, "not JSON")
        val schema = root.int("schema", -1)
        if (schema != SCHEMA_VERSION && schema != 1) return ImportResult(0, 0, rejected = true, "unknown schema")
        if (schema == SCHEMA_VERSION) {
            val sig = root.str("sig")
            val sign = signer
            if (sign == null || sig.isBlank()) {
                return ImportResult(0, 0, rejected = true, "unsigned bundle (schema 2 requires sig)")
            }
            val payload = root - "sig"
            val expected = sign(JsonLite.stringify(payload))?.lowercase()
            if (expected == null || expected != sig.lowercase()) {
                return ImportResult(0, 0, rejected = true, "signature mismatch — bundle was edited or not signed by this device")
            }
        }
        if (root.str("kind").ifBlank { "lucifer-resilience-lessons" } != "lucifer-resilience-lessons") {
            return ImportResult(0, 0, rejected = true, "not a lesson bundle")
        }
        val existing = ledger.lessons().map { fingerprint(it) }.toMutableSet()
        var added = 0
        var duplicates = 0
        var rejectedLessons = 0
        for (entry in root.arr("lessons")) {
            val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: continue
            val lesson = runCatching {
                val key = map.str("k")
                if (key.isBlank()) return@runCatching null
                Lesson(
                    signatureKey = key.take(64),
                    failureClass = runCatching { FailureClass.valueOf(map.str("c")) }.getOrNull() ?: FailureClass.UNKNOWN,
                    kind = RecoveryKind.fromName(map.str("m")) ?: return@runCatching null,
                    target = ResilienceTarget.fromName(map.str("t")) ?: ResilienceTarget.LOCAL,
                    tool = map.str("x").take(32),
                    succeeded = map.bool("s"),
                    note = map.str("n").take(LearningLedger.MAX_NOTE_CHARS),
                    createdAt = map.long("at").let { if (it <= 0L) now else it },
                    episodeId = map.str("e").take(40)
                )
            }.getOrNull()
            if (lesson == null) { rejectedLessons++; continue }
            // Reject any imported lesson that carries a credential — the same rule as on export.
            if (looksLikeSecret("${lesson.note}\n${lesson.tool}\n${lesson.episodeId}")) { rejectedLessons++; continue }
            val fp = map.str("fp").take(32).ifBlank { fingerprint(lesson) }
            if (!existing.add(fp)) { duplicates++; continue }
            if (ledger.record(lesson)) added++
        }
        return ImportResult(added, duplicates, rejected = false,
            reason = if (rejectedLessons > 0) "$rejectedLessons lesson(s) skipped (malformed or credential-bearing)" else "")
    }

    /** Deterministic HMAC-SHA256 hex helper for adapters that hold their own key bytes. */
    fun hmacSha256Hex(key: ByteArray, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    /** SHA-256 hex, for tests and fingerprint checks. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
