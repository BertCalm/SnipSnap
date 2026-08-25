package com.snipsnap.app.store

import com.snipsnap.kit.Names
import com.snipsnap.shell.Copy

/** What the FRESH TAPE dialog does with what you typed. */
sealed interface NameVerdict {
    /** [quip] is the easter-egg line, if the name earned one. */
    data class Ok(val name: String, val quip: String?) : NameVerdict
    data class Rejected(val reason: String) : NameVerdict
}

/**
 * Kit-name validation as a pure function, so the dialog stays dumb.
 *
 * A kit's name becomes a folder on a FAT card an MPC has to browse, so
 * [Names.isMpcSafe] is the real gate — rejecting early beats writing a
 * folder that fails Preflight later. Duplicates are refused
 * case-insensitively because the card's filesystem does not distinguish
 * them.
 */
fun verifyKitName(proposed: String, existing: List<String>): NameVerdict {
    val name = proposed.trim()
    if (name.isBlank()) return NameVerdict.Rejected("NAME IT SOMETHING.")
    if (!Names.isMpcSafe(name)) return NameVerdict.Rejected("THE MPC CAN'T READ THAT NAME.")
    if (existing.any { it.equals(name, ignoreCase = true) }) {
        return NameVerdict.Rejected("YOU ALREADY HAVE THAT TAPE.")
    }
    return NameVerdict.Ok(name, Copy.kitNameResponse(name))
}
