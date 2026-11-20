package com.octopusman.cosic.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

/** How much of the editor's height the reference takes in portrait; the rest is the code. */
internal object ReferenceSplit {
    const val DEFAULT = 0.45f
    const val MIN = 0.2f
    const val MAX = 0.95f

    // Above this the panel counts as tall already, and a tap on the handle brings it back.
    private const val TALL = 0.7f

    /** [fraction] after the handle was dragged by [dragPx] (negative is up) in a layout [totalPx] high. */
    fun dragged(fraction: Float, dragPx: Float, totalPx: Float): Float =
        if (totalPx <= 0f) fraction else (fraction - dragPx / totalPx).coerceIn(MIN, MAX)

    /**
     * [fraction] when the panel opens in a layout [totalPx] high: at least enough for [minPanelPx], so
     * the list shows on a short screen, and at most [MAX].
     */
    fun onOpen(fraction: Float, minPanelPx: Float, totalPx: Float): Float =
        if (totalPx <= 0f) fraction else maxOf(fraction, (minPanelPx / totalPx).coerceAtMost(MAX))

    fun toggled(fraction: Float): Float = if (fraction >= TALL) DEFAULT else MAX
}

/** The grip between the code and the reference: drag it up to give the reference more room, tap to toggle. */
@Composable
internal fun ReferenceHandle(onDrag: (Float) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.reference_handle)
    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(MaterialTheme.colorScheme.surface)
            .draggable(rememberDraggableState(onDrag), Orientation.Vertical)
            .clickable(onClickLabel = label, onClick = onTap)
            .tagged("reference_handle", label),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
        )
    }
}
