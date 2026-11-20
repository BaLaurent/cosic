package com.octopusman.cosic.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import cosic.SliderConfig
import cosic.io.SliderBank
import cosic.io.SliderCodec
import cosic.io.initial

/** A move of a slider: the [number] it now plays and the [text] that goes into the code in place of the old literal. */
internal class SliderChange(val number: Double, val text: String)

/**
 * The move to [raw], a position the control reports: the value snapped to the slider's step and bounds, and written as the code will
 * have it, so what plays is what the code says.
 */
internal fun sliderChange(spec: SliderConfig, raw: Double): SliderChange {
    val text = SliderCodec.format(raw, spec.min, spec.max, spec.step)
    return SliderChange(text.toDouble(), text)
}

/** Where the thumb is: the value the bank holds, else the literal written in the code, else the middle of the range. Always inside the bounds. */
internal fun sliderThumbValue(spec: SliderConfig, banked: Any?): Double {
    val lo = minOf(spec.min, spec.max)
    val hi = maxOf(spec.min, spec.max)
    val value = (banked as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: spec.initial ?: ((lo + hi) / 2)
    return value.coerceIn(lo, hi)
}

/**
 * A slider of the program as a card: the line it is on, the control and the value it plays. Moving it writes the new value into
 * [bank], where the playing pattern reads it, and asks the host to write it into the code through [onRewrite] (see [SliderEdits]).
 * [onGestureStart] and [onGestureEnd] bracket one drag, so the host can make it one step of the undo history.
 */
@Composable
fun SliderCard(
    spec: SliderConfig,
    line: Int,
    bank: SliderBank,
    onRewrite: (SliderConfig, String) -> Unit,
    modifier: Modifier = Modifier,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
) {
    var value by remember(spec) { mutableStateOf(sliderThumbValue(spec, bank.get(spec.id))) }
    var dragging by remember(spec) { mutableStateOf(false) }
    val lo = minOf(spec.min, spec.max)
    val hi = maxOf(spec.min, spec.max)
    val readout = SliderCodec.format(value, spec.min, spec.max, spec.step)
    val label = stringResource(R.string.slider_card_label, line)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(readout, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { position ->
                if (!dragging) {
                    dragging = true
                    onGestureStart()
                }
                val change = sliderChange(spec, position.toDouble())
                if (change.number != value) {
                    value = change.number
                    bank.set(spec.id, change.number)
                    onRewrite(spec, change.text)
                }
            },
            onValueChangeFinished = {
                dragging = false
                onGestureEnd()
            },
            // a slider that cannot move (min == max) has no range for the control
            valueRange = lo.toFloat()..(if (hi > lo) hi else lo + 1.0).toFloat(),
            enabled = hi > lo,
            modifier = Modifier.fillMaxWidth()
                .tagged(spec.id, label)
                .semantics { stateDescription = readout },
        )
    }
}
