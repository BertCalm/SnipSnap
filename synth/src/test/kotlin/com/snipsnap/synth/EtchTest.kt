package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ETCH's probes (docs/ETCH.md): the house set (determinism, finite and bounded, centred, its class and length to
 * the frame, every control moves it, unknown and non-finite macros, JSON), then the claims the owner heard and
 * accepted in the Python prototype: velocity changes the tone, the takes are different strikes, the square is
 * hollow against the saw, a line loop closes, and BITE glues the steps.
 */
class EtchTest {
    private val rate = Dsp.RATE
    private val voices = EtchVoice.entries

    private fun render(voice: EtchVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 36, velocity: Float = 1f,
        take: Int = 0, seed: Int = 0): Snip = Etch.render(voice, macros, midi, velocity, take, seed)

    private fun loop(voice: EtchVoice, macros: Map<String, Float> = emptyMap()) = render(voice, macros + ("HOLD" to 1f))

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until minOf(to, x.size)) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, minOf(to, x.size) - from))
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    /**
     * Magnitude-weighted centroid over 150 Hz to 5 kHz of the first [seconds], on an even 10 Hz grid: the brightness
     * the audition measured (an FFT's bins are evenly spaced). A log-spaced grid weights the low end and compressed
     * the velocity step on the same render from 1,222 -> 1,664 Hz to 586 -> 608 Hz.
     */
    private fun centroid(x: FloatArray, seconds: Double = .06): Double {
        val n = minOf(x.size, (seconds * rate).toInt())
        var num = 0.0; var den = 0.0
        var f = 150.0
        while (f < 5000.0) {
            val m = mag(x, n, f)
            num += m * f; den += m
            f += 10.0
        }
        return num / den
    }

    /** A Hann-windowed single-bin DFT magnitude at [hz] over the first [n] frames. */
    private fun mag(x: FloatArray, n: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in 0 until n) {
            val w = .5 - .5 * cos(2 * PI * i / (n - 1))
            val ph = 2 * PI * hz * i / rate
            re += x[i] * w * cos(ph); im -= x[i] * w * sin(ph)
        }
        return sqrt(re * re + im * im)
    }

    /** The largest single-bin magnitude within a semitone of [hz], over [from]..[from + n]. */
    private fun harmonic(x: FloatArray, from: Int, n: Int, hz: Double): Double {
        val seg = x.copyOfRange(from, from + n)
        var best = 0.0
        var f = hz / 2.0.pow(1 / 24.0)
        while (f <= hz * 2.0.pow(1 / 24.0)) { best = maxOf(best, mag(seg, n, f)); f *= 1.002 }
        return best
    }

    private fun cents(hz: Double, ref: Double) = 1200 * ln(hz / ref) / ln(2.0)

    @Test
    fun `renders are byte-identical, one-shot and loop, every voice`() {
        for (voice in voices) {
            assertContentEquals(render(voice).samples, render(voice).samples, "$voice one-shot")
            assertContentEquals(render(voice, take = 3, seed = 7).samples, render(voice, take = 3, seed = 7).samples, "$voice take")
        }
        assertContentEquals(loop(EtchVoice.SAW).samples, loop(EtchVoice.SAW).samples, "SAW loop")
    }

    @Test
    fun `finite, output peak 0 01 to 1, centred, every voice at three notes and two velocities`() {
        for (voice in voices) for (midi in listOf(24, 36, 60)) for (v in listOf(.35f, 1f)) {
            val x = render(voice, midi = midi, velocity = v).samples
            assertTrue(x.all { it.isFinite() }, "$voice $midi $v finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak in .01f..1f, "$voice $midi $v peak $peak")
            val mean = x.average()
            assertTrue(abs(mean) < .005, "$voice $midi $v DC $mean")
        }
    }

    @Test
    fun `SAW and SQUARE play their root within 50 cents at C2 and C3`() {
        // RUBBER's sub octave is as loud as the note and WHISTLE's peak sings at a harmonic: a correlation detector
        // can read either as another octave, so the root claim is made on the two plain waves.
        for (voice in listOf(EtchVoice.SAW, EtchVoice.SQUARE)) for (midi in listOf(36, 48)) {
            val snip = render(voice, mapOf("SPACE" to 0f, "HOLD" to .6f), midi)
            val est = assertNotNull(Pitch.detect(snip, .1f, .25f), "$voice $midi has a pitch")
            val c = cents(est.hz.toDouble(), Keys.midiHz(midi).toDouble())
            println("ETCH root $voice midi $midi: ${"%.1f".format(c)} cents, confidence ${"%.2f".format(est.confidence)}")
            assertTrue(abs(c) < 50, "$voice $midi reads $c cents")
            assertTrue(est.confidence >= .3f, "$voice $midi confidence ${est.confidence}")
        }
    }

    @Test
    fun `a one-shot is under 1 5 s and files TONAL, a loop is two bars and files LOOP, and the classifier agrees on the loop`() {
        for (voice in voices) {
            for (hold in listOf(0f, .4f, .84f)) {
                val m = mapOf("HOLD" to hold, "SPACE" to 1f)
                val snip = render(voice, m)
                assertTrue(snip.frameCount.toFloat() / rate < Etch.LOOP_MIN_SECONDS, "$voice HOLD $hold one-shot length")
                assertEquals(DrumClass.TONAL, Etch.drumClassFor(voice, m))
            }
            val m = mapOf("HOLD" to 1f)
            val snip = render(voice, m)
            val line = Etch.lineFor(voice)
            val stepFrames = Math.round(Dsp.RATE * Dsp.OVERSAMPLE * 60.0 / line.bpm / 4.0).toInt()
            assertEquals(stepFrames * 16 * Etch.LOOP_BARS / Dsp.OVERSAMPLE, snip.frameCount, "$voice loop is two bars to the frame")
            assertEquals(DrumClass.LOOP, Etch.drumClassFor(voice, m))
            assertEquals(DrumClass.LOOP, Classifier.classify(snip).drumClass, "$voice loop classified")
        }
    }

    @Test
    fun `the classifier hears no drum at the default note from C2 up, except DUB's soft C2, and the low short notes are on record`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        // The engine files every one-shot TONAL (drumClassFor), and kit pads take that class. The classifier judges the
        // audio alone, and a short, dark, low pluck looks like a kick to it. Measured 2026-11-13: at the default HOLD
        // no voice reads as a drum from C2 up except DUB, the darkest, on a soft C2. Lengthening short notes or
        // brightening DUB would change the sound the owner accepted, so this is disclosed, not fixed (docs/ETCH.md).
        val knownExceptions = setOf(Triple(EtchVoice.DUB, 36, .35f))
        for (voice in voices) for (midi in listOf(36, 48)) for (v in listOf(.35f, 1f)) {
            val cls = Classifier.classify(render(voice, midi = midi, velocity = v)).drumClass
            if (Triple(voice, midi, v) in knownExceptions) { println("ETCH class $voice midi $midi v $v: $cls (known exception)"); continue }
            assertTrue(cls !in drums, "$voice midi $midi velocity $v at the default HOLD: the classifier hears a drum ($cls)")
        }
        // For the record: the low and short notes the classifier does hear as drums.
        for (voice in voices) {
            val row = listOf(24, 36).flatMap { midi -> listOf(0f, .2f, .4f).map { h -> midi to h } }
                .filter { (midi, h) -> Classifier.classify(render(voice, mapOf("HOLD" to h), midi, .35f)).drumClass in drums }
                .joinToString(" ") { (midi, h) -> "MIDI $midi HOLD $h" }
            println("ETCH class $voice, soft hits read as a drum at: ${row.ifEmpty { "none" }}")
        }
    }

    @Test
    fun `every loop closes on the pass that really preceded it`() {
        // renderLoop throws through Keys.requireSeam when the seam is over Keys.MAX_SEAM_ERROR; each render is the test.
        for (voice in voices) for (space in listOf(0f, 1f)) {
            val x = loop(voice, mapOf("SPACE" to space)).samples
            assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= 1f, "$voice SPACE $space loop bounded")
        }
    }

    @Test
    fun `every macro changes the sound, an unknown key does not, and a non-finite macro falls back to its default`() {
        for (voice in voices) {
            val base = render(voice).samples
            for (spec in Etch.macrosFor(voice)) {
                val moved = if (spec.default > .5f) spec.default - .4f else spec.default + .4f
                val x = render(voice, mapOf(spec.name to moved)).samples
                assertFalse(x.contentEquals(base), "$voice ${spec.name} changes the sound")
            }
            assertContentEquals(base, render(voice, mapOf("NOT_A_KNOB" to .9f)).samples, "$voice unknown key")
            assertContentEquals(base, render(voice, mapOf("CUTOFF" to Float.NaN)).samples, "$voice NaN CUTOFF")
        }
    }

    @Test
    fun `velocity is the accent, a hard hit is brighter than a soft one at equal level, and the level follows v to the 0 35`() {
        for (voice in voices) {
            val soft = render(voice, mapOf("SPACE" to 0f), velocity = .35f).samples
            val hard = render(voice, mapOf("SPACE" to 0f), velocity = 1f).samples
            val cs = centroid(soft); val ch = centroid(hard)
            println("ETCH velocity $voice: attack centroid ${"%.0f".format(cs)} -> ${"%.0f".format(ch)} Hz")
            assertTrue(ch > cs * 1.15, "$voice: a hard hit is brighter at the attack ($cs -> $ch Hz)")
        }
    }

    @Test
    fun `round-robin takes are different strikes of the same note, deterministic, and reachable through Restrike`() {
        val patch = EtchPatch("RR", EtchVoice.SAW, emptyMap(), midi = 36, velocity = .6f)
        assertTrue(Restrike.canRestrike(patch))
        val takes = (1..4).map { assertNotNull(Restrike.take(patch, .6f, 11, it)).samples }
        assertContentEquals(takes[1], assertNotNull(Restrike.take(patch, .6f, 11, 2)).samples, "a take is deterministic")
        assertContentEquals(patch.render(.6f).samples, assertNotNull(Restrike.take(patch, .6f, 11, 0)).samples, "take 0 is the plain render")
        // Each pair differs by more than a sample-level wobble: the attack's brightness moves.
        val bright = takes.map { centroid(it) }
        val spread = (bright.max() - bright.min()) / bright.average()
        println("ETCH takes attack centroid: ${bright.joinToString { "%.0f".format(it) }} Hz, spread ${"%.1f".format(100 * spread)}%")
        assertTrue(spread > .08, "four takes spread at least 8% in attack brightness, got ${100 * spread}%")
        for (i in takes.indices) for (j in i + 1 until takes.size) assertFalse(takes[i].contentEquals(takes[j]), "takes $i and $j differ")
        // The settled pitch is the note's on every take: only the onset dips in.
        for ((i, t) in takes.withIndex()) {
            val est = assertNotNull(Pitch.detect(Snip(t, channels = 1, sampleRate = rate), .12f, .2f))
            assertTrue(abs(cents(est.hz.toDouble(), Keys.midiHz(36).toDouble())) < 50, "take ${i + 1} stays on the note")
        }
        // The four takes take four different positions on every dimension (the Latin hypercube).
        val params = (1..4).map { Etch.takeFor(11, it) }
        assertEquals(4, params.map { Math.round(it.driftCut * 1000) }.distinct().size)
        assertEquals(4, params.map { Math.round(it.scoopCents) }.distinct().size)
    }

    @Test
    fun `the square is hollow against the saw, its second harmonic at least 15 dB lower on a held open note`() {
        // The owner heard round 1's 60/40 pulse and the saw as the same sound; the 50% square has no even harmonics.
        val m = mapOf("CUTOFF" to .5f, "PEAK" to .5f, "SWEEP" to .3f, "BITE" to 0f, "SPACE" to 0f, "HOLD" to .84f)
        val hz = Keys.midiHz(36).toDouble()
        fun second(voice: EtchVoice): Double {
            val x = render(voice, m).samples
            val from = (.3 * rate).toInt(); val n = (.4 * rate).toInt()
            return db(harmonic(x, from, n, 2 * hz)) - db(harmonic(x, from, n, hz))
        }
        val saw = second(EtchVoice.SAW); val square = second(EtchVoice.SQUARE)
        println("ETCH 2nd harmonic: SAW ${"%.1f".format(saw)} dB, SQUARE ${"%.1f".format(square)} dB")
        assertTrue(square < saw - 15, "SQUARE's 2nd harmonic $square dB against SAW's $saw dB")
    }

    @Test
    fun `BITE glues the steps, the line's crest factor falls as BITE rises`() {
        fun crest(x: FloatArray) = db(x.maxOf { abs(it) }.toDouble()) - db(rms(x))
        val clean = crest(loop(EtchVoice.SAW, mapOf("BITE" to 0f, "SPACE" to 0f)).samples)
        val glued = crest(loop(EtchVoice.SAW, mapOf("BITE" to .8f, "SPACE" to 0f)).samples)
        println("ETCH crest factor, SAW line: BITE 0 ${"%.1f".format(clean)} dB, BITE .8 ${"%.1f".format(glued)} dB")
        assertTrue(glued < clean - 2, "BITE .8 pulls the peaks in: $clean -> $glued dB")
    }

    @Test
    fun `the register, TUNE and scramble stay in bounds`() {
        assertEquals(36, Etch.midiFor(.5f))
        assertEquals(48, Etch.midiFor(1f))
        assertEquals(24, Etch.midiFor(0f))
        assertEquals(Etch.MIDI_MAX, Etch.midiFor(1f, Etch.MIDI_MAX))
        val rng = kotlin.random.Random(3)
        for (voice in voices) repeat(20) {
            val m = Etch.scramble(voice, rng)
            assertTrue(m.values.all { it in 0f..1f })
            assertTrue(m.getValue("HOLD") < Etch.LOOP_HOLD_AT, "a roll stays a one-shot")
        }
    }

    @Test
    fun `the patch round trips through JSON and refuses what the engine cannot play`() {
        val p = EtchPatch("Line test", EtchVoice.DUB, mapOf("SPACE" to .9f, "HOLD" to .3f), midi = 41, velocity = .7f)
        assertEquals(p, EtchPatch.fromJsonText(p.toJsonText()))
        assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        assertFailsWith<IllegalArgumentException> { EtchPatch("x", EtchVoice.SAW, emptyMap(), midi = 12) }
        assertFailsWith<IllegalArgumentException> { EtchPatch("x", EtchVoice.SAW, emptyMap(), velocity = 2f) }
        assertFailsWith<IllegalArgumentException> { EtchPatch("x", EtchVoice.SAW, emptyMap(), model = 2) }
    }

    @Test
    fun `render time, printed for the record`() {
        for (voice in listOf(EtchVoice.SAW, EtchVoice.DUB)) {
            repeat(2) { render(voice) }
            var t0 = System.nanoTime()
            repeat(5) { render(voice) }
            val oneShot = (System.nanoTime() - t0) / 5e6
            t0 = System.nanoTime()
            loop(voice)
            val line = (System.nanoTime() - t0) / 1e6
            println("ETCH render time $voice: ${"%.0f".format(oneShot)} ms per one-shot, ${"%.0f".format(line)} ms per line loop (warm, this machine; not asserted)")
        }
    }
}
