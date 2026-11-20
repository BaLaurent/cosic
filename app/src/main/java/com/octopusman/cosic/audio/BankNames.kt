package com.octopusman.cosic.audio

/** Lowercase, spaces and dashes become underscores, anything else outside `[a-z0-9_]` is dropped. */
internal fun bankName(folderName: String?): String =
    folderName.orEmpty().lowercase().replace(' ', '_').replace('-', '_').filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }

/** The name of the bank that holds [sound] of the kit [kit]: what `s("sound").bank("kit")` plays. */
internal fun kitBankName(kit: String, sound: String): String = "${kit}_$sound"

/** The sound a bank of the kit [kit] is called inside that kit: its [bankName] without the `<kit>_`. */
internal fun kitSoundName(kit: String, bankName: String): String = bankName.removePrefix("${kit}_")

/** A bank an imported folder makes: [name] is what `s("…")` calls it, [kit] the drum machine it is a sound of, if any. */
internal data class ImportedBank(val name: String, val kit: String?)

/**
 * The banks the audio files of [folder] make. The folder that was picked makes one, named after it.
 * A folder inside it ([parent] is the picked folder) is a sound of the kit named after the picked
 * folder, `<parent>_<folder>`: `mykit/bd` plays with `s("bd").bank("mykit")`. It is also a bank named
 * after itself, unless that name is a [builtIn] sound: a kit's `bd` must not replace the built-in `bd`.
 */
internal fun importedBanks(folder: String?, parent: String? = null, builtIn: (String) -> Boolean = { false }): List<ImportedBank> {
    val name = bankName(folder)
    if (name.isEmpty()) return emptyList()
    val kit = bankName(parent)
    if (kit.isEmpty()) return listOf(ImportedBank(name, null))
    return listOfNotNull(ImportedBank(name, null).takeUnless { builtIn(name) }, ImportedBank(kitBankName(kit, name), kit))
}
