package cosic.audio

import cosic.SampleData
import cosic.player.voiceParams

/**
 * Voice parameters the way the scheduler builds them: from the control map of a hap, with Strudel's control names (`cutoff`, not `lpf`).
 * [durationSec] is `Hap.duration / cps` (with `clip` already applied, as the scheduler does).
 */
internal fun vp(source: VoiceSource, durationSec: Double, vararg controls: Pair<String, Any?>, cps: Double = 0.5, cycle: Double = 0.0): VoiceParams =
    voiceParams(mapOf(*controls), source, durationSec, cps, cycle)

/** A long constant sample, which makes every level at the output a direct reading of the gain stages. */
internal fun dcSource(seconds: Double = 5.0, value: Float = 1f): VoiceSource = VoiceSource.Sample(constantSample(seconds, value), null)

internal fun synth(waveform: Waveform): VoiceSource = VoiceSource.Synth(waveform)

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
