package com.octopusman.cosic.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lessons are the first thing a new user reads: every step must follow the layout rules and play cleanly. */
class LessonsTest {
    private val files = File("src/main/assets/lessons").listFiles { file -> file.extension == "cosic" }!!.sortedBy { it.name }

    private fun lessons() = files.map { parseLesson(it.name.removeSuffix(SKETCH_SUFFIX), it.readText()) }

    @Test
    fun parseLessonSplitsAtSeparators() {
        val lesson = parseLesson("x", "// T\n\$: s(\"bd\")\n\n  // ---  \n\n// Two\n\$: s(\"sd\")\n// ---\n\n")
        assertEquals("T", lesson.title)
        assertEquals(listOf("// T\n\$: s(\"bd\")\n", "// Two\n\$: s(\"sd\")\n"), lesson.steps)
    }

    @Test
    fun thereAreTenLessonsInOrder() {
        assertEquals(
            listOf(
                "01-welcome.cosic", "02-editing.cosic", "03-finding.cosic", "04-cycles.cosic", "05-mini-notation.cosic",
                "06-notes.cosic", "07-sound.cosic", "08-transform.cosic", "09-visuals.cosic", "10-perform.cosic",
            ),
            files.map { it.name },
        )
    }

    @Test
    fun lessonFilesFollowTheRules() {
        for ((file, lesson) in files.zip(lessons())) {
            val text = file.readText()
            assertTrue(lesson.steps.size >= 2, "${file.name} has ${lesson.steps.size} steps")
            for ((i, step) in lesson.steps.withIndex()) {
                val where = "${file.name} step ${i + 1}"
                val first = step.lines().first()
                assertTrue(first.startsWith("// ") && '"' !in first, "$where starts with '$first'")
                assertTrue(step.lines().any { Regex("""^\s*\$:""").containsMatchIn(it) }, "$where has no unmuted layer")
            }
            for (line in text.lines()) assertTrue(line.length <= 38, "${file.name}: '$line' is ${line.length} characters")
            assertFalse(text.contains("strudel", ignoreCase = true), "${file.name} names the original project")
            assertFalse(text.contains("@title"), "${file.name} has a @title")
        }
    }

    @Test
    fun everyStepEvaluatesAndPlaysCleanly() {
        for ((file, lesson) in files.zip(lessons())) {
            for ((i, step) in lesson.steps.withIndex()) {
                val where = "${file.name} step ${i + 1}"
                assertEvaluatesCleanly(step, where)
                assertPlaysCleanly(step, where)
            }
        }
    }
}
