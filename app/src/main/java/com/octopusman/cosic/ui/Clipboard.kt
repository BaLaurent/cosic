package com.octopusman.cosic.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes

/** The text on the clipboard, or null when there is none (or only blank text). */
fun Context.clipboardText(): String? {
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
}

/** Puts [text] on the clipboard, under the name [label]. */
fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** A short message at the bottom of the screen. */
fun Context.toast(@StringRes message: Int, vararg formatArgs: Any) {
    Toast.makeText(this, getString(message, *formatArgs), Toast.LENGTH_SHORT).show()
}

/** Whether the system already confirms a copy to the clipboard itself (Android 13 and later), so that the app need not. */
val systemConfirmsCopy: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
