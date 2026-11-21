package cosic.dough

/*
 * dough.h 1611-2564: what an event is, how its text is read and how it is completed. An event is a bag of optional values: a `Float` that is NaN, an `Int` that is -1 or a
 * source that is [UNKNOWN_SOURCE] was not given. `Event` of the C code is [DoughEvent]; `parse_event` is [parseEvent]; `init_event` and its helpers are [initEvent].
 */

// Source (`s`)
internal const val UNKNOWN_SOURCE = -1
internal const val TRI_OSC = 0
internal const val SINE_OSC = 1
internal const val SAW_OSC = 2
internal const val ZAW_OSC = 3
internal const val PULSE_OSC = 4
internal const val PULZE_OSC = 5
internal const val WHITE_NOISE = 6
internal const val PINK_NOISE = 7
internal const val BROWN_NOISE = 8
internal const val CONST = 9
internal const val FILE_SRC = 10

// Command (`dough`)
internal const val CMD_UNKNOWN = -1
internal const val CMD_PLAY = 0
internal const val CMD_UPDATE_ALL = 1
internal const val CMD_UPDATE_GROUP = 2
internal const val CMD_PANIC = 3
internal const val CMD_HUSH = 4
internal const val CMD_HUSH_ENDLESS = 5
internal const val CMD_HUSH_GROUP = 6
internal const val CMD_RESET = 7
internal const val CMD_RESET_TIME = 8
internal const val CMD_RESET_SCHEDULE = 9

/** The size of the buffer `parse_event` copies the text into: 1024 bytes, of which 1023 are read. */
internal const val EVENT_INPUT_SIZE = 1024

internal fun getSource(name: String): Int = when (name) {
    "triangle", "tri" -> TRI_OSC
    "sine" -> SINE_OSC
    "sawtooth", "saw" -> SAW_OSC
    "zawtooth", "zaw" -> ZAW_OSC
    "pulse", "square" -> PULSE_OSC
    "pulze", "zquare" -> PULZE_OSC
    "white" -> WHITE_NOISE
    "pink" -> PINK_NOISE
    "brown" -> BROWN_NOISE
    "const" -> CONST
    else -> UNKNOWN_SOURCE
}

internal fun getCommand(name: String): Int = when (name) {
    "play" -> CMD_PLAY
    "update_all" -> CMD_UPDATE_ALL
    "update_group" -> CMD_UPDATE_GROUP
    "panic" -> CMD_PANIC
    "hush" -> CMD_HUSH
    "hush_endless" -> CMD_HUSH_ENDLESS
    "hush_group" -> CMD_HUSH_GROUP
    "reset" -> CMD_RESET
    "reset_time" -> CMD_RESET_TIME
    "reset_schedule" -> CMD_RESET_SCHEDULE
    else -> CMD_UNKNOWN
}

/** `get_ftype`: the filter slope, 12 dB per octave as 0, 24 as 1, 48 as 2, anything else as a number. */
internal fun getFtype(name: String): Int = when (name) {
    "12db" -> 0
    "24db" -> 1
    "48db" -> 2
    else -> cAtoi(name)
}

/** The event of dough.h: everything a voice is started or changed with. Fields keep the names of the C struct (`lp_active` is [lpActive]). */
internal class DoughEvent {
    @JvmField var cmd = CMD_PLAY
    @JvmField var group = -1
    @JvmField var voices = -1
    @JvmField var reset = -1
    @JvmField var freq = Float.NaN
    @JvmField var rate = Float.NaN
    @JvmField var speed = Float.NaN

    @JvmField var glide = Float.NaN
    @JvmField var glideActive = false

    @JvmField var gain = Float.NaN
    @JvmField var pan = Float.NaN
    @JvmField var velocity = Float.NaN
    @JvmField var postgain = Float.NaN
    @JvmField var gainAdsrActive = false
    @JvmField var gate = Float.NaN
    @JvmField var duration = Float.NaN
    @JvmField var sound = UNKNOWN_SOURCE
    @JvmField var fileSource: DoughFile? = null
    @JvmField var file = -1
    @JvmField var pw = Float.NaN

    @JvmField var attack = Float.NaN
    @JvmField var decay = Float.NaN
    @JvmField var sustain = Float.NaN
    @JvmField var release = Float.NaN

    @JvmField var lpf = Float.NaN
    @JvmField var lpq = Float.NaN
    @JvmField var lpActive = false
    @JvmField var lpe = Float.NaN
    @JvmField var lpa = Float.NaN
    @JvmField var lpd = Float.NaN
    @JvmField var lps = Float.NaN
    @JvmField var lpr = Float.NaN
    @JvmField var lpAdsrActive = false

    @JvmField var hpf = Float.NaN
    @JvmField var hpq = Float.NaN
    @JvmField var hpActive = false
    @JvmField var hpe = Float.NaN
    @JvmField var hpa = Float.NaN
    @JvmField var hpd = Float.NaN
    @JvmField var hps = Float.NaN
    @JvmField var hpr = Float.NaN
    @JvmField var hpAdsrActive = false

    @JvmField var bpf = Float.NaN
    @JvmField var bpq = Float.NaN
    @JvmField var bpActive = false
    @JvmField var bpe = Float.NaN
    @JvmField var bpa = Float.NaN
    @JvmField var bpd = Float.NaN
    @JvmField var bps = Float.NaN
    @JvmField var bpr = Float.NaN
    @JvmField var bpAdsrActive = false

    @JvmField var ftype = -1

    @JvmField var penv = Float.NaN
    @JvmField var patt = Float.NaN
    @JvmField var pdec = Float.NaN
    @JvmField var psus = Float.NaN
    @JvmField var prel = Float.NaN
    @JvmField var pAdsrActive = false

    @JvmField var vib = Float.NaN
    @JvmField var vibmod = Float.NaN
    @JvmField var vibActive = false

    @JvmField var fm = Float.NaN
    @JvmField var fmh = Float.NaN
    @JvmField var fmActive = false
    @JvmField var fme = Float.NaN
    @JvmField var fma = Float.NaN
    @JvmField var fmd = Float.NaN
    @JvmField var fms = Float.NaN
    @JvmField var fmr = Float.NaN
    @JvmField var fmAdsrActive = false

    @JvmField var am = Float.NaN
    @JvmField var amdepth = Float.NaN
    @JvmField var amActive = false

    @JvmField var rm = Float.NaN
    @JvmField var rmdepth = Float.NaN
    @JvmField var rmActive = false

    @JvmField var phaser = Float.NaN
    @JvmField var phaserdepth = Float.NaN
    @JvmField var phasersweep = Float.NaN
    @JvmField var phasercenter = Float.NaN
    @JvmField var phaserActive = false

    @JvmField var flanger = Float.NaN
    @JvmField var flangerdepth = Float.NaN
    @JvmField var flangerfeedback = Float.NaN
    @JvmField var flangerActive = false

    @JvmField var chorus = Float.NaN
    @JvmField var chorusdepth = Float.NaN
    @JvmField var chorusdelay = Float.NaN
    @JvmField var chorusActive = false

    @JvmField var begin = Float.NaN
    @JvmField var end = Float.NaN

    @JvmField var coarse = Float.NaN
    @JvmField var coarseActive = false

    @JvmField var crush = Float.NaN
    @JvmField var crushActive = false

    @JvmField var distort = Float.NaN
    @JvmField var distortvol = Float.NaN
    @JvmField var distortActive = false

    @JvmField var delay = Float.NaN
    @JvmField var delaytime = Float.NaN
    @JvmField var delayfeedback = Float.NaN
    @JvmField var delayActive = false

    @JvmField var verb = Float.NaN
    @JvmField var verbdecay = Float.NaN
    @JvmField var verbdamp = Float.NaN
    @JvmField var verbpredelay = Float.NaN
    @JvmField var verbdiff = Float.NaN
    @JvmField var verbActive = false

    @JvmField var orbit = -1
    @JvmField var channels = 1
    @JvmField var time = Double.NaN
    @JvmField var repeat = Float.NaN

    /** `reset_event`: nothing given. (`channels` and the derived `rate` are left alone, as in C.) */
    fun reset() {
        file = -1
        time = Double.NaN
        repeat = Float.NaN
        orbit = -1
        group = -1
        voices = -1
        freq = Float.NaN
        speed = Float.NaN
        glide = Float.NaN
        glideActive = false
        gain = Float.NaN
        pan = Float.NaN
        gate = Float.NaN
        velocity = Float.NaN
        postgain = Float.NaN
        duration = Float.NaN
        sound = UNKNOWN_SOURCE
        fileSource = null
        pw = Float.NaN
        this.reset = -1
        attack = Float.NaN
        decay = Float.NaN
        sustain = Float.NaN
        release = Float.NaN
        gainAdsrActive = false
        lpf = Float.NaN
        lpq = Float.NaN
        lpe = Float.NaN
        lpa = Float.NaN
        lpd = Float.NaN
        lps = Float.NaN
        lpr = Float.NaN
        lpActive = false
        lpAdsrActive = false
        hpf = Float.NaN
        hpq = Float.NaN
        hpe = Float.NaN
        hpa = Float.NaN
        hpd = Float.NaN
        hps = Float.NaN
        hpr = Float.NaN
        hpActive = false
        hpAdsrActive = false
        bpf = Float.NaN
        bpq = Float.NaN
        bpe = Float.NaN
        bpa = Float.NaN
        bpd = Float.NaN
        bps = Float.NaN
        bpr = Float.NaN
        bpActive = false
        bpAdsrActive = false
        ftype = -1
        penv = Float.NaN
        patt = Float.NaN
        pdec = Float.NaN
        psus = Float.NaN
        prel = Float.NaN
        pAdsrActive = false
        vib = Float.NaN
        vibmod = Float.NaN
        vibActive = false
        fm = Float.NaN
        fmh = Float.NaN
        fmActive = false
        fme = Float.NaN
        fma = Float.NaN
        fmd = Float.NaN
        fms = Float.NaN
        fmr = Float.NaN
        fmAdsrActive = false
        am = Float.NaN
        amdepth = Float.NaN
        amActive = false
        rm = Float.NaN
        rmdepth = Float.NaN
        rmActive = false
        phaser = Float.NaN
        phaserdepth = Float.NaN
        phasersweep = Float.NaN
        phasercenter = Float.NaN
        phaserActive = false
        flanger = Float.NaN
        flangerdepth = Float.NaN
        flangerfeedback = Float.NaN
        flangerActive = false
        chorus = Float.NaN
        chorusdepth = Float.NaN
        chorusdelay = Float.NaN
        chorusActive = false
        begin = Float.NaN
        end = Float.NaN
        crush = Float.NaN
        crushActive = false
        coarse = Float.NaN
        coarseActive = false
        distort = Float.NaN
        distortvol = Float.NaN
        distortActive = false
        delay = Float.NaN
        delaytime = Float.NaN
        delayfeedback = Float.NaN
        delayActive = false
        verb = Float.NaN
        verbdecay = Float.NaN
        verbdamp = Float.NaN
        verbpredelay = Float.NaN
        verbdiff = Float.NaN
        verbActive = false
        cmd = CMD_PLAY
    }

    /** The struct copy `v->p = *p`. */
    fun copyFrom(o: DoughEvent) {
        cmd = o.cmd
        group = o.group
        voices = o.voices
        reset = o.reset
        freq = o.freq
        rate = o.rate
        speed = o.speed
        glide = o.glide
        glideActive = o.glideActive
        gain = o.gain
        pan = o.pan
        velocity = o.velocity
        postgain = o.postgain
        gainAdsrActive = o.gainAdsrActive
        gate = o.gate
        duration = o.duration
        sound = o.sound
        fileSource = o.fileSource
        file = o.file
        pw = o.pw
        attack = o.attack
        decay = o.decay
        sustain = o.sustain
        release = o.release
        lpf = o.lpf
        lpq = o.lpq
        lpActive = o.lpActive
        lpe = o.lpe
        lpa = o.lpa
        lpd = o.lpd
        lps = o.lps
        lpr = o.lpr
        lpAdsrActive = o.lpAdsrActive
        hpf = o.hpf
        hpq = o.hpq
        hpActive = o.hpActive
        hpe = o.hpe
        hpa = o.hpa
        hpd = o.hpd
        hps = o.hps
        hpr = o.hpr
        hpAdsrActive = o.hpAdsrActive
        bpf = o.bpf
        bpq = o.bpq
        bpActive = o.bpActive
        bpe = o.bpe
        bpa = o.bpa
        bpd = o.bpd
        bps = o.bps
        bpr = o.bpr
        bpAdsrActive = o.bpAdsrActive
        ftype = o.ftype
        penv = o.penv
        patt = o.patt
        pdec = o.pdec
        psus = o.psus
        prel = o.prel
        pAdsrActive = o.pAdsrActive
        vib = o.vib
        vibmod = o.vibmod
        vibActive = o.vibActive
        fm = o.fm
        fmh = o.fmh
        fmActive = o.fmActive
        fme = o.fme
        fma = o.fma
        fmd = o.fmd
        fms = o.fms
        fmr = o.fmr
        fmAdsrActive = o.fmAdsrActive
        am = o.am
        amdepth = o.amdepth
        amActive = o.amActive
        rm = o.rm
        rmdepth = o.rmdepth
        rmActive = o.rmActive
        phaser = o.phaser
        phaserdepth = o.phaserdepth
        phasersweep = o.phasersweep
        phasercenter = o.phasercenter
        phaserActive = o.phaserActive
        flanger = o.flanger
        flangerdepth = o.flangerdepth
        flangerfeedback = o.flangerfeedback
        flangerActive = o.flangerActive
        chorus = o.chorus
        chorusdepth = o.chorusdepth
        chorusdelay = o.chorusdelay
        chorusActive = o.chorusActive
        begin = o.begin
        end = o.end
        coarse = o.coarse
        coarseActive = o.coarseActive
        crush = o.crush
        crushActive = o.crushActive
        distort = o.distort
        distortvol = o.distortvol
        distortActive = o.distortActive
        delay = o.delay
        delaytime = o.delaytime
        delayfeedback = o.delayfeedback
        delayActive = o.delayActive
        verb = o.verb
        verbdecay = o.verbdecay
        verbdamp = o.verbdamp
        verbpredelay = o.verbpredelay
        verbdiff = o.verbdiff
        verbActive = o.verbActive
        orbit = o.orbit
        channels = o.channels
        time = o.time
        repeat = o.repeat
    }

    /**
     * `merge_event`: assigns what [source] gives to this event (a running voice). Fields like the time and the command do not make sense to merge, and neither do the
     * source's `file`, `group`, `voices`, `reset` and `coarse` (which the C code leaves out).
     */
    fun merge(source: DoughEvent) {
        if (source.orbit != -1) orbit = source.orbit
        if (source.sound != -1) sound = source.sound
        if (source.ftype != -1) ftype = source.ftype
        if (!source.freq.isNaN()) freq = source.freq
        if (!source.speed.isNaN()) speed = source.speed
        if (!source.glide.isNaN()) glide = source.glide
        if (!source.gain.isNaN()) gain = source.gain
        if (!source.pan.isNaN()) pan = source.pan
        if (!source.gate.isNaN()) gate = source.gate
        if (!source.velocity.isNaN()) velocity = source.velocity
        if (!source.postgain.isNaN()) postgain = source.postgain
        if (!source.duration.isNaN()) duration = source.duration
        if (!source.pw.isNaN()) pw = source.pw
        if (!source.attack.isNaN()) attack = source.attack
        if (!source.decay.isNaN()) decay = source.decay
        if (!source.sustain.isNaN()) sustain = source.sustain
        if (!source.release.isNaN()) release = source.release
        if (!source.lpf.isNaN()) lpf = source.lpf
        if (!source.lpq.isNaN()) lpq = source.lpq
        if (!source.lpe.isNaN()) lpe = source.lpe
        if (!source.lpa.isNaN()) lpa = source.lpa
        if (!source.lpd.isNaN()) lpd = source.lpd
        if (!source.lps.isNaN()) lps = source.lps
        if (!source.lpr.isNaN()) lpr = source.lpr
        if (!source.hpf.isNaN()) hpf = source.hpf
        if (!source.hpq.isNaN()) hpq = source.hpq
        if (!source.hpe.isNaN()) hpe = source.hpe
        if (!source.hpa.isNaN()) hpa = source.hpa
        if (!source.hpd.isNaN()) hpd = source.hpd
        if (!source.hps.isNaN()) hps = source.hps
        if (!source.hpr.isNaN()) hpr = source.hpr
        if (!source.bpf.isNaN()) bpf = source.bpf
        if (!source.bpq.isNaN()) bpq = source.bpq
        if (!source.bpe.isNaN()) bpe = source.bpe
        if (!source.bpa.isNaN()) bpa = source.bpa
        if (!source.bpd.isNaN()) bpd = source.bpd
        if (!source.bps.isNaN()) bps = source.bps
        if (!source.bpr.isNaN()) bpr = source.bpr
        if (!source.penv.isNaN()) penv = source.penv
        if (!source.patt.isNaN()) patt = source.patt
        if (!source.pdec.isNaN()) pdec = source.pdec
        if (!source.psus.isNaN()) psus = source.psus
        if (!source.prel.isNaN()) prel = source.prel
        if (!source.vib.isNaN()) vib = source.vib
        if (!source.vibmod.isNaN()) vibmod = source.vibmod
        if (!source.fm.isNaN()) fm = source.fm
        if (!source.fmh.isNaN()) fmh = source.fmh
        if (!source.fme.isNaN()) fme = source.fme
        if (!source.fma.isNaN()) fma = source.fma
        if (!source.fmd.isNaN()) fmd = source.fmd
        if (!source.fms.isNaN()) fms = source.fms
        if (!source.fmr.isNaN()) fmr = source.fmr
        if (!source.am.isNaN()) am = source.am
        if (!source.amdepth.isNaN()) amdepth = source.amdepth
        if (!source.rm.isNaN()) rm = source.rm
        if (!source.rmdepth.isNaN()) rmdepth = source.rmdepth
        if (!source.phaser.isNaN()) phaser = source.phaser
        if (!source.phaserdepth.isNaN()) phaserdepth = source.phaserdepth
        if (!source.phasersweep.isNaN()) phasersweep = source.phasersweep
        if (!source.phasercenter.isNaN()) phasercenter = source.phasercenter
        if (!source.flanger.isNaN()) flanger = source.flanger
        if (!source.flangerdepth.isNaN()) flangerdepth = source.flangerdepth
        if (!source.flangerfeedback.isNaN()) flangerfeedback = source.flangerfeedback
        if (!source.chorus.isNaN()) chorus = source.chorus
        if (!source.chorusdepth.isNaN()) chorusdepth = source.chorusdepth
        if (!source.chorusdelay.isNaN()) chorusdelay = source.chorusdelay
        if (!source.begin.isNaN()) begin = source.begin
        if (!source.end.isNaN()) end = source.end
        if (!source.crush.isNaN()) crush = source.crush
        if (!source.distort.isNaN()) distort = source.distort
        if (!source.distortvol.isNaN()) distortvol = source.distortvol
        if (!source.delay.isNaN()) delay = source.delay
        if (!source.delaytime.isNaN()) delaytime = source.delaytime
        if (!source.delayfeedback.isNaN()) delayfeedback = source.delayfeedback
        if (!source.verb.isNaN()) verb = source.verb
        if (!source.verbdecay.isNaN()) verbdecay = source.verbdecay
        if (!source.verbdamp.isNaN()) verbdamp = source.verbdamp
        if (!source.verbpredelay.isNaN()) verbpredelay = source.verbpredelay
        if (!source.verbdiff.isNaN()) verbdiff = source.verbdiff
    }
}

/**
 * `parse_event`: reads the text of an event, `key/value/key/value` (the way `Dough.encodeEvent` writes it), into [p], which must have been [DoughEvent.reset]. At most 1023 bytes
 * are read. The text is split at every `/` without joining neighbours (an empty value is an empty token) and the tokens alternate key and value, so a value that contains a `/`
 * shifts everything after it. Keys the engine does not know are skipped, which is why Strudel's canonical names (`pattack`, `hpattack`, `fmenv`...) have no effect on dough.
 */
internal fun parseEvent(p: DoughEvent, msg: String) {
    var bytes = msg.toByteArray(Charsets.UTF_8)
    if (bytes.size > EVENT_INPUT_SIZE - 1) bytes = bytes.copyOf(EVENT_INPUT_SIZE - 1)
    var length = bytes.indexOf(0)
    if (length < 0) length = bytes.size
    val text = String(bytes, 0, length, Charsets.ISO_8859_1)
    var pos = 0
    var index = 0
    var key = ""
    while (pos < length) {
        val slash = text.indexOf('/', pos)
        val stop = if (slash < 0) length else slash
        val token = text.substring(pos, stop)
        pos = stop + 1
        if (index % 2 == 0) {
            key = token
        } else {
            setField(p, key, token)
        }
        index++
    }
}

/** One `key/value` pair of [parseEvent]. */
private fun setField(p: DoughEvent, key: String, token: String) {
    when (key) {
        "dough", "dirt" -> p.cmd = getCommand(token)
        "time", "t" -> p.time = cAtof(token)
        "repeat", "rep" -> p.repeat = cAtof(token).toFloat()
        "gate" -> p.gate = cAtof(token).toFloat()
        "duration", "d" -> p.duration = cAtof(token).toFloat()
        "group" -> p.group = cAtoi(token)
        "voices" -> p.voices = cAtoi(token)
        "freq" -> p.freq = cAtof(token).toFloat()
        "note" -> p.freq = midi2freq(cAtof(token).toFloat())
        "speed" -> p.speed = cAtof(token).toFloat()
        "glide" -> p.glide = cAtof(token).toFloat()
        "sound", "s" -> p.sound = getSource(token)
        "file" -> p.file = cAtoi(token)
        "pw" -> p.pw = cAtof(token).toFloat()
        "lpf", "cutoff" -> p.lpf = cAtof(token).toFloat()
        "lpq", "resonance" -> p.lpq = cAtof(token).toFloat()
        "lpa", "lpattack" -> p.lpa = cAtof(token).toFloat()
        "lpd", "lpdecay" -> p.lpd = cAtof(token).toFloat()
        "lps", "lpsustain" -> p.lps = cAtof(token).toFloat()
        "lpr", "lprelease" -> p.lpr = cAtof(token).toFloat()
        "lpe", "lpenv" -> p.lpe = cAtof(token).toFloat()
        "hpf", "hcutoff" -> p.hpf = cAtof(token).toFloat()
        "hpq", "hresonance" -> p.hpq = cAtof(token).toFloat()
        "hpa" -> p.hpa = cAtof(token).toFloat()
        "hpd" -> p.hpd = cAtof(token).toFloat()
        "hps" -> p.hps = cAtof(token).toFloat()
        "hpr" -> p.hpr = cAtof(token).toFloat()
        "hpe", "hpenv" -> p.hpe = cAtof(token).toFloat()
        "bpf", "bandf" -> p.bpf = cAtof(token).toFloat()
        "bpq", "bandq" -> p.bpq = cAtof(token).toFloat()
        "bpa", "bpattack" -> p.bpa = cAtof(token).toFloat()
        "bpd", "bpdecay" -> p.bpd = cAtof(token).toFloat()
        "bps", "bpsustain" -> p.bps = cAtof(token).toFloat()
        "bpr", "bprelease" -> p.bpr = cAtof(token).toFloat()
        "bpe", "bpenv" -> p.bpe = cAtof(token).toFloat()
        "ftype" -> p.ftype = getFtype(token)
        "gain" -> p.gain = cAtof(token).toFloat()
        "postgain" -> p.postgain = cAtof(token).toFloat()
        "velocity" -> p.velocity = cAtof(token).toFloat()
        "pan" -> p.pan = cAtof(token).toFloat()
        "reset" -> p.reset = cAtoi(token)
        "attack" -> p.attack = cAtof(token).toFloat()
        "decay" -> p.decay = cAtof(token).toFloat()
        "sustain" -> p.sustain = cAtof(token).toFloat()
        "release" -> p.release = cAtof(token).toFloat()
        "penv" -> p.penv = cAtof(token).toFloat()
        "patt" -> p.patt = cAtof(token).toFloat()
        "pdec" -> p.pdec = cAtof(token).toFloat()
        "psus" -> p.psus = cAtof(token).toFloat()
        "prel" -> p.prel = cAtof(token).toFloat()
        "vib" -> p.vib = cAtof(token).toFloat()
        "vibmod" -> p.vibmod = cAtof(token).toFloat()
        "fm", "fmi" -> p.fm = cAtof(token).toFloat()
        "fmh" -> p.fmh = cAtof(token).toFloat()
        "fme" -> p.fme = cAtof(token).toFloat()
        "fma" -> p.fma = cAtof(token).toFloat()
        "fmd" -> p.fmd = cAtof(token).toFloat()
        "fms" -> p.fms = cAtof(token).toFloat()
        "fmr" -> p.fmr = cAtof(token).toFloat()
        "am" -> p.am = cAtof(token).toFloat()
        "amdepth" -> p.amdepth = cAtof(token).toFloat()
        "rm" -> p.rm = cAtof(token).toFloat()
        "rmdepth" -> p.rmdepth = cAtof(token).toFloat()
        "phaser", "phaserrate" -> p.phaser = cAtof(token).toFloat()
        "phaserdepth" -> p.phaserdepth = cAtof(token).toFloat()
        "phasersweep" -> p.phasersweep = cAtof(token).toFloat()
        "phasercenter" -> p.phasercenter = cAtof(token).toFloat()
        "flanger", "flangerrate" -> p.flanger = cAtof(token).toFloat()
        "flangerdepth" -> p.flangerdepth = cAtof(token).toFloat()
        "flangerfeedback" -> p.flangerfeedback = cAtof(token).toFloat()
        "chorus", "chorusrate" -> p.chorus = cAtof(token).toFloat()
        "chorusdepth" -> p.chorusdepth = cAtof(token).toFloat()
        "chorusdelay" -> p.chorusdelay = cAtof(token).toFloat()
        "begin" -> p.begin = clamp(cAtof(token).toFloat(), 0f, 1f)
        "end" -> p.end = clamp(cAtof(token).toFloat(), 0f, 1f)
        "coarse" -> p.coarse = cAtof(token).toFloat()
        "crush" -> p.crush = cAtof(token).toFloat()
        "distort" -> p.distort = cAtof(token).toFloat()
        "distortvol" -> p.distortvol = cAtof(token).toFloat()
        "delay" -> p.delay = cAtof(token).toFloat()
        "delaytime" -> p.delaytime = cAtof(token).toFloat()
        "delayfeedback" -> p.delayfeedback = cAtof(token).toFloat()
        "verb", "reverb", "room" -> p.verb = cAtof(token).toFloat()
        "verbdecay", "roomdecay" -> p.verbdecay = cAtof(token).toFloat()
        "verbdamp", "roomdamp" -> p.verbdamp = cAtof(token).toFloat()
        "verbpredelay", "roompredelay" -> p.verbpredelay = cAtof(token).toFloat()
        "verbdiff", "roomdiff" -> p.verbdiff = cAtof(token).toFloat()
        "orbit" -> p.orbit = cAtoi(token)
        else -> Unit
    }
}

// ---- init_event -----------------------------------------------------------------------------------------------------------------------

private fun initVib(p: DoughEvent) {
    if (p.vib.isNaN() && p.vibmod.isNaN()) {
        p.vibActive = false
        return
    }
    p.vibmod = nanFallback(p.vibmod, 0.15f)
    p.vib = nanFallback(p.vib, 4f)
    p.vibActive = true
}

private fun initFm(p: DoughEvent) {
    if (p.fm.isNaN() && p.fmh.isNaN()) return
    p.fm = nanFallback(p.fm, 1.0f)
    p.fmh = nanFallback(p.fmh, 1.0f)
    p.fmActive = true
}

private fun initAm(p: DoughEvent) {
    if (p.am.isNaN() && p.amdepth.isNaN()) {
        p.amActive = false
        return
    }
    p.amActive = true
    p.am = nanFallback(p.am, 4.0f)
    p.amdepth = nanFallback(p.amdepth, 0.5f)
}

private fun initRm(p: DoughEvent) {
    if (p.rm.isNaN() && p.rmdepth.isNaN()) {
        p.rmActive = false
        return
    }
    p.rmActive = true
    p.rmdepth = nanFallback(p.rmdepth, 1.0f)
}

private fun initPhaser(p: DoughEvent) {
    if (p.phaser.isNaN() && p.phaserdepth.isNaN() && p.phasersweep.isNaN() && p.phasercenter.isNaN()) {
        p.phaserActive = false
        return
    }
    p.phaserActive = true
    p.phaser = nanFallback(p.phaser, 1.0f)
    p.phaserdepth = nanFallback(p.phaserdepth, 0.75f)
    p.phasersweep = nanFallback(p.phasersweep, 2000.0f)
    p.phasercenter = nanFallback(p.phasercenter, 1000.0f)
}

private fun initFlanger(p: DoughEvent) {
    if (p.flanger.isNaN() && p.flangerdepth.isNaN() && p.flangerfeedback.isNaN()) {
        p.flangerActive = false
        return
    }
    p.flangerActive = true
    p.flanger = nanFallback(p.flanger, 0.5f)
    p.flangerdepth = nanFallback(p.flangerdepth, 0.5f)
    p.flangerfeedback = nanFallback(p.flangerfeedback, 0.5f)
}

private fun initChorus(p: DoughEvent) {
    if (p.chorus.isNaN() && p.chorusdepth.isNaN() && p.chorusdelay.isNaN()) {
        p.chorusActive = false
        return
    }
    p.chorusActive = true
    p.chorus = nanFallback(p.chorus, 0.5f)
    p.chorusdepth = nanFallback(p.chorusdepth, 0.5f)
    p.chorusdelay = nanFallback(p.chorusdelay, 25.0f)
}

private fun initLp(p: DoughEvent) {
    if (p.lpe.isNaN() && p.lpf.isNaN()) return
    p.lpf = nanFallback(p.lpf, 20000f)
    p.lpq = nanFallback(p.lpq, 0.2f)
    p.lpActive = true
}

private fun initHp(p: DoughEvent) {
    if (p.hpe.isNaN() && p.hpf.isNaN()) return
    p.hpf = nanFallback(p.hpf, 0f)
    p.hpq = nanFallback(p.hpq, 0.2f)
    p.hpActive = true
}

private fun initBp(p: DoughEvent) {
    if (p.bpe.isNaN() && p.bpf.isNaN()) return
    p.bpf = nanFallback(p.bpf, 20000f)
    p.bpq = nanFallback(p.bpq, 0.2f)
    p.bpActive = true
}

private fun initDistort(p: DoughEvent) {
    if (p.distort.isNaN()) return
    p.distortvol = nanFallback(p.distortvol, 1f)
    p.distortActive = true
}

private fun initDelay(p: DoughEvent) {
    if (p.delay.isNaN() && p.delaytime.isNaN() && p.delayfeedback.isNaN()) return
    p.delay = nanFallback(p.delay, 0.5f)
    p.delaytime = nanFallback(p.delaytime, 0.333f)
    p.delayfeedback = nanFallback(p.delayfeedback, 0.6f)
    p.delayActive = true
}

private fun initVerb(p: DoughEvent) {
    if (p.verb.isNaN() && p.verbdecay.isNaN() && p.verbdamp.isNaN() && p.verbpredelay.isNaN() && p.verbdiff.isNaN()) return
    p.verb = nanFallback(p.verb, 0.5f)
    p.verbdecay = nanFallback(p.verbdecay, 0.75f)
    p.verbdamp = nanFallback(p.verbdamp, 0.95f)
    p.verbpredelay = nanFallback(p.verbpredelay, 0.1f)
    p.verbdiff = nanFallback(p.verbdiff, 0.7f)
    p.verbActive = true
}

/** Runs `init_envelope` for the five fields picked by the caller: [f] holds them on the way in and out. */
private inline fun envelope(f: EnvelopeFields, load: (EnvelopeFields) -> Unit, store: (EnvelopeFields) -> Unit) {
    load(f)
    initEnvelope(f)
    store(f)
}

/**
 * `init_event`: completes [p] for a voice, from the file it names (looked up by `file` in [files]), the engine's rate [sr] and the defaults of every stage that is on.
 * It may run again on a voice that is already playing (after an update), which is why `freq` is kept and `rate` derived from it every time.
 */
internal fun initEvent(p: DoughEvent, files: java.util.concurrent.atomic.AtomicReferenceArray<DoughFile?>, sr: Float, scratch: EnvelopeFields) {
    p.channels = 1
    p.rate = p.freq
    if (p.file != -1) {
        if (p.file >= 0 && p.file < files.length()) p.fileSource = files.get(p.file)
    }
    val file = p.fileSource
    if (file != null) {
        p.sound = FILE_SRC
        p.begin = nanFallback(p.begin, 0.0f)
        p.end = nanFallback(p.end, 1.0f)
        p.channels = file.channels
        p.rate = if (p.rate.isNaN()) 1f else p.rate / file.freq
        val sliceFrames = ((p.end - p.begin) * file.frames.toFloat()).toInt()
        if (sliceFrames != 0) p.rate *= sr / sliceFrames.toFloat()
    } else if (p.sound == UNKNOWN_SOURCE) {
        p.sound = TRI_OSC
    }
    p.gate = nanFallback(p.gate, 1.0f)
    p.gain = nanFallback(p.gain, 1.0f)
    p.velocity = nanFallback(p.velocity, 1.0f)
    p.postgain = nanFallback(p.postgain, 1.0f)
    p.rate = nanFallback(p.rate, 330.0f)
    p.speed = nanFallback(p.speed, 1.0f)
    p.gate = nanFallback(p.gate, 1.0f)
    p.pan = nanFallback(p.pan, 0.5f)
    p.pw = nanFallback(p.pw, 0.5f)
    initVib(p)
    initFm(p)
    initAm(p)
    initRm(p)
    initPhaser(p)
    initFlanger(p)
    initChorus(p)
    initLp(p)
    initHp(p)
    initBp(p)
    initDistort(p)
    initDelay(p)
    initVerb(p)
    if (!p.coarse.isNaN()) p.coarseActive = true
    if (!p.crush.isNaN()) p.crushActive = true
    if (!p.glide.isNaN()) p.glideActive = true
    if (p.ftype == -1) p.ftype = 0
    envelope(scratch, { it.env = p.lpe; it.att = p.lpa; it.dec = p.lpd; it.sus = p.lps; it.rel = p.lpr }, {
        p.lpe = it.env; p.lpa = it.att; p.lpd = it.dec; p.lps = it.sus; p.lpr = it.rel; p.lpAdsrActive = it.active
    })
    envelope(scratch, { it.env = p.hpe; it.att = p.hpa; it.dec = p.hpd; it.sus = p.hps; it.rel = p.hpr }, {
        p.hpe = it.env; p.hpa = it.att; p.hpd = it.dec; p.hps = it.sus; p.hpr = it.rel; p.hpAdsrActive = it.active
    })
    envelope(scratch, { it.env = p.bpe; it.att = p.bpa; it.dec = p.bpd; it.sus = p.bps; it.rel = p.bpr }, {
        p.bpe = it.env; p.bpa = it.att; p.bpd = it.dec; p.bps = it.sus; p.bpr = it.rel; p.bpAdsrActive = it.active
    })
    envelope(scratch, { it.env = p.penv; it.att = p.patt; it.dec = p.pdec; it.sus = p.psus; it.rel = p.prel }, {
        p.penv = it.env; p.patt = it.att; p.pdec = it.dec; p.psus = it.sus; p.prel = it.rel; p.pAdsrActive = it.active
    })
    envelope(scratch, { it.env = p.gain; it.att = p.attack; it.dec = p.decay; it.sus = p.sustain; it.rel = p.release }, {
        p.gain = it.env; p.attack = it.att; p.decay = it.dec; p.sustain = it.sus; p.release = it.rel; p.gainAdsrActive = it.active
    })
    envelope(scratch, { it.env = p.fme; it.att = p.fma; it.dec = p.fmd; it.sus = p.fms; it.rel = p.fmr }, {
        p.fme = it.env; p.fma = it.att; p.fmd = it.dec; p.fms = it.sus; p.fmr = it.rel; p.fmAdsrActive = it.active
    })
    p.gainAdsrActive = true // always apply the gain envelope
}
