package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/** The finite model-three quartet as native notes and the six saved factory rooms. */
object ChamberKits {
    const val NAME = "SnipSnap Chamber Kit"
    const val MODEL = ChamberPresets.MODEL

    private val pentatonic = listOf(0, 3, 5, 7, 10, 12, 15, 17)

    data class Entry(val patch: ChamberPatch, val preset: String, val role: String)

    /** A01–A08: C3–F4. A09–A14: six factory recipes. A15–A16: room contrasts. */
    fun entries(): List<Entry> {
        val factory = ChamberPresets.all()
        val anchor = factory.first { it.name == "Four Answers" }
        val canopy = factory.first { it.name == "Aquarium Ceiling" }
        val notes = pentatonic.map { semitone ->
            val midi = 48 + semitone
            Entry(anchor.copy(name = "${anchor.name} ${Scales.nameOf(midi)}", midi = midi), anchor.name, "native note")
        }
        return notes + factory.map { Entry(it, it.name, "factory preset") } + listOf(
            Entry(anchor.copy(name = "Uniform Room", macros = anchor.macros + ("DIVIDE" to 0f)),
                anchor.name, "uniform boundary contrast"),
            Entry(canopy.copy(name = "Low Canopy", macros = canopy.macros + ("PLACEMENT" to .15f)),
                canopy.name, "near placement contrast"),
        )
    }

    fun patches(): List<ChamberPatch> = entries().map { it.patch }

    /** Each note renders its own coupled room. Pads add no transposition, effects or choking. */
    fun build(): List<ArrangedPad?> = entries().map { entry ->
        val patch = entry.patch
        require(patch.model == MODEL && patch.macros.getValue("HOLD") == 0f)
        val recipe = PadRecipe(patch)
        ArrangedPad(
            snip = recipe.render(),
            drumClass = Chamber.drumClassFor(patch.voice, patch.macros),
            recipe = recipe.toJsonValue(),
            oneShot = true,
            displayName = patch.name,
            source = linkedMapOf(
                "engine" to ChamberPatch.ENGINE,
                "preset" to entry.preset,
                "role" to entry.role,
                "pitchMidi" to patch.midi.toString(),
                "pitchNote" to Scales.nameOf(patch.midi),
                "model" to patch.model.toString(),
                "seed" to patch.seed.toString(),
                "excitationVelocity" to patch.velocity.toString(),
            ),
        )
    }
}
