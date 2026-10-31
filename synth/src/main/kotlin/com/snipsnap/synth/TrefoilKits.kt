package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Scales

/** Twelve colors on C/Eb/F/G, followed by four lower First Weave notes. */
object TrefoilKits {
    const val NAME="SnipSnap Trefoil Kit"
    val velocities=listOf(.3f,.6f,.8f)
    fun patches(): List<TrefoilPatch> {
        val notes=listOf(60,63,65,67)
        val colors=TrefoilPresets.all().mapIndexed { i,p -> p.copy(name="${p.name} ${Scales.nameOf(notes[i%4])}",midi=notes[i%4]) }
        val base=TrefoilPresets.entries.single { it.id=="weave" }.patch
        return colors+listOf(48,51,53,55).map { midi -> base.copy(name="Low Weave ${Scales.nameOf(midi)}",midi=midi) }
    }
    /** A renderer argument lets generators share exact renders with the audition bank. */
    fun build(render: (TrefoilPatch)->Snip = { it.render() }): List<ArrangedPad?> = patches().mapIndexed { i,p ->
        val main=render(p); val recipe=PadRecipe(p)
        ArrangedPad(main,Trefoil.drumClassFor(p.voice),recipe.toJsonValue(),oneShot=true,
            softVariants=velocities.take(2).map { Velocity.peakMatch(main,render(p.copy(velocity=it))) },
            source=linkedMapOf("engine" to TrefoilPatch.ENGINE,"model" to "2","midi" to p.midi.toString(),
                "seed" to p.seed.toString(),"presetId" to if(i<12) TrefoilPresets.entries[i].id else "weave",
                "velocitySoft" to ".3","velocityMid" to ".6","velocityMain" to ".8","velocityNormalization" to "peak-match-main"),
            displayName=p.name)
    }
}
