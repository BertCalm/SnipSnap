package com.snipsnap.app.ui

import com.snipsnap.synth.MacroSpec
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacreVoice

/**
 * Controls belong to the saved harp model; CONTACT is never inferred from a navigation key. HOLD shows only on
 * GROTTO, the one voice with a held loop; the finite voices keep it hidden at 0.
 */
internal object NacreEditing {
    fun showsHold(voice: NacreVoice): Boolean = voice == NacreVoice.GROTTO

    fun macrosFor(patch: NacrePatch): List<MacroSpec> =
        Nacre.macrosFor(patch.voice, patch.model).filterNot { it.name == "HOLD" && !showsHold(patch.voice) }

    fun controls(patch: NacrePatch): Map<String, Float> =
        Nacre.defaults(patch.voice, patch.model) + patch.macros

    /**
     * SCRAMBLE varies the existing gesture/return bus without dropping its saved contact source. GROTTO keeps its HOLD:
     * a held loop stays held and a finite bow keeps its length.
     */
    fun scrambledControls(patch: NacrePatch, generated: Map<String, Float>): Map<String, Float> =
        controls(patch) + generated.filterKeys { it != "CONTACT" && !(it == "HOLD" && showsHold(patch.voice)) }
}
