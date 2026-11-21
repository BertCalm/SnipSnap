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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CONSORT's probes. Every number in a message was read from a real render; nothing here has been
 * listened to. The engine is a prototype ([PatchTypes.PROTOTYPES]): these tests hold its mechanics,
 * not its sound.
 */
class ConsortTest {

    private val dry = mapOf("LOOSE" to 0f, "ROOM" to 0f)
    private val trio = listOf(ConsortChair(ConsortVoice.CLARINET, ConsortInterval.FIFTH), ConsortChair(ConsortVoice.BASSOON, ConsortInterval.MIN7))

    private fun cents(hz: Float, want: Float): Double = 1200.0 * ln(hz / want.toDouble()) / ln(2.0)

    /** The level of one frequency in a stretch of the render (a Hann-windowed single-bin transform), in dB. */
    private fun levelDb(s: Snip, hz: Double, fromSec: Float = 0.5f, seconds: Float = 0.5f): Double {
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

    private fun differ(a: Snip, b: Snip): Boolean {
        if (a.samples.size != b.samples.size) return true
        var sum = 0.0
        for (i in a.samples.indices) { val d = a.samples[i] - b.samples[i]; sum += d * d }
        return sqrt(sum / a.samples.size) > 1e-3
    }

    @Test
    fun `renders are byte identical across runs, alone and as a section`() {
        for (voice in ConsortVoice.entries) assertContentEquals(Consort.render(voice).samples, Consort.render(voice).samples, "$voice")
        val a = Consort.render(ConsortVoice.OBOE, mapOf("GESTURE" to Consort.gestureValue(ConsortGesture.TRILL)), trio, 1f, 3)
        val b = Consort.render(ConsortVoice.OBOE, mapOf("GESTURE" to Consort.gestureValue(ConsortGesture.TRILL)), trio, 1f, 3)
        assertContentEquals(a.samples, b.samples)
    }

    @Test
    fun `every voice is finite, bounded, free of DC and at the house loudness`() {
        for (voice in ConsortVoice.entries) for (chairs in listOf(emptyList(), trio)) {
            val s = Consort.render(voice, emptyMap(), chairs)
            var peak = 0f
            var mean = 0.0
            for (v in s.samples) { assertTrue(v.isFinite(), "$voice has a non-finite sample"); peak = maxOf(peak, abs(v)); mean += v }
            // Dsp.levelTo limits the peak to 0.99; scaling to it in Float can land a few ulps over (0.99000007 was read)
            assertTrue(peak in 0.01f..0.9901f, "$voice peak $peak")
            assertTrue(abs(mean / s.samples.size) < 0.005, "$voice mean ${mean / s.samples.size}")
            val loud = Loudness.of(s)
            assertTrue(loud >= 0.9f * Dsp.MELODIC_LOUDNESS_TARGET || peak >= 0.95f, "$voice loudness $loud, peak $peak")
        }
    }

    @Test
    fun `every pipe lands on its note across two octaves`() {
        for (voice in ConsortVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val s = Consort.render(voice, dry + ("TUNE" to tune))
            val p = Pitch.detect(s, fromSec = 0.5f) ?: error("$voice at TUNE $tune has no detectable pitch")
            val off = cents(p.hz, Consort.frequencyFor(voice, tune))
            assertTrue(abs(off) < 8.0, "$voice at TUNE $tune is $off cents off")
            assertTrue(p.confidence >= 0.8f, "$voice at TUNE $tune has pitch confidence ${p.confidence}")
        }
    }

    @Test
    fun `a hard hit cracks the flute to its octave and leaves the reeds on their note`() {
        val want = Consort.frequencyFor(ConsortVoice.FLUTE, 0.5f)
        for (velocity in listOf(0.3f, 0.7f)) {
            val p = Pitch.detect(Consort.render(ConsortVoice.FLUTE, dry, velocity = velocity), fromSec = 0.6f)!!
            assertTrue(abs(cents(p.hz, want)) < 15.0, "FLUTE at velocity $velocity is ${cents(p.hz, want)} cents from its note")
        }
        val hard = Pitch.detect(Consort.render(ConsortVoice.FLUTE, dry, velocity = 1f), fromSec = 0.6f)!!
        assertTrue(abs(cents(hard.hz, 2 * want)) < 20.0, "a hard FLUTE is ${cents(hard.hz, 2 * want)} cents from the octave")
        for (voice in ConsortVoice.entries - ConsortVoice.FLUTE) {
            val p = Pitch.detect(Consort.render(voice, dry, velocity = 1f), fromSec = 0.6f)!!
            assertTrue(abs(cents(p.hz, Consort.frequencyFor(voice, 0.5f))) < 20.0, "a hard $voice left its note: ${p.hz} Hz")
        }
    }

    @Test
    fun `velocity is a change of tone, brighter the harder the hit`() {
        for (voice in ConsortVoice.entries) {
            val soft = Classifier.classify(Consort.render(voice, dry, velocity = 0.3f)).features.centroidHz
            val mid = Classifier.classify(Consort.render(voice, dry, velocity = 0.7f)).features.centroidHz
            assertTrue(mid > soft * 1.05f, "$voice centroid soft $soft Hz, medium $mid Hz")
        }
    }

    @Test
    fun `the cylinders keep their odd harmonics and the cones play the full series`() {
        for (voice in listOf(ConsortVoice.CLARINET, ConsortVoice.DUDUK)) {
            val s = Consort.render(voice, dry)
            val hz = Consort.frequencyFor(voice, 0.5f).toDouble()
            val second = levelDb(s, 2 * hz) - levelDb(s, hz)
            assertTrue(second < -20.0, "$voice's second harmonic is only $second dB under its first")
        }
        for (voice in listOf(ConsortVoice.SAX, ConsortVoice.OBOE)) {
            val s = Consort.render(voice, dry)
            val hz = Consort.frequencyFor(voice, 0.5f).toDouble()
            val second = levelDb(s, 2 * hz) - levelDb(s, hz)
            assertTrue(second > -15.0, "$voice's second harmonic is $second dB under its first")
        }
    }

    @Test
    fun `every macro changes the sound, and an unknown or non-finite one does not`() {
        val base = Consort.render(ConsortVoice.OBOE, emptyMap(), trio)
        for (spec in Consort.macrosFor(ConsortVoice.OBOE)) {
            val other = if (spec.default < 0.5f) 1f else 0f
            assertTrue(differ(base, Consort.render(ConsortVoice.OBOE, mapOf(spec.name to other), trio)), "${spec.name} did not change the sound")
        }
        assertContentEquals(base.samples, Consort.render(ConsortVoice.OBOE, mapOf("NOPE" to 1f), trio).samples)
        assertContentEquals(base.samples, Consort.render(ConsortVoice.OBOE, mapOf("BREATH" to Float.NaN), trio).samples)
        assertContentEquals(base.samples, Consort.render(ConsortVoice.OBOE, emptyMap(), trio, velocity = Float.NaN).samples)
    }

    @Test
    fun `MOTION moves every gesture, and each gesture differs from a plain note`() {
        val plain = Consort.render(ConsortVoice.SAX, mapOf("HOLD" to 0.6f))
        for (gesture in ConsortGesture.entries) {
            val g = Consort.gestureValue(gesture)
            assertEquals(gesture, Consort.gestureFor(g))
            val low = Consort.render(ConsortVoice.SAX, mapOf("HOLD" to 0.6f, "GESTURE" to g, "MOTION" to 0f))
            val high = Consort.render(ConsortVoice.SAX, mapOf("HOLD" to 0.6f, "GESTURE" to g, "MOTION" to 1f))
            assertTrue(differ(low, high), "MOTION does nothing to $gesture")
            if (gesture != ConsortGesture.PLAIN) assertTrue(differ(plain, Consort.render(ConsortVoice.SAX, mapOf("HOLD" to 0.6f, "GESTURE" to g))), "$gesture is a plain note")
        }
    }

    @Test
    fun `the section is heard, and BLEND brings its fifth forward`() {
        val fifth = listOf(ConsortChair(ConsortVoice.CLARINET, ConsortInterval.FIFTH))
        val root = Consort.frequencyFor(ConsortVoice.OBOE, 0.5f).toDouble()
        val fifthHz = root * Math.pow(2.0, 7.0 / 12.0)
        val alone = Consort.render(ConsortVoice.OBOE, dry)
        val tucked = Consort.render(ConsortVoice.OBOE, dry + ("BLEND" to 0f), fifth)
        val level = Consort.render(ConsortVoice.OBOE, dry + ("BLEND" to 1f), fifth)
        fun fifthOverRoot(s: Snip) = levelDb(s, fifthHz) - levelDb(s, root)
        assertTrue(fifthOverRoot(tucked) > fifthOverRoot(alone) + 10.0, "the fifth is not there: alone ${fifthOverRoot(alone)} dB, tucked ${fifthOverRoot(tucked)} dB")
        assertTrue(fifthOverRoot(level) > fifthOverRoot(tucked) + 8.0, "BLEND did not bring the fifth forward: ${fifthOverRoot(tucked)} to ${fifthOverRoot(level)} dB")
        assertFailsWith<IllegalArgumentException> { Consort.render(ConsortVoice.OBOE, emptyMap(), trio + trio) }
    }

    @Test
    fun `four takes are four different performances of the same note`() {
        val takes = (0..3).map { Consort.render(ConsortVoice.SAX, mapOf("ROOM" to 0f, "HOLD" to 0.6f), take = it) }
        for (i in 0..3) for (j in i + 1..3) assertTrue(differ(takes[i], takes[j]), "takes $i and $j are the same")
        val want = Consort.frequencyFor(ConsortVoice.SAX, 0.5f)
        for ((i, s) in takes.withIndex()) {
            val p = Pitch.detect(s, fromSec = 0.7f)!!
            assertTrue(abs(cents(p.hz, want)) < 15.0, "take $i held ${cents(p.hz, want)} cents from the note")
        }
        // the leaning take comes up into the note: its start is under its own held pitch
        val start = Pitch.detect(takes[3], fromSec = 0.07f, windowSec = 0.08f)!!
        val held = Pitch.detect(takes[3], fromSec = 0.7f)!!
        assertTrue(cents(start.hz, held.hz) < -20.0, "the leaning take starts ${cents(start.hz, held.hz)} cents from its held pitch")
    }

    @Test
    fun `ROOM adds a tail and ROOM 0 adds nothing`() {
        val none = Consort.render(ConsortVoice.OBOE, mapOf("ROOM" to 0f, "HOLD" to 0.3f))
        val some = Consort.render(ConsortVoice.OBOE, mapOf("ROOM" to 0.4f, "HOLD" to 0.3f))
        assertTrue(some.frameCount > none.frameCount)
        // 0.15 s after the dry render ends the room is still ringing
        val from = none.frameCount + (0.15f * Dsp.RATE).toInt()
        var sum = 0.0
        for (i in from until from + 4410) sum += some.samples[i].toDouble() * some.samples[i]
        assertTrue(sqrt(sum / 4410) > 1e-4, "no tail: ${sqrt(sum / 4410)}")
    }

    @Test
    fun `the breath stops and the pipe does not go on blowing`() {
        // The peak is taken over the held note; the check is over an independent window after the release.
        val s = Consort.render(ConsortVoice.SAX, dry + ("HOLD" to 0.3f))
        val blown = Consort.holdSeconds(0.3f)
        var held = 0f
        for (i in (0.3f * Dsp.RATE).toInt() until (blown * Dsp.RATE).toInt()) held = maxOf(held, abs(s.samples[i]))
        var after = 0f
        for (i in ((blown + 0.25f) * Dsp.RATE).toInt() until s.samples.size) after = maxOf(after, abs(s.samples[i]))
        assertTrue(after < 0.05f * held, "the pipe is still sounding 0.25 s after the breath: $after against $held")
    }

    @Test
    fun `the filed class agrees with the render's length to the frame and no render reads as a drum`() {
        var lastUnder: Float? = null
        var firstOver: Float? = null
        for (step in 0..100) {
            val hold = step / 100f
            val m = mapOf("HOLD" to hold, "ROOM" to 0f)
            val seconds = Consort.renderFrames(Consort.settled(m, ConsortVoice.OBOE)).toFloat() / Dsp.RATE
            val filed = Consort.drumClassFor(ConsortVoice.OBOE, m)
            assertEquals(seconds > Consort.LOOP_THRESHOLD_SECONDS, filed == DrumClass.LOOP, "HOLD $hold renders $seconds s and files $filed")
            if (filed == DrumClass.LOOP) { if (firstOver == null) firstOver = hold } else lastUnder = hold
        }
        for (hold in listOf(lastUnder!!, firstOver!!)) {
            val m = mapOf("HOLD" to hold, "ROOM" to 0f)
            val s = Consort.render(ConsortVoice.OBOE, m)
            assertEquals(Consort.renderFrames(Consort.settled(m, ConsortVoice.OBOE)), s.frameCount)
            assertEquals(Consort.drumClassFor(ConsortVoice.OBOE, m), Classifier.classify(s).drumClass, "HOLD $hold")
        }
        // the short notes are where a blown pipe is most like a drum: mostly attack and breath
        for (voice in ConsortVoice.entries) for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (velocity in listOf(0.3f, 0.7f, 1f)) for (hold in listOf(0f, 0.2f)) {
            val m = mapOf("TUNE" to tune, "HOLD" to hold)
            assertEquals(Consort.drumClassFor(voice, m), Classifier.classify(Consort.render(voice, m, velocity = velocity)).drumClass,
                "$voice TUNE $tune velocity $velocity HOLD $hold")
        }
        assertTrue(Consort.isLoop(1f) && Consort.isLoop(Consort.LOOP_FROM), "HOLD's top step sustains")
        assertFalse(Consort.isLoop(Math.nextDown(Consort.LOOP_FROM)), "every HOLD under the step is a finite note")
        assertTrue(ConsortPresets.all().none { Consort.isLoop(it.macros.getValue("HOLD")) }, "no factory recipe sustains")
    }

    @Test
    fun `a patch survives JSON with its section, velocity and take, and a lead alone writes none of them`() {
        val patch = ConsortPatch("Reed trio", ConsortVoice.OBOE, mapOf("HOLD" to 0.2f, "BLEND" to 0.8f), trio, 0.9f, 2)
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertContentEquals(patch.render().samples, back.render().samples)
        val alone = ConsortPatch("Alone", ConsortVoice.DUDUK, emptyMap())
        val keys = alone.toJsonValue().entries.keys
        assertEquals(setOf("engine", "version", "name", "voice", "macros"), keys)
        assertEquals(alone, ConsortPatch.fromJsonText(alone.toJsonText()))
        assertFailsWith<IllegalArgumentException> { ConsortPatch("Too many", ConsortVoice.OBOE, emptyMap(), trio + trio) }
        assertFailsWith<IllegalArgumentException> { ConsortPatch("Bad knob", ConsortVoice.OBOE, mapOf("NOPE" to 0.5f)) }
    }

    @Test
    fun `velocity through the house velocity path changes the render`() {
        val patch = ConsortPatch("Lead", ConsortVoice.SAX, emptyMap())
        assertNotEquals(patch.copy(velocity = 0.3f).render().samples.toList(), patch.copy(velocity = 1f).render().samples.toList())
    }

    @Test
    fun `the recipes are complete, distinct, plainly named and filed in the factory roster`() {
        val all = ConsortPresets.all()
        for (voice in ConsortVoice.entries) {
            val mine = ConsortPresets.forVoice(voice)
            assertTrue(mine.size >= 2 && mine.all { it.voice == voice }, "$voice has ${mine.size} recipes")
            assertTrue(mine.any { it.chairs.isEmpty() } && mine.any { it.chairs.isNotEmpty() }, "$voice needs the lead alone and a section")
        }
        assertEquals(all.size, all.map { it.name }.toSet().size, "recipe names repeat")
        assertEquals(all.size, all.map { it.copy(name = "same") }.toSet().size, "two recipes are the same sound")
        for (p in all) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), "${p.name} is on the blocklist")
            assertEquals(Consort.defaults(p.voice).keys, p.macros.keys, "${p.name} does not carry every macro")
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        }
        // Heard and kept in round K3 (2026-11-20): the factory roster serves them.
        assertEquals(all, Presets.all().filter { it.engine == ConsortPatch.ENGINE })
        assertEquals(ConsortPresets.forVoice(ConsortVoice.OBOE), Presets.forVoice(ConsortPatch.ENGINE, ConsortVoice.OBOE.name))
    }

    @Test
    fun `the kit is a pentatonic row of FLUTE and eight recipes, dry, each filed as the engine files it`() {
        val kit = SynthKits.consort()
        assertEquals(16, kit.size)
        val steps = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        var sections = 0
        for ((i, pad) in kit.withIndex()) {
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val patch = recipe.patch as? ConsortPatch ?: error("pad ${i + 1} is not a CONSORT patch")
            assertEquals(null, recipe.fx, "pad ${i + 1} lands dry: the room is the engine's")
            assertEquals(Consort.drumClassFor(patch.voice, patch.macros), pad.drumClass, "pad ${i + 1} is filed wrongly")
            assertEquals(pad.drumClass, Classifier.classify(pad.snip).drumClass, "pad ${i + 1} does not read as it is filed")
            assertContentEquals(pad.snip.samples, patch.render().samples, "pad ${i + 1}'s recipe does not render the pad")
            if (patch.chairs.isNotEmpty()) sections++
            if (i < 8) {
                assertEquals(ConsortVoice.FLUTE, patch.voice)
                assertEquals(DrumClass.PERC, pad.drumClass, "note pad ${i + 1} should be a struck note")
                val want = Keys.midiHz(Consort.rootMidi(ConsortVoice.FLUTE) + steps[i])
                val p = Pitch.detect(pad.snip, fromSec = 0.2f, windowSec = 0.2f) ?: error("pad ${i + 1} has no pitch")
                assertTrue(abs(cents(p.hz, want)) < 25.0, "pad ${i + 1} plays ${p.hz} Hz, ${cents(p.hz, want)} cents from $want")
            }
        }
        assertEquals(5, sections, "the kit carries five sections")
    }

    @Test
    fun `scramble stays in bounds and renders`() {
        val random = Random(7)
        repeat(6) {
            val voice = ConsortVoice.entries[it]
            val m = Consort.scramble(voice, random, temperature = 1f) + ("HOLD" to 0.1f)
            for ((k, v) in m) assertTrue(v in 0f..1f, "$k rolled $v")
            assertTrue(Consort.render(voice, m).samples.all { s -> s.isFinite() })
        }
    }
}
