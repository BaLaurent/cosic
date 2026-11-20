package com.octopusman.cosic.library

import cosic.BankGroup
import cosic.CosicEngine
import cosic.EvalReport
import cosic.SampleBank
import cosic.SampleData
import cosic.SampleStore
import cosic.SoundEntry
import cosic.SoundLibrary
import cosic.sound.RegistryLibrary
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000

/** A store whose every file is a short constant click, already decoded. */
private object ClickStore : SampleStore {
    private val click = SampleData(FloatArray(2400) { 0.2f }, SAMPLE_RATE)

    override fun sample(url: String): SampleData = click

    override fun fetchText(url: String): String = throw IOException("no network in tests: $url")

    override fun awaitSample(url: String): SampleData = click

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = click
}

/**
 * A library that has every sound: the engine's own (synths, drums) are the engine's, any other name is a bank of one file that is a short
 * constant click. What the default library really holds is the network's to say, so the examples are checked against the sounds they
 * name only as far as the engine is concerned.
 */
private class UniversalLibrary(private val inner: RegistryLibrary) : SoundLibrary by inner {
    override fun sound(name: String): SoundEntry =
        inner.sound(name) ?: SampleBank(name, listOf(BankGroup(null, null, listOf(name))), null, true)
}

private fun universalLibrary(): SoundLibrary = UniversalLibrary(RegistryLibrary(ClickStore, CosicEngine.BUILTIN_SOUNDS))

/** The examples on the Library screen are the first code users run: all of them must evaluate and play cleanly. */
class ExamplesEvaluateTest {
    private val files = File("src/main/assets/examples").listFiles { file -> file.extension == "cosic" }!!.sortedBy { it.name }

    @Test
    fun thereAreSixExamplesInTheOrderTheLibraryShowsThem() {
        assertEquals(
            listOf(
                "01-first-beat.cosic", "02-melody-and-scales.cosic", "03-euclid-and-randomness.cosic",
                "04-effects.cosic", "05-instruments.cosic", "06-layers-and-tempo.cosic",
            ),
            files.map { it.name },
        )
    }

    @Test
    fun theFirstLineOfEachIsItsTitleAsAComment() {
        for (file in files) {
            val title = file.readLines().first()
            assertTrue(title.startsWith("// ") && title.length > 3, "${file.name} starts with '$title'")
        }
    }

    @Test
    fun everyExampleEvaluatesWithoutWarnings() {
        for (file in files) {
            val report = CosicEngine(SAMPLE_RATE, universalLibrary()).evaluate(file.readText())
            val ok = assertIs<EvalReport.Ok>(report, "${file.name}: $report")
            assertEquals(emptyList(), ok.warnings, "${file.name} warns")
        }
    }

    @Test
    fun everyExamplePlaysForEightCyclesWithoutErrorsAndMakesSound() {
        for (file in files) {
            val engine = CosicEngine(SAMPLE_RATE, universalLibrary())
            val ok = assertIs<EvalReport.Ok>(engine.evaluate(file.readText()), file.name)
            engine.play()
            val buffer = FloatArray(256 * 2)
            var loudest = 0f
            repeat((8 / ok.cps * SAMPLE_RATE / 256).toInt()) {
                engine.render(buffer, 256)
                for (sample in buffer) loudest = maxOf(loudest, abs(sample))
            }
            assertNull(engine.snapshot.runtimeError, file.name)
            assertEquals(emptyList(), engine.snapshot.warnings, file.name)
            assertTrue(loudest > 0.05f, "${file.name} is silent (loudest sample $loudest)")
            assertTrue(loudest <= 1f, file.name)
        }
    }
}
