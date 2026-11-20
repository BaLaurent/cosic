package com.octopusman.cosic.osc

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import cosic.SpeechSink as EngineSpeechSink
import java.util.Locale

/**
 * `.speak()` on the text-to-speech engine of the device (`core/speak.mjs`). The engine's output thread calls [speak] when the hap is scheduled, and
 * the text is said at once, ending what was said before (Strudel's `speechSynthesis.cancel()` then `speak()`). [lang] is a language tag (`en`,
 * `fr-FR`); a number as [voice] picks `voices[voice % voices.length]` among the voices whose language tag contains [lang], and a name picks
 * nothing (the default voice of the language), as the comparison of a name with itself in Strudel never matches.
 *
 * The text-to-speech service is bound by the first [speak], not before, so a program that never speaks costs nothing. The engine starts
 * asynchronously: what is asked before it is up is kept, newest only, and said when it is (every utterance cancels the one before it anyway). Without a text-to-speech engine on the device [log] gets one line and later calls are ignored.
 */
class SpeechSink(context: Context, private val log: (String) -> Unit = {}) : EngineSpeechSink, AutoCloseable {
    private class Utterance(val text: String, val lang: String, val voice: Any?)

    private val app = context.applicationContext
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var closed = false
    private var ready = false
    private var failed = false
    private var pending: Utterance? = null
    private var counter = 0

    private fun onInit(status: Int) {
        synchronized(lock) {
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                pending = null
                log("[speak] no text-to-speech engine is available")
                return
            }
            ready = true
            pending?.let { say(it) }
            pending = null
        }
    }

    override fun speak(text: String, lang: String, voice: Any?) {
        synchronized(lock) {
            if (failed || closed) return
            val utterance = Utterance(text, lang, voice)
            if (ready) {
                say(utterance)
                return
            }
            pending = utterance
            // the callback takes the lock too, so it cannot run before `tts` is set
            if (tts == null) tts = TextToSpeech(app) { status -> onInit(status) }
        }
    }

    /** Called with the lock held, after the engine is up. */
    private fun say(u: Utterance) {
        val engine = tts ?: return
        engine.stop()
        engine.language = Locale.forLanguageTag(u.lang)
        val number = u.voice as? Double
        if (number != null) {
            val voices: List<Voice> = (engine.voices ?: emptySet()).filter { it.locale.toLanguageTag().contains(u.lang) }
            // JavaScript's `voices[voice % voices.length]`: the default voice when that is no element (a fraction, a negative number, no voices)
            val index = number % voices.size
            if (voices.isNotEmpty() && index >= 0 && index == Math.floor(index)) engine.voice = voices[index.toInt()]
        }
        engine.speak(u.text, TextToSpeech.QUEUE_FLUSH, null, "cosic-speak-${counter++}")
    }

    override fun close() {
        synchronized(lock) {
            ready = false
            closed = true
            pending = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}
