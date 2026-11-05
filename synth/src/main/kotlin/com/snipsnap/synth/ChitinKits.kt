package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/** Eight C-minor-pentatonic bass notes, then eight contrasting candidate colors.
 * No pad loops: long duration classes retain ordinary one-shot playback. */
object ChitinKits {
    fun patches(): List<ChitinPatch> {
        val anchor=ChitinPresets.forVoice(ChitinVoice.SHELL).first()
        val walk=listOf(0,3,5,7,10,12,15,17).map { semitone ->
            anchor.copy(name="SHELL ${Scales.nameOf(24+semitone)}",macros=anchor.macros+("TUNE" to semitone/24f))
        }
        val colors=ChitinPresets.forVoice(ChitinVoice.WING)+ChitinPresets.forVoice(ChitinVoice.SHELL)
            .filter { it.name in setOf("HOLLOW STEP","PHASE RUN") }
        return walk+colors.sortedBy { Chitin.drumClassFor(it.voice,it.macros)==DrumClass.LOOP }
    }
    fun build(): List<ArrangedPad?> = patches().map { patch ->
        val recipe=PadRecipe(patch)
        ArrangedPad(recipe.render(),Chitin.drumClassFor(patch.voice,patch.macros),recipe.toJsonValue(),
            oneShot=true,displayName=patch.name,source=mapOf(
                "engine" to ChitinPatch.ENGINE,"rootMidi" to Chitin.midiFor(patch.voice,patch.macros.getValue("TUNE")).toString(),
                "candidatePreset" to patch.name,"sustain" to "finite"))
    }
}
