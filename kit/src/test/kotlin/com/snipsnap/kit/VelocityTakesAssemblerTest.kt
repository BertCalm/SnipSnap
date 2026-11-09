package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class VelocityTakesAssemblerTest {
    @Test fun `unequal takes retain exact windows and velocity anchors after persistence`() {
        val rows = (0..2).map { z -> (0..3).map { t -> Snip(FloatArray(10 + z * 4 + t) { (z * 4 + t + 1) / 20f }, 1, 44100) } }
        val dir = createTempDirectory("velocity-takes").toFile()
        try {
            val kit = KitAssembler.assembleArranged("grid", listOf(ArrangedPad(rows[2][0], DrumClass.SNARE, velocityTakes = rows)), dir)
            assertEquals(kit, KitStore.load(dir))
            val pad = kit.pads.single()
            assertTrue(pad.velocityLayers.isEmpty())
            val chain = assertNotNull(pad.chain)
            val wav = WavReader.read(dir.resolve(pad.sampleFile))
            var start = 0L
            rows.flatten().forEachIndexed { i, sample ->
                assertEquals(start until start + sample.frameCount, chain.window(i, wav.frameCount.toLong()))
                sample.samples.forEachIndexed { n, v -> assertTrue(kotlin.math.abs(v - wav.samples[start.toInt() + n]) < 2e-7f) }
                start += sample.frameCount
            }
            for (v in 0..127) {
                val z = assertNotNull(chain.zoneFor(v))
                assertEquals(if (v < 42) 0 else if (v < 85) 4 else 8, z.baseSlice)
                assertEquals(4, z.cycle)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun `grid rejects mixed assembly modes and mismatched sample rates`() {
        val hit = Snip(FloatArray(8), 1, 44100)
        val grid = List(3) { List(4) { hit } }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(hit, DrumClass.SNARE, takes = listOf(hit), velocityTakes = grid) }
        assertFailsWith<IllegalArgumentException> { ArrangedPad(hit, DrumClass.SNARE, velocityTakes = grid + listOf(List(4) { Snip(FloatArray(8), 1, 48000) })) }
    }
}
