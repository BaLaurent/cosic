package com.octopusman.cosic.share

import android.content.Intent
import cosic.io.Metadata
import cosic.io.ShareLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What importing a shared link or pasted code came to. */
internal sealed interface ImportResult {
    /** The code is a project now; [projectId] is its id. */
    data class Created(val projectId: String) : ImportResult

    /** The text is not an address of a shared pattern (and not code to take as it is either). */
    data object NotALink : ImportResult

    /** The address is of a short link that the server has no code for. */
    data object NotFound : ImportResult

    /** The short link could not be resolved: [message] says why. */
    data class Failed(val message: String) : ImportResult
}

/**
 * Turns what the user pasted or another app sent into a project: a web link that carries its code (`https://host/#...`), a short link that the server resolves
 * ([legacy]), or code as it is. [create] makes the project of a name and a code and returns its id; it may block, so it runs off the main thread. The project is
 * named after the `// @title` of the code when it has one, else [DEFAULT_NAME].
 */
internal class SharedImporter(
    private val create: (name: String, code: String) -> String,
    private val legacy: suspend (id: String) -> LegacyResult,
) {
    suspend fun import(text: String): ImportResult = when (val link = ShareLink.parse(text)) {
        is ShareLink.Imported.Code -> created(link.text)
        is ShareLink.Imported.Legacy -> when (val found = legacy(link.id)) {
            is LegacyResult.Found -> created(found.code)
            LegacyResult.NotFound -> ImportResult.NotFound
            is LegacyResult.Failed -> ImportResult.Failed(found.message)
        }
        ShareLink.Imported.None -> ImportResult.NotALink
    }

    private suspend fun created(code: String): ImportResult =
        ImportResult.Created(withContext(Dispatchers.IO) { create(nameOf(code), code) })

    companion object {
        const val DEFAULT_NAME = "Imported pattern"

        /** The title the comments of [code] declare (`// @title ...`), or [DEFAULT_NAME]. */
        fun nameOf(code: String): String {
            val title = try {
                Metadata.parse(code)["title"] as? String
            } catch (e: Exception) {
                null
            }
            return title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME
        }
    }
}

/**
 * The text an intent hands the app that may hold a pattern: the address a link was opened with (`ACTION_VIEW`), or the text another app shares (`ACTION_SEND`, a text
 * type); null for any other intent, or when there is no text.
 */
internal fun sharedTextOf(action: String?, dataString: String?, mimeType: String?, extraText: CharSequence?): String? {
    val text = when (action) {
        Intent.ACTION_VIEW -> dataString
        Intent.ACTION_SEND -> if (mimeType == null || mimeType.startsWith("text/")) extraText?.toString() else null
        else -> null
    }
    return text?.takeIf { it.isNotBlank() }
}

/** [sharedTextOf] of [intent]. */
internal fun sharedTextOf(intent: Intent): String? =
    sharedTextOf(intent.action, intent.dataString, intent.type, intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
