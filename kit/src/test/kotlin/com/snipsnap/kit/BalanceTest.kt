package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceTest {

    /** A peak-normalized tone burst — same peak, very different loudness by duty. */
    private fun hit(hz: Double, seconds: Float, sparse: Boolean = false): Snip {
        val n = (seconds * 44_100).toInt()
        return Snip(
            FloatArray(n) { i ->
                val s = (0.9 * sin(2.0 * PI * hz * i / 44_100)).toFloat()
                if (sparse && (i / 800) % 8 != 0) 0f else s
            },
            1, 44_100,
        )
    }

    @Test
    fun `loudness sees through equal peaks`() {
        val dense = hit(200.0, 0.4f)
        val sparse = hit(4000.0, 0.4f, sparse = true)
        assertEquals(dense.peak(), sparse.peak(), 0.01f)
        assertTrue(
            Loudness.of(dense) > Loudness.of(sparse) * 1.5f,
            "a dense tone is louder than a sparse click train at the same peak",
        )
    }

    @Test
    fun `balance tucks the hats under the kick`() {
        val arranged = listOf(
            ArrangedPad(hit(60.0, 0.4f), DrumClass.KICK),
            ArrangedPad(hit(6000.0, 0.15f), DrumClass.HAT_CLOSED),
            null,
            ArrangedPad(hit(220.0, 0.5f), DrumClass.TONAL),
        )
        val balanced = Balance.apply(arranged)

        val kick = balanced[0]!!.level!!
        val hat = balanced[1]!!.level!!
        assertTrue(balanced[2] == null, "empty slots stay empty")
        assertTrue(kick <= Balance.CEILING + 0.001f, "nothing exceeds the ceiling")
        // Similar-loudness sources: the class targets decide the ordering.
        assertTrue(hat < kick, "hat ($hat) should sit under the kick ($kick)")
    }

    @Test
    fun `balance is a no-op on silence and levels flow into the kit`() {
        val silent = listOf(ArrangedPad(Snip(FloatArray(4410), 1, 44_100), DrumClass.PERC))
        assertEquals(null, Balance.apply(silent)[0]!!.level, "silence keeps the default level")

        val dir = java.nio.file.Files.createTempDirectory("balance").toFile()
        try {
            val arranged = Balance.apply(
                listOf(
                    ArrangedPad(hit(60.0, 0.4f), DrumClass.KICK),
                    ArrangedPad(hit(6000.0, 0.15f), DrumClass.HAT_CLOSED),
                ),
            )
            val kit = KitAssembler.assembleArranged("Balanced", arranged, dir)
            assertEquals(arranged[0]!!.level!!, kit.pad(1)!!.level, 0.0001f)
            assertEquals(arranged[1]!!.level!!, kit.pad(2)!!.level, 0.0001f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `in-key retunes tonal pads only, and the tuning flows into the kit`() {
        fun tone(hz: Double) = Snip(
            FloatArray(22_050) { (0.6 * sin(2.0 * PI * hz * it / 44_100)).toFloat() },
            1, 44_100,
        )
        val arranged = listOf(
            ArrangedPad(tone(60.0), DrumClass.KICK),      // pitched, but not TONAL: untouched
            ArrangedPad(tone(233.8), DrumClass.TONAL),    // ~30c sharp of A#3
        )
        val tuned = InKey.apply(arranged, rootSemitone = 9, scale = Scale.MINOR)
        assertEquals(0, tuned[0]!!.tuneCoarse)
        assertEquals(0, tuned[0]!!.tuneFine)
        val tonal = tuned[1]!!
        assertTrue(
            tonal.tuneCoarse != 0 || tonal.tuneFine != 0,
            "the sharp tonal pad should have been retuned",
        )

        val dir = java.nio.file.Files.createTempDirectory("inkey").toFile()
        try {
            val kit = KitAssembler.assembleArranged("Keyed", tuned, dir)
            assertEquals(tonal.tuneCoarse, kit.pad(2)!!.tuneCoarse)
            assertEquals(tonal.tuneFine, kit.pad(2)!!.tuneFine)
        } finally {
            dir.deleteRecursively()
        }
    }
}
