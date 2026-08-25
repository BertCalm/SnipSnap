package com.snipsnap.app.ui

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.Personality

/** Shown when a kit could not be created, at every personality level. */
const val CREATE_FAILED = "COULDN'T MAKE THAT TAPE."

/**
 * What to say after a FRESH TAPE attempt, or null to stay silent.
 *
 * Personality law 3 — jokes never gate function — is the whole content
 * of this function. "FRESH TAPE. SMELLS LIKE FERRIC OXIDE." is a joke,
 * so OFF silences it. "That didn't work" is function, so it speaks at
 * every level: without that asymmetry a failed create at OFF looks
 * exactly like a success, and the tape you just named is quietly
 * missing from the shelf.
 *
 * It lives here rather than in the dialog's click handler because a rule
 * belongs where a test can hold it still.
 */
fun newTapeToast(
    created: Boolean,
    name: String,
    personality: Personality,
): String? = when {
    !created -> CREATE_FAILED
    Delight.toastsEnabled(personality) -> Copy.kitNameResponse(name) ?: Copy.FRESH_TAPE
    else -> null
}
