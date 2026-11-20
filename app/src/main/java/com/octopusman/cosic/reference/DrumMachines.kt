package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankSummary

/** The drum abbreviations in the order a kit lists its sounds: the ones a groove is made of first. */
private val DRUM_ORDER = listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd", "cb", "cl", "sh", "tb", "perc")

/** Orders drum names: the ones of [DRUM_ORDER] first, in that order, then the others alphabetically. */
internal val drumOrder = compareBy<String>({ DRUM_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: DRUM_ORDER.size }, { it })

/** The name of the bank that holds [sound] of the kit [kit]: what `s("sound").bank("kit")` plays. */
internal fun kitBankName(kit: String, sound: String): String = "${kit}_$sound"

/** The sound a bank of the kit [kit] is called inside that kit: its name without the `<kit>_`. */
internal fun kitSoundName(kit: String, bankName: String): String = bankName.removePrefix("${kit}_")

/**
 * A drum machine or kit: `bank(name)` makes `s("bd")` play the sound `name_bd`. [sounds] are the
 * abbreviations it has, in display order.
 */
internal data class Kit(val name: String, val sounds: List<String>) {
    /** What a tap on the kit plays: its kick, or else its first sound. */
    fun previewSound(): String = kitBankName(name, if ("bd" in sounds) "bd" else sounds.first())

    /**
     * A line that plays the kit, made of the sounds it has: a kick and snare groove with a hat line,
     * resting where a kick or a snare is missing, or else its first four sounds one after another.
     */
    fun snippet(): String {
        val groove = listOfNotNull(
            when {
                "bd" in sounds && "sd" in sounds -> "bd sd [~ bd] sd"
                "bd" in sounds -> "bd ~ [~ bd] ~"
                "sd" in sounds -> "~ sd ~ sd"
                else -> null
            },
            "hh*8".takeIf { "hh" in sounds },
        ).joinToString(", ").ifEmpty { sounds.take(4).joinToString(" ") }
        return "\$: s(\"$groove\").bank(\"$name\")"
    }
}

/**
 * The kits `bank()` can pick: the [machines] the engine synthesizes (name to sounds), then the kits made of the sample [banks] that are
 * sounds of a drum machine (see [BankSummary.kit]), by name. Banks of a kit that is also a machine add their sounds to it, since the
 * sounds the banks lack still come from the machine.
 */
internal fun kits(machines: Map<String, List<String>>, banks: List<BankSummary>): List<Kit> {
    val members = banks.mapNotNull { bank -> bank.kit?.let { it to bank } }.groupBy({ it.first }, { it.second })
    return (machines.keys + members.keys.sortedBy { it.lowercase() }).map { name ->
        val own = members[name].orEmpty()
        val sounds = machines[name].orEmpty() + own.map { kitSoundName(name, it.name) }.sortedWith(drumOrder)
        Kit(name, sounds.distinct())
    }.filter { it.sounds.isNotEmpty() }
}
