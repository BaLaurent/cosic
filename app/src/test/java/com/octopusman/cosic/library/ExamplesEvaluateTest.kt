package com.octopusman.cosic.library

import cosic.BankInfo
import cosic.CosicEngine
import cosic.EvalReport
import cosic.SampleData
import cosic.SoundLibrary
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val SAMPLE_RATE = 48_000

/** The banks the app bundles, read from `assets/samples/index.json`, each entry decoded to a short constant click. */
private class BundledBanks : SoundLibrary {
    private val banks: Map<String, BankInfo> = Json.parseToJsonElement(File("src/main/assets/samples/index.json").readText())
        .jsonObject.getValue("banks").jsonArray.map { it.jsonObject }.associate { bank ->
            val name = bank.getValue("name").jsonPrimitive.content
            val files = bank.getValue("files").jsonArray
            val roots = if (bank.getValue("pitched").jsonPrimitive.boolean) {
                IntArray(files.size) { files[it].jsonObject.getValue("root").jsonPrimitive.int }
            } else {
                null
            }
            name to BankInfo(name, files.size, roots)
        }

    private val click = SampleData(FloatArray(2400) { 0.2f }, SAMPLE_RATE)

    override fun bank(name: String): BankInfo? = banks[name]

    override fun sample(name: String, index: Int): SampleData? = click.takeIf { index in 0 until (banks[name]?.count ?: 0) }
}

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
            val report = CosicEngine(SAMPLE_RATE, BundledBanks()).evaluate(file.readText())
            val ok = assertIs<EvalReport.Ok>(report, "${file.name}: $report")
            assertEquals(emptyList(), ok.warnings, "${file.name} uses a sound that is not bundled")
        }
    }

    @Test
    fun everyExamplePlaysForEightCyclesWithoutErrorsAndMakesSound() {
        for (file in files) {
            val engine = CosicEngine(SAMPLE_RATE, BundledBanks())
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
