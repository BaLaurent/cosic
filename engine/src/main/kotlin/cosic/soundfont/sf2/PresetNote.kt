package cosic.soundfont.sf2

import cosic.pattern.jsMathRound
import kotlin.math.max
import kotlin.math.pow

/** Where the gain automation of a zone goes: the three calls of `AudioParam` that `dahdsr` makes. */
internal interface EnvelopeSink {
    fun setValueAtTime(v: Double, t: Double)

    fun exponentialRampToValueAtTime(v: Double, t: Double)

    /** Cancels what is scheduled from [t] on and holds the value the automation has at [t]. */
    fun cancelAndHoldAtTime(t: Double)
}

/**
 * One zone of a preset note as `sfumato` plays it (`startPresetNote`): the sample, how fast to play it, its loop, and the numbers of the gain envelope (`dahdsr`) and the panner.
 * [peak] is the velocity (0.3 unless a generator says otherwise); times are in seconds, [sustain] is the level after the decay as a fraction of the peak.
 */
internal class PresetZoneVoice(
    val data: FloatArray,
    val sampleRate: Int,
    val rate: Double,
    val loop: Boolean,
    val loopStart: Double,
    val loopEnd: Double,
    val peak: Double,
    val delay: Double,
    val attack: Double,
    val hold: Double,
    val decay: Double,
    val sustain: Double,
    val release: Double,
    val pan: Double,
) {
    /**
     * Schedules on [sink] what `dahdsr` schedules on the gain of a zone that starts at [time], then the release it returns, for a note that stops at [stopAt]: the delay,
     * the exponential attack to the peak, the hold, the exponential decay to the sustain level (never below 1e-3), and at [stopAt] the held value falling exponentially to 1e-3
     * over the release.
     */
    fun schedule(sink: EnvelopeSink, time: Double, stopAt: Double) {
        val attack = max(precision(attack, 4), MIN_TIME)
        val decay = max(precision(decay, 4), MIN_TIME)
        val release = precision(release, 4)
        var l = time
        sink.setValueAtTime(FLOOR, l)
        l += delay
        sink.setValueAtTime(FLOOR, l)
        l += attack
        sink.exponentialRampToValueAtTime(peak, l)
        l += hold
        sink.setValueAtTime(peak, l)
        l += decay
        sink.exponentialRampToValueAtTime(max(sustain * peak, FLOOR), l)
        sink.cancelAndHoldAtTime(stopAt)
        sink.exponentialRampToValueAtTime(FLOOR, stopAt + release)
    }

    /** The events of [schedule] as `[method, value, time]` (the hold as `cancelAndHoldAtTime, time`), for tests. */
    fun envelope(time: Double, stopAt: Double): List<Pair<String, DoubleArray>> {
        val events = ArrayList<Pair<String, DoubleArray>>()
        schedule(
            object : EnvelopeSink {
                override fun setValueAtTime(v: Double, t: Double) { events.add("setValueAtTime" to doubleArrayOf(v, t)) }

                override fun exponentialRampToValueAtTime(v: Double, t: Double) { events.add("exponentialRampToValueAtTime" to doubleArrayOf(v, t)) }

                override fun cancelAndHoldAtTime(t: Double) { events.add("cancelAndHoldAtTime" to doubleArrayOf(t)) }
            },
            time, stopAt,
        )
        return events
    }

    /** When the source stops for a note that stops at [stopAt]: the release of the generators (not rounded) after it. */
    fun sourceStop(stopAt: Double): Double = stopAt + release

    companion object {
        /** The floor of the gain: `Math.max(..., 1e-3)` throughout `dahdsr`. */
        const val FLOOR = 1e-3
        private const val MIN_TIME = 1e-3
    }
}

/** `precision(e, t)` of sfumato: [e] rounded to [digits] decimals the way `Math.round` does. */
private fun precision(e: Double, digits: Int): Double {
    val n = 10.0.pow(digits)
    return jsMathRound(e * n) / n
}

/** The defaults of the generators that have one (`DEFAULT_GENERATOR_VALUES` of `soundfont2`); the others (ranges, references, unused ones) are not merged. */
private val GENERATOR_DEFAULTS: Map<Int, Int> = buildMap {
    for (id in listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 15, 16, 17, 22, 24, 29, 31, 32, 37, 39, 40, 45, 48, 50, 51, 52, 54, 57)) put(id, 0)
    put(8, 13500)
    for (id in listOf(21, 23, 25, 26, 27, 28, 30, 33, 34, 35, 36, 38)) put(id, -12000)
    put(46, -1)
    put(47, -1)
    put(56, 100)
    put(58, -1)
}

private fun tc2s(timecents: Double): Double = 2.0.pow(timecents / 1200.0)

private fun active(keyRange: IntArray?, midi: Double): Boolean = keyRange == null || (keyRange[0] <= midi && midi <= keyRange[1])

/**
 * The zones of [preset] that sound for MIDI note [midi], in the order `sfumato` plays them (`getActiveZones`, `startPresetNote`): the preset zones whose key range holds the
 * note, and in each the zones of its instrument whose key range holds it. The velocity range is not looked at. A generator is the sum of the instrument's value (the zone's, else the
 * instrument's global zone's, else the default) and the preset's (the preset zone's, else the global zone's, else 0); `coarseTune` and `scaleTuning` are not used, as in sfumato.
 */
internal fun presetNote(preset: Sf2Preset, midi: Double): List<PresetZoneVoice> {
    val out = ArrayList<PresetZoneVoice>()
    for (presetZone in preset.zones) {
        if (!active(presetZone.keyRange, midi)) continue
        val instrument = presetZone.instrument
        for (zone in instrument.zones) {
            if (!active(zone.keyRange, midi)) continue
            out.add(voice(zone, presetZone, instrument, preset, midi))
        }
    }
    return out
}

private fun voice(zone: Sf2InstrumentZone, presetZone: Sf2PresetZone, instrument: Sf2Instrument, preset: Sf2Preset, midi: Double): PresetZoneVoice {
    val ids = HashSet<Int>()
    preset.globalZone?.generators?.keys?.let(ids::addAll)
    ids.addAll(presetZone.generators.keys)
    instrument.globalZone?.generators?.keys?.let(ids::addAll)
    ids.addAll(zone.generators.keys)
    val merged = HashMap<Int, Int>()
    for (id in ids) {
        val default = GENERATOR_DEFAULTS[id] ?: continue
        fun valueOf(generators: Map<Int, Sf2Generator>?): Int? = generators?.get(id)?.takeUnless { it.isRange }?.value
        val instrumentLevel = valueOf(zone.generators) ?: valueOf(instrument.globalZone?.generators) ?: default
        val presetLevel = valueOf(presetZone.generators) ?: valueOf(preset.globalZone?.generators) ?: 0
        merged[id] = instrumentLevel + presetLevel
    }
    fun gen(id: Int, absent: Int): Double = (merged[id] ?: absent).toDouble()

    val header = zone.sample.header
    val rootKey = merged[58]?.takeIf { it != -1 }?.toDouble() ?: header.originalPitch.toDouble()
    val base = 100.0 * rootKey + header.pitchCorrection - gen(52, 0)
    val rate = 2.0.pow((midi * 100.0 - base) / 1200.0)
    val loopStart = header.startLoop + gen(2, 0) + gen(45, 0) * 32768.0
    val loopEnd = header.endLoop + gen(3, 0) + gen(50, 0) * 32768.0
    val loop = loopEnd > loopStart && gen(54, 0) == 1.0
    val sustainCentibels = gen(37, 0)
    return PresetZoneVoice(
        data = zone.sample.floats,
        sampleRate = header.sampleRate.toInt(),
        rate = rate,
        loop = loop,
        loopStart = loopStart / header.sampleRate,
        loopEnd = loopEnd / header.sampleRate,
        peak = merged[47]?.toDouble() ?: DEFAULT_VELOCITY,
        delay = tc2s(gen(33, -12000)),
        attack = tc2s(gen(34, -12000)),
        hold = tc2s(gen(35, -12000)),
        decay = tc2s(gen(36, -12000)),
        sustain = if (sustainCentibels >= 960) 0.0 else 1 - sustainCentibels / 1000.0,
        release = tc2s(gen(38, -12000)),
        pan = gen(17, 0) / 1000.0,
    )
}

/** The velocity of a note: `velocity: a = 0.3` of `applyOptions`, unless a generator sets one. */
private const val DEFAULT_VELOCITY = 0.3
