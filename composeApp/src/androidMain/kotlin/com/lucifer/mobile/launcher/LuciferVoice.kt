package com.lucifer.mobile.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * LuciferVoice — he talks back, and now he listens.
 *
 * Two halves that share a lifecycle:
 *
 *  - **out**: Android `TextToSpeech`, after every command and every chat reply.
 *  - **in**: `SpeechRecognizer`, on demand. Android's recognizer streams audio to the system's speech
 *    service, which may be Google's and may be remote. That is a real data flow, so it is stated in the
 *    launcher UI, it respects the same egress switch where the platform allows (the switch cannot police
 *    another app's sockets, and this object says so rather than implying otherwise), and it never runs
 *    without a press.
 *
 * The recogniser is created on demand and destroyed when listening stops: holding one open keeps a
 * microphone session alive, which is exactly the kind of ambient state this codebase avoids.
 */
class LuciferVoice(context: Context) {

    private companion object {
        const val TAG = "LuciferVoice"
    }

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

    // ── listening ────────────────────────────────────────────────────

    /** Why listening could not start, in words that do not blame the user. */
    class SpeechUnavailable(val message: String) : Exception(message)

    @Volatile private var recognizer: SpeechRecognizer? = null

    @Volatile private var listening = false

    fun isListening(): Boolean = listening

    /** True when this device has a speech recognition service at all. Checked before showing a mic. */
    fun canListen(context: Context): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    } catch (_: Exception) {
        false
    }

    /**
     * Start one listening session.
     *
     * @param onPartial called with interim text, so the field can update as he hears it.
     * @param onFinal called once with the best transcript, or the honest reason nothing arrived.
     * @param onDone called after `onFinal` (or after an error) so the caller can re-enable its button.
     */
    fun listen(
        context: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val app = context.applicationContext
        if (listening) {
            onError("Already listening.")
            return
        }
        if (!canListen(app)) {
            onError("This device has no speech recognition service. Install one, or type instead.")
            return
        }
        val created = try {
            // The service may be remote; say so in the log so a later reader is not surprised.
            Log.i(TAG, "starting speech recognition (audio goes to the system speech service)")
            SpeechRecognizer.createSpeechRecognizer(app)
        } catch (error: Exception) {
            onError("Speech recognition could not start: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        recognizer = created
        listening = true

        created.setRecognitionListener(object : RecognitionListener {
            private var finished = false

            private fun finish() {
                if (finished) return
                finished = true
                listening = false
                destroyRecognizer()
                onDone()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val reason = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
                    SpeechRecognizer.ERROR_CLIENT -> "The recogniser was cancelled."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission is not granted. Lucifer will not ask twice in a row."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "The speech service needs a network and could not reach it."
                    SpeechRecognizer.ERROR_NO_MATCH -> "Nothing was recognised. Nothing was sent."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
                    SpeechRecognizer.ERROR_SERVER -> "The speech service returned an error."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
                    else -> "Speech recognition failed ($error)."
                }
                onError(reason)
                finish()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = best(results)
                if (text.isNullOrBlank()) {
                    onError("Nothing was recognised. Nothing was sent.")
                } else {
                    onFinal(text)
                }
                finish()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Offline recognition is NOT requested: most devices have no downloaded offline model, and
            // asking for one would turn "press to talk" into "press and get an error". The consequence is
            // stated where the button is: the audio goes to the system speech service, usually Google's,
            // and that traffic is outside Lucifer's tunnel and outside the ledger.
        }
        try {
            created.startListening(intent)
        } catch (error: Exception) {
            listening = false
            destroyRecognizer()
            onError("The microphone is unavailable: ${error.message ?: error.javaClass.simpleName}")
            onDone()
        }
    }

    /** Stop listening and drop the recogniser. Safe to call at any time, from any thread. */
    fun stopListening() {
        val current = recognizer ?: return
        listening = false
        try {
            current.stopListening()
        } catch (_: Exception) {
            // already stopped
        }
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.cancel()
            current.destroy()
        } catch (_: Exception) {
            // the platform already released it
        }
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    fun shutdown() {
        stopListening()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
