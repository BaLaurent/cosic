package com.octopusman.cosic.editor

import com.octopusman.cosic.io.MqttPasswordPrompt
import com.octopusman.cosic.midi.MidiService
import com.octopusman.cosic.settings.EditorSettings
import com.octopusman.cosic.settings.ProgramEditorSettings
import cosic.SoundLibrary
import kotlinx.coroutines.flow.StateFlow

/**
 * What the editor screen needs of the app besides its view model: the settings that switch its parts on and off, what programs asked of it (`fontSize()`), the
 * MIDI devices for the sheet that lists them, the question that asks for an MQTT password, the sounds an export renders with, and whether all sound goes to OSC
 * (then nothing plays on the device, and the status line says so).
 */
class EditorEnvironment(
    val settings: EditorSettings,
    val programSettings: ProgramEditorSettings,
    val oscRouteAll: StateFlow<Boolean>,
    val midi: MidiService,
    val mqttPrompt: MqttPasswordPrompt,
    val library: SoundLibrary,
)
