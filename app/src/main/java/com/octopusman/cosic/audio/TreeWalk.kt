package com.octopusman.cosic.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import cosic.sound.ImportedFile
import cosic.sound.UserImport

/** A folder with more levels than this is not followed further. */
private const val MAX_DEPTH = 32

/** An entry of a folder: [id] is what its children and its URL are asked for with. */
internal class TreeEntry(val id: String, val name: String, val isDirectory: Boolean)

/**
 * The audio files of a folder and of the folders in it, at any depth, as the folder import of Strudel sees them: the path of a file starts
 * with the name of the picked folder ([rootName]) and holds the names of the folders down to it (`mykit/kick/a.wav`), so that its bank
 * is the folder it is in (see `UserImport`). [children] lists a folder by its id, [urlOf] says where a file is loaded from. Files that
 * are not audio are skipped.
 */
internal fun walkTree(
    rootName: String,
    rootId: String,
    children: (id: String) -> List<TreeEntry>,
    urlOf: (id: String) -> String,
): List<ImportedFile> {
    val files = ArrayList<ImportedFile>()
    val visited = HashSet<String>()

    fun visit(id: String, path: String, depth: Int) {
        if (!visited.add(id)) return
        for (entry in children(id)) {
            when {
                entry.isDirectory -> if (depth < MAX_DEPTH) visit(entry.id, "$path/${entry.name}", depth + 1)
                UserImport.isAudioFile(entry.name) -> files.add(ImportedFile("$path/${entry.name}", entry.name, urlOf(entry.id)))
            }
        }
    }
    visit(rootId, rootName, 0)
    return files
}

/**
 * The audio files under a folder the user picked with the system folder picker ([treeUri]), found with one query per folder (a
 * `DocumentFile` listing asks again for the name and type of every file). A folder that cannot be read any more (removed, drive gone,
 * permission lost) has no files.
 */
internal fun walkDocumentTree(context: Context, treeUri: Uri): List<ImportedFile> {
    val resolver = context.contentResolver
    val rootId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }

    fun query(uri: Uri, columns: Array<String>): List<Array<String?>> = try {
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val rows = ArrayList<Array<String?>>()
            while (cursor.moveToNext()) rows.add(Array(columns.size) { cursor.getString(it) })
            rows
        }.orEmpty()
    } catch (_: Exception) {
        // SecurityException, FileNotFoundException, IllegalArgumentException, a provider that crashed: the folder is not there for us.
        emptyList()
    }

    val rootName = query(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        .firstOrNull()?.get(0) ?: return emptyList()
    return walkTree(
        rootName = rootName,
        rootId = rootId,
        children = { id ->
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id), columns).mapNotNull { row ->
                val documentId = row[0] ?: return@mapNotNull null
                TreeEntry(documentId, row[1].orEmpty(), row[2] == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        },
        urlOf = { id -> DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString() },
    )
}
