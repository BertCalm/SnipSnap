package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.PocketStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadGrooveTest {

    private val rate = 44_100

    /**
     * Two bars at 100 bpm: kick on 1 and 3, snare on 2 and 4, a hat on
     * every offbeat eighth — never two hits on one instant, the way the
     * Ear's own fixture is built, so each hit classifies as itself.
     */
    private fun beat(bars: Int = 2, bpm: Float = 100f): Snip {
        val beatFrames = (60f / bpm * rate).toInt()
        val n = beatFrames * 4 * bars
        val out = FloatArray(n)
        fun place(hit: Snip, at: Int, gain: Float) {
            for (i in hit.samples.indices) {
                val idx = at + i
                if (idx >= n) break
                out[idx] += hit.samples[i] * gain
            }
        }
        val kick = DrumSynth.kick()
        val snare = DrumSynth.snare()
        val hat = DrumSynth.closedHat()
        var t = 0
        var count = 0
        while (t < n) {
            if (count % 2 == 0) place(kick, t, 0.9f) else place(snare, t, 0.8f)
            place(hat, t + beatFrames / 2, 0.5f)
            t += beatFrames
            count++
        }
        return Snip(out, 1, rate)
    }

    private val kit = Kit(
        "T",
        listOf(
            KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK),
            KitPad(slot = 2, sampleFile = "snare.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 3, sampleFile = "hat.wav", drumClass = DrumClass.HAT_CLOSED),
        ),
    )

    @Test
    fun `a beat reads onto the kit's own pads, at its tempo, and lands as the base`() {
        val reading = ReadGroove.read(beat(), kit, "beatbox")
        assertTrue(reading.hits >= 8, "a two-bar beat has more than a handful of sure hits: ${reading.hits}")
        assertTrue(reading.bpm in 90f..110f, "tempo near 100: ${reading.bpm}")
        assertTrue(reading.bars in 2..3, "about two bars: ${reading.bars}")
        val slots = reading.clip.notes.map { it.note - 35 }.toSet()
        assertTrue(1 in slots, "the kick landed on A01")
        assertEquals("beatbox Learned", reading.clip.name)

        val dir = kotlin.io.path.createTempDirectory("readgroove").toFile()
        try {
            // An existing PROG E survives a new reading.
            GrooveStore.save(dir, listOf(Mpc3Clip("Old", 1, listOf(Mpc3Note(36, 0, 0.9f)))))
            GrooveEdit.fork(dir, Mpc3Clip("Old", 1, listOf(Mpc3Note(36, 0, 0.9f))))
            ReadGroove.land(dir, reading)
            val stored = GrooveStore.load(dir)
            assertEquals(reading.clip.name, stored.first().name, "the reading is the captured base now")
            assertNotNull(GrooveEdit.load(dir), "PROG E rode along untouched")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refusals are in words - a tone, an empty tape, a kit with no pads`() {
        val tone = Snip(FloatArray(rate * 2) { (0.5 * Math.sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        val e = assertFailsWith<IllegalArgumentException> { ReadGroove.read(tone, kit, "hum") }
        assertTrue("tempo" in e.message!! || "beat" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { ReadGroove.read(Snip(FloatArray(0), 1, rate), kit, "x") }
        assertFailsWith<IllegalArgumentException> { ReadGroove.read(beat(), Kit("E", emptyList()), "x") }
    }

    @Test
    fun `padFor falls back to a kindred pad and gives up honestly`() {
        val hatsOnly = Kit("H", listOf(KitPad(slot = 5, sampleFile = "oh.wav", drumClass = DrumClass.HAT_OPEN)))
        assertEquals(5, ReadGroove.padFor(hatsOnly, DrumClass.HAT_CLOSED))
        assertEquals(null, ReadGroove.padFor(hatsOnly, DrumClass.KICK))
    }

    @Test
    fun `the feel lands as PROG E over the kit's base, and the pocket keeps on the rack`() {
        val dir = kotlin.io.path.createTempDirectory("feel").toFile()
        try {
            assertFailsWith<IllegalArgumentException> { ReadGroove.feel(beat(), dir, "x") }
            // A straight base pattern to pour the feel on.
            ReadGroove.land(dir, ReadGroove.read(beat(), kit, "base"))
            val felt = ReadGroove.feel(beat(), dir, "drummer")
            assertTrue(felt.covered >= 2)
            assertTrue(GrooveEdit.isProgE(felt.progE))
            assertNotNull(GrooveEdit.load(dir))
            val kept = ReadGroove.keepPocket(felt.pocket, dir)
            assertTrue(kept.isFile && kept.name == "drummer.${PocketStore.EXTENSION}")
            val again = ReadGroove.keepPocket(felt.pocket, dir)
            assertEquals("drummer 2.${PocketStore.EXTENSION}", again.name, "a second bottle never overwrites the first")
            assertEquals(felt.pocket.template, PocketStore.read(kept).template)
        } finally {
            dir.deleteRecursively()
        }
    }
}
