package com.octopusman.cosic.audio

import cosic.CosicEngine

/** Middle C: what a pitched sound is previewed at. */
private const val PREVIEW_NOTE = 60.0

/** Plays sample [index] of the sound [name] once, so the user can hear it; starts the audio output if needed. */
fun CosicEngine.playPreview(audioOutput: AudioOutput, name: String, index: Int, pitched: Boolean) {
    preview(name, index, if (pitched) PREVIEW_NOTE else null)
    audioOutput.ensureRunning()
}
