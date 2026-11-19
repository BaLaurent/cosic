package com.octopusman.cosic.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import com.octopusman.cosic.R

/** The activity behind a Compose context, which Compose hands out wrapped. */
tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity in this context")
}

/** Offers [text] to other apps through the system share sheet. */
fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
}

/** Puts [text] on the clipboard and shows [confirmation], replacing any snackbar still on screen. */
suspend fun Clipboard.copyWithConfirmation(text: String, snackbar: SnackbarHostState, confirmation: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText("cosic", text)))
    snackbar.currentSnackbarData?.dismiss()
    snackbar.showSnackbar(confirmation)
}
