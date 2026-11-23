package com.octopusman.cosic.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.SOURCE_CODE_URL
import com.octopusman.cosic.ui.openLink
import com.octopusman.cosic.ui.tagged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LICENSES_ASSET = "licenses.txt"

private val RULE_LINE = Regex("^\\s*[=-]{3,}\\s*$")
private val LIST_ITEM = Regex("^(- |\\* |\\d+[.)] |[a-z]\\) )")

/**
 * The notices are hard-wrapped at about 70 columns, which breaks into ragged half-lines on a narrow screen. A line that follows a line of at least
 * [wrapWidth] characters (as the source wrote it) is joined to it, unless either is blank or a rule line or the line starts a list item.
 */
internal fun reflowNotices(text: String, wrapWidth: Int = 60): String {
    val out = ArrayList<String>()
    var previousLength = 0
    for (line in text.split('\n')) {
        val previous = out.lastOrNull()
        val joins = previous != null && previousLength >= wrapWidth && previous.isNotBlank() && line.isNotBlank() &&
            !RULE_LINE.matches(previous) && !RULE_LINE.matches(line) && !LIST_ITEM.containsMatchIn(line.trim())
        if (joins) out[out.lastIndex] = previous!!.trimEnd() + " " + line.trimStart() else out.add(line)
        previousLength = line.trimEnd().length
    }
    return out.joinToString("\n")
}

/** The third-party notices bundled in `assets/licenses.txt`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState(initialValue = "") {
        value = withContext(Dispatchers.IO) { reflowNotices(context.assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }) }
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.credits_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.credits_source)) },
                modifier = Modifier
                    .clickable { context.openLink(SOURCE_CODE_URL) }
                    .tagged("source_code", stringResource(R.string.credits_source)),
            )
            // no content description here: it would replace the notices when a screen reader reads them
            Text(
                text = licenses,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .testTag("licenses_text"),
            )
        }
    }
}
