package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KERF's pitched-pad kits ([KerfTonalKits]): each a 16-note scale in its key, each pad sounding its note, rung out
 * within the cap, softer when struck softer, a fresh strike each take. Save, playback and export are `:shell`'s.
 */
class KerfTonalKitsTest {
    private val major = listOf(0, 2, 4, 5, 7, 9, 11); private val minor = listOf(0, 2, 3, 5, 7, 8, 10)

    @Test
    fun `every kit is sixteen notes rising through its key's scale, none retuned past an octave`() {
        for (k in KerfTonalKits.Kit.entries) {
            val d = KerfTonalKits.data(k)
            assertEquals(KerfTonalKits.PADS, d.pads.size, "$k pads")
            val notes = d.pads.map { it.first }
            assertEquals(notes.sorted(), notes, "$k rises"); assertEquals(notes.size, notes.toSet().size, "$k plays each note once")
            val scale = if (d.minor) minor else major
            for (n in notes) assertTrue(Math.floorMod(n - d.tonic, 12) in scale, "$k: ${KerfTonalKits.noteName(n)} is in its key")
            for ((n, note) in d.pads) assertTrue(abs(n - note.rootMidi) <= KerfTonal.MAX_SHIFT, "$k ${KerfTonalKits.noteName(n)} retunes ${n - note.rootMidi}")
            assertTrue(KerfTonalKits.bpm(k) in 60..180, "$k tempo")
        }
    }

    /** Power within 30 cents of [hz] against the median power from 50 Hz to 8 kHz, in dB, from a long FFT of [x]. */
    private fun prominence(x: FloatArray, hz: Double): Double {
        val n = 1 shl 17; val re = FloatArray(n); val im = FloatArray(n)
        for (i in 0 until minOf(n, x.size)) re[i] = x[i]
        com.snipsnap.audio.Fft.forward(re, im)
        val rate = Dsp.RATE
        fun p(k: Int) = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        val lo = (hz * 2.0.pow(-30.0 / 1200) * n / rate).toInt(); val hi = (hz * 2.0.pow(30.0 / 1200) * n / rate).toInt() + 1
        val peak = (lo..hi).maxOf { p(it) }
        val all = ((50.0 * n / rate).toInt()..(8000.0 * n / rate).toInt()).map { p(it) }.sorted()
        return 10 * log10(peak / all[all.size / 2])
    }

    @Test
    fun `every pad sounds its note`() {
        val weak = mutableListOf<String>()
        for (k in KerfTonalKits.Kit.entries) for (pad in 0 until KerfTonalKits.PADS) {
            val midi = KerfTonalKits.data(k).pads[pad].first
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val db = prominence(KerfTonalKits.render(k, pad, 1f, 0).samples, hz)
            if (db < 20.0) weak += "$k ${KerfTonalKits.noteName(midi)}: ${"%.1f".format(java.util.Locale.ROOT, db)} dB"
        }
        assertTrue(weak.isEmpty(), "pads whose note is not a clear peak: $weak")
    }

    @Test
    fun `a pad rings within the cap, a soft strike is softer, and each take is a fresh strike`() {
        for (k in KerfTonalKits.Kit.entries) {
            val pads = KerfTonalKits.build(k, listOf(0, 15))
            for (pad in pads) {
                assertEquals(DrumClass.TONAL, pad.drumClass); assertTrue(pad.oneShot)
                val grid = pad.velocityTakes
                assertEquals(3, grid.size); assertTrue(grid.all { it.size == KerfTonalKits.TAKES })
                val loudest = grid.flatten().maxOf { s -> s.samples.maxOf { abs(it) } }
                assertEquals(Kerf.PEAK, loudest, 1e-5f, "${pad.displayName} level")
                for (s in grid.flatten()) assertTrue(s.samples.size <= ((KerfRender.RING_MAX_SECONDS + .01) * Dsp.RATE).toInt())
                val soft = grid.first().first().samples.maxOf { abs(it) }; val hard = grid.last().first().samples.maxOf { abs(it) }
                assertTrue(soft < hard * .8f, "${pad.displayName} soft $soft hard $hard")
                val a = grid.last()[0].samples; val b = grid.last()[1].samples; val m = minOf(a.size, b.size)
                var d = 0.0; var e = 0.0
                for (i in 0 until m) { val x = (a[i] - b[i]).toDouble(); d += x * x; e += a[i].toDouble() * a[i] }
                assertTrue(10 * log10(d / e) > -60, "${pad.displayName} takes differ")
            }
        }
    }
}
