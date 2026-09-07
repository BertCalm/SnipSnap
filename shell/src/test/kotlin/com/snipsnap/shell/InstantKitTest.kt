package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstantKitTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("instant").toFile()
    private val rate = 44_100

    @AfterTest
    fun tearDown() { temp.deleteRecursively() }

    /** One bar at 100 BPM: kick, hats, snare, an open hat — the break CHOP's own tests use. */
    private fun breakBar(): Snip {
        val step = (60f / 100f / 4f * rate).toInt()
        val hits = listOf(
            0 to DrumSynth.kick(), 2 to DrumSynth.closedHat(), 4 to DrumSynth.snare(), 6 to DrumSynth.closedHat(),
            8 to DrumSynth.kick(), 10 to DrumSynth.closedHat(), 12 to DrumSynth.snare(), 14 to DrumSynth.openHat(),
        )
        val total = FloatArray(step * 16 + rate / 2)
        for ((stepIx, hit) in hits) {
            val at = stepIx * step
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `one tap - a bar of break becomes a playable kit with the kick on A01 and the hats choking`() {
        val dir = File(temp, "Instant")
        val result = InstantKit.build(breakBar(), "Instant", dir)
        assertTrue(result.sliceCount >= 4, "the hits were found: ${result.sliceCount}")
        assertTrue(result.kit.pads.isNotEmpty())
        assertEquals(DrumClass.KICK, result.kit.pad(1)?.drumClass, "kick on A01, the conventional layout")
        assertTrue(result.chokeSet, "closed and open hats choke each other")
        assertEquals(result.kit, KitStore.load(dir), "the kit is on disk, exactly as returned")
        for (pad in result.kit.pads) assertTrue(File(dir, pad.sampleFile).isFile, "${pad.sampleFile} written")
    }

    @Test
    fun `slice takes the committed range, mono, and refuses an empty one`() {
        val stereo = Snip(FloatArray(rate * 2) { if (it % 2 == 0) 0.5f else -0.5f }, 2, rate)
        val cut = InstantKit.slice(stereo, 100..199)
        assertEquals(1, cut.channels)
        assertEquals(100, cut.frameCount)
        assertEquals(rate, InstantKit.slice(stereo, 0..(rate * 4)).frameCount, "a range past the end clamps")
        assertFailsWith<IllegalArgumentException> { InstantKit.slice(stereo, 500..400) }
        assertFailsWith<IllegalArgumentException> { InstantKit.slice(stereo, rate..(rate + 10)) }
    }

    @Test
    fun `silence is refused in words, nothing written`() {
        val dir = File(temp, "Silent")
        val e = assertFailsWith<IllegalArgumentException> { InstantKit.build(Snip(FloatArray(rate), 1, rate), "Silent", dir) }
        assertTrue("no hits" in e.message!!, e.message)
        assertTrue(!File(dir, "kit.json").exists())
        assertFailsWith<IllegalArgumentException> { InstantKit.build(Snip(FloatArray(0), 1, rate), "Empty", dir) }
    }
}
