package com.lucifer.mobile.skills

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.synth.Feedback
import com.lucifer.mobile.synth.SkillSynth
import com.lucifer.mobile.synth.SkillSynthCodec
import com.lucifer.mobile.synth.SynthState
import com.lucifer.mobile.synth.SynthesizedSkill
import com.lucifer.mobile.synth.Text
import java.io.File

/**
 * The Android half of the skill synthesizer: where the state lives, when it is written, and what the
 * rest of the app calls.
 *
 * The rules themselves are in `synth/` — pure Kotlin, host-tested, no imports from here. This object
 * only adds three things the core cannot have: a file, a clock, and a log line.
 *
 * It is **always on** by construction: every chat turn calls [observeIncoming] then [recordAnswer], and
 * [promptBlock] is read whenever a persona is built. The switch in Settings turns it off — that is the
 * user's call to make, and with it off nothing is written and nothing is injected.
 */
object SkillSynthRuntime {

    private const val TAG = "SkillSynth"
    private const val DIR = "skills"
    private const val FILE = "synth_state.json"

    /** How long a rendered prompt block stays attributable to the turn that used it. */
    private const val ATTRIBUTION_TTL_MS = 120_000L

    private data class Rendered(val query: String, val block: SkillSynth.PromptBlock, val at: Long)

    private val lock = Any()

    @Volatile private var cached: SynthState? = null
    @Volatile private var enabledCache: Boolean? = null
    @Volatile private var rendered: Rendered? = null

    // ── state ────────────────────────────────────────────────────────

    fun state(context: Context): SynthState =
        cached ?: synchronized(lock) { cached ?: load(context).also { cached = it } }

    private fun load(context: Context): SynthState = try {
        val file = File(File(context.filesDir, DIR), FILE)
        if (!file.isFile) SynthState() else SkillSynthCodec.decode(AtomicFile(file).readFully().toString(Charsets.UTF_8))
    } catch (error: Exception) {
        // A corrupt file must not take the launcher down; he starts from nothing and says so in logs.
        Log.w(TAG, "learned state unreadable, starting empty", error)
        SynthState()
    }

    private fun persist(context: Context, state: SynthState) {
        cached = state
        try {
            val dir = File(context.filesDir, DIR)
            dir.mkdirs()
            val atomic = AtomicFile(File(dir, FILE))
            val stream = atomic.startWrite()
            try {
                stream.write(SkillSynthCodec.encode(state).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                Log.w(TAG, "could not persist learned skills", error)
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not open the learned-skill store", error)
        }
    }

    // ── the switch ───────────────────────────────────────────────────

    fun enabled(context: Context): Boolean =
        enabledCache ?: AppConfig.readFlag(context, AppConfig.SKILL_SYNTH_ENABLED, default = true)
            .also { enabledCache = it }

    fun setEnabled(context: Context, on: Boolean) {
        enabledCache = on
        AppConfig.writeFlag(context, AppConfig.SKILL_SYNTH_ENABLED, on)
        if (!on) {
            // Stop injecting immediately; what was learned stays on disk until the user forgets it.
            rendered = null
            Log.i(TAG, "learning switched off by the user")
        }
    }

    // ── what the rest of the app calls ───────────────────────────────

    /**
     * The block that goes into a persona prompt, and the ids it contains.
     *
     * The rendered block is remembered so the turn's answer can be attributed to exactly the skills
     * that were in front of him. Without that, a correction could not score anything.
     */
    fun promptBlock(context: Context?, query: String): String {
        val app = context?.applicationContext ?: return ""
        if (!enabled(app)) return ""
        val now = System.currentTimeMillis()
        rendered?.let {
            if (now - it.at < ATTRIBUTION_TTL_MS && Text.sameQuestion(it.query, query)) return it.block.text
        }
        val block = SkillSynth.promptBlock(state(app), query, now)
        rendered = Rendered(query, block, now)
        return block.text
    }

    /**
     * Judge a new user message against the last answer, learn from it, and persist.
     * Returns the feedback so a caller can react (the UI says nothing when it is NONE).
     */
    fun observeIncoming(context: Context?, text: String, surface: String): Feedback {
        val app = context?.applicationContext ?: return Feedback.NONE
        if (!enabled(app)) return Feedback.NONE
        val now = System.currentTimeMillis()
        return try {
            val outcome = SkillSynth.observeIncoming(state(app), text, surface, now)
            if (outcome.state != state(app)) {
                persist(app, outcome.state)
                if (outcome.learned.isNotEmpty()) {
                    outcome.learned.forEach { Log.i(TAG, "learned ${it.kind}: ${it.instruction.take(80)}") }
                }
            }
            outcome.feedback
        } catch (error: Exception) {
            Log.w(TAG, "observation failed; nothing learned", error)
            Feedback.NONE
        }
    }

    /** Record the answer that was just produced, attributed to the block that was rendered for it. */
    fun recordAnswer(context: Context?, question: String, answer: String, surface: String) {
        val app = context?.applicationContext ?: return
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        val ids = rendered
            ?.takeIf { now - it.at < ATTRIBUTION_TTL_MS && Text.sameQuestion(it.query, question) }
            ?.block?.skillIds
            .orEmpty()
        try {
            persist(app, SkillSynth.recordAnswer(state(app), question, answer, surface, now, ids))
        } catch (error: Exception) {
            Log.w(TAG, "could not record the answer", error)
        }
    }

    /** A command that failed and the tactic that fixed it — the recovery loop's contribution. */
    fun learnProcedure(context: Context?, failureClass: String, target: String, tactic: String, worked: Boolean) {
        val app = context?.applicationContext ?: return
        if (!enabled(app)) return
        try {
            val now = System.currentTimeMillis()
            persist(app, SkillSynth.learnProcedure(state(app), failureClass, target, tactic, worked, now))
        } catch (error: Exception) {
            Log.w(TAG, "could not record the recovery outcome", error)
        }
    }

    fun skills(context: Context?): List<SynthesizedSkill> =
        context?.applicationContext?.let { state(it).skills }.orEmpty()

    fun active(context: Context?): List<SynthesizedSkill> =
        context?.applicationContext?.let { state(it).live() }.orEmpty()

    fun summary(context: Context?): String {
        val app = context?.applicationContext ?: return "learning unavailable"
        val state = state(app)
        val off = if (enabled(app)) "" else " · LEARNING OFF"
        return SkillSynth.summary(state) + off
    }

    fun forget(context: Context?, id: String) {
        val app = context?.applicationContext ?: return
        persist(app, SkillSynth.forget(state(app), id))
        rendered = null
    }

    fun forgetAll(context: Context?) {
        val app = context?.applicationContext ?: return
        persist(app, SkillSynth.forgetAll(state(app), System.currentTimeMillis()))
        rendered = null
        Log.i(TAG, "all learned skills forgotten")
    }

    /** Drops the in-memory copy. Tests and the Settings switch use it; nothing else should need to. */
    fun invalidate() {
        cached = null
        rendered = null
    }
}
