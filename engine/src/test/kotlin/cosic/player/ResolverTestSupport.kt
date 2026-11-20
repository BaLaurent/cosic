package cosic.player

import cosic.pattern.ControlMap

/** The lookup of an event that lasts one second at the tempo of 0.5 cycles per second: what a test that does not care about the length of the event asks for. */
internal fun SoundResolver.resolve(controls: ControlMap): SoundLookup = resolve(controls, 1.0, 0.5)
