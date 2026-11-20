package cosic.soundfont.sf2

/** A file that is not a SoundFont 2 file, or one whose structure is broken (`ParseError` of the `soundfont2` package). */
internal class Sf2FormatException(message: String) : RuntimeException(message)

/** The ids of the generators the player reads (`GeneratorType` of `soundfont2`); the others are numbers 0 to 60 in the order of the SoundFont 2 specification. */
internal object Gen {
    const val INSTRUMENT = 41
    const val KEY_RANGE = 43
    const val VEL_RANGE = 44
    const val SAMPLE_ID = 53
    const val LAST = 60

    /** Generators a preset may not set: the sample-address offsets, key and velocity, sample modes, exclusive class and root key (`PRESET_TYPES_BLACKLIST`). */
    val PRESET_BLACKLIST = setOf(0, 1, 2, 3, 4, 12, 45, 46, 47, 50, 54, 57, 58)

    /** Generators an instrument may not set: the unused and reserved ones (`INSTRUMENT_TYPES_BLACKLIST`). */
    val INSTRUMENT_BLACKLIST = setOf(14, 18, 19, 20, 42, 49, 55)
}

/** A generator of a zone: a [value] (a signed 16-bit number), or for the key range (43) and the velocity range (44) the range [lo]..[hi]. */
internal class Sf2Generator(val id: Int, val value: Int, val lo: Int, val hi: Int) {
    val isRange: Boolean get() = id == Gen.KEY_RANGE || id == Gen.VEL_RANGE
}

internal class Sf2SampleHeader(
    val name: String,
    val start: Long,
    val end: Long,
    /** In frames from [start] (the loader subtracts [start], as `getSamples` of `soundfont2` does). */
    var startLoop: Long,
    var endLoop: Long,
    val sampleRate: Long,
    var originalPitch: Int,
    val pitchCorrection: Int,
    val link: Int,
    val type: Int,
)

internal class Sf2Sample(val header: Sf2SampleHeader, val data: ShortArray) {
    /** The data as `sfumato` makes the buffer of a zone: each value divided by 32768. */
    val floats: FloatArray by lazy { FloatArray(data.size) { data[it] / 32768f } }
}

/** The key range of a zone, or null when it has none. */
internal class Sf2Zone(val generators: Map<Int, Sf2Generator>) {
    val keyRange: IntArray? get() = generators[Gen.KEY_RANGE]?.let { intArrayOf(it.lo, it.hi) }
}

internal class Sf2InstrumentZone(val generators: Map<Int, Sf2Generator>, val sample: Sf2Sample) {
    val keyRange: IntArray? get() = generators[Gen.KEY_RANGE]?.let { intArrayOf(it.lo, it.hi) }
}

internal class Sf2Instrument(val name: String, val globalZone: Sf2Zone?, val zones: List<Sf2InstrumentZone>)

internal class Sf2PresetZone(val generators: Map<Int, Sf2Generator>, val instrument: Sf2Instrument) {
    val keyRange: IntArray? get() = generators[Gen.KEY_RANGE]?.let { intArrayOf(it.lo, it.hi) }
}

internal class Sf2PresetHeader(val name: String, val preset: Int, val bank: Int, val bagIndex: Int, val library: Long, val genre: Long, val morphology: Long)

internal class Sf2Preset(val header: Sf2PresetHeader, val globalZone: Sf2Zone?, val zones: List<Sf2PresetZone>)

/**
 * A SoundFont 2 file, read the way the `soundfont2` package (0.4.0, which `sfumato` and so Strudel's `loadSoundfont` use) reads it: the RIFF structure of three lists (info,
 * sample data, preset data), the preset, instrument and sample headers, and for each preset and instrument its zones with their generators. Modulators are not read (nothing
 * uses them). The 16-bit sample data is cut into one array per sample header.
 *
 * Quirks of the package that are kept because the player depends on what it makes of a file: a generator that the package ignores (an unknown id, one a preset or an
 * instrument may not set) is skipped after its id only, so the two bytes of its amount are read as the next id; a zone whose generators have no instrument (preset) or sample
 * (instrument) reference is the global zone when it is the first of the preset or instrument and is dropped otherwise, as is a zone whose reference does not exist.
 */
internal class Sf2File private constructor(
    val version: String,
    val name: String,
    val samples: List<Sf2Sample>,
    val instruments: List<Sf2Instrument>,
    val presets: List<Sf2Preset>,
) {
    companion object {
        /** Reads [bytes]; throws [Sf2FormatException] when they are no SoundFont 2 file. */
        fun parse(bytes: ByteArray): Sf2File {
            val reader = Reader(bytes)
            if (reader.id(0) != "RIFF") throw Sf2FormatException("Invalid file format: expected 'RIFF', got '${reader.id(0)}'")
            if (reader.id(8) != "sfbk") throw Sf2FormatException("Invalid signature: expected 'sfbk', got '${reader.id(8)}'")
            val top = reader.chunks(12, bytes.size)
            if (top.size != 3) throw Sf2FormatException("Invalid sfbk structure: expected 3 chunks, got ${top.size} chunks")
            val (version, name) = info(reader, top[0])
            val smpl = reader.chunks(top[1].children, top[1].end).firstOrNull()
            if (top[1].id != "LIST") throw Sf2FormatException("Unexpected chunk ID: expected 'LIST', got '${top[1].id}'")
            if (smpl == null || smpl.id != "smpl") throw Sf2FormatException("Invalid chunk signature: expected 'smpl', got '${smpl?.id}'")
            if (top[2].id != "LIST") throw Sf2FormatException("Unexpected chunk ID: expected 'LIST', got '${top[2].id}'")
            val pdta = reader.chunks(top[2].children, top[2].end)
            val ids = listOf("phdr", "pbag", "pmod", "pgen", "inst", "ibag", "imod", "igen", "shdr")
            for ((i, id) in ids.withIndex()) {
                if (pdta.getOrNull(i)?.id != id) throw Sf2FormatException("Unexpected chunk ID: expected '$id', got '${pdta.getOrNull(i)?.id}'")
            }
            val presetHeaders = presetHeaders(reader, pdta[0])
            val presetBags = bags(reader, pdta[1])
            reader.requireMultiple(pdta[2], 10)
            val presetGenerators = generators(reader, pdta[3], preset = true)
            val instrumentHeaders = instrumentHeaders(reader, pdta[4])
            val instrumentBags = bags(reader, pdta[5])
            reader.requireMultiple(pdta[6], 10)
            val instrumentGenerators = generators(reader, pdta[7], preset = false)
            val sampleHeaders = sampleHeaders(reader, pdta[8])

            val samples = samples(reader, smpl, sampleHeaders)
            val instruments = instruments(instrumentHeaders, instrumentBags, instrumentGenerators, samples)
            val presets = presets(presetHeaders, presetBags, presetGenerators, instruments)
            return Sf2File(version, name, samples, instruments, presets)
        }

        // ---- RIFF ----------------------------------------------------------------------------------------------------------------------------------------

        private class Chunk(val id: String, val length: Long, val content: Int) {
            /** Where the sub-chunks of a list begin: after the four bytes of its type. */
            val children: Int get() = content + 4

            val end: Int get() = (content + length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private class Reader(val b: ByteArray) {
            fun u8(at: Int): Int = if (at in b.indices) b[at].toInt() and 0xFF else 0

            fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

            fun i16(at: Int): Int = (u16(at) shl 16) shr 16

            fun u32(at: Int): Long = (u8(at).toLong()) or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)

            /** `getStringFromBuffer`: UTF-8, up to the first NUL, trimmed. */
            fun string(at: Int, length: Int): String {
                val from = at.coerceIn(0, b.size)
                val to = (at + length).coerceIn(from, b.size)
                var end = from
                while (end < to && b[end].toInt() != 0) end++
                return String(b, from, end - from, Charsets.UTF_8).trim()
            }

            fun id(at: Int): String = string(at, 4)

            /** The chunks between [from] and [to]: header (id, length), content, then on to the next even offset. */
            fun chunks(from: Int, to: Int): List<Chunk> {
                val out = ArrayList<Chunk>()
                var index = from
                while (index <= minOf(to, b.size) - 8) {
                    val length = u32(index + 4)
                    out.add(Chunk(id(index), length, index + 8))
                    index = (index + 8 + length).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt()
                    if (index % 2 != 0) index++
                }
                return out
            }

            fun requireMultiple(chunk: Chunk, size: Int) {
                if (chunk.length % size != 0L) throw Sf2FormatException("Invalid size for the '${chunk.id}' sub-chunk")
            }
        }

        private fun info(reader: Reader, list: Chunk): Pair<String, String> {
            if (list.id != "LIST") throw Sf2FormatException("Unexpected chunk ID: expected 'LIST', got '${list.id}'")
            var version: String? = null
            var name: String? = null
            for (chunk in reader.chunks(list.children, list.end)) {
                when (chunk.id) {
                    "ifil", "iver" -> {
                        if (chunk.length != 4L) throw Sf2FormatException("Invalid size for the '${chunk.id}' sub-chunk")
                        if (chunk.id == "ifil") version = "${reader.u16(chunk.content)}.${reader.u16(chunk.content + 2)}"
                    }
                    "INAM" -> name = reader.string(chunk.content, chunk.length.toInt())
                }
            }
            if (version == null) throw Sf2FormatException("Missing required 'ifil' sub-chunk")
            if (name.isNullOrEmpty()) throw Sf2FormatException("Missing required 'INAM' sub-chunk")
            return version to name
        }

        // ---- the headers and bags -----------------------------------------------------------------------------------------------------------------------

        private fun presetHeaders(r: Reader, c: Chunk): List<Sf2PresetHeader> {
            r.requireMultiple(c, 38)
            return List((c.length / 38).toInt()) { i ->
                val at = c.content + i * 38
                Sf2PresetHeader(r.string(at, 20), r.u16(at + 20), r.u16(at + 22), r.u16(at + 24), r.u32(at + 26), r.u32(at + 30), r.u32(at + 34))
            }
        }

        private fun instrumentHeaders(r: Reader, c: Chunk): List<Pair<String, Int>> {
            r.requireMultiple(c, 22)
            return List((c.length / 22).toInt()) { i -> r.string(c.content + i * 22, 20) to r.u16(c.content + i * 22 + 20) }
        }

        private fun sampleHeaders(r: Reader, c: Chunk): List<Sf2SampleHeader> {
            r.requireMultiple(c, 46)
            return List((c.length / 46).toInt()) { i ->
                val at = c.content + i * 46
                val pitch = r.u8(at + 40)
                Sf2SampleHeader(
                    r.string(at, 20), r.u32(at + 20), r.u32(at + 24), r.u32(at + 28), r.u32(at + 32), r.u32(at + 36), pitch,
                    (r.u8(at + 41) shl 24) shr 24, r.u16(at + 42), r.u16(at + 44),
                )
            }
        }

        /** A bag: where its generators begin (the zone of a preset or instrument). */
        private fun bags(r: Reader, c: Chunk): List<Int> {
            r.requireMultiple(c, 4)
            return List((c.length / 4).toInt()) { i -> r.u16(c.content + i * 4) }
        }

        private fun generators(r: Reader, c: Chunk, preset: Boolean): List<Sf2Generator> {
            r.requireMultiple(c, 4)
            val out = ArrayList<Sf2Generator>()
            var at = c.content
            val end = c.content + c.length.toInt()
            while (at < end) {
                val id = r.u16(at)
                at += 2
                // `soundfont2` returns after the id for the generators it ignores, without skipping the amount (which is read as the next id), and
                // does not keep a place for them: the generator indices of the zones count the generators that were kept
                if (id > Gen.LAST || (if (preset) id in Gen.PRESET_BLACKLIST else id in Gen.INSTRUMENT_BLACKLIST)) continue
                if (id == Gen.KEY_RANGE || id == Gen.VEL_RANGE) {
                    out.add(Sf2Generator(id, 0, r.u8(at), r.u8(at + 1)))
                } else {
                    out.add(Sf2Generator(id, r.i16(at), 0, 0))
                }
                at += 2
            }
            return out
        }

        // ---- samples, instruments and presets -----------------------------------------------------------------------------------------------------------

        private fun samples(r: Reader, smpl: Chunk, headers: List<Sf2SampleHeader>): List<Sf2Sample> {
            val out = ArrayList<Sf2Sample>()
            for (header in headers) {
                if (header.name == "EOS") continue
                if (header.sampleRate <= 0) throw Sf2FormatException("Illegal sample rate of ${header.sampleRate} hz in sample '${header.name}'")
                if (header.originalPitch in 128..254) header.originalPitch = 60
                header.startLoop -= header.start
                header.endLoop -= header.start
                val from = smpl.content + header.start * 2
                val frames = (header.end - header.start).coerceAtLeast(0)
                val available = ((smpl.end - from) / 2).coerceAtLeast(0)
                val data = ShortArray(minOf(frames, available).toInt()) { r.i16((from + it * 2L).toInt()).toShort() }
                out.add(Sf2Sample(header, data))
            }
            return out
        }

        /** The generators of zone [j]: from its own generator index to that of the next bag, the later of two with one id winning (`getGenerators` of `soundfont2`). */
        private fun zoneGenerators(j: Int, bags: List<Int>, generators: List<Sf2Generator>): Map<Int, Sf2Generator> {
            val start = bags[j]
            val end = if (j + 1 < bags.size) bags[j + 1] else bags.size
            val map = LinkedHashMap<Int, Sf2Generator>()
            for (i in start until end) generators.getOrNull(i)?.let { map[it.id] = it }
            return map
        }

        private class Item<R>(val generators: Map<Int, Sf2Generator>, val reference: R)

        /** `getItemsInZone`: for each header the global zone (an unreferenced first zone) and the zones that reference something that exists. */
        private fun <R> items(
            headers: List<Int>,
            bags: List<Int>,
            generators: List<Sf2Generator>,
            references: List<R>,
            referenceId: Int,
        ): List<Pair<Map<Int, Sf2Generator>?, List<Item<R>>>> = headers.mapIndexed { i, start ->
            val end = if (i + 1 < headers.size) headers[i + 1] else bags.size
            var global: Map<Int, Sf2Generator>? = null
            val zones = ArrayList<Item<R>>()
            for (j in start until end) {
                if (j !in bags.indices) continue
                val gens = zoneGenerators(j, bags, generators)
                val reference = gens[referenceId]
                if (reference == null) {
                    if (j - start == 0) global = gens
                    continue
                }
                zones.add(Item(gens, references.getOrNull(reference.value) ?: continue))
            }
            global to zones
        }

        private fun instruments(headers: List<Pair<String, Int>>, bags: List<Int>, generators: List<Sf2Generator>, samples: List<Sf2Sample>): List<Sf2Instrument> {
            val built = items(headers.map { it.second }, bags, generators, samples, Gen.SAMPLE_ID)
            return headers.indices.filter { headers[it].first != "EOI" }.map { i ->
                val (global, zones) = built[i]
                Sf2Instrument(headers[i].first, global?.let(::Sf2Zone), zones.map { Sf2InstrumentZone(it.generators, it.reference) })
            }
        }

        private fun presets(headers: List<Sf2PresetHeader>, bags: List<Int>, generators: List<Sf2Generator>, instruments: List<Sf2Instrument>): List<Sf2Preset> {
            val built = items(headers.map { it.bagIndex }, bags, generators, instruments, Gen.INSTRUMENT)
            return headers.indices.filter { headers[it].name != "EOP" }.map { i ->
                val (global, zones) = built[i]
                Sf2Preset(headers[i], global?.let(::Sf2Zone), zones.map { Sf2PresetZone(it.generators, it.reference) })
            }
        }
    }
}
