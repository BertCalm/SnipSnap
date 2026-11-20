package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/** Sixteen dry finite performances; LOOP filing is a duration class, never a wrap claim. */
object AccordKits {
    const val NAME = "SnipSnap Accord Kit"

    fun patches(): List<AccordPatch> {
        val brush = AccordPresets.default()
        val notes = listOf(0, 3, 5, 7, 10, 12, 15, 17).map { offset ->
            val midi = 48 + offset
            brush.copy(name = "BRUSH ${Scales.nameOf(midi)}", midi = midi)
        }
        val wood = AccordPresets.forVoice(AccordVoice.WOOD).single()
        val tines = AccordPresets.forVoice(AccordVoice.TINE)
        return notes + listOf(
            wood, tines.single { it.acousticProfile == AccordAcousticProfile.REFERENCE },
            tines.single { it.acousticProfile == AccordAcousticProfile.SOFT_ATTACK },
            tines.single { it.acousticProfile == AccordAcousticProfile.BRIGHT_EDGE },
            tines.single { it.acousticProfile == AccordAcousticProfile.LONG_RING },
            brush.copy(name = "SOFT BRUSH", velocity = .3f),
            brush.copy(name = "HARD BRUSH", velocity = 1f),
            wood.copy(name = "SOFT WOOD", velocity = .3f),
        )
    }

    /** The four voices kept in accord-voices-1, in pad order: A01-A04 REED ... A13-A16 BELL. */
    val NEW_VOICES = listOf(AccordVoice.REED, AccordVoice.COURSE, AccordVoice.ESCAPEMENT, AccordVoice.BELL)

    /** The notes each new voice plays in ACCORD VOICES: C2, C3, C4 and C5, the range it was heard over. */
    val VOICES_NOTES = listOf(36, 48, 60, 72)

    /**
     * ACCORD VOICES: each new voice's factory recipe at C2, C3, C4 and C5, normal touch (.75),
     * default controls and the reference seed. These are the renders the owner kept in
     * accord-voices-1 (the C2-C5 clips), pad for pad; the C3 pads are the factory presets.
     */
    fun voicesPatches(): List<AccordPatch> = NEW_VOICES.flatMap { voice ->
        val preset = AccordPresets.forVoice(voice).single()
        VOICES_NOTES.map { midi -> preset.copy(name = "${voice.name} ${Scales.nameOf(midi)}", midi = midi) }
    }

    fun arranged(render: (AccordPatch) -> Snip = Accord::render): List<ArrangedPad> = arrange(patches(), render)

    /** ACCORD VOICES' sixteen pads, built exactly as [arranged] builds the ACCORD starter's. */
    fun voicesArranged(render: (AccordPatch) -> Snip = Accord::render): List<ArrangedPad> = arrange(voicesPatches(), render)

    private fun arrange(patches: List<AccordPatch>, render: (AccordPatch) -> Snip): List<ArrangedPad> = patches.map { patch ->
        ArrangedPad(
            snip = render(patch), drumClass = Accord.drumClassFor(patch),
            recipe = PadRecipe(patch).toJsonValue(), oneShot = true, displayName = patch.name,
            source = mapOf("engine" to AccordPatch.ENGINE, "model" to patch.model.toString(),
                "rootMidi" to patch.midi.toString(), "velocity" to patch.velocity.toString(),
                "acousticProfile" to patch.acousticProfile.name, "seed" to patch.seed.toString(),
                "initializer" to patch.initializer.name, "sourceSeconds" to patch.sourceSeconds.toString(),
                "playback" to "FINITE_ONE_SHOT"),
            colorHex = when (patch.voice) {
                AccordVoice.BRUSH -> "#a5bdaf"
                AccordVoice.WOOD -> "#d1a77b"
                AccordVoice.TINE -> "#9cb9cb"
                AccordVoice.REED -> "#c4b98a"
                AccordVoice.COURSE -> "#b4adc4"
                AccordVoice.ESCAPEMENT -> "#c9a796"
                AccordVoice.BELL -> "#d4c592"
            },
        )
    }
}
