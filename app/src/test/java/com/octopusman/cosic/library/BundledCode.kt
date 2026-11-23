package com.octopusman.cosic.library

import cosic.BankGroup
import cosic.CosicEngine
import cosic.EvalReport
import cosic.SampleBank
import cosic.SampleData
import cosic.SampleStore
import cosic.SoundEntry
import cosic.SoundLibrary
import cosic.sound.RegistryLibrary
import java.io.IOException
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000

/** A store whose every file is a short constant click, already decoded. */
private object ClickStore : SampleStore {
    private val click = SampleData(FloatArray(2400) { 0.2f }, SAMPLE_RATE)

    override fun sample(url: String): SampleData = click

    override fun fetchText(url: String): String = throw IOException("no network in tests: $url")

    override fun awaitSample(url: String): SampleData = click

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = click
}

/**
 * A library that has every sound: the engine's own (synths, drums) are the engine's, any other name is a bank of one file that is a short
 * constant click. What the default library really holds is the network's to say, so the bundled code is checked against the sounds it
 * names only as far as the engine is concerned.
 */
private class UniversalLibrary(private val inner: RegistryLibrary) : SoundLibrary by inner {
    override fun sound(name: String): SoundEntry =
        inner.sound(name) ?: SampleBank(name, listOf(BankGroup(null, null, listOf(name))), null, true)
}

private fun universalLibrary(): SoundLibrary = UniversalLibrary(RegistryLibrary(ClickStore, CosicEngine.BUILTIN_SOUNDS))

/** Asserts that [code] evaluates without an error or a warning. [what] names it in the failure message. */
internal fun assertEvaluatesCleanly(code: String, what: String) {
    val report = CosicEngine(SAMPLE_RATE, universalLibrary()).evaluate(code)
    val ok = assertIs<EvalReport.Ok>(report, "$what: $report")
    assertEquals(emptyList(), ok.warnings, "$what warns")
}

/** Asserts that [code] plays for eight cycles without a runtime error or a warning, makes sound, and does not clip. */
internal fun assertPlaysCleanly(code: String, what: String) {
    val engine = CosicEngine(SAMPLE_RATE, universalLibrary())
    val ok = assertIs<EvalReport.Ok>(engine.evaluate(code), what)
    engine.play()
    val buffer = FloatArray(256 * 2)
    var loudest = 0f
    repeat((8 / ok.cps * SAMPLE_RATE / 256).toInt()) {
        engine.render(buffer, 256)
        for (sample in buffer) loudest = maxOf(loudest, abs(sample))
    }
    assertNull(engine.snapshot.runtimeError, what)
    assertEquals(emptyList(), engine.snapshot.warnings, what)
    assertTrue(loudest > 0.05f, "$what is silent (loudest sample $loudest)")
    assertTrue(loudest <= 1f, what)
}
