package cosic.parity

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Whether the per-frame comparison of [AudioParityTest] can be passed at all: a second render of Strudel itself (`node tools/strudel-ref/audio/render.mjs --seed 7 --out <dir>`,
 * other seeds for what the page randomises, and the AudioWorklets of Chromium have their own random numbers) is compared with the committed reference by the same code. An entry
 * the reference does not pass against itself is random by design and must say so (`random` in its reference file, which makes the parity test compare it long-term, where it
 * must pass against itself). Runs with `-Dcosic.audioParity.stability=<dir of the second render>`; writes `build/reports/audio-reference-stability.md`.
 */
internal class AudioReferenceStabilityTest {
    @Test
    fun theReferenceAgreesWithAnotherRenderOfStrudel() {
        val other = System.getProperty("cosic.audioParity.stability")
        assumeTrue("needs -Dcosic.audioParity.stability=<dir of a second reference render>", !other.isNullOrBlank())
        val dir = File(other!!)
        val problems = ArrayList<String>()
        val report = StringBuilder("# Stability of the Strudel reference\n\n| entry | per-frame | long-term | declared random |\n|---|---|---|---|\n")
        for (file in File(ParityData.dir, "audio").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val second = File(dir, file.name).takeIf { it.isFile } ?: continue
            val a = AudioParityTest.AudioCase(file.nameWithoutExtension, file).load()
            val b = AudioParityTest.AudioCase(file.nameWithoutExtension, second).load()
            val perFrame = AudioCompare.compare(a.channels, b.channels, tonal = !a.noisy)
            val longTerm = AudioCompare.compareLongTerm(a.longTerm, LongTerm.of(b.channels), b.frames)
            report.appendLine("| ${file.nameWithoutExtension} | ${if (perFrame.passed) "same" else "differs: " + perFrame.failures.firstOrNull()} | ${if (longTerm.passed) "same" else "differs: " + longTerm.failures.firstOrNull()} | ${a.random ?: ""} |")
            if (a.random == null && !perFrame.passed) problems.add("${file.nameWithoutExtension}: two renders of Strudel differ per frame (${perFrame.failures.joinToString("; ")}): declare it random or change the entry")
            if (a.random != null && perFrame.passed) problems.add("${file.nameWithoutExtension}: declared random, but two renders of Strudel agree per frame: drop the declaration")
            if (a.random != null && !longTerm.passed) problems.add("${file.nameWithoutExtension}: two renders of Strudel differ even long-term (${longTerm.failures.joinToString("; ")})")
        }
        File("build/reports").also { it.mkdirs() }.resolve("audio-reference-stability.md").writeText(report.toString())
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
