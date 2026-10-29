package com.lucifer.mobile.omni_bridge.knowledge

import android.content.Context
import com.lucifer.mobile.omni_bridge.four_horsemen.FourHorsemenOrchestrator
import com.lucifer.mobile.memory.LocalConversationMemory
import com.lucifer.mobile.surface.Surface
import com.lucifer.mobile.surface.SurfaceRuntime

/**
 * ToEReasoningEngine — Lucifer's super-reasoning loop.
 *
 * PERCEIVE → UNDERSTAND → DECIDE → ACT, grounded in the Omega ToE.
 *
 * The corpus now lives INSIDE Lucifer (EmbeddedToE — APK assets + optional
 * GitHub-synced overlays). It is always available, offline, with zero moving
 * parts. The external MCP server (OmegaKnowledgeClient) is an optional
 * enrichment: if it happens to be up, we also consult it; if not, the
 * embedded corpus carries the reasoning alone. He is never "dark".
 */
class ToEReasoningEngine(
    context: Context,
    private val horsemen: FourHorsemenOrchestrator = FourHorsemenOrchestrator(context)
) {

    /** Kept for surface-awareness reads. */
    private val appContext: Context = context.applicationContext

    private val embedded = EmbeddedToE(context)
    private val remote = OmegaKnowledgeClient()
    private val localMemory = LocalConversationMemory(context.applicationContext)

    init {
        // Warm the embedded index at startup — instant intuition, no I/O wait later.
        Thread {
            try {
                embedded.warmUp()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** The knowledge is embedded — always alive. */
    fun knowledgeServerAlive(): Boolean {
        warmUpIfNeeded()
        return embedded.ready
    }

    private fun warmUpIfNeeded() {
        if (!embedded.ready) {
            try {
                embedded.warmUp()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The full reasoning pass for a chat message.
     * Corpus evidence comes from the embedded ToE; optional remote enrichment.
     */
    /**
     * Answer [input].
     *
     * @param surface the interface the user is actually speaking through. Capability questions about
     *   that interface are answered deterministically here — no model, no guessing — and every other
     *   turn carries a surface block so "here" means something. Callers must pass their real surface;
     *   the [Surface.UNKNOWN] default reports an unnamed surface rather than inventing a launcher.
     */
    suspend fun reason(input: String, surface: Surface = Surface.UNKNOWN): String {
        SurfaceRuntime.answerIfAboutSurface(appContext, input, surface)?.let { return it }
        warmUpIfNeeded()

        // 1. UNDERSTAND: consult the corpus on the key concepts.
        val keywords = extractKeywords(input)
        val evidence = StringBuilder()
        for (kw in keywords.take(2)) {
            val hits = embedded.search(kw, 4)
            for (h in hits.take(3)) {
                evidence.append(h).append("\n")
            }
        }

        // Optional remote enrichment (ignored if the MCP server is dark).
        // These are suspend calls in a suspend function: `runBlocking` here parked the caller's thread
        // behind two network round-trips instead of suspending it.
        try {
            val remoteAlive = remote.isAlive()
            if (remoteAlive) {
                val rhits = remote.search(keywords.firstOrNull() ?: "", 4)
                if (!rhits.isNullOrBlank()) {
                    evidence.append(rhits.take(400)).append("\n")
                }
            }
        } catch (_: Exception) {
        }

        // 2. DECIDE + ACT: synthesize with the Four Horsemen over ToE evidence.
        val deliberation = horsemen.deliberate(input, evidence.toString(), surface)
        val offlineMemory = if (deliberation.startsWith("Council offline")) {
            try { localMemory.search(input) } catch (_: Exception) { "" }
        } else ""
        if (offlineMemory.isNotBlank()) {
            return "$deliberation\n\n$offlineMemory"
        }

        return if (evidence.isNotBlank()) {
            val citationList = evidence.lines()
                .filter { it.startsWith("[Vol") || it.startsWith("[Omega") || it.startsWith("[ToE") }
                .take(3)
                .map { it.substringBefore("]").removePrefix("[") }
            val cites = citationList.joinToString("; ")
            val grounding = if (cites.isNotBlank()) " Grounding: $cites." else ""
            "By the ToE: $deliberation.$grounding"
        } else {
            deliberation
        }
    }

    /** Direct corpus access for the chat UI when the user asks for a file/proof. */
    suspend fun readFile(name: String): String? {
        warmUpIfNeeded()
        return embedded.read(name) ?: remote.read(name)
    }

    suspend fun axioms(): Map<String, String> {
        warmUpIfNeeded()
        return embedded.axioms()
    }

    /** Pull the latest ToE knowledge from GitHub into his live overlay. */
    suspend fun syncFromGithub(): String {
        warmUpIfNeeded()
        return embedded.syncFromGithub()
    }

    /** Kept for Settings compatibility — embedded knowledge has no URL. */
    fun updateBaseUrl(url: String) {
        remote.baseUrl = url.trim().trimEnd('/')
    }

    fun stats(): Map<String, Any> {
        warmUpIfNeeded()
        return embedded.stats()
    }

    /** Extract 1-3 likely search keywords from a natural-language question. */
    private fun extractKeywords(input: String): List<String> {
        val words = input.lowercase()
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.length in 4..18 }
            .filter { it !in STOPWORDS }
        val domainWords = words.filter { it in DOMAIN_HINTS }
        return (domainWords + words).distinct().take(3)
    }

    companion object {
        // Compiled once. extractKeywords runs on every knowledge question, and a fresh Regex per call
        // re-parses two patterns to discard them immediately.
        private val NON_WORD = Regex("[^a-z0-9\\s]")
        private val WHITESPACE = Regex("\\s+")

        private val STOPWORDS = setOf(
            "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
            "the", "that", "this", "then", "than", "them", "they", "there", "here",
            "about", "into", "from", "with", "have", "been", "were", "will", "would",
            "could", "should", "does", "doing", "your", "yours", "mine", "lucifer",
            "tell", "explain", "describe", "please", "can", "you", "are", "and",
            "for", "but", "not", "was", "its", "it's", "has", "had", "him", "his"
        )
        private val DOMAIN_HINTS = setOf(
            "entropy", "energy", "gravity", "quantum", "spacetime", "emergence",
            "information", "holographic", "black", "hole", "thermodynamics",
            "time", "arrow", "multiverse", "economics", "network", "evolution",
            "computation", "compiler", "ctc", "timelike", "curves", "dimension",
            "axiom", "proof", "theorem", "lean", "omega", "nothing", "void",
            "entanglement", "epr", "measurement", "collapse", "cosmology",
            "expansion", "dark", "sector", "societal", "morphogenesis", "fractal",
            "scale", "conservation", "symmetry", "field", "particle", "state"
        )
    }
}
