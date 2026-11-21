package com.octopusman.cosic.input

import cosic.InputKind
import kotlin.test.Test
import kotlin.test.assertEquals

class InputSwitchesTest {
    private val calls = ArrayList<String>()
    private val switches = InputSwitches(
        motion = { calls.add("motion=$it") },
        gamepad = { calls.add("gamepad=$it") },
        midiIn = { calls.add("midi=$it") },
        releaseKeys = { calls.add("keys released") },
    )

    @Test
    fun aProgramThatReadsNothingSwitchesEverythingOff() {
        switches.use(emptySet())
        assertEquals(listOf("motion=false", "gamepad=false", "midi=false"), calls)
    }

    @Test
    fun onlyWhatTheProgramReadsIsSwitchedOn() {
        switches.use(setOf(InputKind.MOTION, InputKind.MIDI_IN))
        assertEquals(listOf("motion=true", "gamepad=false", "midi=true"), calls)
    }

    @Test
    fun theNextProgramSwitchesOffWhatItDoesNotRead() {
        switches.use(setOf(InputKind.GAMEPAD))
        calls.clear()
        switches.use(setOf(InputKind.MOTION))
        assertEquals(listOf("motion=true", "gamepad=false", "midi=false"), calls)
    }

    @Test
    fun theMouseAndTheKeyboardAreNeverSwitched() {
        switches.use(setOf(InputKind.MOUSE, InputKind.KEYBOARD))
        assertEquals(listOf("motion=false", "gamepad=false", "midi=false"), calls)
    }

    @Test
    fun releasingEverythingLetsGoOfTheKeysToo() {
        switches.releaseAll()
        assertEquals(listOf("motion=false", "gamepad=false", "midi=false", "keys released"), calls)
    }
}
