package com.octopusman.cosic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.BuildConfig
import com.octopusman.cosic.R
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.monetization.BannerAd

/** Top bar of the secondary screens: a back arrow, the [title] and optional [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { BackButton(onBack) },
        actions = actions,
    )
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.tagged("back", stringResource(R.string.action_back))) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
    }
}

/**
 * The ad banner for a Scaffold's bottom bar: nothing while ads are off, and no space taken while
 * the keyboard is up, so the banner never covers what is being typed. The banner stays composed
 * while hidden: tearing the AdView down and rebuilding it would request a new ad every time the
 * keyboard closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdBannerBar(ads: AdsController) {
    val adsEnabled by ads.adsEnabled.collectAsStateWithLifecycle()
    if (!adsEnabled) return
    val hidden = WindowInsets.isImeVisible
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (hidden) layout(0, 0) {} else layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        BannerAd(BuildConfig.ADMOB_BANNER_ID)
    }
}
