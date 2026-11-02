package com.snipsnap.shell

import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch

/** The editor exposes the current controls while keeping an untouched original recipe exact. */
object CausticEditing {
    fun controls(patch: CausticPatch, stored: Map<String, Float> = emptyMap()): Map<String, Float> {
        val defaults = CausticInstrument.defaults(patch.voice, 2)
        val values = defaults.mapValues { (name, fallback) ->
            stored[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback
        } + patch.macros
        // A stale bag cannot request an expansion on behalf of an original recipe.
        return if (patch.model == 1) values + mapOf("HOLD" to 0f, "MOTION" to 0f) else values
    }

    fun edited(
        patch: CausticPatch,
        name: String = patch.name,
        macros: Map<String, Float> = patch.macros,
        requestExtension: Boolean = false,
        model: Int? = null,
    ): CausticPatch {
        val selected = model ?: if (patch.model == 1 && requestExtension) 2 else patch.model
        if (selected == 1) require((macros["MOTION"] ?: 0f) == 0f) {
            "CAUSTIC MOTION requires the expanded instrument"
        }
        return patch.copy(name = name, model = selected,
            macros = if (selected == 1) macros - "MOTION" else macros)
    }
}
