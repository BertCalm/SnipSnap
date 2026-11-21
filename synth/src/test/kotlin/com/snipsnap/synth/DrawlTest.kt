package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DRAWL's probes (the house set: snipsnap-conventions §5). The engine is a prototype
 * ([PatchTypes.PROTOTYPES]): the owner kept the Python prototype's six voices by ear, and nobody
 * has heard these Kotlin renders, so these tests hold the mechanics, not the sound.
 */
class DrawlTest {

    private val still = mapOf("SLIDE" to 0f, "WAVER" to 0f)

    private fun cents(hz: Float, want: Float): Double = 1200.0 * ln(hz / want.toDouble()) / ln(2.0)

    private fun rmsDiff(a: Snip, b: Snip): Double {
        val n = minOf(a.samples.size, b.samples.size)
        var s = 0.0
        for (i in 0 until n) { val d = a.samples[i] - b.samples[i]; s += d * d }
        return sqrt(s / n) + if (a.samples.size != b.samples.size) 1.0 else 0.0
    }

    private fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) p = max(p, abs(x[i]))
        return p
    }

    /** Magnitude-weighted centroid of the first [ms] after level matching (StrikeVelocityTest's probe shape). */
    private fun onsetCentroid(s: Snip, ms: Int = 80): Double {
        val n = s.sampleRate * ms / 1000
        val bins = 256
        var num = 0.0
        var den = 0.0
        for (b in 1 until bins) {
            val hz = b * 12000.0 / bins
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                val x = s.samples[i] * w
                re += x * cos(2 * PI * hz * i / s.sampleRate)
                im -= x * sin(2 * PI * hz * i / s.sampleRate)
            }
            val mag = sqrt(re * re + im * im)
            num += hz * mag
            den += mag
        }
        return num / den
    }

    @Test
    fun `every voice renders finite, bounded, DC free and at the house loudness`() {
        for (voice in DrawlVoice.entries) {
            val s = Drawl.render(voice)
            assertTrue(s.samples.all { it.isFinite() }, "$voice has a non-finite sample")
            val p = peak(s.samples)
            assertTrue(p in 0.01f..0.99f, "$voice peak $p")
            val mean = s.samples.average()
            assertTrue(abs(mean) < 0.005, "$voice DC $mean")
            val loud = Loudness.of(s)
            assertTrue(loud >= 0.9f * Dsp.MELODIC_LOUDNESS_TARGET || p >= 0.95f,
                "$voice loudness $loud against ${Dsp.MELODIC_LOUDNESS_TARGET}, peak $p")
        }
    }

    @Test
    fun `renders are byte identical across runs`() {
        for (voice in DrawlVoice.entries) assertContentEquals(Drawl.render(voice).samples, Drawl.render(voice).samples, "$voice")
    }

    @Test
    fun `the root is the note at three TUNE positions, once the bar has landed`() {
        for (voice in DrawlVoice.entries) for (tune in listOf(.25f, .5f, .75f)) {
            val s = Drawl.render(voice, still + ("TUNE" to tune))
            // STEEL swells in under the volume pedal: read it once the swell is up
            val from = if (voice == DrawlVoice.STEEL) 0.8f else 0.25f
            val p = Pitch.detect(s, fromSec = from) ?: error("$voice at TUNE $tune has no detectable pitch")
            val off = cents(p.hz, Drawl.frequencyFor(voice, tune))
            assertTrue(abs(off) < 10.0, "$voice at TUNE $tune reads ${"%.1f".format(off)} cents off")
            assertTrue(p.confidence >= 0.3f, "$voice at TUNE $tune pitch confidence ${p.confidence}")
        }
    }

    @Test
    fun `a slide starts below the note and lands on it`() {
        for (voice in listOf(DrawlVoice.CONE, DrawlVoice.BOTTLE, DrawlVoice.LAP)) {
            val s = Drawl.render(voice, mapOf("SLIDE" to 1f, "WAVER" to 0f, "PICK" to .2f))
            val start = Pitch.detect(s, fromSec = 0.004f, windowSec = 0.04f)!!
            val landed = Pitch.detect(s, fromSec = 0.6f)!!
            val want = Drawl.frequencyFor(voice, .5f)
            assertTrue(cents(start.hz, want) < -300.0, "$voice starts ${"%.0f".format(cents(start.hz, want))} cents from the note")
            assertTrue(abs(cents(landed.hz, want)) < 10.0, "$voice lands ${"%.1f".format(cents(landed.hz, want))} cents off")
        }
    }

    @Test
    fun `every macro changes the sound, an unknown key does not, and a broken value falls back`() {
        for (voice in DrawlVoice.entries) {
            val base = Drawl.render(voice)
            for (spec in Drawl.macrosFor(voice)) {
                val lo = Drawl.render(voice, mapOf(spec.name to 0f))
                val hi = Drawl.render(voice, mapOf(spec.name to 1f))
                assertTrue(rmsDiff(lo, hi) > 1e-3, "$voice ${spec.name} does nothing")
            }
            assertContentEquals(base.samples, Drawl.render(voice, mapOf("NOT_A_MACRO" to 1f)).samples, "$voice unknown key")
            assertContentEquals(base.samples, Drawl.render(voice, mapOf("SLIDE" to Float.NaN)).samples, "$voice NaN SLIDE")
        }
    }

    @Test
    fun `WAVER moves the pitch, and more of it moves it further`() {
        fun spread(voice: DrawlVoice, waver: Float): Double {
            val s = Drawl.render(voice, mapOf("SLIDE" to 0f, "WAVER" to waver, "RING" to 1f))
            val want = Drawl.frequencyFor(voice, .5f)
            val cs = (0 until 12).mapNotNull { k -> Pitch.detect(s, fromSec = 0.9f + 0.05f * k, windowSec = 0.04f) }
                .map { cents(it.hz, want) }
            val m = cs.average()
            return sqrt(cs.sumOf { (it - m) * (it - m) } / cs.size)
        }
        for (voice in listOf(DrawlVoice.BOTTLE, DrawlVoice.LAP, DrawlVoice.GUT)) {
            val none = spread(voice, 0f)
            val half = spread(voice, .5f)
            val full = spread(voice, 1f)
            assertTrue(none < 3.0 && half > none + 5.0 && full > half + 5.0,
                "$voice WAVER spread ${"%.1f".format(none)} / ${"%.1f".format(half)} / ${"%.1f".format(full)} cents")
        }
    }

    @Test
    fun `a harder pick is brighter as well as louder, at one gain per sound`() {
        for (voice in DrawlVoice.entries) {
            val touches = listOf(.3f, .75f, 1f).map { Drawl.render(voice, mapOf("SLIDE" to 0f), velocity = it) }
            val loud = touches.map { Loudness.of(it) }
            val bright = touches.map { s ->
                val g = 1f / max(1e-9f, peak(s.samples, 0, s.sampleRate * 3 / 10))
                onsetCentroid(Snip(FloatArray(s.samples.size) { s.samples[it] * g }, 1, s.sampleRate))
            }
            assertTrue(loud[0] < loud[1] && loud[1] <= loud[2] * 1.001f, "$voice loudness by touch $loud")
            assertTrue(bright[0] < bright[1] && bright[1] < bright[2], "$voice onset centroid by touch $bright")
        }
    }

    @Test
    fun `takes are distinct strikes that keep the note`() {
        for (voice in DrawlVoice.entries) {
            val takes = (0 until Drawl.TAKES).map { Drawl.render(voice, still, take = it) }
            for (a in takes.indices) for (b in a + 1 until takes.size)
                assertTrue(rmsDiff(takes[a], takes[b]) > 1e-3, "$voice takes $a and $b are the same strike")
            val from = if (voice == DrawlVoice.STEEL) 0.8f else 0.25f
            for ((t, s) in takes.withIndex()) {
                val p = Pitch.detect(s, fromSec = from)!!
                val off = cents(p.hz, Drawl.frequencyFor(voice, .5f))
                assertTrue(abs(off) < 10.0, "$voice take $t reads ${"%.1f".format(off)} cents off")
            }
        }
    }

    @Test
    fun `the render is as long as its class says, and never files as a drum`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in DrawlVoice.entries) for (hold in listOf(0f, 1f)) {
            val m = mapOf("HOLD" to hold)
            val s = Drawl.render(voice, m)
            assertEquals(Drawl.renderFrames(voice, m), s.samples.size, "$voice HOLD $hold length")
            assertEquals(DrumClass.LOOP, Drawl.drumClassFor(voice, m), "$voice HOLD $hold class")
            val heard = Classifier.classify(s).drumClass
            assertTrue(heard !in drums, "$voice HOLD $hold classified as $heard")
        }
        assertEquals(8f, Drawl.seconds(DrawlVoice.CONE, 1f))
    }

    @Test
    fun `the ring dies away and never grows back`() {
        for (voice in DrawlVoice.entries) {
            val s = Drawl.render(voice, mapOf("HOLD" to .5f)).samples
            val sr = Dsp.RATE
            // STEEL's swell peaks late: take the strike from the first 1.2 s, the tail from the last second
            val strike = peak(s, 0, (1.2 * sr).toInt())
            val tail = peak(s, s.size - sr, s.size)
            assertTrue(tail < 0.5f * strike, "$voice tail peak $tail against strike $strike")
        }
    }

    @Test
    fun `the patch round-trips through JSON and refuses bad performance fields`() {
        for (voice in DrawlVoice.entries) {
            val patch = DrawlPatch("Saved $voice", voice, mapOf("RING" to .3f), velocity = .5f, take = 2)
            assertEquals(patch, DrawlPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
        val plain = DrawlPatch("Plain", DrawlVoice.CONE)
        assertTrue("velocity" !in plain.toJsonText() && "take" !in plain.toJsonText(), "defaults are not written")
        assertFailsWith<IllegalArgumentException> { DrawlPatch("Bad", DrawlVoice.CONE, velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { DrawlPatch("Bad", DrawlVoice.CONE, take = -1) }
    }

    @Test
    fun `scramble stays inside 0 to 1 and inside the voice's own controls`() {
        val random = Random(7)
        for (voice in DrawlVoice.entries) repeat(20) {
            val m = Drawl.scramble(voice, random)
            assertEquals(Drawl.defaults(voice).keys, m.keys)
            assertTrue(m.values.all { it in 0f..1f }, "$voice scrambled to $m")
        }
    }

    @Test
    fun `the provisional recipes are the kept ones, distinct, plainly named and held out of the factory roster`() {
        val all = DrawlPresets.all()
        for (voice in DrawlVoice.entries) {
            val mine = DrawlPresets.forVoice(voice)
            assertTrue(mine.size == 2 && mine.all { it.voice == voice }, "$voice has ${mine.size} recipes")
            assertEquals(Drawl.defaults(voice), mine[0].macros, "$voice's first recipe is its kept default")
        }
        assertEquals(all.size, all.map { it.name }.toSet().size, "recipe names repeat")
        assertEquals(all.size, all.map { it.copy(name = "same") }.toSet().size, "two recipes are the same sound")
        for (p in all) {
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), "${p.name} is on the blocklist")
            assertEquals(Drawl.defaults(p.voice).keys, p.macros.keys, "${p.name} does not carry every macro")
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        }
        assertTrue(Presets.all().none { it.engine == DrawlPatch.ENGINE }, "DRAWL is a prototype: nothing in the factory roster")
        for (name in listOf("Dobro slide", "GIBSON STEEL", "Rickenbacker lap", "Sho-Bud cry", "Emmons push", "National Reso"))
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), "$name passes the blocklist")
        assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn("NATIONAL ANTHEM"), "plain English is refused")
    }

    /** The level of one frequency over a stretch of the render (a Hann-windowed single-bin transform), in dB. */
    private fun levelDb(s: Snip, hz: Double, fromSec: Float, seconds: Float = .5f): Double {
        val from = (fromSec * s.sampleRate).toInt()
        val n = (seconds * s.sampleRate).toInt()
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            val x = s.samples[from + i] * w
            re += x * cos(2 * PI * hz * i / s.sampleRate)
            im -= x * sin(2 * PI * hz * i / s.sampleRate)
        }
        return 20 * log10(sqrt(re * re + im * im) / n + 1e-12)
    }

    private val triad = DrawlStrum.Articulation(listOf(0, 4, 7))

    @Test
    fun `a strum sounds every chord tone, once each string has landed`() {
        for (voice in DrawlVoice.entries) {
            val s = Drawl.render(voice, mapOf("WAVER" to 0f), strum = triad)
            val root = Drawl.midiFor(voice, .5f)
            val from = if (voice == DrawlVoice.STEEL) 1.0f else .45f
            val off = levelDb(s, Keys.midiHz(root + 2).toDouble(), from)
            for (iv in triad.intervals) {
                val on = levelDb(s, Keys.midiHz(root + iv).toDouble(), from)
                assertTrue(on > off + 6, "$voice chord tone +$iv sits ${"%.1f".format(on - off)} dB over a tone not in the chord")
            }
        }
    }

    @Test
    fun `strum direction, sweep and STEEL's pedals each change the chord`() {
        for (voice in DrawlVoice.entries) {
            val down = Drawl.render(voice, strum = triad)
            assertTrue(rmsDiff(down, Drawl.render(voice)) > 1e-3, "$voice strum sounds like the single note")
            assertTrue(rmsDiff(down, Drawl.render(voice, strum = triad.copy(direction = DrawlStrum.Direction.UP))) > 1e-3, "$voice UP is DOWN")
            assertTrue(rmsDiff(down, Drawl.render(voice, strum = triad.copy(sweepSeconds = .2))) > 1e-3, "$voice sweep does nothing")
            assertEquals(Drawl.render(voice).samples.size, down.samples.size, "$voice strum changes the length")
            assertContentEquals(down.samples, Drawl.render(voice, strum = triad).samples, "$voice strum is not deterministic")
        }
        val pedalled = Drawl.render(DrawlVoice.STEEL, mapOf("SLIDE" to 1f), strum = triad)
        val still = Drawl.render(DrawlVoice.STEEL, mapOf("SLIDE" to 0f), strum = triad)
        val top = Keys.midiHz(Drawl.midiFor(DrawlVoice.STEEL, .5f) + 7).toDouble()
        // before the pedal goes down (it starts at 0.4 s) the pedalled chord's top tone is two semitones flat
        assertTrue(levelDb(pedalled, top, .15f, .2f) < levelDb(still, top, .15f, .2f) - 6, "STEEL's pedals do not hold the top tone back")
    }

    @Test
    fun `a strum round-trips, takes the chord tool's voicing and refuses a bad chord`() {
        val chord = ChordStabs.Stab("Am7", 69, listOf(0, 3, 7, 10))
        val patch = DrawlStrum.fromChord(DrawlPatch("Chord", DrawlVoice.LAP), chord, .12, DrawlStrum.Direction.UP)
        assertEquals(listOf(0, 3, 7, 10), patch.strum!!.intervals)
        // A4 sits within TUNE's octave of LAP's D4 root, so it is played where it is
        assertEquals(69, Drawl.midiFor(DrawlVoice.LAP, patch.macros.getValue("TUNE")))
        assertEquals(patch, DrawlPatch.fromJsonText(patch.toJsonText()))
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        val far = DrawlStrum.fromChord(DrawlPatch("Low", DrawlVoice.GUT), ChordStabs.Stab("C", 24, listOf(0, 4, 7)))
        assertEquals(48, Drawl.midiFor(DrawlVoice.GUT, far.macros.getValue("TUNE")), "a chord out of reach moves by whole octaves")
        assertFailsWith<IllegalArgumentException> { DrawlStrum.Articulation(listOf(4, 7)) }
        assertFailsWith<IllegalArgumentException> { DrawlStrum.Articulation(listOf(0)) }
        assertFailsWith<IllegalArgumentException> { DrawlStrum.Articulation(listOf(0, 4), sweepSeconds = .5) }
        assertEquals(DrumClass.LOOP, Drawl.drumClassFor(DrawlVoice.CONE))
    }

    @Test
    fun `ROBIN deals the saved sound first, then takes 1 to 3 in turn, keeping the chord`() {
        val patch = DrawlPatch("Robin", DrawlVoice.CONE, strum = triad)
        assertTrue(Restrike.canRestrike(patch))
        assertContentEquals(patch.render().samples, Restrike.take(patch, 1f, 0, 0)!!.samples, "take 0 is the saved render")
        val dealt = (1..6).map { Restrike.drawlTake(patch.take, it) }
        assertEquals(listOf(1, 2, 3, 1, 2, 3), dealt)
        for (k in 1..3) assertContentEquals(patch.copy(take = k).render().samples, Restrike.take(patch, 1f, 99, k)!!.samples, "take $k")
        assertContentEquals(patch.copy(velocity = .375f).render().samples, Restrike.take(patch, .5f, 0, 0)!!.samples, "Robin's velocity scales the saved touch")
    }

    @Test
    fun `a note renders in a playable time`() {
        // the house playability rule is tens of ms per knob turn; a cached sound pays for one render
        for (voice in DrawlVoice.entries) Drawl.render(voice) // warm the JIT and the gain cache
        val started = System.nanoTime()
        for (voice in DrawlVoice.entries) Drawl.render(voice, take = 1)
        val each = (System.nanoTime() - started) / 1e6 / DrawlVoice.entries.size
        println("DRAWL: ${"%.0f".format(each)} ms per default note after warm-up (desktop JVM)")
        assertTrue(each < 400.0, "DRAWL took ${"%.0f".format(each)} ms per note")
    }

    @Test
    fun `measured numbers for the spec`() {
        // Not an assertion: the numbers the round-1 ledger quotes, printed from real renders.
        for (voice in DrawlVoice.entries) {
            val s = Drawl.render(voice)
            val p = Pitch.detect(Drawl.render(voice, still), fromSec = if (voice == DrawlVoice.STEEL) 0.8f else 0.25f)!!
            println("DRAWL $voice: ${s.samples.size} frames, peak ${"%.3f".format(peak(s.samples))}, " +
                "loudness ${"%.4f".format(Loudness.of(s))}, root ${"%.2f".format(cents(p.hz, Drawl.frequencyFor(voice, .5f)))} cents, " +
                "confidence ${"%.2f".format(p.confidence)}, centroid ${"%.0f".format(Classifier.classify(s).features.centroidHz)} Hz, " +
                "level ${"%.1f".format(20 * log10(Loudness.of(s).toDouble()))} dB")
        }
    }
}
