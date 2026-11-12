package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class SporeMembraneTest {
    @Test fun `free motion decays and wire contacts cannot add mechanical energy`() {
        val corners=listOf(0f,.5f,1f).map { SporeMembrane.Controls(it,it,it,it,it) }+
            listOf(SporeMembrane.Controls(1f,0f,1f,1f,1f),SporeMembrane.Controls(0f,0f,1f,1f,1f))
        for(voice in SporeMembrane.Voice.entries)for(c in corners) {
            val p=SporeMembrane.probe(voice,c)
            assertTrue(p.sound.samples.all { it.isFinite() })
            assertTrue(p.sound.peak() in .00001f..1f)
            assertTrue(abs(p.sound.samples.average())<.005)
            assertEquals(0f,abs(p.sound.samples.last()))
            assertTrue(p.trace.maximumContactEnergyAdded<1e-10)
            assertTrue(p.trace.maximumFreeEnergy <= p.trace.attackEnergy*1.03,"free energy increased: ${p.trace}")
            assertTrue(p.trace.finalEnergy < p.trace.attackEnergy*1e-5)
        }
    }
    @Test fun `coupling transfers energy to an unstruck head and contacts change snare motion`() {
        val on=SporeMembrane.probe(SporeMembrane.Voice.SNARE)
        val isolated=SporeMembrane.probe(SporeMembrane.Voice.SNARE,coupling=false)
        val wiresOff=SporeMembrane.probe(SporeMembrane.Voice.SNARE,wireContact=false)
        assertEquals(0.0,isolated.trace.bottomPeak)
        assertEquals(0,isolated.trace.contacts)
        assertTrue(on.trace.bottomPeak>.001)
        assertTrue(on.trace.contacts>100,"wire contacts must actually occur")
        assertEquals(0,wiresOff.trace.contacts)
        assertFalse(on.sound.samples.contentEquals(wiresOff.sound.samples))
    }
    @Test fun `takes are deterministic physical restrikes and velocity changes shape`() {
        for(voice in SporeMembrane.Voice.entries) {
            val hard=SporeMembrane.render(voice)
            assertContentEquals(hard.samples,SporeMembrane.render(voice).samples)
            for(t in 1..2)assertFalse(hard.samples.contentEquals(SporeMembrane.render(voice,take=t).samples))
            val soft=SporeMembrane.render(voice,velocity=.3f)
            assertTrue(soft.peak()<hard.peak())
            val difference=hard.samples.indices.sumOf { i -> (hard.samples[i]/hard.peak()-soft.samples[i]/soft.peak()).toDouble().pow(2) }
            assertTrue(difference>1.0,"velocity must change contact and modal balance, not just gain")
            assertTrue(SporeMembrane.render(voice,velocity=0f).samples.all { it==0f })
        }
    }
    @Test fun `each applicable control changes the waveform`() {
        val base=SporeMembrane.Controls()
        for(voice in SporeMembrane.Voice.entries) {
            val hard=SporeMembrane.render(voice)
            val changes=listOf(base.copy(tune=.8f),base.copy(damp=.8f),base.copy(strike=.8f),base.copy(body=.8f))+
                if(voice==SporeMembrane.Voice.SNARE)listOf(base.copy(wires=.9f))else emptyList()
            for(c in changes)assertFalse(hard.samples.contentEquals(SporeMembrane.render(voice,c).samples))
        }
    }
}
