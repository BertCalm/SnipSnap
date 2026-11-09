package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.nio.file.Files
import kotlin.test.*

class ArrangedGridTest {
    private fun take(frames: Int, level: Float) = Snip(FloatArray(frames) { level }, 1, 44100)

    @Test fun `unequal take lengths and zone cycles retain exact boundaries`() {
        val dir=Files.createTempDirectory("arranged-grid").toFile()
        try {
            val grid=listOf(listOf(take(8,.1f),take(13,.2f)),listOf(take(17,.3f),take(21,.4f),take(5,.5f)))
            val input=ArrangedPad(grid.last().first(),DrumClass.PERC,velocityTakes=grid)
            val kit=KitAssembler.assembleArranged("Grid",listOf(input),dir)
            assertEquals(kit,KitStore.load(dir))
            val pad=kit.pads.single()
            assertEquals(listOf(0L,8L,21L,38L,59L),pad.chain!!.boundaries)
            assertEquals(listOf(ChainZone(0,63,0,2),ChainZone(64,127,2,3)),pad.chain!!.zones)
            assertTrue(pad.velocityLayers.isEmpty())
            assertEquals(64,WavReader.read(java.io.File(dir,pad.sampleFile)).frameCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `incomplete conflicting or mismatched grids are rejected before writing`() {
        val a=take(8,.1f); val b=take(8,.2f); val grid=listOf(listOf(a,b),listOf(b,a))
        assertFailsWith<IllegalArgumentException> { ArrangedPad(b,DrumClass.PERC,velocityTakes=grid.take(1)) }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(b,DrumClass.PERC,velocityTakes=listOf(emptyList(),listOf(b,a))) }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(b,DrumClass.PERC,velocityTakes=grid,softVariants=listOf(a)) }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(a,DrumClass.PERC,velocityTakes=grid) }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(b,DrumClass.PERC,velocityTakes=listOf(listOf(a,Snip(floatArrayOf(.1f),1,22050)),grid.last())) }
    }
}
