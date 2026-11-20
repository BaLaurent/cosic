package com.octopusman.cosic.audio

import java.io.IOException
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleUrlsTest {
    private fun normalized(raw: String) = normalizeUrl(raw).toString()

    @Test
    fun onlyTheFirstHashIsDataAndALaterOneStartsAFragmentThatIsNeverSent() {
        assertEquals("https://h/a%20b/c%23d.wav", normalized("https://h/a b/c#d.wav"))
        assertEquals("https://h/a%23b", normalized("https://h/a#b#c.wav"))
    }

    @Test
    fun aPathThatIsEncodedAlreadyStaysWhatItWas() {
        assertEquals(
            "https://h/Grand%20Piano%2C%20Steinway%20B/x%231.wav",
            normalized("https://h/Grand%20Piano%2C%20Steinway%20B/x%231.wav"),
        )
    }

    @Test
    fun thePunctuationOfRealSampleNamesIsLeftAlone() {
        assertEquals("https://h/Renaissance%20Organ/4'/90's-sd.wav", normalized("https://h/Renaissance%20Organ/4'/90's-sd.wav"))
        assertEquals(
            "https://h/Mbira%20Mavembe%20(Gandanga)%2C%20Zimbabwe/Mb.wav",
            normalized("https://h/Mbira%20Mavembe%20(Gandanga)%2C%20Zimbabwe/Mb.wav"),
        )
        assertEquals("https://h/a&b+c,d;e=f@g[h]i\$j!k*l~m:n.wav", normalized("https://h/a&b+c,d;e=f@g[h]i\$j!k*l~m:n.wav"))
    }

    @Test
    fun spacesQuotesAnglesBackticksBracesAndControlsAreEncodedInThePath() {
        assertEquals("https://h/a%20b%22c%3Cd%3Ee%60f%7Bg%7Dh%01i.wav", normalized("https://h/a b\"c<d>e`f{g}h\u0001i.wav"))
    }

    @Test
    fun nonAsciiCharactersAreEncodedAsUtf8() {
        assertEquals("https://h/%C3%A9/%C3%BC.wav", normalized("https://h/é/ü.wav"))
        assertEquals("https://h/%F0%9F%8E%B5.wav", normalized("https://h/\uD83C\uDFB5.wav")) // a character outside the BMP
        assertEquals("https://h/%EF%BF%BD.wav", normalized("https://h/\uD800.wav")) // a lone surrogate
    }

    @Test
    fun aQuestionMarkStartsTheQueryWhichEncodesTheApostropheAndLeavesTheRest() {
        assertEquals(
            "https://shabda.ndre.gr/speech/hello.json?gender=f&language=en-GB&strudel=1%27",
            normalized("https://shabda.ndre.gr/speech/hello.json?gender=f&language=en-GB&strudel=1'"),
        )
        assertEquals("https://h/a'b?x=a%20b%22c%3Cd%3E&y=%7B", normalized("https://h/a'b?x=a b\"c<d>&y=%7B"))
        // in the path the apostrophe stays, and the query keeps its braces
        assertEquals("https://h/p?q={a}`b", normalized("https://h/p?q={a}`b"))
    }

    @Test
    fun dotSegmentsAreResolvedAndBackslashesAreSlashes() {
        assertEquals("https://h/b/c.wav", normalized("https://h/a/../b/./c.wav"))
        assertEquals("https://h/b/c.wav", normalized("https://h/a/%2e%2e/b/%2E/c.wav"))
        assertEquals("https://h/a/b.wav", normalized("https://h\\a\\b.wav"))
        assertEquals("https://h/", normalized("https://h/a/.."))
    }

    @Test
    fun aUrlWithoutAPathIsLeftWithoutOne() {
        assertEquals("http://localhost:5432", normalized("http://localhost:5432"))
        assertEquals("http://localhost:5432?a=b", normalized("http://localhost:5432?a=b"))
    }

    @Test
    fun theSchemeAndHostAreKeptButAnInternationalHostBecomesPunycode() {
        assertEquals("https://Host.Example:8443/x", normalized("https://Host.Example:8443/x"))
        assertEquals("https://xn--bcher-kva.example/x", normalized("https://bücher.example/x"))
        assertEquals("https://user@xn--bcher-kva.example:8080/x", normalized("https://user@bücher.example:8080/x"))
    }

    @Test
    fun whitespaceAroundAndInsideTheUrlGoesAsTheUrlParserDoesIt() {
        assertEquals("https://h/ab.wav", normalized("  https://h/a\tb.wav\n"))
    }

    @Test
    fun aUrlThatIsNotAbsoluteHttpIsAnError() {
        for (bad in listOf("ftp://h/x", "file:///x", "//h/x", "h/x", "", "https:///x", "not a url", "content://x/y")) {
            assertFailsWith<IOException>(bad) { normalizeUrl(bad) }
        }
    }

    @Test
    fun normalizingTwiceChangesNothing() {
        for (raw in listOf("https://h/a b/c#d.wav", "https://h/é?x='", "https://h/a/../b%20c", "https://h/Renaissance%20Organ/4'/x.wav")) {
            val once = normalized(raw)
            assertEquals(once, normalized(once), raw)
        }
    }

    @Test
    fun everyFileOfTheDefaultLibraryBecomesAnAllowedUrlThatNormalizingLeavesAlone() {
        var checked = 0
        for (pack in listOf("vcsl", "tidal-drum-machines", "piano", "uzu-drumkit", "uzu-wavetables", "mridangam")) {
            val text = javaClass.getResourceAsStream("/samples/$pack.json")!!.bufferedReader().readText()
            // Every string value of the maps that ends in an audio extension is a path.
            for (match in Regex("\"([^\"]+\\.(?:wav|mp3|ogg|flac))\"").findAll(text)) {
                val raw = "https://strudel.b-cdn.net/x/" + match.groupValues[1]
                val url = normalizeUrl(raw)
                requireAllowed(url)
                assertFalse(' ' in url.toString() || '"' in url.toString(), raw)
                assertEquals(url.toString(), normalizeUrl(url.toString()).toString(), raw)
                checked++
            }
        }
        assertTrue(checked > 4_000, "checked $checked files")
    }

    @Test
    fun theFilesOfTheLibraryThatNeedEncodingGetTheEncodingOfTheBrowser() {
        // 426 file names hold raw spaces, one an apostrophe: the browser sends %20, and leaves the apostrophe.
        assertEquals(
            "https://strudel.b-cdn.net/VCSL/Chordophones/Composite%20Chordophones/Concert%20Harp/KSHarp_A2_mf1.wav",
            normalized("https://strudel.b-cdn.net/VCSL/Chordophones/Composite Chordophones/Concert Harp/KSHarp_A2_mf1.wav"),
        )
    }

    @Test
    fun sha1MatchesTheKnownVector() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1Hex(""))
    }

    @Test
    fun cleartextIsAllowedForLocalAndPrivateHostsOnly() {
        val allowed = listOf(
            "localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.20",
            "169.254.1.1", "studio.local", "Studio.Local", "[::1]",
        )
        val refused = listOf(
            "example.com", "172.32.0.1", "172.15.0.1", "192.169.1.1", "8.8.8.8", "1.2.3.4.5", "10.0.0.256", "localhost.example.com", "evil.local.com",
            "0.0.0.0", "10.0.0", "10.a.0.1", "",
        )
        for (host in allowed) assertTrue(isCleartextAllowed(host), host)
        for (host in refused) assertFalse(isCleartextAllowed(host), host)
    }

    @Test
    fun httpsIsAlwaysAllowedAndHttpOnlyToLocalHosts() {
        requireAllowed(URL("https://example.com/x"))
        requireAllowed(URL("http://localhost:5432/x"))
        requireAllowed(URL("http://192.168.0.7:8080/x"))
        assertFailsWith<IOException> { requireAllowed(URL("http://example.com/x")) }
        assertFailsWith<IOException> { requireAllowed(URL("http://8.8.8.8/x")) }
        assertFailsWith<IOException> { requireAllowed(URL("ftp://localhost/x")) }
    }

    @Test
    fun aRedirectIsAbsoluteOrRelativeToTheUrlItCameFrom() {
        val base = URL("https://h/dir/a.wav")

        assertEquals("https://h/b.wav", resolveRedirect(base, "/b.wav").toString())
        assertEquals("https://h/dir/b.wav", resolveRedirect(base, "b.wav").toString())
        assertEquals("https://other/x%20y.wav", resolveRedirect(base, "https://other/x y.wav").toString())
        assertEquals("https://elsewhere/x", resolveRedirect(base, "//elsewhere/x").toString())
        assertEquals("http://localhost:5432/x", resolveRedirect(base, "http://localhost:5432/x").toString())
        assertEquals("https://h/dir/a%20b.wav", resolveRedirect(base, "a b.wav").toString())
    }
}
