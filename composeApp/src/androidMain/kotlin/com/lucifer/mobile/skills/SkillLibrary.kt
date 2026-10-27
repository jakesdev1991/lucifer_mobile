package com.lucifer.mobile.skills

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Readiness is deliberately separate from the skill title: reference knowledge is never a tool. */
data class SkillPack(
    val id: String,
    val title: String,
    val readiness: String,
    val prerequisites: String,
    val safetyBoundary: String,
    val verification: String,
    val references: List<String>
)

data class ReferenceSkill(
    val id: String,
    val packId: String,
    val name: String,
    val summary: String,
    val readiness: String,
    val keywords: List<String>
)

/**
 * Versioned, immutable asset catalog. It fails closed on malformed or oversized data and
 * provides bounded retrieval for model prompts; entries are advisory reference material only.
 */
class SkillLibrary private constructor(
    val schemaVersion: Int,
    val packs: List<SkillPack>,
    val skills: List<ReferenceSkill>,
    val loadError: String? = null
) {
    private val packIndex = packs.associateBy { it.id }

    val isAvailable: Boolean get() = loadError == null && skills.isNotEmpty()
    val statusSummary: String
        get() = if (isAvailable) {
            "Skill library v$schemaVersion · ${skills.size} reference skills · ${packs.size} packs · guidance only"
        } else {
            "Reference skill library unavailable · ${loadError ?: "no entries loaded"}"
        }

    fun packFor(skill: ReferenceSkill): SkillPack? = packIndex[skill.packId]

    fun search(query: String, limit: Int = 20): List<ReferenceSkill> {
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()
        if (terms.isEmpty()) return skills.take(limit.coerceIn(1, MAX_SEARCH_RESULTS))

        return skills.mapNotNull { skill ->
            val pack = packIndex[skill.packId] ?: return@mapNotNull null
            val name = skill.name.lowercase()
            val summary = skill.summary.lowercase()
            val category = pack.title.lowercase()
            val keywords = skill.keywords.map(String::lowercase)
            val score = terms.sumOf { term ->
                when {
                    name == term -> 8
                    name.contains(term) -> 5
                    keywords.any { it == term || it.contains(term) } -> 4
                    category.contains(term) -> 2
                    summary.contains(term) -> 1
                    else -> 0
                }
            }
            if (score > 0) skill to score else null
        }
            .sortedWith(compareByDescending<Pair<ReferenceSkill, Int>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
            .map { it.first }
    }

    /** Compact, status-aware evidence for one LLM call; never treats reference entries as tools. */
    fun promptContext(query: String, maxEntries: Int = 3, maxChars: Int = DEFAULT_PROMPT_CHARS): String {
        val hits = search(query, maxEntries.coerceIn(1, MAX_PROMPT_ENTRIES))
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("Relevant entries from Lucifer's reference skill library (guidance only, not executable tools):")
            appendLine("Check the active capability registry before claiming Lucifer can perform an action; respect each entry's prerequisites and safety boundary.")
            val sourcesIncluded = mutableSetOf<String>()
            for (skill in hits) {
                val pack = packFor(skill) ?: continue
                appendLine("- ${skill.name} [${skill.readiness}]: ${skill.summary}")
                appendLine("  Prerequisite: ${pack.prerequisites}")
                appendLine("  Boundary: ${pack.safetyBoundary}")
                appendLine("  Evidence: ${pack.verification}")
                if (sourcesIncluded.add(pack.id)) appendLine("  Sources: ${pack.references.take(2).joinToString()}")
            }
        }.trim().take(maxChars.coerceIn(300, MAX_PROMPT_CHARS))
    }

    companion object {
        private const val ASSET_PATH = "skills/skill_library.json"
        private const val MAX_ASSET_BYTES = 256 * 1024
        private const val MAX_PACKS = 24
        private const val MAX_SKILLS = 200
        private const val MAX_SEARCH_RESULTS = 50
        private const val MAX_PROMPT_ENTRIES = 5
        private const val DEFAULT_PROMPT_CHARS = 2_200
        private const val MAX_PROMPT_CHARS = 3_200
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_]{1,63}$")

        @Volatile private var cached: SkillLibrary? = null

        @Synchronized
        fun load(assets: AssetManager): SkillLibrary {
            cached?.let { return it }
            val loaded = try {
                val bytes = assets.open(ASSET_PATH).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ASSET_BYTES) { "Skill library asset exceeds its size limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                decode(String(bytes, StandardCharsets.UTF_8))
            } catch (error: Exception) {
                SkillLibrary(0, emptyList(), emptyList(), error.message?.take(180) ?: "asset validation failed")
            }
            cached = loaded
            return loaded
        }

        private fun decode(raw: String): SkillLibrary {
            require(raw.isNotBlank() && raw.length <= MAX_ASSET_BYTES) { "Skill library is empty or oversized." }
            val root = JSONObject(raw)
            val version = root.getInt("schemaVersion")
            require(version == 1) { "Unsupported skill library schema $version." }
            bounded(root.getString("library"), 100, "library title")
            val packArray = root.getJSONArray("packs")
            require(packArray.length() in 1..MAX_PACKS) { "Invalid skill pack count." }
            val packs = (0 until packArray.length()).map { index -> parsePack(packArray.getJSONObject(index)) }
            require(packs.map { it.id }.distinct().size == packs.size) { "Duplicate skill pack id." }

            val skillArray = root.getJSONArray("skills")
            require(skillArray.length() in 1..MAX_SKILLS) { "Invalid skill count." }
            val skills = (0 until skillArray.length()).map { index -> parseSkill(skillArray.getJSONObject(index)) }
            require(skills.map { it.id }.distinct().size == skills.size) { "Duplicate skill id." }
            val packIds = packs.map { it.id }.toSet()
            require(skills.all { it.packId in packIds }) { "Skill references an unknown pack." }
            require(packs.all { it.readiness == "REFERENCE_ONLY" } && skills.all { it.readiness == "REFERENCE_ONLY" }) {
                "Catalog entries must remain explicitly reference-only; tool readiness belongs in the live registry."
            }
            return SkillLibrary(version, packs, skills)
        }

        private fun parsePack(json: JSONObject): SkillPack {
            val id = bounded(json.getString("id"), 64, "pack id")
            require(ID_PATTERN.matches(id)) { "Invalid pack id." }
            val referencesJson = json.optJSONArray("references") ?: JSONArray()
            require(referencesJson.length() in 1..8) { "Each pack needs bounded source references." }
            val references = (0 until referencesJson.length()).map { index ->
                val reference = bounded(referencesJson.getString(index), 240, "reference")
                require(reference.startsWith("https://")) { "Reference must be an HTTPS source link." }
                reference
            }
            return SkillPack(
                id = id,
                title = bounded(json.getString("title"), 80, "pack title"),
                readiness = bounded(json.getString("readiness"), 32, "pack readiness"),
                prerequisites = bounded(json.getString("prerequisites"), 600, "pack prerequisites"),
                safetyBoundary = bounded(json.getString("safetyBoundary"), 600, "pack safety boundary"),
                verification = bounded(json.getString("verification"), 600, "pack verification"),
                references = references
            )
        }

        private fun parseSkill(json: JSONObject): ReferenceSkill {
            val id = bounded(json.getString("id"), 64, "skill id")
            require(ID_PATTERN.matches(id)) { "Invalid skill id." }
            val keywordsJson = json.getJSONArray("keywords")
            require(keywordsJson.length() in 1..16) { "Invalid skill keyword count." }
            val keywords = (0 until keywordsJson.length()).map { index ->
                bounded(keywordsJson.getString(index), 48, "keyword").lowercase()
            }
            return ReferenceSkill(
                id = id,
                packId = bounded(json.getString("packId"), 64, "pack id"),
                name = bounded(json.getString("name"), 100, "skill name"),
                summary = bounded(json.getString("summary"), 360, "skill summary"),
                readiness = bounded(json.getString("readiness"), 32, "skill readiness"),
                keywords = keywords
            )
        }

        private fun bounded(value: String, max: Int, label: String): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty() && normalized.length <= max) { "Invalid $label length." }
            return normalized
        }
    }
}
