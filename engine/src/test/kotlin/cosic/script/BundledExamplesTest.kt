package cosic.script

import cosic.pattern.Rational
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The examples the app puts on its Library screen (app/src/main/assets/examples) are programs of the language Strudel
 * defines, so they must do what they say: a program that Strudel would warn about, such as arithmetic on a control pattern
 * (`riff.add(7)` changes nothing there), would write a line to the console and not sound as its comment promises.
 * The app's own test checks that they play; this one checks that nothing is said about them.
 */
class BundledExamplesTest {
    private val directory = File("../app/src/main/assets/examples")

    private fun examples(): List<File> {
        assumeTrue("the app's examples are next to the engine module", directory.isDirectory)
        return directory.listFiles { file -> file.extension == "cosic" }!!.sortedBy { it.name }
    }

    @Test
    fun everyExampleRunsEightCyclesWithoutAConsoleLine() {
        val files = examples()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val session = ReplSession()
            val program = session.evaluate(file.readText())
            val haps = program.pattern.queryArc(Rational.ZERO, Rational(8))
            assertTrue(haps.isNotEmpty(), "${file.name} plays nothing")
            assertEquals(emptyList(), session.host.recentLines().map { "${it.level}: ${it.message}" }, "${file.name} writes to the console")
        }
    }

    @Test
    fun theFifthOfTheLayersExampleIsAboveTheRiff() {
        val file = examples().first { it.name.startsWith("06-") }
        val haps = ReplSession().evaluate(file.readText()).pattern.queryArc(Rational.ZERO, Rational.ONE)
        val notes = haps.mapNotNull { (it.value as? Map<*, *>)?.get("note") }
        // the riff keeps its note names, its copy a fifth up is made of the numbers `add(note(7))` computes (c3 is 48)
        assertTrue("c3" in notes && 55.0 in notes, notes.toString())
    }
}
