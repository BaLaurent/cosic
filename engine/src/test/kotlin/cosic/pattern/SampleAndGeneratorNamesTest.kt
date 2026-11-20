package cosic.pattern

import cosic.parity.ParityData
import cosic.script.Realm
import cosic.script.SessionHost
import cosic.script.catalogue.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where Strudel puts the names of the sample functions and the generators: as a global function, as a method of patterns, or as
 * both (the oracle's `strudel-names.json`). A name Strudel has only as a method (`FX`, `collect`) is no global here either, and one
 * it has only as a global (`run`, `morph`) is no method.
 */
class SampleAndGeneratorNamesTest {
    private val sampleFunctions = listOf(
        "chop", "striate", "loopAt", "loopat", "loopAtCps", "loopatcps", "slice", "splice", "fit",
        "_chop", "_striate", "_loopAt", "_loopat", "_loopAtCps", "_loopatcps", "_slice", "_splice",
    )

    private val generators = listOf(
        "run", "binary", "binaryN", "binaryL", "binaryNL", "base", "xfade", "beat", "_beat", "_morph", "morph", "parray",
        "partials", "phases", "FX", "collect", "hsla", "_hsla", "hsl", "_hsl",
    )

    @Test
    fun everyNameIsWhereStrudelHasIt() {
        val oracle = Json.parseToJsonElement(ParityData.namesFile.readText()).jsonObject
        fun names(list: String): Set<String> = oracle.getValue(list).jsonArray.map { it.jsonPrimitive.content }.toSet()
        val strudelGlobals = names("globals")
        val strudelMethods = names("methods")
        val realm = Realm(SessionHost())
        Catalogue.install(realm)
        val globals = realm.global.ownKeys().toSet()
        val methods = realm.patternPrototype.ownKeys().toSet()
        for (name in sampleFunctions + generators) {
            assertEquals(name in strudelGlobals, name in globals, "$name as a global function")
            assertEquals(name in strudelMethods, name in methods, "$name as a method")
        }
    }
}
