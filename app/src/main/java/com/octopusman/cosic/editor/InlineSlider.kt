package com.octopusman.cosic.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import cosic.io.SliderCodec

/** Where the thumb is: the number the slider plays now ([value], null when nothing has set it), else the number written in the code, else the middle of the range; inside the bounds. */
internal fun sliderThumb(seed: SliderSeed, value: Double?): Double {
    val lo = minOf(seed.min, seed.max)
    val hi = maxOf(seed.min, seed.max)
    val number = value?.takeIf { it.isFinite() } ?: seed.initial?.takeIf { it.isFinite() } ?: ((lo + hi) / 2)
    return number.coerceIn(lo, hi)
}

/** The number a touch at [x] of a control [widthPx] wide means: the middle of the thumb (of radius [radiusPx]) goes from `radiusPx` to `widthPx - radiusPx`. */
internal fun sliderValueAt(x: Float, widthPx: Float, radiusPx: Float, min: Double, max: Double): Double {
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    val span = widthPx - 2 * radiusPx
    if (span <= 0f) return lo
    val fraction = ((x - radiusPx) / span).coerceIn(0f, 1f)
    return lo + fraction * (hi - lo)
}

/** How far along its track the thumb is, from 0 to 1. */
internal fun sliderFraction(value: Double, min: Double, max: Double): Float {
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    return if (hi > lo) ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() else 0f
}

/**
 * The control of a slider, laid over the gap in front of its number (INLINE D-E3): a track and a thumb. A touch on it, and a drag, sets the number: [onStart] comes first, then
 * [onChange] with the position the finger is at on every move, and [onEnd] when the finger goes (or the gesture is cancelled). The moves are consumed, so the scroll of the
 * editor does not take the drag.
 */
@Composable
internal fun InlineSlider(
    value: Double,
    min: Double,
    max: Double,
    onStart: () -> Unit,
    onChange: (Double) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    val track = MaterialTheme.colorScheme.outline
    val filled = MaterialTheme.colorScheme.primary
    val thumbRadius = THUMB_DP / 2
    val readout = SliderCodec.format(value, min, max, null)
    Canvas(
        modifier
            .pointerInput(min, max) {
                val radius = thumbRadius.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    try {
                        start()
                        change(sliderValueAt(down.position.x, size.width.toFloat(), radius, min, max))
                        drag(down.id) { moved ->
                            moved.consume()
                            change(sliderValueAt(moved.position.x, size.width.toFloat(), radius, min, max))
                        }
                    } finally {
                        end()
                    }
                }
            }
            .semantics {
                stateDescription = readout
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), minOf(min, max).toFloat()..maxOf(max, min).toFloat())
                setProgress { target ->
                    start()
                    change(target.toDouble())
                    end()
                    true
                }
            },
    ) {
        val radius = thumbRadius.dp.toPx()
        val trackHeight = TRACK_DP.dp.toPx()
        val centreY = size.height / 2
        val x = radius + sliderFraction(value, min, max) * (size.width - 2 * radius)
        drawRoundRect(track, Offset(radius, centreY - trackHeight / 2), Size(size.width - 2 * radius, trackHeight), CornerRadius(trackHeight / 2))
        drawRoundRect(filled, Offset(radius, centreY - trackHeight / 2), Size(x - radius, trackHeight), CornerRadius(trackHeight / 2))
        drawCircle(filled, radius, Offset(x, centreY))
    }
}

/**
 * The slider [slider] of the editor: it reads its number from [values] here, in its own scope, so that dragging it recomposes it and nothing else. [line] is the 1-based line it is
 * on, for accessibility. The number the thumb shows is the one in [values], else the one written in the code.
 */
@Composable
internal fun SliderHost(
    slider: PlannedSlider,
    values: Map<String, Double>,
    onStart: (String) -> Unit,
    onChange: (String, Double) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seed = slider.anchor.seed
    val id = slider.id
    InlineSlider(
        value = sliderThumb(seed, values[id]),
        min = seed.min,
        max = seed.max,
        onStart = { onStart(id) },
        onChange = { onChange(id, it) },
        onEnd = onEnd,
        modifier = modifier.tagged(id, stringResource(R.string.editor_slider, slider.line)),
    )
}

internal const val TRACK_DP = 4
internal const val THUMB_DP = 14
