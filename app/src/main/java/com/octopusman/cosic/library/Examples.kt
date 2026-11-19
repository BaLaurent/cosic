package com.octopusman.cosic.library

import android.content.res.AssetManager

private const val EXAMPLES_DIR = "examples"
private const val EXAMPLE_SUFFIX = ".cosic"

/** A bundled sketch. [number] is the two-digit prefix of its file name, for example "01". */
data class Example(val number: String, val title: String, val code: String)

/** The sketches in `assets/examples`, in file order. Each file starts with a `// Title` line. */
fun loadExamples(assets: AssetManager): List<Example> =
    assets.list(EXAMPLES_DIR).orEmpty().filter { it.endsWith(EXAMPLE_SUFFIX) }.sorted().map { file ->
        val code = assets.open("$EXAMPLES_DIR/$file").bufferedReader().use { it.readText() }
        Example(number = file.substringBefore('-'), title = exampleTitle(code), code = code)
    }

internal fun exampleTitle(code: String): String = code.lineSequence().first().removePrefix("//").trim()
