package cosic.io

import cosic.io.ShareLink.Imported
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The links of the web REPL. `tools/strudel-ref/share-oracle.mjs` runs `code2hash` and `hash2code` of the pinned commit (and so the `atob`,
 * `decodeURIComponent` and `TextDecoder` of Node under them) over programs, broken links and random bytes, and the codec must give the same
 * answer for every one of them, the failures included.
 */
class ShareLinkTest {
    private val oracle: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/share/oracle.json")?.bufferedReader()?.readText()
            ?: error("/share/oracle.json is missing: run `node tools/strudel-ref/share-oracle.mjs`")
        Json.parseToJsonElement(text).jsonObject
    }

    private val tunes: Map<String, String> by lazy {
        val text = javaClass.getResourceAsStream("/parity/tunes.json")!!.bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["code"]!!.jsonPrimitive.content
        }
    }

    private fun codeOf(case: JsonObject): String =
        case["code"]?.jsonPrimitive?.content ?: tunes.getValue(case["source"]!!.jsonPrimitive.content.removePrefix("tunes:"))

    // ---- against Strudel ------------------------------------------------------------------------------------------------

    @Test
    fun theHashOfEveryProgramIsStrudels() {
        val cases = oracle["encode"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 50)
        for (case in cases) {
            val code = codeOf(case)
            val hash = case["hash"]!!.jsonPrimitive.content
            val label = case["source"]?.jsonPrimitive?.content ?: code.take(40)
            assertEquals(hash, ShareLink.hash(code), "hash of $label")
            // what comes back is the code, but for a byte order mark that the decoder drops and a surrogate that was written as U+FFFD
            val decoded = case["decoded"]?.jsonPrimitive?.content ?: code
            assertEquals(decoded, ShareLink.decodeHash(hash), "decoded $label")
        }
    }

    @Test
    fun everyLinkIsReadOrRefusedAsStrudelDoes() {
        val cases = oracle["decode"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 400)
        var refused = 0
        for (case in cases) {
            val hash = case["hash"]!!.jsonPrimitive.content
            val error = case["error"]?.jsonPrimitive?.contentOrNull
            if (error != null) {
                refused++
                assertNull(ShareLink.decodeHash(hash), "$hash makes Strudel throw $error")
            } else {
                assertEquals(case["code"]!!.jsonPrimitive.content, ShareLink.decodeHash(hash), "decoded $hash")
            }
        }
        assertTrue(refused > 20, "the oracle has links Strudel cannot read")
    }

    // ---- the vectors of the documentation of Strudel ----------------------------------------------------------------------

    @Test
    fun theLinkInTheReadmeOfStrudel() {
        val code = "\$: s(\"bd*4\")\n\nall(osc)"
        assertEquals("JDogcygiYmQqNCIpCgphbGwob3NjKQ%3D%3D", ShareLink.hash(code))
        assertEquals(code, ShareLink.decodeHash("JDogcygiYmQqNCIpCgphbGwob3NjKQ%3D%3D"))
    }

    @Test
    fun theLinkInTheCommentOfUtilMjs() {
        assertEquals("ImMzIGUzIg%3D%3D", ShareLink.hash("\"c3 e3\""))
        assertEquals("\"c3 e3\"", ShareLink.decodeHash("ImMzIGUzIg%3D%3D"))
        assertEquals("https://strudel.cc/#ImMzIGUzIg%3D%3D", ShareLink.encode("\"c3 e3\""))
    }

    @Test
    fun theCodeTheReplStartsWithRoundTrips() {
        val code = "\$: s(\"[bd <hh oh>]*2\").bank(\"tr909\").dec(.4)"
        assertEquals(code, ShareLink.decodeHash(ShareLink.hash(code)))
    }

    @Test
    fun unicodeRoundTrips() {
        for (code in listOf("é 🎵", "日本語 한국어 العربية", "👨‍👩‍👧‍👦", "a\u0000b")) {
            assertEquals(code, ShareLink.decodeHash(ShareLink.hash(code)), code)
        }
    }

    @Test
    fun theSharedCodeHasThePrebakeScriptInFrontWhenAskedTo() {
        val link = ShareLink.forSharing("s(\"bd\")", "const x = 1")
        assertEquals("const x = 1\ns(\"bd\")", (ShareLink.parse(link) as Imported.Code).text)
        assertEquals(ShareLink.encode("s(\"bd\")"), ShareLink.forSharing("s(\"bd\")", null))
    }

    // ---- what a pasted text or an opened link is --------------------------------------------------------------------------

    @Test
    fun aLinkOfTheWebReplIsItsCode() {
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("https://strudel.cc/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("  https://strudel.cc/#ImMzIGUzIg%3D%3D\n"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("http://www.STRUDEL.cc/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("https://strudel.tidalcycles.org/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("strudel.cc/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("https://strudel.cc#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("#ImMzIGUzIg%3D%3D"))
    }

    @Test
    fun aShortLinkIsItsKey() {
        assertEquals(Imported.Legacy("J01s5i1J0200"), ShareLink.parse("https://strudel.cc/?J01s5i1J0200"))
        assertEquals(Imported.Legacy("J01s5i1J0200"), ShareLink.parse("https://strudel.cc/?J01s5i1J0200&other=1"))
        assertEquals(Imported.Legacy("J01s5i1J0200"), ShareLink.parse("https://strudel.cc/?J01s5i1J0200#"))
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/?"))
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/"))
    }

    @Test
    fun aFragmentIsReadBeforeTheQueryAsTheReplDoes() {
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("https://strudel.cc/?J01s5i1J0200#ImMzIGUzIg%3D%3D"))
        // a fragment that cannot be read ends it: the query is not looked at then (initCode)
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/?J01s5i1J0200#%"))
    }

    @Test
    fun theTextBetweenTheFirstAndTheSecondHashIsTheCode() {
        assertEquals(Imported.Code("\"c3 e3\""), ShareLink.parse("https://strudel.cc/#ImMzIGUzIg%3D%3D#ignored"))
    }

    @Test
    fun otherLinksAreNotImported() {
        assertEquals(Imported.None, ShareLink.parse("https://example.com/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc.example.com/#ImMzIGUzIg%3D%3D"))
        assertEquals(Imported.None, ShareLink.parse("https://github.com/uzu/strudel#readme"))
        // a page of the web REPL that is not the REPL: its fragment is an anchor
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/learn/getting-started/#first-sounds"))
        assertEquals(Imported.None, ShareLink.parse("https://user@example.com:8080/#ImMzIGUzIg%3D%3D"))
    }

    @Test
    fun aLinkThatCannotBeReadHoldsNothing() {
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/#-_-_"))
        assertEquals(Imported.None, ShareLink.parse("https://strudel.cc/#Y"))
    }

    @Test
    fun aTextThatIsNoAddressIsCodeAsItIs() {
        val code = "  s(\"bd sd\")\n.fast(2)\n"
        assertEquals(Imported.Code(code), ShareLink.parse(code))
        assertEquals(Imported.None, ShareLink.parse(""))
        assertEquals(Imported.None, ShareLink.parse(" \n\t "))
    }

    // ---- short links ---------------------------------------------------------------------------------------------------

    @Test
    fun theRequestForAShortLink() {
        assertEquals(
            "https://pidxdsxphlhzjnzmifth.supabase.co/rest/v1/code_v1?select=code&hash=eq.J01s5i1J0200",
            ShareLink.legacyRequestUrl("J01s5i1J0200"),
        )
        assertEquals("https://pidxdsxphlhzjnzmifth.supabase.co/rest/v1/code_v1?select=code&hash=eq.a+b%26c", ShareLink.legacyRequestUrl("a b&c"))
    }

    @Test
    fun theKeyIsTheOneOfTheWebRepl() {
        val source = java.io.File("tools/strudel-ref/strudel/website/src/repl/util.mjs").takeIf { it.exists() }
            ?: java.io.File("../tools/strudel-ref/strudel/website/src/repl/util.mjs").takeIf { it.exists() }
            ?: return // the checkout of Strudel is not there
        assertTrue(ShareLink.LEGACY_KEY in source.readText(), "the key of the web REPL is not ${ShareLink.LEGACY_KEY}")
    }

    @Test
    fun theCodeInTheAnswerOfTheServer() {
        assertEquals("s(\"bd\")", ShareLink.legacyCode("[{\"code\":\"s(\\\"bd\\\")\"}]"))
        assertEquals("a", ShareLink.legacyCode("[{\"code\":\"a\"},{\"code\":\"b\"}]"))
        assertNull(ShareLink.legacyCode("[]"))
        assertNull(ShareLink.legacyCode("{\"message\":\"nope\"}"))
        assertNull(ShareLink.legacyCode("not json"))
        assertNull(ShareLink.legacyCode("[{\"code\":null}]"))
    }

    // ---- the pieces ------------------------------------------------------------------------------------------------------

    @Test
    fun lonelySurrogatesBecomeTheReplacementCharacter() {
        assertEquals("\uFFFD", ShareLink.decodeHash(ShareLink.hash("\ud800")))
        assertEquals("a\uFFFDb", ShareLink.decodeHash(ShareLink.hash("a\udc00b")))
        assertEquals("🎵", ShareLink.decodeHash(ShareLink.hash("🎵")))
    }

    @Test
    fun aByteOrderMarkInFrontIsDropped() {
        assertEquals("\"c3\"", ShareLink.decodeHash(ShareLink.hash("\ufeff\"c3\"")))
        assertEquals("\ufeffa", ShareLink.decodeHash(ShareLink.hash("\ufeff\ufeffa")))
    }

    @Test
    fun theBytesOfACharacterThatIsCutShortAreOneReplacement() {
        // E2 82 AC is the euro sign; E2 82 alone is one maximal part of it, as is F0 9F 8E
        assertEquals("\uFFFDa", ShareLink.decodeUtf8(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0x61)))
        assertEquals("\uFFFD", ShareLink.decodeUtf8(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x8E.toByte())))
        assertEquals("\uFFFD\uFFFD\uFFFD", ShareLink.decodeUtf8(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0xFF.toByte())))
    }

    @Test
    fun theOracleHoldsWhatItShould() {
        // a safeguard for the data itself: the commit it was made at, and the programs of the REPL among its cases
        assertEquals("84ef32eec8074f90c6690ab8d6a6c204f508b182", oracle["strudelCommit"]!!.jsonPrimitive.content)
        val sources = oracle["encode"]!!.jsonArray.mapNotNull { it.jsonObject["source"]?.jsonPrimitive?.content }
        if (sources.size != tunes.size) fail("every tune of the REPL is a case: ${sources.size} of ${tunes.size}")
    }
}
