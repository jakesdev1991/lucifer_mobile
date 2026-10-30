package com.lucifer.mobile.omni_bridge.knowledge

import android.content.Context
import android.util.Log
import com.lucifer.mobile.privacy.EgressClass
import com.lucifer.mobile.privacy.EgressHttp
import com.lucifer.mobile.privacy.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * EmbeddedToE — the Omega Theory of Everything, inside Lucifer himself.
 *
 * The corpus (54 Lean 4 proof volumes, OmegaAxioms/Protocol/DimensionalHierarchy/
 * UnifiedFoundation, txt proofs, technical docs, full book, plus his super-reasoning
 * system prompt) ships INSIDE the APK at assets/toe/. No server, no SSH, no VM,
 * no Termux, no network needed — his common sense is always present, offline.
 *
 * GitHub link: jakesdev1991/Omega_Theory_Everything is his persistent source of
 * truth. syncFromGithub() pulls the latest volume files over HTTPS (GitHub raw
 * API, no auth needed for the public repo) and overlays them into files/toe_live/,
 * which takes precedence over the bundled assets. New pushes = new intuitions.
 */
class EmbeddedToE(private val context: Context) {

    /** One user action = one authorization. The switch is global, so the first check covers the loop. */

    companion object {
        private const val TAG = "EmbeddedToE"
        private const val ASSET_DIR = "toe"
        private const val LIVE_DIR = "toe_live"
        private const val GITHUB_RAW = "https://raw.githubusercontent.com/jakesdev1991/Omega_Theory_Everything/main"
        private const val GITHUB_API = "https://api.github.com/repos/jakesdev1991/Omega_Theory_Everything"
        private const val MAX_FILE_CHARS = 400_000
    }

    /** name -> full text. Live overlays beat bundled assets. */
    private val cache = ConcurrentHashMap<String, String>()
    private val fileList = mutableListOf<String>()

    @Volatile
    var ready = false
        private set

    /** Load the index (file list) — fast; contents load lazily per file. */
    fun warmUp() {
        if (ready) return
        try {
            fileList.clear()
            // bundled assets
            val assets = context.assets.list(ASSET_DIR) ?: emptyArray()
            for (f in assets) {
                if (!fileList.contains(f)) fileList.add(f)
            }
            // live overlays (GitHub-synced)
            val liveDir = context.getFileStreamPath("")
            val live = java.io.File(liveDir, LIVE_DIR)
            if (liveDir != null && live.isDirectory) {
                live.listFiles()?.forEach { f ->
                    if (f.isFile && !fileList.contains(f.name)) fileList.add(f.name)
                }
            }
            ready = true
            Log.i(TAG, "EmbeddedToE ready: ${fileList.size} knowledge files")
        } catch (e: Exception) {
            Log.e(TAG, "warmUp failed", e)
        }
    }

    fun listVolumes(): List<String> {
        warmUp()
        return fileList.sorted()
    }

    /** Read one knowledge file (live overlay first, then bundled asset). */
    fun read(name: String): String? {
        warmUp()
        cache[name]?.let { return it }
        // 1. live overlay
        try {
            val live = java.io.File(context.filesDir, "$LIVE_DIR/$name")
            if (live.isFile) {
                val text = live.readText().take(MAX_FILE_CHARS)
                cache[name] = text
                return text
            }
        } catch (_: Exception) {}
        // 2. bundled asset
        return try {
            context.assets.open("$ASSET_DIR/$name").bufferedReader().use { r ->
                val text = r.readText().take(MAX_FILE_CHARS)
                cache[name] = text
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "read($name) failed", e)
            null
        }
    }

    /** The axioms — OmegaAxioms + DimensionalHierarchy + Protocol + UnifiedFoundation. */
    fun axioms(): Map<String, String> {
        warmUp()
        val out = linkedMapOf<String, String>()
        val core = listOf(
            "OmegaAxioms.lean", "OmegaDimensionalHierarchy.lean",
            "OmegaProtocol.lean", "OmegaUnifiedFoundation.lean"
        )
        for (f in core) {
            read(f)?.let { out[f] = it.take(6_000) }
        }
        // txt variants if the lean ones are thin
        if (out.isEmpty()) {
            for (f in listOf("OmegaAxioms.txt", "OmegaUnifiedFoundation.txt", "OmegaProtocol.txt")) {
                read(f)?.let { out[f] = it.take(6_000) }
            }
        }
        return out
    }

    fun systemPrompt(): String? = read("lucifer_super_reasoning.md")

    /**
     * Keyword/regex search across the corpus. Returns formatted hits:
     * "[Vol08_BlackHoleThermodynamics.lean] L42: ..." per line.
     */
    suspend fun search(query: String, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        warmUp()
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val rx = try {
            Regex(q, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
        val results = mutableListOf<String>()
        for (name in fileList) {
            if (results.size >= limit) break
            val text = read(name) ?: continue
            var count = 0
            val sb = StringBuilder()
            val lines = text.lines()
            for (i in lines.indices) {
                val line = lines[i]
                val hit = rx?.containsMatchIn(line) ?: line.contains(q, ignoreCase = true)
                if (hit) {
                    count++
                    if (count <= 2) {
                        sb.append("L${i + 1}: ").append(line.trim().take(140)).append(" | ")
                    }
                }
            }
            if (count > 0) {
                results.add("[$name] ${count} hits: $sb".trim())
            }
        }
        results
    }

    /**
     * Sync from GitHub over HTTPS — pulls the latest .lean/.txt/.md knowledge
     * files from the public repo and overlays them into files/toe_live/.
     * No git, no SSH, no auth (public repo). Returns a status summary.
     */
    suspend fun syncFromGithub(): String = withContext(Dispatchers.IO) {
        val clearance = NetworkGuard.authorize(
            "$GITHUB_API/git/trees/main?recursive=1",
            userInitiated = true,
            expected = EgressClass.CODE
        )
        if (clearance.blocked) return@withContext NetworkGuard.refusalMessage(clearance, "the corpus sync")
        try {
            // 1. List repo tree (knowledge paths)
            val treeReply = EgressHttp.exchange(
                EgressHttp.Request(
                    url = "$GITHUB_API/git/trees/main?recursive=1",
                    userInitiated = true,
                    expected = EgressClass.CODE,
                    headers = mapOf("Accept" to "application/vnd.github+json"),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 20_000,
                    decision = clearance
                )
            )
            if (treeReply.status != 200) {
                return@withContext "[ERROR] GitHub API ${treeReply.status} — " +
                    (treeReply.error.ifBlank { "check network" })
            }
            val body = treeReply.text()
            val tree = org.json.JSONObject(body).optJSONArray("tree") ?: return@withContext "[ERROR] empty tree"
            // 2. Pick the knowledge files we bundle.
            // Full repo paths, not stripped ones: the dir prefix is what makes the raw URL valid.
            // The old code stripped "lean_proofs/" and then guessed the prefix back from the first
            // four characters of the filename, so anything outside Vol*/Omega*/ToE*/lakefile — and
            // every txt_proofs file — was requested from the repo root and came back 404.
            val wanted = mutableSetOf<String>()
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (!item.optString("type").equals("blob", ignoreCase = true)) continue
                if (path.startsWith("lean_proofs/") && (path.endsWith(".lean") || path.endsWith(".txt"))) {
                    wanted.add(path)
                } else if (path.startsWith("txt_proofs/") && path.endsWith(".txt")) {
                    wanted.add(path)
                } else if (path == "Omega_Theory_v4.0_Technical.md" || path == "Omega_Theory_Laymans_Guide.md") {
                    wanted.add(path)
                }
            }
            if (wanted.isEmpty()) return@withContext "[ERROR] no knowledge files found in tree"
            // 3. Download each into files/toe_live/
            val liveDir = java.io.File(context.filesDir, LIVE_DIR)
            liveDir.mkdirs()
            var ok = 0
            var fail = 0
            for (path in wanted) {
                // Overlays are flat: files/toe_live/<basename>, which is also the cache and index key.
                val name = path.substringAfterLast('/')
                try {
                    // One socket per file. The old loop opened two — one to "$GITHUB_RAW/lean_proofs/$name"
                    // that was never read from, then a second to a guessed URL that usually 404'd.
                    val reply = EgressHttp.exchange(
                        EgressHttp.Request(
                            url = "$GITHUB_RAW/$path",
                            userInitiated = true,
                            expected = EgressClass.CODE,
                            connectTimeoutMs = 10_000,
                            readTimeoutMs = 20_000,
                            decision = clearance
                        )
                    )
                    val text = if (reply.status == 200) reply.text() else null
                    if (text != null) {
                        java.io.File(liveDir, name).writeText(text)
                        cache.remove(name)
                        ok++
                    } else {
                        fail++
                    }
                } catch (e: Exception) {
                    fail++
                }
            }
            // The index caches itself behind `ready`; without clearing it the new overlays exist on
            // disk but are never listed, so nothing can read them until the next cold start.
            ready = false
            warmUp()
            val summary = "Synced $ok files from jakesdev1991/Omega_Theory_Everything" +
                    if (fail > 0) " ($fail failed)" else ""
            Log.i(TAG, summary)
            summary
        } catch (e: Exception) {
            Log.e(TAG, "syncFromGithub failed", e)
            "[ERROR] sync failed: ${e.message}"
        }
    }

    fun stats(): Map<String, Any> {
        warmUp()
        return mapOf(
            "files" to fileList.size,
            "lean_volumes" to fileList.count { it.endsWith(".lean") },
            "source" to "embedded (APK assets) + files/toe_live overlays",
            "github" to "jakesdev1991/Omega_Theory_Everything"
        )
    }
}
