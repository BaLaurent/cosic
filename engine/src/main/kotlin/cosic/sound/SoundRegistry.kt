package cosic.sound

import cosic.BuiltinSound
import cosic.SoundEntry
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import java.util.Collections
import java.util.IdentityHashMap

/** JavaScript's `\s`: more than the white space Java's `\s` knows. */
private val JS_WHITESPACE = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

/** The key a sound is registered under (`registerSound`, superdough.mjs:61-64): lower case, runs of white space as one `_`. */
fun registryKey(name: String): String = name.lowercase().replace(JS_WHITESPACE, "_")

/**
 * `soundMap` of superdough: the registered sounds by key, and the engine's own sounds behind them. The map is replaced as a whole
 * under a lock by every change and never modified once published, so lookups need no lock; they see either the registry before a
 * change or after it, never half of one.
 *
 * Keys are lower case (see [registryKey]), except those of [alias], which keeps the alias as written: [lookup] lowercases what it is
 * asked for, so such an alias cannot be found (Strudel's behaviour, kept on purpose).
 */
internal class SoundRegistry(builtins: Set<String>) {
    private val builtinEntries: Map<String, BuiltinSound> = builtins.associateBy({ it.lowercase() }) { BuiltinSound(it) }
    private val lock = Any()

    @Volatile
    private var sounds: Map<String, SoundEntry> = emptyMap()

    /** Counts the changes; see `SoundLibrary.revision`. */
    @Volatile
    var revision: Long = 0L
        private set

    /** `getSound(s)`: the registered sound of that name in any case, else the built-in of it, else null. */
    fun lookup(name: String): SoundEntry? {
        val key = name.lowercase()
        return sounds[key] ?: builtinEntries[key]
    }

    /** The registered sound whose key is exactly [key]: no lower casing and no built-ins (`soundMap.get()[s]` of `getDuration`). */
    fun exact(key: String): SoundEntry? = sounds[key]

    /** Registers [entry] under the key of its name, replacing what was there (the place of a replaced key in the list stays). */
    fun register(entry: SoundEntry) = registerAll(listOf(entry))

    fun registerAll(entries: List<SoundEntry>) {
        if (entries.isEmpty()) return
        update { map -> for (entry in entries) map[registryKey(entry.name)] = entry }
    }

    /** Removes the sound registered as [name]; true when there was one. Aliases of it stay. */
    fun remove(name: String): Boolean = synchronized(lock) {
        val key = registryKey(name)
        if (key !in sounds) return false
        update { it.remove(key) }
        true
    }

    /** `soundAlias`: [alias], as written, becomes another name of [original]. False when [original] is unknown. */
    fun alias(original: String, alias: String): Boolean = synchronized(lock) {
        val entry = lookup(original) ?: return false
        update { it[alias] = entry }
        true
    }

    /**
     * `aliasBankMap` (superdough.mjs:88-119). For every sound registered now, whose key `<bank>_<sound>` has a second part, and whose
     * bank [map] gives an alias or a list of aliases (keys of [map] are also read in lower case), the same entry is registered as
     * `<alias>_<sound>` in lower case. Only the first two `_`-parts of a key count. Sounds registered after this call are not aliased.
     */
    fun aliasBank(map: Map<String, Any?>) {
        val lookupMap = LinkedHashMap(map)
        for ((key, value) in map) lookupMap[key.lowercase()] = value
        synchronized(lock) {
            val current = sounds
            if (current.isEmpty()) return
            update { out ->
                for ((key, entry) in current) {
                    val parts = key.split("_")
                    val suffix = parts.getOrNull(1)
                    if (suffix.isNullOrEmpty()) continue
                    val target = lookupMap[parts[0]]
                    if (!jsTruthy(target)) continue
                    when (target) {
                        is String -> out["${target}_$suffix".lowercase()] = entry
                        is List<*> -> for (alias in target) out["${jsToString(alias)}_$suffix".lowercase()] = entry
                    }
                }
            }
        }
    }

    /** The registered sounds, each once (an entry that has aliases is listed under its first key), in the order of first registration. */
    fun entries(): List<SoundEntry> {
        val seen = Collections.newSetFromMap(IdentityHashMap<SoundEntry, Boolean>())
        return sounds.values.filter { seen.add(it) }
    }

    /** Publishes a copy of the registry changed by [change]. */
    private inline fun update(change: (MutableMap<String, SoundEntry>) -> Unit) {
        synchronized(lock) {
            val copy = LinkedHashMap(sounds)
            change(copy)
            sounds = copy
            revision++
        }
    }
}
