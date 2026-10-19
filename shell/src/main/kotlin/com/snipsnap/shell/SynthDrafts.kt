package com.snipsnap.shell

import com.snipsnap.json.JsonException
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches

/**
 * Loaded SYNTH recipes in MAKE's navigation bag. The separate macro entries
 * hold slider edits; these snapshots retain note, velocity, model and other
 * recipe fields even after editing clears the highlighted preset name.
 * No rendered audio or persistent preset file belongs in this bag.
 */
object SynthDrafts {
    private const val PREFIX = "recipe."

    private fun key(patch: Patch) = "$PREFIX${patch.engine}.${patch.voiceName}"

    /** Ignore stale, unsupported or misplaced entries like the lab's other settings. */
    fun read(settings: Map<String, String>): List<Patch> = settings.mapNotNull { (name, json) ->
        if (!name.startsWith(PREFIX)) return@mapNotNull null
        val patch = try {
            Patches.fromJsonText(json)
        } catch (_: JsonException) {
            return@mapNotNull null
        } catch (_: IllegalArgumentException) {
            return@mapNotNull null
        }
        patch.takeIf { name == key(it) }
    }

    /** Replace recipe snapshots without touching sliders, selection or preset names. */
    fun write(settings: MutableMap<String, String>, patches: Iterable<Patch>) {
        val recipes = patches.associate { key(it) to it.toJsonText() }
        settings.keys.removeAll { it.startsWith(PREFIX) }
        settings.putAll(recipes)
    }
}
