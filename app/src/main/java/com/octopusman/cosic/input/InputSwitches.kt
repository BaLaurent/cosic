package com.octopusman.cosic.input

import cosic.InputKind

/**
 * Which inputs of the phone are listened to, for the program that plays: a program says what it reads ([InputKind], `ProgramInfo.inputsUsed`) and only
 * that is switched on (motion sensors and game controllers drain the battery, MIDI inputs hold devices that other apps may want). Every call of [use]
 * sets all three switches, so what the next program does not read goes off. The pointer and the keys are not switched: they are followed all the time (they cost nothing).
 *
 * The switches are plain functions so that the app's hubs plug in and a test can watch them; each is called on the thread that calls [use].
 */
class InputSwitches(
    private val motion: (Boolean) -> Unit,
    private val gamepad: (Boolean) -> Unit,
    private val midiIn: (Boolean) -> Unit,
    private val releaseKeys: () -> Unit,
) {
    /** Switches on what a program that reads [used] needs, and everything else off. */
    fun use(used: Set<InputKind>) {
        motion(InputKind.MOTION in used)
        gamepad(InputKind.GAMEPAD in used)
        midiIn(InputKind.MIDI_IN in used)
    }

    /** The program stopped or the screen was left: nothing is listened to, and the keys that were held are let go (their key-up events will not come). */
    fun releaseAll() {
        motion(false)
        gamepad(false)
        midiIn(false)
        releaseKeys()
    }
}
