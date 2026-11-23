package com.octopusman.cosic.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.octopusman.cosic.R

const val PRIVACY_POLICY_URL = "https://github.com/BaLaurent/cosic/blob/master/docs/privacy-policy.md"
const val SOURCE_CODE_URL = "https://github.com/BaLaurent/cosic"

/** Opens [url] in the browser; says so when the device has no app that can (`LocalUriHandler` would crash there). */
fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.link_no_app)
    }
}
