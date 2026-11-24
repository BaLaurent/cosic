package com.octopusman.cosic.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The examples and music styles on the Library screen are the first code users run: all of them must evaluate and play cleanly. */
class ExamplesEvaluateTest {
    private fun sketches(dir: String) = File("src/main/assets/$dir").listFiles { file -> file.extension == "cosic" }!!.sortedBy { it.name }

    private val examples = sketches("examples")
    private val styles = sketches("styles")
    private val files = examples + styles

    @Test
    fun thereAreSixExamplesInTheOrderTheLibraryShowsThem() {
        assertEquals(
            listOf(
                "01-first-beat.cosic", "02-melody-and-scales.cosic", "03-euclid-and-randomness.cosic",
                "04-effects.cosic", "05-instruments.cosic", "06-layers-and-tempo.cosic",
            ),
            examples.map { it.name },
        )
    }

    @Test
    fun thereIsOneStyleSketchPerMusicStyleInTheOrderTheLibraryShowsThem() {
        assertEquals(
            listOf(
                "01-ambient.cosic", "02-lofi-hiphop.cosic", "03-trap.cosic", "04-synthwave.cosic", "05-house.cosic", "06-techno.cosic",
                "07-trance.cosic", "08-chiptune.cosic", "09-hardbass.cosic", "10-drum-and-bass.cosic", "11-hardcore.cosic",
            ),
            styles.map { it.name },
        )
    }

    @Test
    fun theFirstLineOfEachIsItsTitleAsAComment() {
        for (file in files) {
            val title = file.readLines().first()
            assertTrue(title.startsWith("// ") && title.length > 3, "${file.parentFile.name}/${file.name} starts with '$title'")
        }
    }

    @Test
    fun everyExampleEvaluatesWithoutWarnings() {
        for (file in files) assertEvaluatesCleanly(file.readText(), "${file.parentFile.name}/${file.name}")
    }

    @Test
    fun everyExamplePlaysForEightCyclesWithoutErrorsAndMakesSound() {
        for (file in files) assertPlaysCleanly(file.readText(), "${file.parentFile.name}/${file.name}")
    }
}
