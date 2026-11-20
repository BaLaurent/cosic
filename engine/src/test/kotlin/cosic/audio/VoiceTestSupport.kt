package cosic.audio

import cosic.SampleData
import cosic.audio.sources.SourceSpec
import cosic.audio.sources.SynthRegistry
import cosic.audio.sources.asSourceControls
import cosic.player.voiceParams
import java.util.concurrent.atomic.AtomicLong

/**
 * Voice parameters the way the scheduler builds them: from the control map of a hap, with Strudel's control names (`cutoff`, not `lpf`).
 * [durationSec] is `Hap.duration / cps` (with `clip` already applied, as the scheduler does).
 */
internal fun vp(source: VoiceSource, durationSec: Double, vararg controls: Pair<String, Any?>, cps: Double = 0.5, cycle: Double = 0.0): VoiceParams =
    voiceParams(mapOf(*controls), source, durationSec, cps, cycle)

/** [data] played as it is: no repitching, a full level source, the sampler's controls honoured. */
internal fun plain(data: SampleData): VoiceSource = VoiceSource.Sample(SampleSourceParams(data))

/** A long constant sample, which makes every level at the output a direct reading of the gain stages. */
internal fun dcSource(seconds: Double = 5.0, value: Float = 1f): VoiceSource = plain(constantSample(seconds, value))

private val synthSeeds = AtomicLong()

/**
 * The [VoiceSource] of the built-in sound [name] for an event with these [controls], which last [durationSec] seconds at the tempo [cps]: what the sound lookup makes of it
 * (`SynthRegistry`, with a seed of its own) for an engine of [sampleRate]. A source plays once: make one for every voice.
 */
internal fun synth(name: String, durationSec: Double, controls: Map<String, Any?>, cps: Double = 0.5, sampleRate: Int = SR): VoiceSource {
    val spec = SourceSpec(sampleRate, controls.asSourceControls(), durationSec, cps, synthSeeds.incrementAndGet())
    val canonical = checkNotNull(SynthRegistry.canonical(name)) { "no synth called $name" }
    return VoiceSource.Synth(canonical, checkNotNull(SynthRegistry.create(canonical, spec)))
}

/** [vp] of a built-in sound: the source of [name] is made from the same [controls], for the same duration and tempo. */
internal fun synthVp(name: String, durationSec: Double, vararg controls: Pair<String, Any?>, cps: Double = 0.5, cycle: Double = 0.0, sampleRate: Int = SR): VoiceParams {
    val map = mapOf(*controls)
    return voiceParams(map, synth(name, durationSec, map, cps, sampleRate), durationSec, cps, cycle)
}

/** The left channel of [frames] frames of a mixer that has [params] started. */
internal fun renderLeft(params: VoiceParams, frames: Int = SR / 2): FloatArray {
    val mixer = Mixer(SR)
    check(mixer.startVoice(params)) { "voice was skipped" }
    return channel(mixer.renderFrames(frames), 0)
}

/** Both channels, interleaved. */
internal fun renderStereo(params: VoiceParams, frames: Int = SR / 2): FloatArray {
    val mixer = Mixer(SR)
    check(mixer.startVoice(params)) { "voice was skipped" }
    return mixer.renderFrames(frames)
}

/** A sample of [seconds] made of the given values; shorthand for sample-path tests. */
internal fun sampleOf(vararg values: Float, rate: Int = SR): SampleData = SampleData(floatArrayOf(*values), rate)

/** A stereo sample of [seconds]: a 220 Hz sine on the left and a 330 Hz sine on the right. */
internal fun stereoSample(seconds: Double, rate: Int = SR): SampleData {
    val left = sineWave(220.0, seconds, 1.0, rate)
    val right = sineWave(330.0, seconds, 1.0, rate)
    return SampleData(FloatArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }, rate, channels = 2)
}
