package com.snipsnap.app.ui

import com.snipsnap.synth.MacroSpec
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch

/** Controls belong to the saved harp model; CONTACT is never inferred from a navigation key. */
internal object NacreEditing {
    fun macrosFor(patch: NacrePatch): List<MacroSpec> =
        Nacre.macrosFor(patch.voice, patch.model).filterNot { it.name == "HOLD" }

    fun controls(patch: NacrePatch): Map<String, Float> =
        Nacre.defaults(patch.voice, patch.model) + patch.macros

    /** SCRAMBLE varies the existing gesture/return bus without dropping its saved contact source. */
    fun scrambledControls(patch: NacrePatch, generated: Map<String, Float>): Map<String, Float> =
        controls(patch) + generated.filterKeys { it != "CONTACT" }
}
