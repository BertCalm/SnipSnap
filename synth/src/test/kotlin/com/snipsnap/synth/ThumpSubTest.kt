package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * THUMP SUB's gesture and contracts, measured on real renders: the root holds, the slide reads as a
 * pitch move, DRIVE puts the drum where a phone plays, a softer hit is a different strike, four takes
 * are four strikes with one root, and no setting makes a LOOP.
 */
class ThumpSubTest {
    private val rate = Dsp.RATE
    private val voice = ThumpVoice.SUB
    private val defaults = Thump.defaults(voice)

    /** A fourth down, starting early in a long tail so the slide has arrived while there is still signal to read. */
    private val fourthDown = defaults + mapOf("SLIDE" to .5f - 5f / 24f, "DECAY" to .85f, "WHEN" to .25f)

    private fun render(m: Map<String, Float>, velocity: Float = 1f, take: Int = 0) = Thump.render(voice, m, velocity, take)

    /** Energy between [lo] and [hz] against energy under 2 kHz, by a direct DFT at 5 Hz steps. */
    private fun bandShare(x: FloatArray, lo: Float, hi: Float): Double {
        fun power(hz: Double): Double {
            var re = 0.0; var im = 0.0
            val w = 2 * PI * hz / rate
            for (i in x.indices) { re += x[i] * cos(w * i); im += x[i] * sin(w * i) }
            return re * re + im * im
        }
        var band = 0.0; var all = 0.0
        var hz = 10.0
        while (hz <= 2_000.0) { val p = power(hz); all += p; if (hz >= lo && hz < hi) band += p; hz += 5.0 }
        return band / all
    }

    /** How unlike two renders are once level is taken out: 0 for the same shape. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it }); val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    private fun cents(hz: Float, ref: Float) = 1200.0 * log2(hz.toDouble() / ref)

    /**
     * The root, read from 100 ms in: the settle is 0.35 octaves falling with a 12 ms time constant, so at
     * 50 ms the sine is still 12 cents high by design, and by 100 ms under a cent. The detector itself is
     * good to about 5 cents at 58 Hz over this window.
     */
    private fun rootOf(s: Snip): Float = assertNotNull(Pitch.detect(s, fromSec = .10f, windowSec = .25f), "no root heard").hz

    /** The pitch once the slide has had three time constants to arrive (95 percent of the way). */
    private fun arrivedPitch(m: Map<String, Float>, s: Snip): Float {
        val t60 = Thump.subT60(m.getValue("DECAY"))
        val target = Thump.subSlideSemitones(m.getValue("SLIDE"))
        val tau = .08f + .10f * abs(target) / 12f
        val from = Thump.subSlideStart(m.getValue("WHEN"), t60) + 3f * tau
        // A quarter second: at 44 Hz the detector needs about ten cycles to call the note (measured: 0.15 s reads nothing).
        return assertNotNull(Pitch.detect(s, fromSec = from, windowSec = .25f), "no pitch heard after the slide at $from s").hz
    }

    @Test fun `the root holds and the slide reads as a pitch move`() {
        val root = Thump.subRootHz(defaults.getValue("TUNE"))
        // Before the slide starts every shape sits on its root.
        for (slide in listOf(.5f, .5f - 5f / 24f, 1f)) {
            val s = render(fourthDown + ("SLIDE" to slide))
            assertEquals(0.0, cents(rootOf(s), root), 12.0, "SLIDE $slide root")
        }
        // After it, the note is within 60 cents of 95 percent of the way to the target. The fifth down is
        // read from a higher root, because the detector's floor is near 40 Hz and 58 Hz down a fifth is 39.
        for ((slide, target, tune) in listOf(Triple(.5f - 5f / 24f, -500.0, .5f), Triple(1f, 1200.0, .5f), Triple(.5f - 7f / 24f, -700.0, .75f))) {
            val m = fourthDown + mapOf("SLIDE" to slide, "TUNE" to tune)
            val moved = cents(arrivedPitch(m, render(m)), Thump.subRootHz(tune))
            assertEquals(target * .95, moved, 60.0, "SLIDE $slide moved $moved cents")
        }
        // No slide: no move.
        val straight = fourthDown + ("SLIDE" to .5f)
        assertEquals(0.0, cents(arrivedPitch(straight, render(straight)), root), 12.0)
    }

    @Test fun `DRIVE puts the drum where a phone plays`() {
        // The SUB pedal's lesson: a clean sub is all under 100 Hz, which a phone does not play. DRIVE's
        // harmonics land between 150 and 400 Hz, and the share there grows with it.
        val shares = listOf(0f, .5f, 1f).map { bandShare(render(fourthDown + ("DRIVE" to it)).samples, 150f, 400f) }
        assertTrue(shares[0] < shares[1] && shares[1] < shares[2], "150 to 400 Hz share $shares")
        assertTrue(shares[2] > 4 * shares[0], "DRIVE 1 has ${shares[2]} of the drum in the phone band against ${shares[0]} at DRIVE 0")
        assertTrue(shares[0] < .01, "a clean sub leaks ${shares[0]} into the phone band")
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        val hard = render(fourthDown, 1f)
        val soft = render(fourthDown, .3f)
        // Both are peak-normalised, so a quieter copy would measure 0 here.
        assertTrue(shapeDistance(hard.samples, soft.samples) > .05)
        // Cleaner and darker: less drive, less click.
        assertTrue(bandShare(soft.samples, 150f, 400f) < bandShare(hard.samples, 150f, 400f))
        assertTrue(bandShare(soft.samples, 1_000f, 2_000f) < bandShare(hard.samples, 1_000f, 2_000f))
        // And still the same note.
        assertEquals(0.0, cents(rootOf(soft), rootOf(hard)), 8.0)
    }

    @Test fun `four takes are four strikes of one drum`() {
        val takes = List(4) { render(fourthDown, 1f, it) }
        for (a in 0 until 4) for (b in a + 1 until 4) {
            assertTrue(shapeDistance(takes[a].samples, takes[b].samples) > .05, "takes $a and $b are the same strike")
        }
        val roots = takes.map { rootOf(it) }
        assertTrue(cents(roots.max(), roots.min()) < 8.0, "takes detune the sub: $roots")
        // Take 4 deals take 0's settings again with its own click; a negative take is not an error.
        assertTrue(shapeDistance(takes[0].samples, render(fourthDown, 1f, 4).samples) < .05)
        assertTrue(render(fourthDown, 1f, -1).samples.all { it.isFinite() })
        // Every other THUMP voice renders the same bytes whatever the take.
        for (other in ThumpVoice.entries - voice) {
            assertContentEquals(Thump.render(other).samples, Thump.render(other, take = 2).samples, other.name)
        }
    }

    @Test fun `no setting makes a LOOP and the default reads as a kick`() {
        // The classifier files anything over 1.5 s as LOOP. The decay range is set so the longest sub stays under it.
        for (decay in listOf(0f, .5f, 1f)) for (v in listOf(.3f, 1f)) {
            val s = render(defaults + ("DECAY" to decay), v)
            assertTrue(s.frameCount < 1.5f * rate, "DECAY $decay v $v is ${s.frameCount} frames")
        }
        assertEquals(DrumClass.KICK, Classifier.classify(render(defaults)).drumClass)
        // A long sub reads TONAL to the classifier. Disclosed, not hidden: the kits author its class.
        val long = Classifier.classify(render(defaults + ("DECAY" to 1f)))
        assertTrue(long.drumClass == DrumClass.TONAL || long.drumClass == DrumClass.KICK, "${long.drumClass}")
    }

    @Test fun `the drum is clean at every velocity and take`() {
        for (v in listOf(.05f, .3f, 1f)) for (take in 0 until 4) for (m in listOf(defaults, defaults.keys.associateWith { 0f }, defaults.keys.associateWith { 1f })) {
            val x = render(m, v, take).samples
            assertTrue(x.all { it.isFinite() && it in -1f..1f })
            assertTrue(abs(x[0]) < .02f, "jumps to ${x[0]} on its first frame")
            assertTrue(abs(x.average()) < .005)
            assertTrue(abs(x.last()) < 1e-3f)
        }
        // A non-finite control falls back to its default rather than reaching the phase.
        assertContentEquals(render(defaults).samples, render(defaults + ("SLIDE" to Float.NaN)).samples)
    }
}
