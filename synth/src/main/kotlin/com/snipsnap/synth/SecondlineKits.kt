package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** Provisional authored kits from the native listening candidate, before factory promotion. */
object SecondlineKits {
    const val BPM = 104f
    const val MODEL = 4
    enum class Kit(val id: String,val title: String) {
        CLASSIC("second-line-classic","Second Line Classic"), TRIANGLE("second-line-triangle","Second Line Triangle")
    }
    fun patches(kit: Kit): List<SecondlinePatch> {
        val voices=ParadeSecondLine.Voice.entries.take(16).toMutableList()
        if(kit==Kit.TRIANGLE) { voices[13]=ParadeSecondLine.Voice.TRIANGLE;voices[14]=ParadeSecondLine.Voice.TRIANGLE_MUTED }
        return voices.map { SecondlinePatch(it.name.lowercase().replace('_',' '),it,ParadeSecondLine.defaults(),MODEL) }
    }
    fun build(kit: Kit) = patches(kit).map { patch ->
        val grid=listOf(.3f,.6f,1f).map { v -> List(3) { take->patch.render(v,take) } }
        val kind=when(patch.voice) {
            ParadeSecondLine.Voice.BASS_OPEN,ParadeSecondLine.Voice.BASS_MUTED -> DrumClass.KICK
            ParadeSecondLine.Voice.SNARE_CENTER,ParadeSecondLine.Voice.SNARE_GHOST,ParadeSecondLine.Voice.RIMSHOT -> DrumClass.SNARE
            else -> DrumClass.PERC
        }
        ArrangedPad(grid.last().first(),kind,PadRecipe(patch).toJsonValue(),displayName=patch.name,
            level=if(patch.voice.name.startsWith("CYMBAL")) .55f else .8f,oneShot=true,muteGroup=0,velocityTakes=grid,
            source=mapOf("engine" to SecondlinePatch.ENGINE,"model" to patch.model.toString(),"kit" to kit.id,"takes" to "3","velocities" to ".3,.6,1"))
    }
}
