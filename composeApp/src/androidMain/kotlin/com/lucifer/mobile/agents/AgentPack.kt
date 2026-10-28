package com.lucifer.mobile.agents

/**
 * An agent class is one Markdown file with a small front-matter header, so the user can edit it in
 * any text editor and Lucifer can create one from a proposal.
 *
 * The parser is deliberately forgiving about unknown keys and strict about limits: a malformed file
 * becomes a rejected entry with a reason, never a crash and never a silent behaviour change.
 */
enum class AgentRole(val label: String) {
    GENERAL("general"),
    SCOUT("scout"),
    RESEARCHER("researcher"),
    BUILDER("builder"),
    ANALYST("analyst"),
    OPERATOR("operator"),
    CUSTOM("custom");

    companion object {
        fun from(raw: String?): AgentRole =
            entries.firstOrNull { it.label.equals(raw?.trim(), ignoreCase = true) } ?: CUSTOM
    }
}

data class AgentPack(
    val id: String,
    val name: String,
    val role: AgentRole,
    val author: Author,
    val enabled: Boolean,
    val version: Int,
    val skills: List<String>,
    val targets: List<String>,
    val triggers: List<String>,
    val mandate: String,
    val updatedAt: Long = 0L,
    /** Populated when this pack came from a file Lucifer could not fully trust. */
    val problem: String? = null
) {
    val isUsable: Boolean get() = problem == null && id.isNotBlank() && mandate.length >= PromptRules.MIN_BODY_CHARS

    val oneLine: String
        get() = "$name · ${role.label} · ${if (enabled) "enabled" else "disabled"} · by ${author.name.lowercase()}"

    /** Skills and triggers are advisory hints for routing, never tool grants. */
    fun matches(text: String): Int {
        val lower = text.lowercase()
        var score = 0
        for (trigger in triggers) {
            if (trigger.length >= 3 && lower.contains(trigger.lowercase())) score += 4
        }
        for (skill in skills) {
            if (skill.length >= 3 && lower.contains(skill.lowercase())) score += 2
        }
        if (lower.contains(name.lowercase())) score += 5
        if (role != AgentRole.CUSTOM && lower.contains(role.label)) score += 3
        return score
    }
}

object AgentClassFile {

    val TARGETS = setOf("LOCAL", "TERMUX", "VM", "SHIZUKU")
    private const val MAX_NAME = 80
    private const val MAX_LIST_ITEMS = 12
    private const val MAX_ITEM_CHARS = 40

    data class Parsed(val header: Map<String, String>, val body: String)

    fun parse(raw: String): Parsed {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        if (!text.startsWith("---")) return Parsed(emptyMap(), text.trim())
        val lines = text.lines()
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return Parsed(emptyMap(), text.trim())
        val header = LinkedHashMap<String, String>()
        for (line in lines.subList(1, endIndex + 1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val split = trimmed.indexOf(':')
            if (split <= 0) continue
            val key = trimmed.substring(0, split).trim().lowercase()
            val value = trimmed.substring(split + 1).trim()
            header[key] = value
        }
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()
        return Parsed(header, body)
    }

    /**
     * Parse a file into a pack. [fallbackId] comes from the filename, so a file without front matter
     * is still usable instead of being silently ignored.
     */
    fun decode(raw: String, fallbackId: String, now: Long = 0L): AgentPack {
        val parsed = parse(raw)
        val header = parsed.header
        val id = header["id"]?.let(PromptRules::slugify)?.takeIf(PromptRules::isValidId)
            ?: PromptRules.slugify(fallbackId)
        val name = header["name"]?.take(MAX_NAME)?.takeIf { it.isNotBlank() } ?: id.replace('-', ' ')
        val role = AgentRole.from(header["role"])
        val author = when (header["created_by"]?.trim()?.lowercase()) {
            "user" -> Author.USER
            "lucifer" -> Author.LUCIFER
            else -> Author.SYSTEM
        }
        val enabled = header["enabled"]?.trim()?.lowercase()?.let { it != "false" && it != "no" && it != "0" } ?: true
        val version = header["version"]?.trim()?.toIntOrNull()?.coerceIn(0, 999) ?: 1
        val skills = splitList(header["skills"])
        val triggers = splitList(header["triggers"])
        val targets = splitList(header["targets"]).map { it.uppercase() }.filter { it in TARGETS }
        val mandate = parsed.body.trim().let { body ->
            if (body.isNotBlank()) body else parsed.header["mandate"]?.trim().orEmpty()
        }
        val problem = when {
            !PromptRules.isValidId(id) -> "invalid agent id '$id'"
            mandate.length < PromptRules.MIN_BODY_CHARS -> "mandate is missing or too short"
            mandate.length > PromptRules.MAX_AGENT_CHARS -> "mandate exceeds ${PromptRules.MAX_AGENT_CHARS} characters"
            PromptRules.looksLikeSecret(mandate) -> "mandate appears to contain a credential"
            else -> null
        }
        return AgentPack(
            id = id,
            name = name,
            role = role,
            author = author,
            enabled = enabled,
            version = version,
            skills = skills,
            targets = targets,
            triggers = triggers,
            mandate = mandate.take(PromptRules.MAX_AGENT_CHARS),
            updatedAt = now,
            problem = problem
        )
    }

    /** Round-trip a pack back to an editable file. Unknown extra keys are not preserved. */
    fun encode(pack: AgentPack): String = buildString {
        append("---\n")
        append("id: ").append(pack.id).append('\n')
        append("name: ").append(pack.name.replace('\n', ' ').take(MAX_NAME)).append('\n')
        append("role: ").append(pack.role.label).append('\n')
        append("created_by: ").append(pack.author.name.lowercase()).append('\n')
        append("enabled: ").append(if (pack.enabled) "true" else "false").append('\n')
        append("version: ").append(pack.version).append('\n')
        append("skills: ").append(pack.skills.joinToString(", ")).append('\n')
        append("targets: ").append(pack.targets.joinToString(",")).append('\n')
        append("triggers: ").append(pack.triggers.joinToString(", ")).append('\n')
        append("---\n")
        append(pack.mandate.trim())
        append('\n')
    }

    private fun splitList(raw: String?): List<String> = raw.orEmpty()
        .split(',', ';', '|')
        .map { it.trim().trim('"', '\'') }
        .filter { it.isNotBlank() }
        .map { it.take(MAX_ITEM_CHARS) }
        .distinct()
        .take(MAX_LIST_ITEMS)
}

/** A routing decision: which agent class should handle a request, and why. */
data class AgentSelection(val pack: AgentPack?, val score: Int, val reason: String)

/**
 * The ecosystem roster. Files are the source of truth; this object only answers "who handles this",
 * "what is enabled", and "what is broken".
 */
class AgentRegistry(val packs: List<AgentPack>) {

    val usable: List<AgentPack> get() = packs.filter { it.isUsable }
    val enabled: List<AgentPack> get() = usable.filter { it.enabled }
    val problems: List<AgentPack> get() = packs.filter { !it.isUsable }

    fun byId(id: String): AgentPack? = packs.firstOrNull { it.id == id }

    fun general(): AgentPack? = byId(PromptDefaults.GENERAL_AGENT_ID).takeIf { it?.isUsable == true }

    /** The agent a bare request should use: the General when present, otherwise the first enabled. */
    fun fallback(): AgentPack? = general()?.takeIf { it.enabled } ?: enabled.firstOrNull()

    /**
     * Pick the best agent for a request. Ties prefer the General, then a stable id order, so the
     * same request always routes the same way.
     */
    fun select(text: String, explicitId: String? = null): AgentSelection {
        explicitId?.let { requested ->
            val match = byId(requested)
            return when {
                match == null -> AgentSelection(null, 0, "No agent with id '$requested'.")
                !match.isUsable -> AgentSelection(null, 0, "Agent '${match.name}' cannot be used: ${match.problem}.")
                !match.enabled -> AgentSelection(null, 0, "Agent '${match.name}' is disabled.")
                else -> AgentSelection(match, 100, "Explicitly requested.")
            }
        }
        if (enabled.isEmpty()) return AgentSelection(null, 0, "No usable agent classes are enabled.")
        val scored = enabled.map { pack -> pack to pack.matches(text) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<AgentPack, Int>> { it.second }
                    .thenByDescending { it.first.role == AgentRole.GENERAL }
                    .thenBy { it.first.id }
            )
        val best = scored.firstOrNull()
        return when {
            best == null -> fallback()?.let { AgentSelection(it, 0, "No agent trigger matched; using the default agent.") }
                ?: AgentSelection(null, 0, "No usable agent classes are enabled.")
            else -> AgentSelection(best.first, best.second, "Matched its name, role, skills, or triggers.")
        }
    }

    /** Compact roster text for prompts and the panel. */
    fun rosterText(maxChars: Int = 2_400): String {
        if (packs.isEmpty()) return "No agent classes exist yet."
        return buildString {
            append("Agent classes (${enabled.size} enabled / ${packs.size} total):\n")
            for (pack in packs.sortedBy { it.id }) {
                append("- ").append(pack.id).append(" · ").append(pack.name)
                    .append(" · ").append(pack.role.label)
                    .append(if (pack.enabled) "" else " · DISABLED")
                if (!pack.isUsable) append(" · BROKEN: ").append(pack.problem)
                if (pack.skills.isNotEmpty()) append(" · skills: ").append(pack.skills.joinToString("/"))
                if (pack.triggers.isNotEmpty()) append(" · triggers: ").append(pack.triggers.joinToString("/"))
                append('\n')
            }
        }.trim().take(maxChars)
    }

    /** Detect overlap so the General can recommend merging rather than growing the roster. */
    fun overlappingPairs(): List<Pair<AgentPack, AgentPack>> {
        val list = enabled
        val out = mutableListOf<Pair<AgentPack, AgentPack>>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                val shared = a.triggers.map(String::lowercase).intersect(b.triggers.map(String::lowercase).toSet())
                val sharedSkills = a.skills.map(String::lowercase).intersect(b.skills.map(String::lowercase).toSet())
                if (shared.size >= 2 || sharedSkills.size >= 3) out.add(a to b)
            }
        }
        return out
    }

    companion object {
        /**
         * Build a registry from raw files. [generalDefault] is written only when no General exists,
         * which is what makes the built-in tactician appear on first run without overwriting edits.
         */
        fun fromFiles(
            files: Map<String, String>,
            now: Long = System.currentTimeMillis()
        ): AgentRegistry {
            val packs = files.map { (id, raw) -> AgentClassFile.decode(raw, id, now) }
                .sortedBy { it.id }
                .take(PromptRules.MAX_AGENTS)
            val deduped = packs.distinctBy { it.id }
            return AgentRegistry(deduped)
        }
    }
}
