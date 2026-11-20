package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.BankSummary

/**
 * A sample bank as the sample repository lists it. A bank of the default library [tag]ged as a drum machine and called `<kit>_<sound>`
 * is a sound of that kit; the tag of the default drum names (`bd`, `sd`) is the same.
 */
internal fun testBank(
    name: String,
    count: Int = 3,
    pitched: Boolean = false,
    kind: BankKind = BankKind.DEFAULT,
    tag: String? = null,
    wavetable: Boolean = false,
) = BankSummary(name, count, pitched, kind, tag, prebake = kind == BankKind.DEFAULT, treeUri = null, wavetable = wavetable)
