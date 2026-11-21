package cosic.dough

/*
 * dough.h 2570-2922: a voice, and what it does for every sample: source, pitch modulation, envelopes, filters, degraders, modulation, phaser, flanger, chorus and pan.
 * Voices live in the engine's array and are all allocated when the engine is made; nothing here allocates.
 */

/** The state one channel of a voice keeps for its effects. */
internal class ChannelFx {
    @JvmField val lpBq = Array(4) { BiquadFilter() }
    @JvmField val hpBq = Array(4) { BiquadFilter() }
    @JvmField val bpBq = Array(4) { BiquadFilter() }
    @JvmField val coarse = Coarse()
    @JvmField val phaser = Phaser()
    @JvmField val flanger = Flanger()

    fun reset() {
        for (i in 0 until 4) {
            lpBq[i].reset()
            hpBq[i].reset()
            bpBq[i].reset()
        }
        phaser.reset()
        flanger.reset()
        coarse.reset()
    }
}

internal class DoughVoice {
    @JvmField val glideLag = Lag()
    @JvmField val phasor = Phasor()
    @JvmField val noisePink = PinkNoise()
    @JvmField val noiseBrown = BrownNoise()
    @JvmField val gainAdsr = AdsrNode()
    @JvmField val lpAdsr = AdsrNode()
    @JvmField val hpAdsr = AdsrNode()
    @JvmField val bpAdsr = AdsrNode()
    @JvmField val fmAdsr = AdsrNode()
    @JvmField val pAdsr = AdsrNode()
    @JvmField val vibLfo = Phasor()
    @JvmField val fmModulator = Phasor()
    @JvmField val amLfo = Phasor()
    @JvmField val rmLfo = Phasor()
    @JvmField val chorus = Chorus()

    /** The value of `voice_counter` when the voice was played: group stealing takes the smallest. */
    @JvmField var id = 0L

    @JvmField var time = 0f

    /** The audio channels of the sample being made. */
    @JvmField val ch = FloatArray(DOUGH_CHANNELS)

    @JvmField val fx = Array(DOUGH_CHANNELS) { ChannelFx() }

    @JvmField val p = DoughEvent()

    /** `reset_voice`. */
    fun reset() {
        for (c in 0 until DOUGH_CHANNELS) fx[c].reset()
        gainAdsr.reset()
        lpAdsr.reset()
        hpAdsr.reset()
        bpAdsr.reset()
        fmAdsr.reset()
        pAdsr.reset()
        phasor.phase = 0f
        noisePink.reset()
        noiseBrown.reset()
        vibLfo.phase = 0f
        fmModulator.phase = 0f
        amLfo.phase = 0f
        rmLfo.phase = 0f
        chorus.reset()
        glideLag.reset()
        time = 0.0f
    }

    /** `shift_envelopes`: moves the start of every envelope by [offset] seconds (the voice's own time is about to start again at 0). */
    fun shiftEnvelopes(offset: Float) {
        gainAdsr.startTime += offset
        lpAdsr.startTime += offset
        hpAdsr.startTime += offset
        bpAdsr.startTime += offset
        fmAdsr.startTime += offset
        pAdsr.startTime += offset
    }

    /** The voice's source for one sample into [ch]; true when a file source reached the end of its slice (which ends the voice). */
    private fun runSource(e: DoughEngine, freq: Float): Boolean {
        val isr = e.isr
        when (p.sound) {
            TRI_OSC -> ch[0] = (triOscUpdate(phasor, freq, isr).toDouble() * GAIN_TRI).toFloat()
            SINE_OSC -> ch[0] = (sineOscUpdate(phasor, freq, isr).toDouble() * GAIN_SINE).toFloat()
            ZAW_OSC -> ch[0] = (zawOscUpdate(phasor, freq, isr).toDouble() * GAIN_SAW).toFloat()
            SAW_OSC -> ch[0] = (sawOscUpdate(phasor, freq, isr).toDouble() * GAIN_SAW).toFloat()
            PULSE_OSC -> ch[0] = (pulseOscUpdate(phasor, freq, p.pw, isr).toDouble() * GAIN_PULSE).toFloat()
            PULZE_OSC -> ch[0] = (pulzeOscUpdate(phasor, freq, p.pw, isr).toDouble() * GAIN_PULSE).toFloat()
            WHITE_NOISE -> ch[0] = ((e.rng.randomFloat() * 2 - 1).toDouble() * GAIN_WHITE).toFloat()
            PINK_NOISE -> ch[0] = (noisePink.update(e.rng).toDouble() * GAIN_PINK).toFloat()
            BROWN_NOISE -> ch[0] = (noiseBrown.update(e.rng).toDouble() * GAIN_BROWN).toFloat()
            CONST -> ch[0] = 1f
            FILE_SRC -> {
                val file = p.fileSource
                if (file == null) {
                    ch[0] = 0f
                    ch[1] = 0f
                    return false
                }
                return fileSourceUpdate(phasor, freq, file, p.begin, p.end, ch, isr)
            }
        }
        return false
    }

    private fun applyPenv(freq: Float): Float {
        val gate = 1.0f // never enter the pitch release phase
        var env = pAdsr.update(time, gate, p.patt, p.pdec, p.psus, p.prel)
        if (p.psus == 1f) env -= 1 // the attack targets the frequency: env is in [-1, 0]
        val factor = ourExp2f((env * p.penv) / 12)
        return freq * factor
    }

    private fun applyVib(freq: Float, e: DoughEngine): Float {
        val mod = sineOscUpdate(vibLfo, p.vib, e.isr)
        return freq * (ourExp2f(mod * p.vibmod / 12))
    }

    private fun applyFm(freq: Float, e: DoughEngine): Float {
        var fm = p.fm
        if (p.fmAdsrActive) {
            val env = fmAdsr.update(time, p.gate, p.fma, p.fmd, p.fms, p.fmr)
            fm = p.fme * env * fm + fm
        }
        val modfreq = freq * p.fmh
        val modgain = modfreq * fm
        val mod = sineOscUpdate(fmModulator, modfreq, e.isr)
        return freq + mod * modgain
    }

    /**
     * `run_voice`: one sample of the voice into [ch]. A voice that has ended is freed by the engine (`free_voice` of slot [index], which then holds another voice): the caller must read
     * the slot again, not use this object.
     */
    fun run(e: DoughEngine, index: Int) {
        var gainEnvelope = 0.0f
        if (p.gainAdsrActive) gainEnvelope = gainAdsr.update(time, p.gate, p.attack, p.decay, p.sustain, p.release)
        if (gainAdsr.state == ADSR_OFF) {
            e.freeVoice(index)
            return
        }
        var freq = p.rate
        freq *= p.speed
        if (p.glideActive) freq = glideLag.update(freq, p.glide, e.lagUnit)
        if (p.fmActive) freq = applyFm(freq, e)
        if (p.pAdsrActive) freq = applyPenv(freq)
        if (p.vibActive) freq = applyVib(freq, e)

        val wrapped = runSource(e, freq)
        if (wrapped) {
            e.freeVoice(index)
            return
        }

        var lpCutoff = p.lpf
        if (p.lpActive) {
            if (p.lpAdsrActive) {
                val env = lpAdsr.update(time, p.gate, p.lpa, p.lpd, p.lps, p.lpr)
                lpCutoff = clamp(p.lpe * env * lpCutoff + lpCutoff, 0f, 20000f)
            }
        }
        var hpCutoff = p.hpf
        if (p.hpActive) {
            if (p.hpAdsrActive) {
                val env = hpAdsr.update(time, p.gate, p.hpa, p.hpd, p.hps, p.hpr)
                hpCutoff = clamp(p.hpe * env * hpCutoff + hpCutoff, 0f, 20000f)
            }
        }
        var bpCutoff = p.bpf
        if (p.bpActive) {
            if (p.bpAdsrActive) {
                val env = bpAdsr.update(time, p.gate, p.bpa, p.bpd, p.bps, p.bpr)
                bpCutoff = clamp(p.bpe * env * bpCutoff + bpCutoff, 0f, 20000f)
            }
        }

        var amGain = 1.0f
        if (p.amActive) {
            val mod = sineOscUpdate(amLfo, p.am, e.isr)
            val depth = clamp(p.amdepth, 0.0f, 1.0f)
            amGain = (1.0 + (mod * depth).toDouble()).toFloat() // `1.0 + mod * depth`: the product is a float, the sum a double
        }
        var rmGain = 1.0f
        if (p.rmActive) {
            val mod = sineOscUpdate(rmLfo, p.rm, e.isr)
            val depth = clamp(p.rmdepth, 0.0f, 1.0f)
            rmGain = ((1.0 - depth.toDouble()) + (mod * depth).toDouble()).toFloat()
        }

        val sr = e.sr
        val isr = e.isr
        val channels = if (p.channels > DOUGH_CHANNELS) DOUGH_CHANNELS else p.channels // the C code writes past `ch` for a file of more than two channels
        val stages = if (p.ftype == 0) 1 else if (p.ftype == 1) 2 else 4
        for (i in 0 until channels) {
            ch[i] *= p.gain * p.velocity
            val fxi = fx[i]
            if (p.lpActive) {
                var signal = ch[i]
                for (stage in 0 until stages) signal = fxi.lpBq[stage].update(signal, BIQUAD_LOWPASS, lpCutoff, p.lpq, 0f, sr)
                ch[i] = signal
            }
            if (p.hpActive) {
                var signal = ch[i]
                for (stage in 0 until stages) signal = fxi.hpBq[stage].update(signal, BIQUAD_HIGHPASS, hpCutoff, p.hpq, 0f, sr)
                ch[i] = signal
            }
            if (p.bpActive) {
                var signal = ch[i]
                for (stage in 0 until stages) signal = fxi.bpBq[stage].update(signal, BIQUAD_BANDPASS, bpCutoff, p.bpq, 0f, sr)
                ch[i] = signal
            }
            if (p.coarseActive) ch[i] = fxi.coarse.update(ch[i], p.coarse.toInt())
            if (p.crushActive) ch[i] = crush(ch[i], p.crush)
            if (p.distortActive) ch[i] = distort(ch[i], p.distort, p.distortvol)
            if (p.amActive) ch[i] *= amGain
            if (p.rmActive) ch[i] *= rmGain
            if (p.phaserActive) ch[i] = fxi.phaser.update(ch[i], p.phaser, p.phaserdepth, p.phasercenter, p.phasersweep, sr, isr)
            if (p.flangerActive) ch[i] = fxi.flanger.update(ch[i], p.flanger, p.flangerdepth, p.flangerfeedback, sr, isr)
            ch[i] *= gainEnvelope * p.postgain
        }
        for (i in channels until DOUGH_CHANNELS) ch[i] = ch[i % channels]

        if (p.chorusActive) {
            chorus.update(ch[0], ch[1], p.chorus, p.chorusdepth, p.chorusdelay, sr, isr)
            ch[0] = chorus.left
            ch[1] = chorus.right
        }

        if (p.pan != 0.5f) {
            val panpos = ((p.pan * FPI).toDouble() / 2.0).toFloat()
            ch[0] *= parCosf(panpos)
            ch[1] *= parSinf(panpos)
        }
        time += 1 / sr
        if (!p.duration.isNaN() && p.duration != 0f && time > p.duration) p.gate = 0.0f
    }
}
