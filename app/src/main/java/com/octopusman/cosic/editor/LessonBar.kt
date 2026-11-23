package com.octopusman.cosic.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

/** Under the editor of a lesson: the step shown and the buttons to the steps before and after it. */
@Composable
internal fun LessonBar(step: Int, count: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val previous = stringResource(R.string.lesson_previous)
    val next = stringResource(R.string.lesson_next)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onStep(step - 1) },
                enabled = step > 0,
                modifier = Modifier.heightIn(min = 48.dp).tagged("lesson_previous", previous),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text(previous)
            }
            Text(
                text = stringResource(R.string.lesson_step, step + 1, count),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag("lesson_step"),
            )
            TextButton(
                onClick = { onStep(step + 1) },
                enabled = step < count - 1,
                modifier = Modifier.heightIn(min = 48.dp).tagged("lesson_next", next),
            ) {
                Text(next)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}
