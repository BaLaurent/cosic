package cosic.soundfont.sf2

import cosic.audio.AParam
import cosic.audio.BlockContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The most zones one note plays; sfumato has no limit, a preset of a General MIDI font has a few. */
internal const val MAX_PRESET_ZONES = 16

/**
 * Plays the zones of one preset note the way `startPresetNote` of sfumato does, for one voice: every zone is its sample (read with linear interpolation at its rate, looped
 * when the font says so, stopped `release` seconds after the note), multiplied by its gain envelope (`dahdsr`, exponential ramps) and panned by its pan (a Web Audio
 * StereoPanner with a mono input), and all of them are added. Nothing allocates after construction.
 */
internal class PresetPlayer(private val sampleRate: Int) {
    private class Zone {
        var data = EMPTY
        var position = 0.0
        var step = 0.0
        var looping = false
        var loopStart = 0.0
        var loopEnd = 0.0
        var left = 0f
        var right = 0f
        var stopFrame = 0L
        var playing = false
        val gain = AParam(0.0, 0.0, 3.0e38, 12)
    }

    private val zones = Array(MAX_PRESET_ZONES) { Zone() }
    private var count = 0
    private var startTime = 0.0

    /** Holds the value the automation has at the cancel time and drops what was scheduled after it. */
    private val sink = object : EnvelopeSink {
        var param: AParam = zones[0].gain

        override fun setValueAtTime(v: Double, t: Double) = param.setValueAtTime(v, t)

        override fun exponentialRampToValueAtTime(v: Double, t: Double) = param.exponentialRampToValueAtTime(v, t, startTime)

        override fun cancelAndHoldAtTime(t: Double) {
            val held = param.valueAt(t)
            param.cancelScheduledValues(t)
            param.setValueAtTime(held, t)
        }
    }

    /**
     * Starts [voices] at time [t]; the note stops at [stopAt] (the sources `release` seconds later). Returns when the last source stops (at least [t]). At most
     * [MAX_PRESET_ZONES] zones play.
     */
    fun start(voices: List<PresetZoneVoice>, t: Double, stopAt: Double): Double {
        startTime = t
        count = minOf(voices.size, MAX_PRESET_ZONES)
        var end = t
        for (i in 0 until count) {
            val voice = voices[i]
            val zone = zones[i]
            zone.data = voice.data
            zone.position = 0.0
            zone.step = voice.rate * voice.sampleRate / sampleRate
            zone.looping = false
            if (voice.loop) {
                // the Web Audio rule: a loop that starts before it ends, within the buffer; else the whole buffer
                val frames = voice.data.size.toDouble()
                val start = voice.loopStart * voice.sampleRate
                val finish = voice.loopEnd * voice.sampleRate
                zone.looping = true
                zone.loopStart = 0.0
                zone.loopEnd = frames
                if (start >= 0.0 && finish > 0.0 && start < finish) {
                    val clipped = minOf(finish, frames)
                    if (start < clipped) {
                        zone.loopStart = start
                        zone.loopEnd = clipped
                    }
                }
            }
            val x = (voice.pan.coerceIn(-1.0, 1.0) + 1.0) / 2.0
            zone.left = cos(x * PI / 2.0).toFloat()
            zone.right = sin(x * PI / 2.0).toFloat()
            val stop = voice.sourceStop(stopAt)
            zone.stopFrame = Math.ceil(stop * sampleRate).toLong()
            zone.playing = voice.data.isNotEmpty() && zone.step > 0.0 && zone.step.isFinite()
            zone.gain.reset(0.0)
            sink.param = zone.gain
            voice.schedule(sink, t, stopAt)
            if (stop > end) end = stop
        }
        return end
    }

    /** Writes the next `ctx.n` frames of all zones into [l] and [r] (overwriting them). */
    fun fill(l: FloatArray, r: FloatArray, ctx: BlockContext) {
        val n = ctx.n
        java.util.Arrays.fill(l, 0, n, 0f)
        java.util.Arrays.fill(r, 0, n, 0f)
        for (z in 0 until count) {
            val zone = zones[z]
            if (!zone.playing) continue
            val gains = ctx.borrow()
            zone.gain.fill(gains, ctx)
            val data = zone.data
            val frames = data.size
            var pos = zone.position
            for (i in 0 until n) {
                if (ctx.startFrame + i >= zone.stopFrame) {
                    zone.playing = false
                    break
                }
                if (zone.looping) {
                    if (pos >= zone.loopEnd) pos = zone.loopStart + (pos - zone.loopStart) % (zone.loopEnd - zone.loopStart)
                } else if (pos >= frames) {
                    zone.playing = false
                    break
                }
                val i0 = pos.toInt()
                val frac = (pos - i0).toFloat()
                var i1 = i0 + 1
                if (zone.looping) {
                    if (i1 >= zone.loopEnd) i1 = zone.loopStart.toInt()
                } else if (i1 >= frames) {
                    i1 = i0
                }
                val a = data[i0]
                val sample = (a + (data[i1] - a) * frac) * gains[i]
                l[i] += sample * zone.left
                r[i] += sample * zone.right
                pos += zone.step
            }
            zone.position = pos
            ctx.release()
        }
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
