package cosic.render

import cosic.BankInfo
import cosic.SampleData
import cosic.SoundLibrary
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * Banks read from disk for offline rendering: every sub-folder of [root] that holds `.wav` files is an unpitched bank,
 * named like the app names imported banks (lowercase, spaces and dashes as `_`, nothing but `a-z0-9_`), with its files
 * in the order of their lowercase names. Everything is decoded up front, so lookups never wait. Throws
 * [IllegalArgumentException] naming the file when one cannot be read.
 */
internal class FolderLibrary(root: File) : SoundLibrary {
    private val banks: Map<String, List<SampleData>> = LinkedHashMap<String, List<SampleData>>().also { banks ->
        for (folder in root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }) {
            val files = folder.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
                .orEmpty().sortedBy { it.name.lowercase() }
            val name = bankName(folder.name)
            if (files.isNotEmpty() && name.isNotEmpty()) banks[name] = files.map(::readWav)
        }
    }

    override fun bank(name: String): BankInfo? = banks[name]?.let { BankInfo(name, it.size, null) }

    override fun sample(name: String, index: Int): SampleData? = banks[name]?.getOrNull(index)

    private fun bankName(folderName: String): String =
        folderName.lowercase().replace(' ', '_').replace('-', '_').filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    /** The file as mono: its channels averaged, at the sample rate it has. */
    private fun readWav(file: File): SampleData {
        try {
            AudioSystem.getAudioInputStream(file).use { source ->
                val channels = source.format.channels
                val pcm = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, source.format.sampleRate, 16, channels, channels * 2, source.format.sampleRate, false,
                )
                val bytes = AudioSystem.getAudioInputStream(pcm, source).use { it.readAllBytes() }
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val mono = FloatArray(bytes.size / (2 * channels)) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) sum += data.getShort((frame * channels + channel) * 2) / 32768f
                    sum / channels
                }
                return SampleData(mono, Math.round(source.format.sampleRate))
            }
        } catch (e: UnsupportedAudioFileException) {
            throw IllegalArgumentException("cannot read ${file.path}: ${e.message}", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("cannot read ${file.path}: ${e.message}", e)
        }
    }
}
