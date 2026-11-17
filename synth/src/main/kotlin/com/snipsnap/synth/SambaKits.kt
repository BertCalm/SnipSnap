package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** Provisional native kit, available to verification tooling before factory promotion. */
object SambaKits {
    const val BPM = 140f
    fun patches() = ParadeSamba.Voice.entries.map { voice ->
        SambaPatch(voice.name.lowercase().replace('_',' '),voice,ParadeSamba.defaults())
    }
    fun build() = patches().map { patch ->
        val grid=listOf(.3f,.6f,1f).map { v -> List(3) { take -> patch.render(v,take) } }
        ArrangedPad(grid.last().first(),DrumClass.PERC,PadRecipe(patch).toJsonValue(),displayName=patch.name,
            level=.8f,oneShot=true,muteGroup=0,velocityTakes=grid,
            source=mapOf("engine" to SambaPatch.ENGINE,"model" to patch.model.toString(),"kit" to "samba-bateria-native-candidate","takes" to "3","velocities" to ".3,.6,1"))
    }
}
