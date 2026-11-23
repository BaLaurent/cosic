package com.octopusman.cosic.library

import android.content.res.AssetManager
import java.io.IOException

private const val LESSONS_DIR = "lessons"

/** A line that is exactly this (spaces around it ignored) separates two steps of a lesson file. */
internal const val STEP_SEPARATOR = "// ---"

/** A bundled lesson: [id] is its file name without `.cosic` ("01-welcome"), [title] its first line without `//`, [steps] the programs it shows one after the other. */
data class Lesson(val id: String, val title: String, val steps: List<String>)

/** The lessons in `assets/lessons`, in file order. */
fun loadLessons(assets: AssetManager): List<Lesson> =
    assets.list(LESSONS_DIR).orEmpty().filter { it.endsWith(SKETCH_SUFFIX) }.sorted().map { file ->
        val text = assets.open("$LESSONS_DIR/$file").bufferedReader().use { it.readText() }
        parseLesson(file.removeSuffix(SKETCH_SUFFIX), text)
    }

/** The lesson [id]; null when the app has no such lesson (any more). */
fun loadLesson(assets: AssetManager, id: String): Lesson? = try {
    val text = assets.open("$LESSONS_DIR/$id$SKETCH_SUFFIX").bufferedReader().use { it.readText() }
    parseLesson(id, text)
} catch (e: IOException) {
    null
}

internal fun parseLesson(id: String, text: String): Lesson {
    val blocks = mutableListOf<MutableList<String>>(mutableListOf())
    for (line in text.lines()) {
        if (line.trim() == STEP_SEPARATOR) blocks += mutableListOf<String>() else blocks.last() += line
    }
    val steps = blocks
        .map { block -> block.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() } }
        .filter { it.isNotEmpty() }
        .map { it.joinToString("\n") + "\n" }
    return Lesson(id, exampleTitle(text), steps)
}
