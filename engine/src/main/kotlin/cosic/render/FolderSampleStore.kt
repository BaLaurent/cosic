package cosic.render

import cosic.CosicEngine
import cosic.SampleData
import cosic.SampleStore
import cosic.audio.WavDecoder
import cosic.sound.ImportedFile
import cosic.sound.RegistryLibrary
import cosic.sound.UserImport
import java.io.File
import java.io.IOException

/**
 * The `.wav` files under a folder, decoded up front so that lookups never wait, as a [SampleStore]: the url of a file is its path.
 * Nothing is fetched from anywhere else. Throws [IllegalArgumentException] naming the file when one cannot be read.
 */
internal class FolderSampleStore(root: File) : SampleStore {
    private val samples = HashMap<String, SampleData>()

    /** The files found, with a relative path that starts with the name of [root], as the folder import of Strudel sees them. */
    val files: List<ImportedFile> = root.walkTopDown().filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }.map { file ->
        samples[file.path] = readWav(file)
        ImportedFile("${root.name}/${file.relativeTo(root).invariantSeparatorsPath}", file.name, file.path)
    }.toList()

    override fun fetchText(url: String): String = throw IOException("the offline renderer reads no maps: $url")

    override fun sample(url: String): SampleData? = samples[url]

    override fun awaitSample(url: String): SampleData = samples[url] ?: throw IOException("no sample at $url")

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = WavDecoder.decode(bytes)

    private fun readWav(file: File): SampleData = try {
        WavDecoder.decode(file.readBytes())
    } catch (e: IOException) {
        throw IllegalArgumentException("cannot read ${file.path}: ${e.message}", e)
    }
}

/**
 * A library for offline rendering: the sounds the engine makes itself, and the banks that Strudel's folder import makes of the `.wav` files
 * under [root] (every folder with files is a bank named after it, see [UserImport]).
 */
internal fun folderLibrary(root: File): RegistryLibrary {
    val store = FolderSampleStore(root)
    val library = RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS)
    for (bank in UserImport.banks(store.files)) library.registerSampleSource(bank.sourceKey, bank.files.map { it.url })
    return library
}
