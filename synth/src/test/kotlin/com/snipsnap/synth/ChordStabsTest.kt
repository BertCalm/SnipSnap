package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ChordStabs]: a key's chords, one per pad, voiced near one centre. */
class ChordStabsTest {

    private val allKeys = (0..11).flatMap { r -> listOf(KeySpec(r, Scale.MAJOR), KeySpec(r, Scale.MINOR)) }

    @Test
    fun `C minor lays out its triads, a wide tonic, its sevenths and an add9`() {
        assertEquals(
            listOf("Cm", "Ddim", "D#", "Fm", "Gm", "G#", "A#", "Cm", "Cm7", "Dm7b5", "D#maj7", "Fm7", "Gm7", "G#maj7", "A#7", "Cmadd9"),
            ChordStabs.stabs(KeySpec.parse("Cm")).map { it.name },
        )
        assertEquals(
            listOf("C", "Dm", "Em", "F", "G", "Am", "Bdim", "C", "Cmaj7", "Dm7", "Em7", "Fmaj7", "G7", "Am7", "Bm7b5", "Cadd9"),
            ChordStabs.stabs(KeySpec.parse("C")).map { it.name },
        )
    }

    @Test
    fun `every chord is in its key and every voicing is one STOOP plays`() {
        for (key in allKeys) {
            val scale = key.scale.intervals.map { (it + key.rootSemitone) % 12 }.toSet()
            val stabs = ChordStabs.stabs(key)
            assertEquals(16, stabs.size, key.label)
            for (s in stabs) {
                for (n in s.notes) assertTrue(n % 12 in scale, "${key.label} ${s.name}: ${n % 12} is in the key")
                assertTrue(0 in s.intervals && s.intervals.size in 1..4 && s.intervals.all { it in -24..24 }, "${key.label} ${s.name}: ${s.intervals}")
                assertEquals(s.intervals.size, s.intervals.distinct().size, "${key.label} ${s.name}: distinct tones")
                // The patch's own init refuses anything STOOP cannot render.
                StoopPresets.forVoice(StoopVoice.CHORD).first().copy(midi = s.rootMidi, chordIntervals = s.intervals)
            }
            // A08 is A01 with its root an octave up; A16 is the tonic with a ninth.
            assertEquals(stabs[0].intervals + stabs[7].intervals.last(), stabs[7].intervals, "${key.label} wide tonic")
            assertEquals(stabs[0].rootMidi, stabs[7].rootMidi)
        }
    }

    /**
     * Voice leading: every chord sits within a fourth of the centre, so two
     * neighbouring pads never jump the register, and every key lands in the
     * same band (no key an octave above another).
     */
    @Test
    fun `chords gather near one centre in every key`() {
        for (key in allKeys) {
            val centre = ChordStabs.centreMidi(key)
            assertTrue(centre in 59..70, "${key.label} centre $centre")
            val stabs = ChordStabs.stabs(key)
            for (s in stabs.take(7) + stabs.drop(8).take(7)) {
                assertTrue(abs(s.notes.average() - centre) <= 5.0, "${key.label} ${s.name}: mean ${s.notes.average()} against $centre")
                assertTrue(s.notes.max() - s.notes.min() <= 12, "${key.label} ${s.name}: close position within an octave: ${s.notes}")
            }
            for (i in 1 until 7) {
                val step = abs(stabs[i].notes.average() - stabs[i - 1].notes.average())
                assertTrue(step <= 6.0, "${key.label}: ${stabs[i - 1].name} to ${stabs[i].name} moves ${"%.1f".format(step)} semitones on average")
            }
        }
    }

    @Test
    fun `a key that is not seven notes is refused`() {
        assertFailsWith<IllegalArgumentException> { ChordStabs.stabs(KeySpec.parse("Cminpent")) }
        assertFailsWith<IllegalArgumentException> { ChordStabs.stabs(KeySpec.parse("Cchrom")) }
    }

    @Test
    fun `the kit is sixteen tonal stabs with soft takes and recipes that rebuild them`() {
        val kit = ChordStabs.kit()
        assertEquals(16, kit.size)
        val stabs = ChordStabs.stabs(KeySpec.parse("Cm"))
        for ((i, pad) in kit.withIndex()) {
            requireNotNull(pad)
            assertEquals(DrumClass.TONAL, pad.drumClass, stabs[i].name)
            assertEquals(stabs[i].name, pad.displayName)
            assertEquals(2, pad.softVariants.size, "${stabs[i].name}: two softer takes")
            val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as StoopPatch
            assertEquals(stabs[i].rootMidi, patch.midi)
            assertEquals(stabs[i].intervals, patch.chordIntervals)
            assertTrue(patch.render().samples.contentEquals(pad.snip.samples), "${stabs[i].name}: the recipe rebuilds the pad")
            assertTrue(pad.snip.samples.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    /**
     * The share of the sustain's spectral magnitude (60 ms on, 8192 frames,
     * 60 Hz to 8 kHz) further than 1.5% from every harmonic of every chord
     * tone: intermodulation and noise, the mud a saturator on a summed
     * chord adds.
     */
    private fun offHarmonic(samples: FloatArray, rate: Int, notes: List<Int>): Double {
        val from = (0.06 * rate).toInt()
        val n = 8192
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) re[i] = samples.getOrElse(from + i) { 0f } * (0.5f - 0.5f * cos(2.0 * Math.PI * i / (n - 1)).toFloat())
        Fft.forward(re, im)
        val tones = notes.map { 440.0 * 2.0.pow((it - 69) / 12.0) }
        var on = 0.0
        var off = 0.0
        for (b in 1..n / 2) {
            val hz = b.toDouble() * rate / n
            if (hz < 60 || hz > 8000) continue
            val m = sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())
            val near = tones.any { f -> val k = Math.round(hz / f).coerceAtLeast(1); abs(hz - k * f) <= maxOf(0.015 * hz, 1.5 * rate / n) }
            if (near) on += m else off += m
        }
        return off / (on + off)
    }

    /**
     * STOOP's WEAR saturates the summed chord, so it intermodulates the
     * tones. The kit plays Small Cinema at WEAR 0. Measured at the time of
     * writing, C minor's sixteen stabs: 2.1% off-harmonic on average, 2.5%
     * at most; the same stabs at WEAR 1: 6.7% on average. The bound keeps
     * the kit clean and proves the metric sees the saturator.
     */
    @Test
    fun `the kit's stabs stay clean of intermodulation, and WEAR would muddy them`() {
        val stabs = ChordStabs.stabs(KeySpec.parse("Cm"))
        val base = StoopPresets.forVoice(StoopVoice.CHORD).single { it.name == ChordStabs.PRESET }
        fun shares(wear: Float) = stabs.map { st ->
            val p = base.copy(midi = st.rootMidi, chordIntervals = st.intervals, macros = base.macros + ("WEAR" to wear))
            offHarmonic(p.render().samples, Dsp.RATE, st.notes)
        }
        val kit = shares(base.macros.getValue("WEAR"))
        assertTrue(kit.max() < 0.04, "the kit's worst stab: ${kit.max()}")
        val worn = shares(1f)
        assertTrue(worn.average() > 2 * kit.average(), "WEAR 1 (${worn.average()}) against the kit (${kit.average()})")
    }
}
