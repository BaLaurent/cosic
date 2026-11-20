package cosic.io.input

import cosic.pattern.KeyboardState

/**
 * The names a program may give the keys that `keyDown("Control:j")` and `.whenKey(...)` wait for, `keyAlias` of `util.mjs`, in its order:
 * everything else is a `KeyboardEvent.key` value (`a`, `Enter`, `" "`, `ArrowUp`...) as it stands.
 */
object KeyboardModel {
    val keyAlias: Map<String, String> = linkedMapOf(
        "control" to "Control",
        "ctrl" to "Control",
        "alt" to "Alt",
        "shift" to "Shift",
        "down" to "ArrowDown",
        "up" to "ArrowUp",
        "left" to "ArrowLeft",
        "right" to "ArrowRight",
    )

    /**
     * A copy of the keys that are held right now (`getCurrentKeyboardState`: `{ ...keyState }`), a key that was released with `false`; null
     * when no keyboard feeds the signals (Strudel without a window returns `undefined`).
     */
    fun currentState(): Map<String, Boolean>? = KeyboardState.provider?.invoke()?.let { LinkedHashMap(it) }
}
