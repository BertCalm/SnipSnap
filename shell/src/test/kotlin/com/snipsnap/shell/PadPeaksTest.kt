package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadPeaksTest {

    private fun writeWav(file: File, samples: FloatArray) {
        val bytes = ByteArrayOutputStream().apply { WavWriter.write(this, Snip(samples, 1, 44_100)) }.toByteArray()
        file.writeBytes(bytes)
    }

    @Test
    fun `every readable pad gets its columns, a missing file is simply absent`() {
        val dir = kotlin.io.path.createTempDirectory("padpeaks").toFile()
        try {
            val tone = FloatArray(22_050) { (0.8f * sin(2.0 * Math.PI * 220.0 * it / 44_100)).toFloat() }
            writeWav(File(dir, "kick.wav"), tone)
            writeWav(File(dir, "rest.wav"), FloatArray(4_410))
            val kit = Kit(
                "T",
                listOf(
                    KitPad(slot = 1, sampleFile = "kick.wav"),
                    KitPad(slot = 2, sampleFile = "rest.wav"),
                    KitPad(slot = 3, sampleFile = "gone.wav"),
                ),
            )
            val peaks = PadPeaks.forKit(kit, dir)
            assertEquals(setOf(1, 2), peaks.keys)
            val kick = peaks.getValue(1)
            assertEquals(PadPeaks.COLUMNS, kick.size)
            assertTrue(kick.any { it.max > 0.5f }, "a loud tone shows in its columns")
            assertTrue(kick.any { it.min < -0.5f })
            val rest = peaks.getValue(2)
            assertTrue(rest.all { it.max == 0f && it.min == 0f }, "silence is a flat line, not an absence")
            assertNull(peaks[3], "a pad whose file is gone draws nothing and breaks nothing")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an empty kit is an empty map, and a column count must be positive`() {
        val dir = kotlin.io.path.createTempDirectory("padpeaks").toFile()
        try {
            val kit = Kit("E", emptyList())
            assertTrue(PadPeaks.forKit(kit, dir).isEmpty())
            assertFalse(PadPeaks.forKit(kit, dir).containsKey(1))
            assertFailsWith<IllegalArgumentException> { PadPeaks.forKit(kit, dir, columns = 0) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
