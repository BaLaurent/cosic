package cosic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decision 5 of the parity plan: the word "Strudel" never appears in what the app shows, carries or says about itself (UI, resources, store text). Repository
 * documents and tests may use it. What the engine ships (`src/main/resources`: the reference cards, the tonal tables, the soundfont list) is part of the app, and
 * so are the Android resources, assets and manifest of the app module, so they are all scanned here. The reference text is Strudel's own JSDoc, which
 * `tools/reference/build-reference.mjs` rewords where it names the product, and which fails when it cannot. The address of the sample servers in the code is an
 * address, not a word of the app, and is not looked at. The license notices (`assets/licenses.txt`) are the exception: Cosic is a port of Strudel under the AGPL,
 * and its copyright and license notices must name the work it derives from and its authors.
 */
class ProductNameTest {
    private val engineResources: File = listOf("src/main/resources", "engine/src/main/resources").map(::File).first { it.isDirectory }

    /** `app/src/main` when the tests run from the engine's directory or from the repository's. */
    private val appMain: File? = listOf("../app/src/main", "app/src/main").map(::File).firstOrNull { it.isDirectory }

    private fun mentions(base: File, place: File): List<String> =
        place.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.flatMap { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            Regex("strudel", RegexOption.IGNORE_CASE).findAll(text).map { match ->
                val around = text.substring(maxOf(0, match.range.first - 40), minOf(text.length, match.range.last + 40)).replace(Regex("\\s+"), " ")
                "${file.relativeTo(base)}: ...$around..."
            }
        }?.toList().orEmpty()

    @Test
    fun noResourceOfTheEngineNamesTheProduct() {
        assertEquals(emptyList(), mentions(engineResources, engineResources), "the engine's resources name the product (reword them where they are made)")
    }

    @Test
    fun noResourceAssetOrManifestOfTheAppNamesTheProduct() {
        val main = appMain ?: return
        // the hosts of the links the app opens (`<data android:host="...">` of the intent filter) are addresses, not names shown to anyone;
        // the license notices must credit the work Cosic derives from
        val hits = listOf("res", "assets", "AndroidManifest.xml").flatMap { mentions(main, File(main, it)) }
            .filterNot { it.contains("android:host=") || it.startsWith("assets${File.separator}licenses.txt:") }
        assertEquals(emptyList(), hits, "the app's resources name the product")
    }
}
