package com.lucifer.mobile.verification

/**
 * The vault's gates, made checkable.
 *
 * `audits/lean-corpus/` found 3 Critical, 8 High and 11 Medium issues that **kernel soundness never
 * catches**: 96 theorems proving `0 ≤ 0` under grand names, a Kepler equation true only because
 * `M = 0` and `x / 0 = 0`, and two contradictory definitions sharing one name. Every one of those
 * typechecks perfectly. So "does it compile" is the floor, not the audit, and a server that stops
 * there is selling the floor under the name of the building.
 *
 * These checks are **heuristics, not a decision procedure**. Lean's kernel decides truth; nothing here
 * decides meaning. What it does is refuse to let a statement be *silently* worthless: it names the
 * theorem, the line, and the reason a human should look. That is the honest shape for this class of
 * bug, because "does this statement mean what its name claims" is a judgment call, and the value is in
 * putting the judgment in front of the right eyes at the right time.
 *
 * Two rules encoded in the types:
 *
 * - **Nothing here ever says "validated".** The best verdict is `GATES CLEAN`, which means "a human has
 *   not yet looked". Validation is Jacob reproducing it, and it cannot be delegated to a machine we
 *   wrote, let alone to a customer's own key.
 * - **A blocker is a blocker.** `sorry`, `native_decide`, a declared `axiom`, a missing declaration, or
 *   a vacuous statement with an overclaiming name all stop a finding from being called anything better
 *   than `UNVALIDATED`.
 */
object StatementGates {

    enum class Gate(val label: String) {
        PLACEHOLDERS("no sorry / admit / native_decide / axiom"),
        VACUITY("the statement is not vacuous, closed, or degenerate"),
        STATEMENT("the name asserts no more than the statement proves"),
        PARITY("the cited declaration exists where it is claimed")
    }

    enum class Severity { BLOCKER, REVIEW }

    data class Suspect(
        val gate: Gate,
        val severity: Severity,
        val line: Int?,
        val declaration: String?,
        val detail: String
    ) {
        fun render(): String = buildString {
            append(if (severity == Severity.BLOCKER) "× " else "· ")
            append(gate.name.lowercase())
            line?.let { append(" line $it") }
            declaration?.let { append(" `$it`") }
            append(" — ")
            append(detail)
        }
    }

    /** A parsed declaration: enough to judge the statement, not to elaborate it. */
    data class Declaration(
        val line: Int,
        val name: String,
        val binders: String,
        val statement: String,
        val body: String?
    )

    data class Report(
        val suspects: List<Suspect>,
        val declarations: List<String>,
        val projectPlaceholders: Int,
        val parityChecked: List<String>,
        val notes: List<String>
    ) {
        val blockers: Int get() = suspects.count { it.severity == Severity.BLOCKER }
        val reviews: Int get() = suspects.count { it.severity == Severity.REVIEW }

        /**
         * `GATED` is the ceiling. A clean run means the machine found nothing to object to; it is not a
         * review, and the status string says so in the one place a customer will read it.
         */
        val status: String
            get() = when {
                blockers > 0 -> "BLOCKED"
                reviews > 0 -> "REVIEW"
                else -> "GATES CLEAN — human validation still required"
            }

        val findingStatus: String
            get() = when {
                blockers > 0 -> "UNVALIDATED (blocked by gates)"
                reviews > 0 -> "UNVALIDATED (needs review)"
                else -> "GATED — awaiting human validation"
            }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "status" to status,
            "findingStatus" to findingStatus,
            "blockers" to blockers,
            "reviews" to reviews,
            "declarations" to declarations,
            "projectPlaceholders" to projectPlaceholders,
            "parityChecked" to parityChecked,
            "suspects" to suspects.map { suspect ->
                linkedMapOf<String, Any?>(
                    "gate" to suspect.gate.name,
                    "severity" to suspect.severity.name,
                    "line" to suspect.line,
                    "declaration" to suspect.declaration,
                    "detail" to suspect.detail
                )
            },
            "notes" to notes
        )
    }

    // ── entry point ─────────────────────────────────────────────────────────────

    fun report(
        source: String? = null,
        claimed: List<String> = emptyList(),
        projectPlaceholders: Int = 0,
        parityFound: List<String> = emptyList()
    ): Report {
        if (source.isNullOrBlank() && claimed.isEmpty() && projectPlaceholders == 0) {
            return Report(
                suspects = emptyList(),
                declarations = emptyList(),
                projectPlaceholders = 0,
                parityChecked = emptyList(),
                notes = listOf("Nothing to audit: no source and no declarations were supplied.")
            )
        }
        val text = stripComments(source.orEmpty())
        val declarations = parseDeclarations(text)
        val notes = mutableListOf<String>()
        val suspects = mutableListOf<Suspect>()

        // Gate 1 — hidden axioms. Cheap, complete, and the most common way a proof is not a proof.
        suspects += scanPlaceholders(text)
        if (projectPlaceholders > 0) {
            suspects += Suspect(
                Gate.PLACEHOLDERS,
                Severity.BLOCKER,
                null,
                null,
                "the project contains $projectPlaceholders placeholder(s) outside this submission"
            )
        }

        // Gate 2 & 3 — vacuity and overclaiming names, judged per declaration.
        val degenerate = degenerateDefinitions(text)
        declarations.forEach { declaration ->
            suspects += vacuity(declaration, degenerate)
            suspects += overclaim(declaration)
        }
        declarations.forEach { declaration ->
            val overclaiming = suspects.filter {
                it.gate == Gate.STATEMENT && it.declaration == declaration.name
            }
            val vacuous = suspects.filter {
                it.gate == Gate.VACUITY && it.declaration == declaration.name
            }
            if (overclaiming.isNotEmpty() && vacuous.isNotEmpty()) {
                suspects += Suspect(
                    Gate.STATEMENT,
                    Severity.BLOCKER,
                    declaration.line,
                    declaration.name,
                    "this is the corpus pattern: a grand name over a statement that cannot be false. " +
                        "A valid proof of a worthless statement."
                )
            }
        }

        // Contradictory definitions: two declarations sharing one name is the corpus Critical that
        // kernel soundness cannot see, because each is separately well-formed.
        declarations.groupBy { it.name }.filter { it.value.size > 1 }.forEach { (name, group) ->
            val distinct = group
                .map { "${it.binders.trim()}|${it.statement.trim()}|${it.body.orEmpty().trim()}" }
                .distinct()
            suspects += Suspect(
                Gate.STATEMENT,
                if (distinct.size > 1) Severity.BLOCKER else Severity.REVIEW,
                group.first().line,
                name,
                if (distinct.size > 1) {
                    "`$name` is declared ${group.size} times with different statements. Two things " +
                        "share one name, so any theorem citing it proves whatever the reader assumes."
                } else {
                    "`$name` is declared ${group.size} times with the same statement; likely a duplicate"
                }
            )
        }

        // Gate 4 — parity. A finding citing a theorem that is not where it says it is.
        val available = parityFound.toSet()
        claimed.forEach { name ->
            val present = when {
                available.isNotEmpty() -> available.contains(name)
                source != null -> declaresName(text, name)
                else -> false
            }
            if (!present) {
                suspects += Suspect(
                    Gate.PARITY,
                    Severity.BLOCKER,
                    null,
                    name,
                    if (source == null && available.isEmpty()) {
                        "not verified: no host was reached to look for it"
                    } else {
                        "cited, but no `theorem`/`lemma`/`def` of that name is present"
                    }
                )
            }
        }

        if (declarations.isEmpty() && source != null) {
            notes += "No `theorem`/`lemma`/`def` was parsed from the source; gates 2 and 3 had nothing to judge."
        }
        if (declarations.any { it.statement.isBlank() }) {
            notes += "At least one declaration had no statement on its opening lines; vacuity is unjudged there."
        }

        return Report(
            suspects = suspects.distinctBy { "${it.gate}|${it.line}|${it.declaration}|${it.detail}" },
            declarations = declarations.map { it.name }.filter { it.isNotBlank() },
            projectPlaceholders = projectPlaceholders,
            parityChecked = claimed,
            notes = notes
        )
    }

    // ── gate 1: hidden axioms ───────────────────────────────────────────────────

    /**
     * `native_decide` is not a placeholder — it is a proof by trusting the compiler, which is why the
     * corpus gate bans it alongside `sorry`. `admit` and a bare `axiom` declaration are the same
     * category: something asserted rather than proved.
     */
    fun scanPlaceholders(text: String): List<Suspect> {
        val hits = mutableListOf<Suspect>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val word = Regex("\\b(sorry|admit|native_decide|sorryAx)\\b").find(line)
            if (word != null) {
                hits += Suspect(
                    Gate.PLACEHOLDERS,
                    Severity.BLOCKER,
                    index + 1,
                    null,
                    "`${word.value}` — the proof is not complete; gate 1 requires zero"
                )
            } else if (Regex("^\\s*(?:private\\s+|protected\\s+)?axiom\\s+([A-Za-z0-9_'.]+)").containsMatchIn(line)) {
                hits += Suspect(
                    Gate.PLACEHOLDERS,
                    Severity.BLOCKER,
                    index + 1,
                    Regex("axiom\\s+([A-Za-z0-9_'.]+)").find(line)?.groupValues?.get(1),
                    "an `axiom` declaration is an assumption wearing a theorem's clothes"
                )
            }
        }
        return hits
    }

    // ── gate 2: vacuity and degenerate models ─────────────────────────────────

    fun vacuity(declaration: Declaration, degenerate: Map<String, String> = emptyMap()): List<Suspect> {
        val suspects = mutableListOf<Suspect>()
        val statement = declaration.statement.trim()
        if (statement.isEmpty()) return suspects

        val compact = statement.replace(Regex("\\s+"), "")
        val literalTrue = compact == "True" || compact == "true"
        val reflexive = if (literalTrue) null else Regex("^([A-Za-z_][A-Za-z0-9_'.]*)=\\1$").find(compact)

        // Closed statements: no variable can appear, so nothing about a model is claimed.
        if (!literalTrue && reflexive == null && isClosed(compact)) {
            suspects += Suspect(
                Gate.VACUITY,
                Severity.REVIEW,
                declaration.line,
                declaration.name,
                "`$statement` contains no variables, so it is a numerical fact rather than a claim " +
                    "about the system. This is the `0 ≤ 0` pattern."
            )
        }
        if (literalTrue) {
            suspects += Suspect(
                Gate.VACUITY,
                Severity.BLOCKER,
                declaration.line,
                declaration.name,
                "the statement is literally `True`; it cannot be false, so proving it says nothing"
            )
        }
        if (reflexive != null) {
            suspects += Suspect(
                Gate.VACUITY,
                Severity.BLOCKER,
                declaration.line,
                declaration.name,
                "`$statement` is true by reflexivity for every value; it cannot constrain anything"
            )
        }

        // Unsatisfiable hypotheses: the statement is vacuously true and says nothing.
        val impossible = Regex(
            ":\\s*(?:False\\b|~\\(?0\\s*=\\s*0\\)?|¬\\(?0\\s*=\\s*0\\)?|Not\\s*\\(?0\\s*=\\s*0\\)?|1\\s*=\\s*0\\b|0\\s*=\\s*1\\b)"
        ).find(declaration.binders)
        if (impossible != null) {
            suspects += Suspect(
                Gate.VACUITY,
                Severity.BLOCKER,
                declaration.line,
                declaration.name,
                "a hypothesis `${impossible.value.trim()}` can never hold, so the theorem is vacuously " +
                    "true and constrains nothing"
            )
        }

        // Division by a literal zero: Lean defines x / 0 = 0, so an equation can typecheck as 0 = 0
        // while its physical reading is false. This is the Kepler case.
        if (Regex("/\\s*\\(?\\s*0(?![0-9.])").containsMatchIn(statement) || statement.contains("/ 0)")) {
            suspects += Suspect(
                Gate.VACUITY,
                Severity.BLOCKER,
                declaration.line,
                declaration.name,
                "divides by a literal zero; Lean defines `x / 0 = 0`, so the equation can be true " +
                    "while its reading is false"
            )
        }

        // Degenerate definitions: a quantity fixed at 0 (or Unit) makes every theorem about it vacuous
        // at every parameter — `M = 0` in the corpus.
        degenerate.forEach { (name, body) ->
            if (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(statement) && mentionsModel(statement)) {
                suspects += Suspect(
                    Gate.VACUITY,
                    Severity.REVIEW,
                    declaration.line,
                    declaration.name,
                    "uses `$name`, defined as `$body`; at that value the statement may be true for " +
                        "every parameter. Check the non-degenerate instantiation."
                )
            }
        }
        return suspects
    }

    // ── gate 3: names that assert more than the statement ────────────────────

    private val claimWords = listOf(
        "everything", "theoryofeverything", "universal", "unique", "complete", "exact",
        "global", "master", "perfect", "absolute", "fundamental", "guarantee"
    )

    fun overclaim(declaration: Declaration): List<Suspect> {
        val suspects = mutableListOf<Suspect>()
        val flat = declaration.name.lowercase().replace("_", "")
        val claimed = claimWords.filter { flat.contains(it) }
        if (claimed.isNotEmpty()) {
            val weak = isClosed(declaration.statement.replace(Regex("\\s+"), "")) ||
                declaration.statement.trim() == "True"
            suspects += Suspect(
                Gate.STATEMENT,
                if (weak) Severity.BLOCKER else Severity.REVIEW,
                declaration.line,
                declaration.name,
                "the name claims `${claimed.joinToString("`, `")}` but the statement is " +
                    if (weak) {
                        "a closed numerical fact. Rename it or prove the real content."
                    } else {
                        "ordinary. Confirm the name does not overreach the statement."
                    }
            )
        }
        // The `_Stmt` evasion named in the corpus audit: an alias whose name is not the name a
        // finding cites, used to slip past name-based matching.
        if (Regex("_stmt$|_statement$", RegexOption.IGNORE_CASE).containsMatchIn(declaration.name)) {
            suspects += Suspect(
                Gate.STATEMENT,
                Severity.REVIEW,
                declaration.line,
                declaration.name,
                "the name carries a `_Stmt`/`_Statement` suffix; resolve the alias before citing it, " +
                    "or a claim can point at a name that only exists here"
            )
        }
        return suspects
    }

    // ── gate 4: declaration parity ───────────────────────────────────────────

    fun declaresName(text: String, name: String): Boolean =
        Regex("^\\s*(?:@\\[[^]]*]\\s*)*(?:private\\s+|protected\\s+|noncomputable\\s+)?(?:theorem|lemma|def|abbrev|example)\\s+${Regex.escape(name)}\\b", RegexOption.MULTILINE)
            .containsMatchIn(text)

    // ── parsing ──────────────────────────────────────────────────────────────

    /**
     * Lifts the declarations a human can judge. Multi-line statements are joined until `:=` or a blank
     * line, which is enough for the shape these audits take and cheap enough to run on every call.
     */
    fun parseDeclarations(text: String): List<Declaration> {
        val lines = text.lines()
        val declarations = mutableListOf<Declaration>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val head = Regex(
                "^\\s*(?:@\\[[^]]*]\\s*)*(?:private\\s+|protected\\s+|noncomputable\\s+)?(theorem|lemma|def|abbrev|example)\\s+([A-Za-z_][A-Za-z0-9_'.]*)"
            ).find(line)
            if (head == null) {
                index++
                continue
            }
            val name = head.groupValues[2]
            val afterName = line.substring(head.range.last + 1)
            val collected = StringBuilder(afterName)
            var cursor = index
            while (cursor + 1 < lines.size && !hasTopLevelAssign(collected.toString()) && cursor - index < 12) {
                cursor++
                collected.append(' ').append(lines[cursor].trim())
            }
            val joined = collected.toString()
            val (binders, statement, body) = splitDeclaration(joined)
            declarations += Declaration(index + 1, name, binders, statement, body)
            index = cursor + 1
        }
        return declarations
    }

    private fun splitDeclaration(joined: String): Triple<String, String, String?> {
        val colon = topLevelColon(joined)
        if (colon < 0) return Triple("", joined.trim().take(300), null)
        val binders = joined.substring(0, colon).trim()
        val rest = joined.substring(colon + 1)
        val assign = indexOfTopLevel(rest, ":=")
        return if (assign >= 0) {
            Triple(binders, rest.substring(0, assign).trim(), rest.substring(assign + 2).trim().take(300))
        } else {
            Triple(binders, rest.trim().take(400), null)
        }
    }

    private fun hasTopLevelAssign(text: String): Boolean = indexOfTopLevel(text, ":=") >= 0

    private fun topLevelColon(text: String): Int = indexOfTopLevel(text, ":")

    /** Finds a token at bracket depth zero, so `(f : Nat) : P` splits at the second colon. */
    private fun indexOfTopLevel(text: String, token: String): Int {
        var depth = 0
        var index = 0
        while (index < text.length - token.length + 1) {
            val character = text[index]
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
            if (depth == 0 && text.startsWith(token, index)) {
                if (token == ":" && index + 1 < text.length && text[index + 1] == ':') {
                    index++
                    continue
                }
                return index
            }
            index++
        }
        return -1
    }

    /** Definitions whose value is a constant that can make a whole model degenerate. */
    fun degenerateDefinitions(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val match = Regex(
                "^\\s*(?:private\\s+|noncomputable\\s+)?(?:def|abbrev)\\s+([A-Za-z_][A-Za-z0-9_'.]*)[^=]*:=\\s*(Unit|PUnit|Fin 0|Fin 1|Empty|⊥|False|false|0|0\\.0)\\s*$"
            ).find(line)
            if (match != null) result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    /** A statement with no identifier left after removing numerals and operators has no variables. */
    private fun isClosed(compact: String): Boolean {
        if (compact.isEmpty()) return false
        var remainder = compact
        DECLARATION_WORDS.forEach { remainder = remainder.replace(it, "") }
        remainder = remainder.replace(Regex("[0-9+\\-*/^≤≥<>=≠∧∨¬()\\[\\]{}:.,'\\s%|]"), "")
        remainder = remainder.replace(Regex("(?i)(true|false)"), "")
        return remainder.isEmpty()
    }

    /** True when a statement visibly talks about a modelled quantity rather than a bare number. */
    private fun mentionsModel(statement: String): Boolean =
        Regex("[A-Za-z]").containsMatchIn(statement) && !isClosed(statement.replace(Regex("\\s+"), ""))

    /** Removes `--` comments and `/- … -/` blocks, so prose cannot trip a gate. */
    fun stripComments(source: String): String {
        val out = StringBuilder()
        var index = 0
        var blockDepth = 0
        while (index < source.length) {
            if (blockDepth > 0) {
                when {
                    source.startsWith("/-", index) -> { blockDepth++; index += 2 }
                    source.startsWith("-/", index) -> { blockDepth--; index += 2 }
                    else -> index++
                }
                continue
            }
            when {
                source.startsWith("/-", index) -> { blockDepth = 1; index += 2 }
                source.startsWith("--", index) -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                else -> { out.append(source[index]); index++ }
            }
        }
        return out.toString()
    }

    private val DECLARATION_WORDS = listOf("Nat.", "Int.", "Float.", "Rat.", "Real.", "Prop.")
}
