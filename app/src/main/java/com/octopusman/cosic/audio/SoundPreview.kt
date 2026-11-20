package com.octopusman.cosic.audio

import cosic.CosicEngine
import cosic.SampleBank
import kotlin.math.abs
import kotlin.math.floor

/** Middle C: what a pitched sound is previewed at. */
private const val PREVIEW_NOTE = 60.0

/** What `note` is when nothing sets it: C2, the pitch a sample is recorded at (`valueToMidi(value, 36)`). */
private const val DEFAULT_MIDI = 36.0

/**
 * The file of [bank] that previewing sample [index] plays, as the engine chooses it (`getCommonSampleInfo`): the group of the note [midi]
 * (the one with the key nearest to it, the first of them in the order of the map; the only group of a bank that is no map), and in it file
 * [index], wrapping around. Null when the bank has no file to play.
 */
internal fun previewFile(bank: SampleBank, index: Int, midi: Double): Pair<Int, Int>? {
    val groups = bank.groups
    var group = 0
    if (bank.pitched) {
        var nearest = Double.POSITIVE_INFINITY
        group = -1
        for ((i, candidate) in groups.withIndex()) {
            val key = candidate.midi ?: continue
            if (key.isNaN()) continue
            val distance = abs(key - midi)
            if (distance < nearest) {
                nearest = distance
                group = i
            }
        }
        if (group < 0) return null
    }
    val files = groups.getOrNull(group)?.urls?.size ?: 0
    if (files == 0) return null
    return group to Math.floorMod(floor(index + 0.5).toInt(), files)
}

/**
 * Plays sample [index] of the sound [name] once, so the user can hear it; starts the audio output if needed. A sample that is still on
 * the network is waited for here (the engine only waits a second for one), so that the first tap on a sound plays it too.
 */
suspend fun CosicEngine.playPreview(samples: SampleRepository, audioOutput: AudioOutput, name: String, index: Int, pitched: Boolean) {
    val note = if (pitched) PREVIEW_NOTE else null
    val bank = samples.sound(name) as? SampleBank
    val file = bank?.let { previewFile(it, index, note ?: DEFAULT_MIDI) }
    if (bank != null && file != null) samples.load(bank, file.first, file.second)
    preview(name, index, note)
    audioOutput.ensureRunning()
}
