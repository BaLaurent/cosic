package com.octopusman.cosic.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        for (file in files) assertEvaluatesCleanly(file.readText(), file.name)
    }

    @Test
    fun everyExamplePlaysForEightCyclesWithoutErrorsAndMakesSound() {
        for (file in files) assertPlaysCleanly(file.readText(), file.name)
    }
}
