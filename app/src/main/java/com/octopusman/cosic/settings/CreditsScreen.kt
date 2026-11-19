package com.octopusman.cosic.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.tagged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LICENSES_ASSET = "licenses.txt"

/** The third-party notices bundled in `assets/licenses.txt`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState(initialValue = "") {
        value = withContext(Dispatchers.IO) { context.assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() } }
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.credits_title), onBack) }) { padding ->
        Text(
            text = licenses,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxWidth()
                .tagged("licenses_text", stringResource(R.string.credits_title)),
        )
    }
}
