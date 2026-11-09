package com.snipsnap.synth

import kotlin.test.*

class BackwashAcousticTest {
    @Test fun `recorded takes and source dynamics survive recipe replay`() {
        for(v in BackwashVoice.entries.filter { !it.legacy }) {
            val p=BackwashPatch("Acoustic",v,Backwash.defaults(v)+mapOf("TRAIL" to 0f,"PRESS" to 0f,"DRIVE" to 0f))
            assertEquals(5,p.model)
            val ids=mutableSetOf<String>()
            for(z in 0 until BackwashAcoustic.layerCount(v)) for(t in 0 until BackwashAcoustic.takeCount(v)) {
                val velocity=(z+.5f)/BackwashAcoustic.layerCount(v)
                val patch=p.copy(take=t)
                val out=patch.render(velocity)
                assertTrue(out.samples.all { it.isFinite() && kotlin.math.abs(it)<=.95001f })
                assertContentEquals(out.samples,BackwashPatch.fromJsonText(patch.toJsonText()).render(velocity).samples)
                assertTrue(ids.add(BackwashAcoustic.sourceId(v,velocity,t)))
            }
            assertContentEquals(p.render().samples,p.copy(take=BackwashAcoustic.takeCount(v)).render().samples)
        }
    }
    @Test fun `old model four remains unchanged and new ride keeps its tail`() {
        for(v in BackwashVoice.entries) {
            val m=BackwashV4.defaults(v)
            assertContentEquals(BackwashV4.render(v,m).samples,BackwashPatch("Old",v,m,4).render().samples)
        }
        val p=BackwashPatch("Bell",BackwashVoice.RIDE,Backwash.defaults(BackwashVoice.RIDE))
        val s=p.render()
        assertTrue(s.frameCount>44100*4)
        assertTrue(s.samples.drop(44100).sumOf { it.toDouble()*it }>1e-4)
    }
    @Test fun `authored rolls and risers are distinct and persist`() {
        val p=BackwashPatch("Roll",BackwashVoice.TAMBOURINE,Backwash.defaults(BackwashVoice.TAMBOURINE),articulation="ROLL",take=1)
        assertContentEquals(p.render().samples,BackwashPatch.fromJsonText(p.toJsonText()).render().samples)
        assertFalse(p.render().samples.contentEquals(p.copy(articulation="RISER").render().samples))
    }
}
