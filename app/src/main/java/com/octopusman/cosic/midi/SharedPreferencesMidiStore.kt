package com.octopusman.cosic.midi

import android.content.Context
import cosic.io.midi.MidiStateStore

/**
 * Keeps the values `midin` has received (a string under a key, like `localStorage` in a browser) in the app's private preferences, so that a
 * knob keeps its value from one run of the app to the next. Saving does not wait for the disk.
 */
class SharedPreferencesMidiStore(context: Context) : MidiStateStore {
    private val preferences = context.applicationContext.getSharedPreferences("midi_input_state", Context.MODE_PRIVATE)

    override fun load(key: String): String? = preferences.getString(key, null)

    override fun save(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}
