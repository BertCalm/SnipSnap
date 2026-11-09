package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

class HalobarGlockenspielTest {
    @Test fun `the separate study kit preserves all sixteen native source recipes and paired notes`() {
        val pads=SynthKits.halobarGlockenspielStudy()
        assertEquals(16,pads.size)
        val notes=mutableListOf<Int>()
        for((index,pad) in pads.withIndex()) {
            val saved=assertNotNull(pad)
            assertEquals(true,saved.oneShot)
            val recipe=PadRecipe.fromJsonValue(assertNotNull(saved.recipe))
            val patch=assertIs<HalobarPatch>(recipe.patch)
            assertEquals(HalobarVoice.GLOCKENSPIEL,patch.voice)
            assertNull(recipe.fx)
            assertEquals(if(index<8)0f else .6f,patch.macros.getValue("SPACE"))
            notes+=Halobar.midiFor(patch.voice,patch.macros.getValue("TUNE"))
            assertContentEquals(saved.snip.samples,recipe.render().samples)
        }
        val walk=listOf(72,75,77,79,82,84,87,89)
        assertEquals(walk+walk,notes)
        assertEquals(16,pads.map { assertNotNull(it).displayName }.distinct().size)
    }

    private fun amplitude(samples: FloatArray,hz: Double): Double {
        val start=(.025*Halobar.RATE).toInt()
        val end=min(samples.size,(.20*Halobar.RATE).toInt())
        var re=0.0;var im=0.0
        for(i in start until end) {
            val phase=2*PI*hz*i/Halobar.RATE
            re+=samples[i]*cos(phase);im+=samples[i]*sin(phase)
        }
        return 2*hypot(re,im)/(end-start)
    }

    @Test fun `glockenspiel rings inharmonic steel modes instead of the tuned vibraphone spectrum`() {
        val v=HalobarVoice.GLOCKENSPIEL
        val controls=mapOf("TUNE" to Halobar.tuneForMidi(v,72),"SPACE" to 0f,"TOUCH" to .8f)
        val glock=Halobar.renderProbe(v,controls)
        val vib=Halobar.renderProbe(HalobarVoice.VIBRAPHONE,controls+
            ("TUNE" to Halobar.tuneForMidi(HalobarVoice.VIBRAPHONE,72)))
        val root=amplitude(glock.samples,glock.plan.hz)
        for(ratio in listOf(2.756,5.404)) {
            val own=amplitude(glock.samples,glock.plan.hz*ratio)
            assertTrue(own>root*.025,"glockenspiel lost its $ratio steel-bar mode: $own / $root")
            assertTrue(amplitude(vib.samples,glock.plan.hz*ratio)<own*.1,
                "the new voice must add a spectral family beyond vibraphone")
        }
    }

    @Test fun `the higher register has an exact midpoint and every saved note round trips`() {
        val v=HalobarVoice.GLOCKENSPIEL
        assertEquals(72,Halobar.midiFor(v,.5f))
        assertEquals(60,Halobar.midiFor(v,0f))
        assertEquals(96,Halobar.midiFor(v,1f))
        for(note in 60..96) {
            val p=HalobarPatch("Saved glockenspiel",v,mapOf("TUNE" to Halobar.tuneForMidi(v,note)))
            val saved=Patches.fromJsonText(p.toJsonText()) as HalobarPatch
            assertEquals(note,Halobar.midiFor(saved.voice,saved.macros.getValue("TUNE")))
            assertEquals(1,saved.model)
        }
        assertFailsWith<IllegalArgumentException> { Halobar.tuneForMidi(v,59) }
        assertFailsWith<IllegalArgumentException> { Halobar.tuneForMidi(v,97) }
        for(space in listOf(0f,.35f)) {
            val p=Halobar.renderProbe(v,mapOf("TUNE" to 1f,"SPACE" to space),normalize=true)
            val pitch=assertNotNull(Pitch.detect(Snip(p.samples,1,Halobar.RATE/2),fromSec=.1f,windowSec=.5f))
            assertTrue(abs(1200*log2(pitch.hz*2/p.plan.hz))<5,"high C7 lost its root")
            assertTrue(pitch.confidence>=.3)
        }
    }

    @Test fun `touch and finite hold interact with the glockenspiel return`() {
        val v=HalobarVoice.GLOCKENSPIEL
        for((a,b) in listOf("TOUCH" to "SPACE","HOLD" to "RETURN")) {
            val corners=listOf(0f to 0f,0f to 1f,1f to 0f,1f to 1f).map { (x,y) ->
                Halobar.renderProbe(v,mapOf(a to x,b to y)).samples
            }
            val n=corners.maxOf { it.size };var energy=0.0
            for(i in 0 until n) {
                val d=corners[3].getOrElse(i){0f}-corners[2].getOrElse(i){0f}-
                    corners[1].getOrElse(i){0f}+corners[0].getOrElse(i){0f}
                energy+=d.toDouble()*d
            }
            assertTrue(sqrt(energy/n)>.0001,"glockenspiel $a x $b lost its interaction")
        }
    }
}
