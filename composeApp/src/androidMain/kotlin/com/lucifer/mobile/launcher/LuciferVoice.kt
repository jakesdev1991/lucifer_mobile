package com.lucifer.mobile.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * LuciferVoice — he talks back.
 * Wraps Android's TextToSpeech; after every command he speaks the result,
 * and in chat he speaks his replies.
 */
class LuciferVoice(context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    /** Global kill-switch from Settings (persisted). Session mutes use setSessionMuted. */
    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            try {
                tts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun isEnabled(): Boolean = enabled

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(0.85f)
            }
        }
    }

    /** Speak a chunk of text; strips bracket tags and keeps it under ~300 chars. */
    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        val clean = text
            .replace(Regex("\\[(INFO|ERROR|WARN|SUCCESS)\\]\\s*"), "")
            .replace(Regex("[\\$`#|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
        if (clean.isBlank()) return
        try {
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "lucifer_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    /** Speak a short command-result summary: first output line + exit status. */
    fun speakResult(output: List<String>) {
        if (!enabled) return
        val exitLine = output.lastOrNull { it.startsWith("[exit") } ?: ""
        val firstMeaningful = output.firstOrNull {
            it.isNotBlank() && !it.startsWith("[exit") && !it.startsWith("[INFO]")
        } ?: return
        val verdict = if (exitLine.contains("0")) "done" else "with errors"
        speak("$firstMeaningful. $verdict")
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
