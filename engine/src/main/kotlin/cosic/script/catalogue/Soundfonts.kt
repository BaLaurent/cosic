package cosic.script.catalogue

import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsNumberToString
import cosic.script.Realm
import cosic.script.js.JsObject
import cosic.soundfont.SoundfontFiles
import cosic.soundfont.soundfontPattern
import cosic.soundfont.soundfontUrl
import cosic.soundfont.sf2.Sf2File
import cosic.soundfont.sf2.Sf2Generator
import cosic.soundfont.sf2.Sf2Instrument
import cosic.soundfont.sf2.Sf2Preset
import cosic.soundfont.sf2.Sf2Sample
import cosic.soundfont.sf2.Sf2Zone

/**
 * A soundfont that `loadSoundfont(url)` read (the `SoundFont2` object of the `soundfont2` package): its [file] is what `.soundfont(sf, n)` plays; the program can also look at
 * `metaData`, `samples`, `instruments` and `presets`, which are made when they are first asked for.
 */
internal class SoundFontObject(val realm: Realm, val file: Sf2File) : JsObject(realm.objectPrototype) {
    override val className: String get() = "SoundFont2"

    init {
        val meta = realm.newObject()
        meta.defineOwn("version", file.version)
        meta.defineOwn("name", file.name)
        defineOwn("metaData", meta)
        val samples = lazy { file.samples.map { sampleObject(realm, it) } }
        val instruments = lazy { file.instruments.map { instrumentObject(realm, it, samples.value) } }
        val presets = lazy { file.presets.map { presetObject(realm, it, instruments.value) } }
        defineAccessor("samples", { realm.newArray(samples.value) })
        defineAccessor("instruments", { realm.newArray(instruments.value) })
        defineAccessor("presets", { realm.newArray(presets.value) })
    }

    private fun obj(realm: Realm, vararg entries: Pair<String, Any?>): JsObject = realm.newObject().also { o -> for ((k, v) in entries) o.defineOwn(k, v) }

    private fun sampleObject(realm: Realm, sample: Sf2Sample): JsObject {
        val h = sample.header
        val header = obj(
            realm, "name" to h.name, "start" to h.start.toDouble(), "end" to h.end.toDouble(), "startLoop" to h.startLoop.toDouble(), "endLoop" to h.endLoop.toDouble(),
            "sampleRate" to h.sampleRate.toDouble(), "originalPitch" to h.originalPitch.toDouble(), "pitchCorrection" to h.pitchCorrection.toDouble(), "link" to h.link.toDouble(),
            "type" to h.type.toDouble(),
        )
        val out = obj(realm, "header" to header)
        out.defineAccessor("data", { realm.newArray(sample.data.map { it.toDouble() }) })
        return out
    }

    private fun generators(realm: Realm, map: Map<Int, Sf2Generator>): JsObject = realm.newObject().also { o ->
        for ((id, g) in map) {
            o.defineOwn(id.toString(), if (g.isRange) obj(realm, "id" to id.toDouble(), "range" to obj(realm, "lo" to g.lo.toDouble(), "hi" to g.hi.toDouble()))
            else obj(realm, "id" to id.toDouble(), "value" to g.value.toDouble()))
        }
    }

    private fun zone(realm: Realm, generators: Map<Int, Sf2Generator>, vararg more: Pair<String, Any?>): JsObject {
        val o = realm.newObject()
        generators[43]?.let { o.defineOwn("keyRange", obj(realm, "lo" to it.lo.toDouble(), "hi" to it.hi.toDouble())) }
        o.defineOwn("generators", generators(realm, generators))
        o.defineOwn("modulators", realm.newObject())
        for ((k, v) in more) o.defineOwn(k, v)
        return o
    }

    private fun globalZone(realm: Realm, zone: Sf2Zone?): Any? = if (zone == null) Undefined else zone(realm, zone.generators)

    private fun instrumentObject(realm: Realm, instrument: Sf2Instrument, samples: List<JsObject>): JsObject {
        val zones = instrument.zones.map { zone(realm, it.generators, "sample" to samples[file.samples.indexOf(it.sample)]) }
        return obj(realm, "header" to obj(realm, "name" to instrument.name), "globalZone" to globalZone(realm, instrument.globalZone), "zones" to realm.newArray(zones))
    }

    private fun presetObject(realm: Realm, preset: Sf2Preset, instruments: List<JsObject>): JsObject {
        val h = preset.header
        val header = obj(
            realm, "name" to h.name, "preset" to h.preset.toDouble(), "bank" to h.bank.toDouble(), "bagIndex" to h.bagIndex.toDouble(), "library" to h.library.toDouble(),
            "genre" to h.genre.toDouble(), "morphology" to h.morphology.toDouble(),
        )
        val zones = preset.zones.map { zone(realm, it.generators, "instrument" to instruments[file.instruments.indexOf(it.instrument)]) }
        return obj(realm, "header" to header, "globalZone" to globalZone(realm, preset.globalZone), "zones" to realm.newArray(zones))
    }
}

/**
 * The soundfonts of the program (the `@strudel/soundfonts` package, whose `gm_*` sounds are the library's): `loadSoundfont(url)` and the method `.soundfont(sf, n)`.
 *
 * `loadSoundfont` waits for the file (`await loadSoundfont(url)` is the same as `loadSoundfont(url)` here) and answers the same object for the same url. `.soundfont(sf, n)`
 * plays every note of the pattern on preset `n % sf.presets.length` of [sf] (an index that is no preset is an error) with sfumato's own sound, instead of the default output.
 */
private val loaded = java.util.concurrent.ConcurrentHashMap<String, SoundFontObject>()

internal fun registerSoundfonts() = catalogue("Soundfonts") {
    function(listOf("loadSoundfont"), 1) { ctx ->
        val url = soundfontUrl(ctx.arg(0))
        val file = try {
            SoundfontFiles.load(url)
        } catch (e: cosic.pattern.CosicError) {
            ctx.fail(e.message)
        }
        // the same url is the same object, as `await` of one cached promise is in Strudel
        loaded.compute(url) { _, old -> if (old != null && old.realm === ctx.realm && old.file === file) old else SoundFontObject(ctx.realm, file) }
    }
    method(listOf("soundfont"), 2) { ctx ->
        val sf = ctx.arg(0) as? SoundFontObject ?: ctx.fail("soundfont() needs a soundfont: const sf = await loadSoundfont(url); note(\"c e g\").soundfont(sf)")
        val n = if (ctx.arg(1) === Undefined) 0.0 else jsToNumber(ctx.arg(1))
        val count = sf.file.presets.size
        val index = n % count
        if (count == 0 || index != Math.floor(index) || index < 0 || index >= count) {
            ctx.fail("this soundfont has no preset ${jsNumberToString(index)}: it has $count presets")
        }
        soundfontPattern(ctx.bridge.receiverOf(ctx.self, "soundfont"), sf.file.presets[index.toInt()])
    }
}
