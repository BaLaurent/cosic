package com.octopusman.cosic.audio

import cosic.BuiltinSound
import cosic.SoundEntry
import cosic.sound.ImportedFile
import cosic.sound.RegistryLibrary
import cosic.sound.UserImport
import cosic.sound.registryKey

/** The audio files of one imported folder: [id] is what the folder is called in the settings (its URI), [files] what the walk of it found. */
internal class ImportedTree(val id: String, val files: List<ImportedFile>)

/**
 * The banks the user imported, as sounds of the library. They are registered on top of the default sounds, in the order of the folders, so
 * that a bank wins the name of one before it and of a default sound (Strudel's `registerSampleSource` replaces: an imported `bd` is the
 * `bd` of every program). What a bank replaced comes back when the folder is removed.
 *
 * [replaceAll] does the whole job anew every time (a few registrations per folder); there is no state to keep right but the list of what
 * was replaced.
 */
internal class ImportedBanks(private val library: RegistryLibrary) {
    private class Applied(val tree: String, val replaced: SoundEntry?)

    // By registry key, in the order of registration.
    private val applied = LinkedHashMap<String, Applied>()

    /** The folder (its [ImportedTree.id]) the sound with registry key [key] was imported from, or null when it was not imported. */
    @Synchronized
    fun treeOf(key: String): String? = applied[key]?.tree

    /**
     * Makes [trees] the folders that are imported, in their order: everything an earlier call registered goes, and gives the names of the
     * sounds it replaced back; then the banks of the folders are registered.
     */
    @Synchronized
    fun replaceAll(trees: List<ImportedTree>) {
        for ((key, bank) in applied) {
            library.unregister(key)
            val replaced = bank.replaced ?: continue
            // The sound may have been found under an alias (`tr909_bd` for `RolandTR909_bd`): it is registered again under its own name, and the alias made again.
            if (registryKey(replaced.name) == key) library.register(replaced) else library.soundAlias(replaced.name, key)
        }
        applied.clear()
        for (tree in trees) {
            for (bank in UserImport.banks(tree.files)) {
                // What the name was before any folder took it: what a bank of an earlier folder replaced, else the sound that is registered now
                // (the engine's own sounds are behind the registry and need no care).
                val replaced = applied[bank.name]?.replaced ?: library.sound(bank.name)?.takeIf { it !is BuiltinSound }
                library.registerSampleSource(bank.sourceKey, bank.files.map { it.url })
                applied[bank.name] = Applied(tree.id, replaced)
            }
        }
    }
}
