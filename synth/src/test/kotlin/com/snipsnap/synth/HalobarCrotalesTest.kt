package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

class HalobarCrotalesTest {
    private val voice=HalobarVoice.CROTALES
    private fun amplitude(samples: FloatArray,hz: Double,startSeconds: Double=.025): Double {
        val start=(startSeconds*Halobar.RATE).toInt()
        val end=min(samples.size,start+(.2*Halobar.RATE).toInt())
        var re=0.0;var im=0.0
        for(i in start until end) {
            val phase=2*PI*hz*i/Halobar.RATE
            re+=samples[i]*cos(phase);im+=samples[i]*sin(phase)
        }
        return 2*hypot(re,im)/(end-start)
    }

    @Test fun `disc modes and longer root decay distinguish crotales from steel bars at the same note`() {
        fun controls(v: HalobarVoice)=mapOf("TUNE" to Halobar.tuneForMidi(v,84),"SPACE" to 0f,"TOUCH" to .8f)
        val disc=Halobar.renderProbe(voice,controls(voice))
        val bar=Halobar.renderProbe(HalobarVoice.GLOCKENSPIEL,controls(HalobarVoice.GLOCKENSPIEL))
        val root=amplitude(disc.samples,disc.plan.hz)
        for(ratio in listOf(2.01,3.94)) {
            val own=amplitude(disc.samples,disc.plan.hz*ratio)
            assertTrue(own>root*.025,"lost disc mode $ratio: $own / $root")
            assertTrue(amplitude(bar.samples,disc.plan.hz*ratio)<own*.1,"new mode overlaps the glockenspiel source")
        }
        val discRetention=amplitude(disc.samples,disc.plan.hz,1.5)/root
        val barRetention=amplitude(bar.samples,bar.plan.hz,1.5)/amplitude(bar.samples,bar.plan.hz)
        assertTrue(discRetention>2*barRetention,"disc ring did not outlast the bar: $discRetention / $barRetention")
        assertTrue(discRetention<1,"unforced root gained energy after its attack")
        assertTrue(disc.plan.modeT60.first()>2*bar.plan.modeT60.first())
    }

    @Test fun `TOUCH still shortens contact above midpoint throughout the highest octave`() {
        for(note in listOf(84,96,108)) {
            fun contact(touch: Float)=Halobar.plan(voice,mapOf("TUNE" to Halobar.tuneForMidi(voice,note),"TOUCH" to touch)).contactSeconds
            assertTrue(contact(0f)>contact(.5f),"MIDI $note soft contact is pinned")
            assertTrue(contact(.5f)>contact(1f)*1.1,"MIDI $note upper TOUCH range is pinned")
        }
    }

    @Test fun `every crotales note saves exactly and the full register retains its root`() {
        assertEquals(84,Halobar.midiFor(voice,0f))
        assertEquals(96,Halobar.midiFor(voice,.5f))
        assertEquals(108,Halobar.midiFor(voice,1f))
        for(note in 84..108) {
            val patch=HalobarPatch("Saved crotales",voice,mapOf("TUNE" to Halobar.tuneForMidi(voice,note)))
            val saved=assertIs<HalobarPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(note,Halobar.midiFor(saved.voice,saved.macros.getValue("TUNE")))
            assertEquals(1,saved.model)
        }
        for(note in listOf(83,109))assertFailsWith<IllegalArgumentException> { Halobar.tuneForMidi(voice,note) }
        for(note in listOf(84,96,108))for(space in listOf(0f,.35f)) {
            val p=Halobar.renderProbe(voice,mapOf("TUNE" to Halobar.tuneForMidi(voice,note),"SPACE" to space),normalize=true)
            var factor=1;while(p.plan.hz/factor>1100)factor*=2
            val detected=assertNotNull(Pitch.detect(Snip(p.samples,1,Halobar.RATE/factor),
                fromSec=.05f*factor,windowSec=.25f*factor))
            assertTrue(abs(1200*log2(detected.hz*factor/p.plan.hz))<5,"MIDI $note lost its root")
            assertTrue(detected.confidence>=.3,"MIDI $note root confidence ${detected.confidence}")
        }
    }

    @Test fun `disc contact and finite ring interact with the shared return`() {
        for((a,b) in listOf("TOUCH" to "SPACE","HOLD" to "RETURN")) {
            val corners=listOf(0f to 0f,0f to 1f,1f to 0f,1f to 1f).map { (x,y) ->
                Halobar.renderProbe(voice,mapOf(a to x,b to y)).samples
            }
            val n=corners.maxOf { it.size };var energy=0.0
            for(i in 0 until n) {
                val d=corners[3].getOrElse(i){0f}-corners[2].getOrElse(i){0f}-
                    corners[1].getOrElse(i){0f}+corners[0].getOrElse(i){0f}
                energy+=d.toDouble()*d
            }
            assertTrue(sqrt(energy/n)>.0001,"crotales $a x $b lost its interaction")
        }
    }

    @Test fun `separate sixteen pad crotales study preserves editable paired source recipes`() {
        val pads=SynthKits.halobarCrotalesStudy()
        assertEquals(16,pads.size)
        val notes=mutableListOf<Int>()
        for((index,pad) in pads.withIndex()) {
            val saved=assertNotNull(pad)
            assertEquals(true,saved.oneShot)
            val recipe=PadRecipe.fromJsonValue(assertNotNull(saved.recipe))
            val patch=assertIs<HalobarPatch>(recipe.patch)
            assertEquals(voice,patch.voice)
            assertNull(recipe.fx)
            assertEquals(if(index<8)0f else .6f,patch.macros.getValue("SPACE"))
            notes+=Halobar.midiFor(voice,patch.macros.getValue("TUNE"))
            assertContentEquals(saved.snip.samples,recipe.render().samples)
        }
        val walk=listOf(84,87,89,91,94,96,99,101)
        assertEquals(walk+walk,notes)
        assertEquals(16,pads.map { assertNotNull(it).displayName }.distinct().size)
    }
}
