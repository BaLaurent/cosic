package com.octopusman.cosic.library

import android.content.res.AssetManager

private const val EXAMPLES_DIR = "examples"
private const val STYLES_DIR = "styles"
internal const val SKETCH_SUFFIX = ".cosic"

/** A bundled sketch. [number] is the two-digit prefix of its file name, for example "01". */
data class Example(val number: String, val title: String, val code: String)

/** The short sketches in `assets/examples`, in file order. Each file starts with a `// Title` line. */
fun loadExamples(assets: AssetManager): List<Example> = loadSketches(assets, EXAMPLES_DIR)

/** The sketches in `assets/styles`, one small song per music style, in file order. Each file starts with a `// Title` line. */
fun loadStyles(assets: AssetManager): List<Example> = loadSketches(assets, STYLES_DIR)

private fun loadSketches(assets: AssetManager, dir: String): List<Example> =
    assets.list(dir).orEmpty().filter { it.endsWith(SKETCH_SUFFIX) }.sorted().map { file ->
        val code = assets.open("$dir/$file").bufferedReader().use { it.readText() }
        Example(number = file.substringBefore('-'), title = exampleTitle(code), code = code)
    }

internal fun exampleTitle(code: String): String = code.lineSequence().first().removePrefix("//").trim()
