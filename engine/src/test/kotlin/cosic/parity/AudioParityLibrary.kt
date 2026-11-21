package cosic.parity

import cosic.CosicEngine
import cosic.SampleData
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.SampleStore
import cosic.audio.WavDecoder
import cosic.sound.RegistryLibrary
import cosic.soundfont.GmFonts
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The files the Chromium reference render used (tools/strudel-ref/audio/render.mjs stores everything its page fetched): `<cache>/<host>/<path and query as written>`,
 * the sample maps, the samples and the soundfonts of the public libraries. Nothing here touches the network. A file that is not WAV (the piano's mp3, the zones of the
 * soundfonts) is read from the PCM Chromium made of it, `<cache>/_decoded/<sha1 of the encoded bytes>.wav`: the engine has no decoder for those on the JVM (the app
 * uses Android's), and this way both renders play the very samples.
 */
internal class CacheSampleStore(private val root: File) : SampleStore {
    private val decoded = ConcurrentHashMap<String, SampleData>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** The cache file of [url]; the same name render.mjs `cacheFile` makes of the same text. */
    fun fileOf(url: String): File {
        val match = Regex("^https?://([^/?#]+)([^#]*)", RegexOption.IGNORE_CASE).find(url) ?: throw IOException("not an http(s) url: $url")
        var rest = match.groupValues[2].replace(" ", "%20").replace("..", "%2E%2E")
        if (rest.isEmpty() || rest.startsWith("?")) rest = "/$rest"
        if (rest.endsWith("/")) rest += "_index"
        return rest.split('/').filter { it.isNotEmpty() }.fold(File(root, match.groupValues[1])) { dir, part -> File(dir, part) }
    }

    /** Strudel's rule before every fetch (`url.replace('#', '%23')`, the first one only). */
    private fun fixed(url: String): String = url.replaceFirst("#", "%23")

    override fun fetchText(url: String): String {
        val file = fileOf(fixed(url))
        if (!file.isFile) throw IOException("not in the reference cache: $url")
        return file.readText()
    }

    override fun sample(url: String): SampleData? {
        if (url in missing) return null
        return try {
            awaitSample(url)
        } catch (e: IOException) {
            missing.add(url)
            System.err.println("audio parity: ${e.message}")
            null
        }
    }

    override fun awaitSample(url: String): SampleData {
        decoded[url]?.let { return it }
        val file = fileOf(fixed(url))
        if (!file.isFile) {
            // a file the reference could not fetch (render.mjs marks it): Strudel played silence for it ("Unable to decode audio data"), and so does a silent sample here
            if (File(file.path + ".missing").isFile) return SampleData(FloatArray(1), 48000).also { decoded[url] = it }
            throw IOException("not in the reference cache: $url")
        }
        val data = decode(file.readBytes(), url)
        decoded[url] = data
        return data
    }

    override fun decode(bytes: ByteArray, nameHint: String): SampleData {
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            return WavDecoder.decode(bytes)
        }
        // Chromium decoded these bytes; the font loader of Strudel hands it the zone padded with zeros to the length of its base64 text (fontloader.mjs getFontBuffer)
        val padded = bytes.copyOf(4 * ((bytes.size + 2) / 3))
        val sidecar = listOf(bytes, padded).map { File(File(root, "_decoded"), "${sha1(it)}.wav") }.firstOrNull { it.isFile }
            ?: throw IOException("no Chromium-decoded copy of $nameHint (sha1 ${sha1(bytes)})")
        return WavDecoder.decode(sidecar.readBytes())
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The Dirt-Samples map `prebake()` passes inline, as render.mjs read it from prebake.mjs. */
    fun dirtSamples(): Map<String, Any?> {
        val text = File(root, "_prebake/dirt-samples.json").takeIf { it.isFile }?.readText() ?: throw IOException("run tools/strudel-ref/audio/render.mjs first")
        @Suppress("UNCHECKED_CAST")
        return cosic.sound.LooseJson.parse(text) as Map<String, Any?>
    }

    companion object {
        /** `-Dcosic.audioParity.cache`, else `$COSIC_SAMPLE_CACHE`, else `~/.cache/cosic-samples`. */
        fun defaultRoot(): File = File(
            System.getProperty("cosic.audioParity.cache") ?: System.getenv("COSIC_SAMPLE_CACHE") ?: (System.getProperty("user.home") + "/.cache/cosic-samples"),
        )
    }
}

/**
 * The library `prebake()` of the REPL makes (website/src/repl/prebake.mjs; the app's `DefaultLibrary` is the same list), read from [store] instead of the
 * network: the `gm_*` soundfonts, the packs of strudel.b-cdn.net, the Dirt-Samples of the tunes, and the aliases of the drum machines. A pack that is not in the
 * cache is left out, as a pack the network does not deliver is. A fresh library per program: programs register sounds in it.
 */
internal fun prebakedLibrary(store: CacheSampleStore): RegistryLibrary {
    val cdn = "https://strudel.b-cdn.net"
    val library = RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS)
    GmFonts.register(library)
    fun pack(file: String, directory: String, tag: String? = null) {
        try {
            library.loadSampleMap("$cdn/$file", "$cdn/$directory", SampleMapOptions(prebake = true, tag = tag))
        } catch (e: SampleMapException) {
            System.err.println("audio parity: ${e.message}")
        }
    }
    pack("piano.json", "piano/")
    pack("vcsl.json", "VCSL/")
    pack("tidal-drum-machines.json", "tidal-drum-machines/machines/", "drum-machines")
    pack("uzu-drumkit.json", "uzu-drumkit/", "drum-machines")
    pack("uzu-wavetables.json", "uzu-wavetables/")
    pack("mridangam.json", "mrid/", "drum-machines")
    library.loadSampleMap(store.dirtSamples(), "$cdn/Dirt-Samples/", SampleMapOptions(prebake = true))
    try {
        library.aliasBank("$cdn/tidal-drum-machines-alias.json")
    } catch (e: SampleMapException) {
        System.err.println("audio parity: ${e.message}")
    }
    return library
}
