package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ArrangedGridTest {
    private fun hit(frames: Int) = Snip(FloatArray(frames) { .1f }, 1, 44100)

    @Test fun `unequal take lengths and zone depths retain every boundary`() {
        val root = createTempDirectory("arranged-grid").toFile()
        try {
            val grid = listOf(listOf(hit(10),hit(20)), listOf(hit(30),hit(40),hit(50)))
            val pad = ArrangedPad(grid.last().first(), DrumClass.PERC, velocityTakes=grid)
            val kit = KitAssembler.assembleArranged("Grid", listOf(pad), root)
            assertEquals(kit, KitStore.load(root))
            val saved = kit.pads.single()
            val chain = assertNotNull(saved.chain)
            assertEquals(listOf(0L,10L,30L,60L,100L),chain.boundaries)
            assertEquals(listOf(ChainZone(0,63,0,2),ChainZone(64,127,2,3)),chain.zones)
            assertEquals(150,WavReader.read(root.resolve(saved.sampleFile)).frameCount)
        } finally { root.deleteRecursively() }
    }

    @Test fun `grid rejects ambiguous articulation and empty takes`() {
        val grid = List(3) { List(3) { hit(10) } }
        val mixed = assertFailsWith<IllegalArgumentException> {
            ArrangedPad(hit(10),DrumClass.PERC,softVariants=listOf(hit(10)),velocityTakes=grid)
        }
        assertTrue(mixed.message!!.contains("grid replaces"))
        val empty = assertFailsWith<IllegalArgumentException> {
            ArrangedPad(hit(10),DrumClass.PERC,velocityTakes=listOf(listOf(hit(0),hit(10)),grid[0]))
        }
        assertTrue(empty.message!!.contains("nonempty"))
    }
}
