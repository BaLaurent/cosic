package cosic.render

import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE =
    "usage: RenderCli (--code <source> | --file <path>) --out <wav> [--cycles N] [--sr 48000] [--samples <dir>]"

private const val EXIT_FAILED = 1
private const val EXIT_USAGE = 2

private class UsageError(message: String) : Exception(message)

private class Options(
    /** The program text; reading it may fail, which is reported like any other error of the run. */
    val source: () -> String,
    val cycles: Double,
    val out: File,
    val sampleRate: Int,
    val samples: File?,
)

private fun parse(args: Array<String>): Options {
    var code: String? = null
    var file: String? = null
    var out: String? = null
    var samples: String? = null
    var cycles = 4.0
    var sampleRate = 48_000
    var i = 0
    fun value(flag: String): String = args.getOrNull(++i) ?: throw UsageError("$flag needs a value")
    while (i < args.size) {
        when (val flag = args[i]) {
            "--code" -> code = value(flag)
            "--file" -> file = value(flag)
            "--out" -> out = value(flag)
            "--samples" -> samples = value(flag)
            "--cycles" -> cycles = value(flag).toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                ?: throw UsageError("--cycles needs a positive number")
            "--sr" -> sampleRate = value(flag).toIntOrNull()?.takeIf { it in 8_000..192_000 }
                ?: throw UsageError("--sr needs a sample rate between 8000 and 192000")
            else -> throw UsageError("unknown option '$flag'")
        }
        i++
    }
    val text = code
    val path = file
    val source: () -> String = when {
        text != null && path == null -> ({ text })
        path != null && text == null -> ({ File(path).readText() })
        else -> throw UsageError("give exactly one of --code and --file")
    }
    if (out == null) throw UsageError("--out is required")
    val samplesFolder = samples?.let(::File)
    if (samplesFolder != null && !samplesFolder.isDirectory) throw UsageError("--samples needs a folder: $samples")
    return Options(source, cycles, File(out), sampleRate, samplesFolder)
}

/**
 * Renders a program to a WAV file from the command line, with the engine that runs in the app:
 * `--code "<program>"` or `--file <path>`, `--cycles N` (default 4), `--out <wav>`, `--sr 48000`, and optionally
 * `--samples <dir>`, whose sub-folders of `.wav` files become unpitched banks. A program the engine rejects is
 * reported on stderr and ends the run with exit code 1; bad options give the usage and exit code 2.
 */
object RenderCli {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args, System.out, System.err))
    }

    internal fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val options = try {
            parse(args)
        } catch (e: UsageError) {
            err.println(e.message)
            err.println(USAGE)
            return EXIT_USAGE
        }
        try {
            val code = options.source()
            val library = options.samples?.let { FolderLibrary(it) } ?: EmptyLibrary
            val samples = OfflineRenderer.render(code, options.cycles, options.sampleRate, library)
            OfflineRenderer.writeWav(options.out, samples, options.sampleRate)
            val seconds = String.format(Locale.ROOT, "%.2f", samples.size / 2.0 / options.sampleRate)
            out.println("wrote ${options.out.path}: $seconds s at ${options.sampleRate} Hz")
            return 0
        } catch (e: IllegalStateException) {
            err.println(e.message)
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
        } catch (e: IOException) {
            err.println(e.message ?: e.toString())
        }
        return EXIT_FAILED
    }
}
