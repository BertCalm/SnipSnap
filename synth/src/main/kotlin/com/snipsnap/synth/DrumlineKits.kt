package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** Two authored batteries. Grids use the same transport as other velocity/RR kits. */
object DrumlineKits {
    const val VERSION = 1
    val velocities = listOf(.3f,.6f,1f)
    const val TAKES = 3
    enum class Kit(val id: String, val title: String, val bpm: Float) {
        FIELD("drumline-field","DRUMLINE FIELD CADENCE",120f),
        STREET("drumline-street","DRUMLINE STREET CADENCE",100f)
    }
    fun patches(kit: Kit): List<DrumlinePatch> {
        val voices=listOf(DrumlineVoice.SNARE,DrumlineVoice.RIMSHOT,DrumlineVoice.FLAM,
            if(kit==Kit.FIELD)DrumlineVoice.ROLL else DrumlineVoice.RISER,
            DrumlineVoice.TENOR_1,if(kit==Kit.FIELD)DrumlineVoice.TENOR_2 else DrumlineVoice.TENOR_3,
            if(kit==Kit.FIELD)DrumlineVoice.TENOR_4 else DrumlineVoice.TENOR_5,DrumlineVoice.TENOR_6,
            DrumlineVoice.BASS_1,DrumlineVoice.BASS_2,DrumlineVoice.BASS_3,DrumlineVoice.BASS_4,DrumlineVoice.BASS_5,
            if(kit==Kit.FIELD)DrumlineVoice.BASS_UNISON else DrumlineVoice.BASS_SPLIT,
            DrumlineVoice.CRASH,if(kit==Kit.FIELD)DrumlineVoice.CHOKE else DrumlineVoice.TOUCH)
        return voices.map { v ->
            val m=Drumline.defaults(v).toMutableMap()
            if(kit==Kit.STREET) {
                m["TUNE"]=.32f;m["DAMP"]=.28f
                if("TIGHT" in m)m["TIGHT"]=.55f
                if("PLAYERS" in m)m["PLAYERS"]=if(v in setOf(DrumlineVoice.CRASH,DrumlineVoice.TOUCH))1f/3 else 3f/7
                if(v.name.startsWith("BASS"))m["STRIKE"]=.35f
            }
            DrumlinePatch(v.name.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() },v,m)
        }
    }
    fun build(kit: Kit): List<ArrangedPad> = patches(kit).map { patch ->
        // Bounded sequential generation keeps peak memory independent of processor count.
        val grid=velocities.map { v -> List(TAKES) { take -> patch.render(v,take) } }
        ArrangedPad(grid.last().first(),Drumline.drumClassFor(patch.voice),PadRecipe(patch).toJsonValue(),
            displayName=patch.name,level=if(patch.voice in setOf(DrumlineVoice.CRASH,DrumlineVoice.CHOKE,DrumlineVoice.TOUCH)).55f else .8f,
            oneShot=true,muteGroup=0,velocityTakes=grid,
            source=mapOf("engine" to "DRUMLINE","model" to patch.model.toString(),"kitVersion" to VERSION.toString(),
                "kit" to kit.id,"velocities" to velocities.joinToString(","),"takes" to TAKES.toString()))
    }
}
