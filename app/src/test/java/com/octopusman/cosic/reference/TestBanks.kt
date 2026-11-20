package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankOrigin
import com.octopusman.cosic.audio.BankSummary

/** A sample bank as the sample repository lists it; [kit] makes it a sound of that drum machine. */
internal fun testBank(
    name: String,
    count: Int = 3,
    pitched: Boolean = false,
    origin: BankOrigin = BankOrigin.BUNDLED,
    kit: String? = null,
) = BankSummary(name, count, pitched, origin, treeUri = null, kit = kit)
