package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.math.exp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileSoundTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("tilesound").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(n: Int, hz: Double, decay: Double = 0.0) = Snip(
        FloatArray(n) { i ->
            val env = if (decay > 0) exp(-i / decay) else 1.0
            (0.5 * env * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat()
        },
        1, 44_100,
    )

    /** Three tones, one silent pad, one pad whose file is missing. */
    private fun buildKit(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(8_000, 90.0, decay = 2_000.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(5_000, 400.0))
        WavWriter.write(File(dir, "A03_HatClosed_01.wav"), tone(2_000, 4_000.0))
        WavWriter.write(File(dir, "A04_Silent_01.wav"), Snip(FloatArray(4_000), 1, 44_100))
        val kit = Kit(
            "Sound Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
                KitPad(slot = 3, sampleFile = "A03_HatClosed_01.wav", drumClass = DrumClass.HAT_CLOSED),
                KitPad(slot = 4, sampleFile = "A04_Silent_01.wav", drumClass = DrumClass.PERC),
                KitPad(slot = 5, sampleFile = "A05_Gone_01.wav", drumClass = DrumClass.PERC),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `the envelope of a decaying tone descends`() {
        val dir = File(temp, "kit")
        buildKit(dir)
        val kick = TileSound.read(File(dir, "A01_Kick_01.wav"))!!
        val env = kick.envelope(8)
        assertEquals(8, env.size)
        assertEquals(1f, env[0], "the loudest bin is one")
        for (i in 1 until env.size) assertTrue(env[i] < env[i - 1], "bin $i (${env[i]}) below bin ${i - 1} (${env[i - 1]})")
        assertTrue(env.all { it in 0f..1f })
    }

    @Test
    fun `silence is flat, not NaN`() {
        val dir = File(temp, "silent")
        buildKit(dir)
        val s = TileSound.read(File(dir, "A04_Silent_01.wav"))!!
        assertEquals(0f, s.peak)
        assertTrue(s.envelope(16).all { it == 0f }, "flat envelope")
        assertTrue(s.attack(64).all { it == 0f }, "flat attack")
        assertTrue((s.envelope(16) + s.attack(64) + s.slice(1, 3, 20)).none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `a slice has the points asked for and stays within one`() {
        val dir = File(temp, "slice")
        buildKit(dir)
        val snare = TileSound.read(File(dir, "A02_Snare_01.wav"))!!
        val slice = snare.slice(2, 4, 48)
        assertEquals(48, slice.size)
        assertTrue(slice.all { kotlin.math.abs(it) <= 1f })
        assertTrue(slice.any { it > 0.5f } && slice.any { it < -0.5f }, "a tone swings both ways")
        assertEquals(32, snare.attack(32).size)
    }

    @Test
    fun `the longest pad is the default seed`() {
        val dir = File(temp, "longest")
        val kit = buildKit(dir)
        assertEquals(1, TileSound.longestSlot(kit, dir))
    }

    @Test
    fun `a missing seed slot falls back to the longest pad`() {
        val dir = File(temp, "fallback")
        val kit = buildKit(dir)
        assertEquals(8_000, TileSound.seed(kit, dir, slot = 9)!!.frames, "slot 9 does not exist")
        assertEquals(8_000, TileSound.seed(kit, dir, slot = 5)!!.frames, "slot 5's file is gone")
        assertEquals(8_000, TileSound.seed(kit, dir, slot = null)!!.frames, "AUTO")
        assertEquals(5_000, TileSound.seed(kit, dir, slot = 2)!!.frames, "a real slot is honoured")
        val pads = TileSound.pads(kit, dir)
        assertEquals(listOf(1, 2, 3, 4), pads.map { it.slot }, "the pad with no file is absent")
        assertEquals(Schemes.classColor(DrumClass.SNARE), pads[1].color)
        val empty = Kit("Empty", emptyList())
        assertNull(TileSound.longestSlot(empty, dir))
        assertNull(TileSound.seed(empty, dir, null))
    }
}
