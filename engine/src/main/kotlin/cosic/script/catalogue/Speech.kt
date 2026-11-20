package cosic.script.catalogue

import cosic.io.OutputBus
import cosic.io.speakText
import cosic.pattern.CosicError
import cosic.pattern.Registered
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.pattern.jsToString
import cosic.pattern.patternOf

/**
 * `speak(lang, voice)` of `core/speak.mjs`: speaks every hap value with the text-to-speech of the device (`utterance.lang = lang`, and with
 * a number `voice` the voice `voices[voice % voices.length]` among those whose language contains [lang]; a name as `voice` picks nothing, as
 * in Strudel, whose comparison of the name with itself never matches). The hook is dominant: the hap does not sound as well. It runs when the
 * hap is scheduled (on the dispatcher of the output bus), as Strudel's does, and every utterance cancels the one still speaking.
 */
private class SpeakHook(private val lang: Any?, private val voice: Any?, private val outputs: OutputBus) : ScheduleHook {
    override fun run(hap: ScheduledHap) {
        val sink = outputs.speech ?: throw CosicError("speech synthesis is not available")
        sink.speak(speakText(hap.hap.value), jsToString(lang), voice as? Double ?: (voice as? String))
    }
}

/**
 * `register('speak', (lang, voice, pat) => ...)`, made for the outputs of the evaluation in progress, so its hook can reach the speech device
 * (the entries of the `Registry` have no access to them). It patternifies `lang` and `voice` like any registered function.
 */
private fun speakFunction(outputs: OutputBus): Registered = Registered(
    names = listOf("speak"),
    arity = 3,
    patternify = true,
    preserveSteps = false,
    join = { it.innerJoin() },
    impl = { a -> patternOf(a[2]).onSchedule(SpeakHook(a[0], a[1], outputs), dominant = true) },
)

internal fun registerSpeech() = catalogue("Speech") {
    function(listOf("speak"), 3) { ctx -> speakFunction(ctx.effects.outputs).global(ctx.args.indices.map(ctx::kernelArg)) }
    method(listOf("speak"), 2) { ctx ->
        speakFunction(ctx.effects.outputs).method(ctx.bridge.receiverOf(ctx.self, "speak"), ctx.args.indices.map(ctx::kernelArg))
    }
    method(listOf("_speak"), 2) { ctx ->
        speakFunction(ctx.effects.outputs).plain(ctx.bridge.receiverOf(ctx.self, "_speak"), ctx.args.indices.map(ctx::kernelArg))
    }
}
