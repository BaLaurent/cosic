package com.octopusman.cosic.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import cosic.io.InputState

/**
 * What a browser's `KeyboardEvent.key` calls a key of a hardware keyboard: `Control`, `Shift`, `ArrowUp`, `" "`, `j`... The keyboard
 * signals of a program (`keyDown("Control:j")`, `.whenKey(...)`) use those names.
 */
object KeyMap {
    /**
     * The W3C key name of the Android key [keyCode] that types [unicodeChar] (0 when it types nothing), or null for a key that has no
     * name here (media keys, a dead key's accent...).
     */
    fun w3cKey(keyCode: Int, unicodeChar: Int): String? {
        named[keyCode]?.let { return it }
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
        if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) return String(Character.toChars(unicodeChar))
        // a key that types nothing under the modifiers held: the letter or digit it stands for
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        return null
    }

    /** The W3C name of the key [event] is about. The character is read without Ctrl, Alt and Meta, as a browser reports `key` (`j` for Control+j). */
    fun w3cKey(event: KeyEvent): String? {
        val typing = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        return w3cKey(event.keyCode, event.getUnicodeChar(typing))
    }

    private val named: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_CTRL_LEFT to "Control", KeyEvent.KEYCODE_CTRL_RIGHT to "Control",
        KeyEvent.KEYCODE_ALT_LEFT to "Alt", KeyEvent.KEYCODE_ALT_RIGHT to "Alt",
        KeyEvent.KEYCODE_SHIFT_LEFT to "Shift", KeyEvent.KEYCODE_SHIFT_RIGHT to "Shift",
        KeyEvent.KEYCODE_META_LEFT to "Meta", KeyEvent.KEYCODE_META_RIGHT to "Meta",
        KeyEvent.KEYCODE_DPAD_UP to "ArrowUp", KeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
        KeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft", KeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
        KeyEvent.KEYCODE_ENTER to "Enter", KeyEvent.KEYCODE_NUMPAD_ENTER to "Enter",
        KeyEvent.KEYCODE_SPACE to " ", KeyEvent.KEYCODE_ESCAPE to "Escape", KeyEvent.KEYCODE_TAB to "Tab",
        KeyEvent.KEYCODE_DEL to "Backspace", KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
        KeyEvent.KEYCODE_MOVE_HOME to "Home", KeyEvent.KEYCODE_MOVE_END to "End",
        KeyEvent.KEYCODE_PAGE_UP to "PageUp", KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
        KeyEvent.KEYCODE_INSERT to "Insert", KeyEvent.KEYCODE_CAPS_LOCK to "CapsLock",
    )
}

/**
 * Tells an [InputState] which keys of a hardware keyboard are held (`keyState[event.key]` of Strudel), from the key events the app sees. It only
 * observes: no event is consumed, so typing in the editor is not affected.
 */
class KeyTracker(private val inputs: InputState) {
    private val held = HashSet<String>()

    /** Notes [event]; the key goes down or up in the state. Returns nothing: callers never consume the event because of this. */
    fun observe(event: KeyEvent) {
        val key = KeyMap.w3cKey(event) ?: return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                held.add(key)
                inputs.keyDown(key)
            }
            KeyEvent.ACTION_UP -> {
                held.remove(key)
                inputs.keyUp(key)
            }
        }
    }

    /** Lets go of every key that is held: the window lost the focus, so the key-up events will not come. */
    fun releaseAll() {
        for (key in held) inputs.keyUp(key)
        held.clear()
    }
}

/** Feeds [tracker] with the keys of the focused tree below this modifier; the events go on to whoever handles them. */
fun Modifier.trackKeys(tracker: KeyTracker): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) tracker.observe(event.nativeKeyEvent)
    false
}
