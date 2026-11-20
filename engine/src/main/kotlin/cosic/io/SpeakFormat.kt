package cosic.io

import cosic.pattern.jsToString

/**
 * The text `.speak()` says for a hap value: what `new SpeechSynthesisUtterance(words)` makes of it, which is JavaScript's `String(value)`.
 * A string is itself, a number is written as JavaScript writes it (`3` for 3.0, `0.1`), a list joins its elements with commas, `null` and
 * `undefined` are the words, and a map is `[object Object]` (which is what `s("bd").speak("en")` says: speak the control map's word, not a sound name).
 */
internal fun speakText(value: Any?): String = jsToString(value)
