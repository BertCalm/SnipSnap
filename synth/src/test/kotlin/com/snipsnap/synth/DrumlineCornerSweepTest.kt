package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.*

class DrumlineCornerSweepTest {
    @Test fun `audit enumerates every control corner at both touch endpoints and a center`() {
        val cases=DrumlineCornerSweep.cases()
        assertEquals(1029,cases.size)
        for(v in DrumlineVoice.entries) {
            val selected=cases.filter { it.voice==v }
            val corners=1 shl Drumline.macrosFor(v).size
            assertEquals(2*corners+1,selected.size,v.name)
            for(bits in 0 until corners)assertEquals(setOf(.05f,1f),selected.filter { it.corner==bits }.map { it.velocity }.toSet())
            assertEquals(1,selected.count { it.corner==-1 })
        }
    }
    @Test fun `audit refuses nonfinite clipped silent biased and unfinished signals`() {
        fun check(x: FloatArray,issue: String) = assertTrue(issue in DrumlineCornerSweep.violations(Snip(x,1,44100)),issue)
        check(floatArrayOf(Float.NaN,0f),"nonfinite")
        check(floatArrayOf(1.1f,-1.1f,0f),"peak")
        check(FloatArray(20),"silent")
        check(floatArrayOf(.1f,.1f,0f),"dc")
        check(floatArrayOf(.2f,-.2f),"tail")
        assertTrue(DrumlineCornerSweep.violations(Snip(floatArrayOf(.2f,-.2f,0f),1,44100)).isEmpty())
    }
}
