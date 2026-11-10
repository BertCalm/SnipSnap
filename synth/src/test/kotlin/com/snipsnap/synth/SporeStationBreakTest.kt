package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.xpm.DrumProgram
import kotlin.math.roundToInt
import kotlin.test.*

class SporeStationBreakTest {
    @Test fun `audition selects exact velocity boundaries and cycles each zone independently`() {
        val grid = List(3) { zone -> List(3) { take -> Snip(floatArrayOf((zone*3+take+1)/10f),1,Dsp.RATE) } }
        val pad = ArrangedPad(grid.last().first(),DrumClass.SNARE,level=1f,velocityTakes=grid)
        val velocities = listOf(41,42,84,85,127,41,41,41,42,42)
        val expected = listOf(.1f,.4f,.5f,.7f,.8f,.2f,.3f,.1f,.6f,.4f)
        val phrase = velocities.mapIndexed { i,v -> SporeStationAuditionGenerator.Event(i*.5,0,v/127f) }
        val rendered = SporeStationAuditionGenerator.groove(listOf(pad),phrase=phrase)
        val gain = DrumProgram("test",emptyList()).level
        phrase.forEachIndexed { i,e ->
            val frame = (e.beat*60/SporeStation.BPM*Dsp.RATE).roundToInt()
            assertEquals(expected[i]*e.velocity*gain,rendered.samples[frame*2],.000001f)
        }
        assertContentEquals(rendered.samples,SporeStationAuditionGenerator.groove(listOf(pad),phrase=phrase).samples)
    }

    @Test fun `break phrases have quiet grace strokes and bounded two bar turnarounds`() {
        val phrases = (0..2).map { SporeStationAuditionGenerator.breakEvents(it) }
        assertEquals(3,phrases.distinct().size)
        for(events in phrases) {
            assertTrue(events.all { it.beat in 0.0..<8.0 && it.pad in 0..15 && it.velocity in 0f..1f })
            val snares=events.filter { it.pad in listOf(2,4) }
            assertTrue(snares.any { it.velocity<.33f } && snares.any { it.velocity>.9f })
            val gaps=snares.zipWithNext().map { (a,b) -> b.beat-a.beat }
            assertTrue(gaps.any { it in .04.. .07 }, "flam has a distinct grace onset")
            assertTrue(gaps.any { it in .08.. .11 }, "drag has two distinct grace onsets")
            assertTrue(events.any { it.pad==7 } && events.any { it.pad==6 && it.beat>6.8 })
        }
    }
}
