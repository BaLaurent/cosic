package cosic.dough

import cosic.SampleData
import cosic.SampleStore
import cosic.pattern.CosicError
import java.io.IOException
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The text of events, its parser, the table of sounds and the conversion of samples. */
class DoughBridgeTest {
    // ---- the text of an event ---------------------------------------------------------------------------------------------------

    @Test
    fun theTextOfEveryEventOfTheGoldenIsWhatDoughEncodeEventWrote() {
        val text = checkNotNull(DoughBridgeTest::class.java.getResourceAsStream("/dough/golden.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        var count = 0
        for (case in Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray) {
            for (event in case.jsonObject["events"]!!.jsonArray) {
                val o = event.jsonObject
                val controls = LinkedHashMap<String, Any?>()
                for ((key, value) in o["controls"]!!.jsonObject) {
                    val p = value as JsonPrimitive
                    controls[key] = if (p.isString) p.content else p.content.toDouble()
                }
                assertEquals(o["encoded"]!!.jsonPrimitive.content, encodeEvent(controls), case.jsonObject["name"]!!.jsonPrimitive.content)
                count++
            }
        }
        assertTrue(count > 100)
    }

    @Test
    fun valuesAreWrittenAsJavaScriptWritesThem() {
        val text = encodeEvent(linkedMapOf("a" to 1.0, "b" to 0.1, "c" to 1e21, "d" to 1e-7, "e" to true, "f" to listOf(1.0, "x"), "g" to -0.0, "h" to Double.NaN))
        assertEquals("a/1/b/0.1/c/1e+21/d/1e-7/e/true/f/1,x/g/0/h/NaN", text)
    }

    @Test
    fun aHapBecomesTheEventOfHap2dough() {
        val controls = linkedMapOf<String, Any?>("s" to "sine", "note" to "c4", "_cosic_engine" to "dough", "duration" to 9.0, "gain" to 0.5)
        val event = hapToDoughEvent(controls, 0.25)
        // `{dough: 'play', ...hap.value, time, duration: hap.duration / cps}`: the keys of the hap in order (duration keeps its place), `_cosic` controls left out, the note a number
        assertEquals("dough/play/s/sine/note/60/duration/0.25/gain/0.5", encodeEvent(event))
        assertEquals("dough/update_group/group/1", encodeEvent(hapToDoughEvent(linkedMapOf("dough" to "update_group", "group" to 1.0), 1.0)).substringBefore("/duration"))
        assertFailsWith<CosicError> { hapToDoughEvent(linkedMapOf("note" to "zz"), 1.0) }
    }

    // ---- the parser -------------------------------------------------------------------------------------------------------------

    private fun parse(text: String): DoughEvent = parseDoughEvent(text)

    private val floatKeys: List<Pair<List<String>, (DoughEvent) -> Float>> = listOf(
        listOf("repeat", "rep") to { e -> e.repeat }, listOf("gate") to { e -> e.gate }, listOf("duration", "d") to { e -> e.duration },
        listOf("freq") to { e -> e.freq }, listOf("speed") to { e -> e.speed }, listOf("glide") to { e -> e.glide }, listOf("pw") to { e -> e.pw },
        listOf("lpf", "cutoff") to { e -> e.lpf }, listOf("lpq", "resonance") to { e -> e.lpq }, listOf("lpa", "lpattack") to { e -> e.lpa },
        listOf("lpd", "lpdecay") to { e -> e.lpd }, listOf("lps", "lpsustain") to { e -> e.lps }, listOf("lpr", "lprelease") to { e -> e.lpr },
        listOf("lpe", "lpenv") to { e -> e.lpe },
        listOf("hpf", "hcutoff") to { e -> e.hpf }, listOf("hpq", "hresonance") to { e -> e.hpq }, listOf("hpa") to { e -> e.hpa }, listOf("hpd") to { e -> e.hpd },
        listOf("hps") to { e -> e.hps }, listOf("hpr") to { e -> e.hpr }, listOf("hpe", "hpenv") to { e -> e.hpe },
        listOf("bpf", "bandf") to { e -> e.bpf }, listOf("bpq", "bandq") to { e -> e.bpq }, listOf("bpa", "bpattack") to { e -> e.bpa },
        listOf("bpd", "bpdecay") to { e -> e.bpd }, listOf("bps", "bpsustain") to { e -> e.bps }, listOf("bpr", "bprelease") to { e -> e.bpr },
        listOf("bpe", "bpenv") to { e -> e.bpe },
        listOf("gain") to { e -> e.gain }, listOf("postgain") to { e -> e.postgain }, listOf("velocity") to { e -> e.velocity }, listOf("pan") to { e -> e.pan },
        listOf("attack") to { e -> e.attack }, listOf("decay") to { e -> e.decay }, listOf("sustain") to { e -> e.sustain }, listOf("release") to { e -> e.release },
        listOf("penv") to { e -> e.penv }, listOf("patt") to { e -> e.patt }, listOf("pdec") to { e -> e.pdec }, listOf("psus") to { e -> e.psus }, listOf("prel") to { e -> e.prel },
        listOf("vib") to { e -> e.vib }, listOf("vibmod") to { e -> e.vibmod },
        listOf("fm", "fmi") to { e -> e.fm }, listOf("fmh") to { e -> e.fmh }, listOf("fme") to { e -> e.fme }, listOf("fma") to { e -> e.fma },
        listOf("fmd") to { e -> e.fmd }, listOf("fms") to { e -> e.fms }, listOf("fmr") to { e -> e.fmr },
        listOf("am") to { e -> e.am }, listOf("amdepth") to { e -> e.amdepth }, listOf("rm") to { e -> e.rm }, listOf("rmdepth") to { e -> e.rmdepth },
        listOf("phaser", "phaserrate") to { e -> e.phaser }, listOf("phaserdepth") to { e -> e.phaserdepth }, listOf("phasersweep") to { e -> e.phasersweep },
        listOf("phasercenter") to { e -> e.phasercenter },
        listOf("flanger", "flangerrate") to { e -> e.flanger }, listOf("flangerdepth") to { e -> e.flangerdepth }, listOf("flangerfeedback") to { e -> e.flangerfeedback },
        listOf("chorus", "chorusrate") to { e -> e.chorus }, listOf("chorusdepth") to { e -> e.chorusdepth }, listOf("chorusdelay") to { e -> e.chorusdelay },
        listOf("coarse") to { e -> e.coarse }, listOf("crush") to { e -> e.crush }, listOf("distort") to { e -> e.distort }, listOf("distortvol") to { e -> e.distortvol },
        listOf("delay") to { e -> e.delay }, listOf("delaytime") to { e -> e.delaytime }, listOf("delayfeedback") to { e -> e.delayfeedback },
        listOf("verb", "reverb", "room") to { e -> e.verb }, listOf("verbdecay", "roomdecay") to { e -> e.verbdecay }, listOf("verbdamp", "roomdamp") to { e -> e.verbdamp },
        listOf("verbpredelay", "roompredelay") to { e -> e.verbpredelay }, listOf("verbdiff", "roomdiff") to { e -> e.verbdiff },
    )

    @Test
    fun everyKeyAndAliasOfTheEngineSetsItsField() {
        for ((keys, field) in floatKeys) {
            for (key in keys) assertEquals(0.5f, field(parse("$key/0.5")), key)
        }
        for (key in listOf("group")) assertEquals(3, parse("$key/3").group)
        assertEquals(3, parse("voices/3").voices)
        assertEquals(1, parse("reset/1").reset)
        assertEquals(2, parse("orbit/2").orbit)
        assertEquals(4, parse("file/4").file)
        for (key in listOf("time", "t")) assertEquals(0.25, parse("$key/0.25").time)
        for (key in listOf("dough", "dirt")) assertEquals(CMD_HUSH, parse("$key/hush").cmd)
        for (key in listOf("s", "sound")) assertEquals(SAW_OSC, parse("$key/saw").sound)
    }

    @Test
    fun theNoteIsMidiAndBeginAndEndAreClamped() {
        assertEquals(440f, parse("note/69").freq, 440f * 1e-3f) // through the approximation of exp2
        assertEquals(midi2freq(60f), parse("note/60").freq)
        assertEquals(0f, parse("begin/-3").begin)
        assertEquals(1f, parse("end/7").end)
        assertEquals(0.25f, parse("begin/0.25").begin)
    }

    @Test
    fun theSoundsAndCommandsAreTheOnesTheEngineKnows() {
        val sounds = mapOf(
            "triangle" to TRI_OSC, "tri" to TRI_OSC, "sine" to SINE_OSC, "sawtooth" to SAW_OSC, "saw" to SAW_OSC, "zawtooth" to ZAW_OSC, "zaw" to ZAW_OSC, "pulse" to PULSE_OSC,
            "square" to PULSE_OSC, "pulze" to PULZE_OSC, "zquare" to PULZE_OSC, "white" to WHITE_NOISE, "pink" to PINK_NOISE, "brown" to BROWN_NOISE, "const" to CONST,
            "bd" to UNKNOWN_SOURCE, "SINE" to UNKNOWN_SOURCE, "" to UNKNOWN_SOURCE,
        )
        for ((name, source) in sounds) assertEquals(source, getSource(name), name)
        val commands = mapOf(
            "play" to CMD_PLAY, "update_all" to CMD_UPDATE_ALL, "update_group" to CMD_UPDATE_GROUP, "panic" to CMD_PANIC, "hush" to CMD_HUSH, "hush_endless" to CMD_HUSH_ENDLESS,
            "hush_group" to CMD_HUSH_GROUP, "reset" to CMD_RESET, "reset_time" to CMD_RESET_TIME, "reset_schedule" to CMD_RESET_SCHEDULE, "stop" to CMD_UNKNOWN,
        )
        for ((name, command) in commands) assertEquals(command, getCommand(name), name)
        assertEquals(listOf(0, 1, 2, 7, 0), listOf("12db", "24db", "48db", "7", "x").map { getFtype(it) })
    }

    @Test
    fun keysTheEngineDoesNotKnowAreIgnored() {
        val e = parse("pattack/0.5/hpattack/0.1/fmenv/3/roomsize/2/clip/1/n/3/unit/c/bank/x/penv/12")
        assertTrue(e.patt.isNaN() && e.hpa.isNaN() && e.fme.isNaN())
        assertEquals(12f, e.penv)
    }

    @Test
    fun theValuesAreReadTheWayTheCCodeReadsThem() {
        assertEquals(0, cAtoi("3.5"))
        assertEquals(0, cAtoi("12abc"))
        assertEquals(0, cAtoi("+3"))
        assertEquals(-12, cAtoi("-12"))
        assertEquals(0.0, cAtof("true"))
        assertEquals(0.0, cAtof(""))
        assertEquals(1000.0, cAtof("1e3"))
        assertEquals(1.5, cAtof("1.5"))
        assertEquals(-0.0, cAtof("-"))
        assertEquals(1.5, cAtof("1.5e+2"), "a plus in the exponent ends it: atoi reads 0")
        assertEquals(0.012, cAtof("1.2e-2"), 1e-12)
    }

    @Test
    fun aSlashInAValueShiftsEverythingAfterIt() {
        // the tokens alternate key and value, so `bank/a/gain` makes gain the value of `bank`'s next key
        val e = parse("s/saw/bank/a/gain/gain/0.2/duration/0.05")
        assertEquals(0f, e.gain) // "gain" is read as a number: 0
        assertTrue(e.duration.isNaN())
        // an empty value is an empty token, not skipped, and a trailing slash adds nothing
        assertEquals(0f, parse("pan//lpf/5/").pan)
        assertEquals(5f, parse("pan//lpf/5/").lpf)
    }

    @Test
    fun onlyTheFirst1023BytesAreRead() {
        val text = "gain/0.5/" + "x/y/".repeat(300) + "pan/0.1"
        assertTrue(parse(text).pan.isNaN())
        assertEquals(0.5f, parse(text).gain)
    }

    // ---- the event struct -------------------------------------------------------------------------------------------------------

    private fun fieldsOf() = DoughEvent::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

    @Test
    fun copyFromCopiesEveryField() {
        val a = DoughEvent()
        val file = DoughFile(FloatArray(2), 1, 2, 1f)
        for ((i, f) in fieldsOf().withIndex()) {
            when (f.type) {
                java.lang.Float.TYPE -> f.setFloat(a, 1.5f + i)
                Integer.TYPE -> f.setInt(a, 7 + i)
                java.lang.Boolean.TYPE -> f.setBoolean(a, true)
                java.lang.Double.TYPE -> f.setDouble(a, 2.5 + i)
                else -> f.set(a, file)
            }
        }
        val b = DoughEvent()
        b.copyFrom(a)
        for (f in fieldsOf()) assertEquals(f.get(a), f.get(b), "${f.name} was not copied")
    }

    @Test
    fun mergeTakesWhatIsGivenAndOnlyThat() {
        val notMerged = setOf("cmd", "group", "voices", "reset", "rate", "fileSource", "file", "coarse", "channels", "time", "repeat")
        for (f in fieldsOf()) {
            val src = DoughEvent().also { it.reset() }
            val dst = DoughEvent().also { it.reset() }
            when (f.type) {
                java.lang.Float.TYPE -> f.setFloat(src, 3.25f)
                Integer.TYPE -> f.setInt(src, 3)
                else -> continue // flags, the time and the file are not merged
            }
            dst.merge(src)
            val merged = f.get(dst) == f.get(src)
            if (f.name in notMerged) assertFalse(merged, "${f.name} must not be merged") else assertTrue(merged, "${f.name} was not merged")
        }
    }

    // ---- the table of sounds ----------------------------------------------------------------------------------------------------

    private class UrlStore(private val data: Map<String, SampleData> = emptyMap(), private val texts: Map<String, String> = emptyMap()) : SampleStore {
        override fun fetchText(url: String): String = texts[url] ?: throw IOException("no text at $url")

        override fun sample(url: String): SampleData? = data[url]

        override fun awaitSample(url: String): SampleData = data[url] ?: throw IOException("no sample at $url")

        override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder")
    }

    private fun sampleOf(frames: Int, rate: Int = 48000, channels: Int = 1) = SampleData(FloatArray(frames * channels) { 0.5f }, rate, channels)

    /** Resolves [event] until the file is loaded (the first ask starts the load on a thread of its own). */
    private fun awaitResolved(sounds: DoughSounds, event: MutableMap<String, Any?>, warnings: MutableList<String> = ArrayList()): Boolean {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            warnings.clear()
            if (sounds.resolve(event, warnings::add)) return true
            Thread.sleep(2)
        }
        return false
    }

    private fun soundsWith(store: UrlStore, urls: Map<String, List<String>>): DoughSounds {
        val engine = DoughEngine(48000)
        val sounds = DoughSounds(48000, store, engine)
        sounds.register(urls, "")
        return sounds
    }

    @Test
    fun theEnginesOwnSoundsNeedNoFile() {
        val sounds = soundsWith(UrlStore(), emptyMap())
        for (name in listOf("triangle", "tri", "sine", "sawtooth", "saw", "zawtooth", "zaw", "pulse", "square", "pulze", "zquare", "white", "pink", "brown")) {
            val event = linkedMapOf<String, Any?>("s" to name)
            assertTrue(sounds.resolve(event) { error("warned about $name: $it") })
            assertFalse(event.containsKey("file"))
        }
        assertTrue(sounds.resolve(linkedMapOf("note" to 60.0)) { error(it) }, "no sound is no file")
    }

    @Test
    fun aNameThatIsNotInTheMapWarnsAndPlaysAnyway() {
        val sounds = soundsWith(UrlStore(), emptyMap())
        val warnings = ArrayList<String>()
        assertTrue(sounds.resolve(linkedMapOf("s" to "bd")) { warnings.add(it) })
        assertTrue(sounds.resolve(linkedMapOf("sound" to 3.0)) { warnings.add(it) })
        assertEquals(listOf("sound bd not found in soundMap", "sound 3 not found in soundMap"), warnings)
    }

    @Test
    fun aFileIsSkippedWhileItLoadsThenNamedByItsNumber() {
        val store = UrlStore(mapOf("https://x/a.wav" to sampleOf(4800), "https://x/b.wav" to sampleOf(2400)))
        val sounds = soundsWith(store, mapOf("kick" to listOf("https://x/a.wav", "https://x/b.wav")))
        val first = linkedMapOf<String, Any?>("s" to "kick", "n" to 1.0)
        assertTrue(awaitResolved(sounds, first))
        assertEquals(0.0, first["file"], "the first file loaded is number 0")
        val second = linkedMapOf<String, Any?>("s" to "kick")
        assertTrue(awaitResolved(sounds, second))
        assertEquals(1.0, second["file"])
        // `n` counts modulo the length: 3 is the second file again, but the key `kick:3` is a load of its own, which gets the next number
        val again = linkedMapOf<String, Any?>("s" to "kick", "n" to 3.0)
        assertTrue(awaitResolved(sounds, again))
        assertEquals(2.0, again["file"])
        val repeated = linkedMapOf<String, Any?>("s" to "kick", "n" to 3.0)
        assertTrue(awaitResolved(sounds, repeated))
        assertEquals(2.0, repeated["file"], "a key that was loaded is not loaded again")
    }

    @Test
    fun unitCScalesTheSpeedToTheLengthOfTheFile() {
        val store = UrlStore(mapOf("https://x/a.wav" to sampleOf(4800)))
        val sounds = soundsWith(store, mapOf("kick" to listOf("https://x/a.wav")))
        val plain = linkedMapOf<String, Any?>("s" to "kick", "unit" to "c")
        assertTrue(awaitResolved(sounds, plain))
        assertEquals(0.1, checkNotNull(plain["speed"] as Double), 1e-12)
        val fast = linkedMapOf<String, Any?>("s" to "kick", "unit" to "c", "speed" to 2.0)
        assertTrue(awaitResolved(sounds, fast))
        assertEquals(0.2, checkNotNull(fast["speed"] as Double), 1e-12)
        val other = linkedMapOf<String, Any?>("s" to "kick", "unit" to "s")
        assertTrue(awaitResolved(sounds, other))
        assertFalse(other.containsKey("speed"))
    }

    @Test
    fun aFileThatCannotBeLoadedWarnsAndIsNotTriedAgain() {
        val store = UrlStore()
        val sounds = soundsWith(store, mapOf("kick" to listOf("https://x/missing.wav", "https://x/b.wav")))
        val warnings = ArrayList<String>()
        val event = linkedMapOf<String, Any?>("s" to "kick")
        assertTrue(awaitResolved(sounds, event, warnings))
        assertEquals(listOf("error loading sound kick:0"), warnings)
        assertFalse(event.containsKey("file"))
        // an index that is no whole number below the length is no file either (`urls[1.5]` is undefined)
        for (n in listOf(1.5, -1.0, Double.NaN)) {
            val bad = linkedMapOf<String, Any?>("s" to "kick", "n" to n)
            val w = ArrayList<String>()
            assertTrue(awaitResolved(sounds, bad, w), "n $n")
            assertTrue(w.single().startsWith("error loading sound kick:"), "n $n: $w")
        }
    }

    @Test
    fun aMapRegistersListsOfFilesWithTheBaseInFront() {
        val sounds = DoughSounds(48000, UrlStore(), DoughEngine(48000))
        sounds.register(linkedMapOf("_base" to "ignored", "a" to listOf("x.wav", 3.0), "b" to "not a list", "c" to emptyList<Any?>()), "https://h/")
        assertEquals(listOf("https://h/x.wav", "https://h/3"), sounds.urls("a"))
        assertNull(sounds.urls("b"))
        assertNull(sounds.urls("_base"))
        assertEquals(emptyList(), sounds.urls("c"))
        // no base: `undefined + url`
        sounds.register(mapOf("d" to listOf("y.wav")), cosic.pattern.Undefined)
        assertEquals(listOf("undefinedy.wav"), sounds.urls("d"))
    }

    @Test
    fun theUrlsOfPseudoMaps() {
        assertEquals("https://raw.githubusercontent.com/u/r/main/strudel.json", mapUrl("github:u/r"))
        assertEquals("https://raw.githubusercontent.com/u/r/dev/strudel.json", mapUrl("github:u/r/dev/"))
        assertEquals("http://localhost:5432", mapUrl("local:anything"))
        assertEquals("https://shabda.ndre.gr/bass,drums.json?strudel=1", mapUrl("shabda:bass,drums"))
        assertEquals("https://shabda.ndre.gr/speech/hello.json?gender=f&language=en-GB&strudel=1'", mapUrl("shabda/speech:hello"))
        assertEquals("https://shabda.ndre.gr/speech/hi.json?gender=m&language=fr-FR&strudel=1'", mapUrl("shabda/speech/fr-FR/m:hi"))
        assertEquals("https://x/y.json", mapUrl("https://x/y.json"))
    }

    // ---- samples ----------------------------------------------------------------------------------------------------------------

    @Test
    fun aSampleAtTheEnginesRateAndOfTwoChannelsIsUsedAsItIs() {
        val data = sampleOf(100, 48000, 2)
        assertSame(data.samples, toDoughFile(data, 48000).pcm)
        val file = toDoughFile(data, 48000)
        assertEquals(100, file.frames)
        assertEquals(2, file.channels)
        assertEquals(65.46f, file.freq)
    }

    @Test
    fun aSampleAtAnotherRateIsConvertedAndMoreThanTwoChannelsAreCutToTwo() {
        val ramp = SampleData(FloatArray(441) { it / 441f }, 44100, 1)
        val file = toDoughFile(ramp, 48000)
        assertEquals(480, file.frames)
        for (i in 0 until file.frames) assertTrue(abs(file.pcm[i] - i / 480f) < 2e-3f, "frame $i: ${file.pcm[i]}")
        val surround = SampleData(FloatArray(10 * 4) { (it % 4).toFloat() }, 48000, 4)
        val two = toDoughFile(surround, 48000)
        assertEquals(2, two.channels)
        assertContentEquals(FloatArray(20) { (it % 2).toFloat() }, two.pcm)
        assertEquals(0, toDoughFile(SampleData(FloatArray(0), 48000, 1), 44100).frames)
    }
}
