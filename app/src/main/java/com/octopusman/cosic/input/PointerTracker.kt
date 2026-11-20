package com.octopusman.cosic.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import cosic.io.InputState

/** Where along an extent of [extent] pixels a pointer at [position] is, as a fraction (`clientX / body.clientWidth`); null while the extent is unknown. Not clamped. */
internal fun pointerFraction(position: Float, extent: Int): Double? = if (extent > 0) position.toDouble() / extent else null

/**
 * The pointer as `mousex` and `mousey` read it: a touch, a drag or the hover of a mouse sets the position, as a fraction of this node's width and
 * height (put the modifier on the root of the screen), and the last position stays when the finger lifts. It only observes (the Initial pass,
 * nothing is consumed), so the editor and the buttons work as they do.
 */
fun Modifier.trackPointer(inputs: InputState): Modifier = pointerInput(inputs) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            pointerFraction(change.position.x, size.width)?.let { inputs.mouseX = it }
            pointerFraction(change.position.y, size.height)?.let { inputs.mouseY = it }
        }
    }
}
