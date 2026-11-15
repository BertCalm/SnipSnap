package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/** Three full-dynamics dance kits. Every role, including roll/riser, carries a complete 3 x 4 grid. */
object FootworkKits {
    const val VERSION = 3
    const val TAKES = 4
    val velocities = listOf(.30f,.65f,1f)
    enum class Kit(val id: String,val title: String,val bpm: Int) {
        TAP("footwork-tap-jazz","Tap Jazz",124),
        STREET("footwork-street-breaks","Street Breaks",110),
        INDUSTRIAL("footwork-industrial-stepping","Industrial Stepping",132)
    }
    val names = listOf("Stamp","Heel","Toe","Ball Step","Forward Brush","Back Brush","Shuffle","Flap",
        "Hand Clap","Thigh Slap","Chest Pat","Finger Click","Bucket","Broom","Loose Metal","Board","Foot Roll","Gathering Riser")
    fun build(kit: Kit): List<ArrangedPad> = FootworkVoice.entries.mapIndexed { index,voice ->
        // PadHit applies linear touch gain. Compensate each authored velocity anchor once,
        // so playing at that velocity reproduces the selected full-dynamics source.
        // Between anchors the ordinary continuous pad gain still applies.
        val grid=velocities.map { v -> List(TAKES) { take ->
            val raw=Footwork.render(voice,v,take,kit)
            Snip(FloatArray(raw.samples.size) { raw.samples[it]/v },1,Dsp.RATE)
        } }
        val cls=when(voice) {
            FootworkVoice.STAMP -> DrumClass.KICK
            FootworkVoice.CLAP -> DrumClass.CLAP
            FootworkVoice.ROLL,FootworkVoice.RISER -> DrumClass.LOOP
            else -> DrumClass.PERC
        }
        ArrangedPad(grid.last().first(),cls,displayName=names[index],velocityTakes=grid,level=1f,source=mapOf(
            "engine" to "FOOTWORK","model" to Footwork.VERSION.toString(),"kitVersion" to VERSION.toString(),
            "kit" to kit.name,"voice" to voice.name,"role" to index.toString(),"bpm" to kit.bpm.toString(),
            "velocities" to velocities.joinToString(","),"takes" to TAKES.toString(),"dynamics" to "full-fixed-gain", "velocityGain" to "source-divided-by-anchor; playback-linear",
            "articulation" to if(voice==FootworkVoice.ROLL) "authored-roll" else if(voice==FootworkVoice.RISER) "authored-riser" else "finite"
        ))
    }
}
