package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round chamber-voices-1 (ChamberCandidates). Each keeps the struck quartet's passive
 * room and changes what it holds: TIMBER (a marimba bar over note-tuned wooden resonators), MASS (a big skin in a stone
 * hall), ALLOY (a steel pan's tuned shell in a metal room), GLAZE (a singing bowl's split mode pairs in a glass room)
 * and BOUNCE, the spare (a hammered string whose hammer bounces). CHAMBER is finite: no held keys. Nothing here is
 * heard. The kept renders are pinned below against the build before this round.
 */
class ChamberCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = ChamberVoice.entries.filter { it !in Chamber.KEPT_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceIn(0, x.size - 1)
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: ChamberVoice, midi: Int = 60, velocity: Float = Chamber.NORMAL_VELOCITY) =
        renders.getOrPut(Triple(voice, midi, velocity)) { Chamber.render(voice, emptyMap(), midi, velocity).samples }
    private fun loudnessDb(x: FloatArray) = 20 * log10(Loudness.of(x, 1, Dsp.RATE).toDouble())

    /** Power between 2 and 8 kHz over the power within 3 % of the root, dB, over the whole note. */
    private fun harshBandDb(x: FloatArray, midi: Int): Double {
        val size = 262_144
        val spectrum = Fft.magnitudeSpectrum(x.copyOf(size), size)
        var band = 0.0
        var fundamental = 0.0
        for (bin in 1 until size / 2) {
            val f = bin * Dsp.RATE.toDouble() / size
            val power = spectrum[bin].toDouble() * spectrum[bin]
            if (f >= 2_000.0 && f < 8_000.0) band += power
            if (abs(f - hz(midi)) <= .03 * hz(midi)) fundamental += power
        }
        return 10.0 * log10(band.coerceAtLeast(1e-30) / fundamental)
    }

    @Test
    fun `candidates are appended, register model only and offered nowhere`() {
        assertEquals(listOf(ChamberVoice.TIMBER, ChamberVoice.MASS, ChamberVoice.ALLOY, ChamberVoice.GLAZE, ChamberVoice.BOUNCE), candidates)
        assertEquals(listOf(ChamberVoice.HYBRID, ChamberVoice.AQUARIUM), Chamber.KEPT_VOICES)
        assertEquals(Chamber.KEPT_VOICES, ChamberVoice.entries.take(2))
        assertTrue((ChamberPresets.all() + ChamberPresets.original()).all { it.voice in Chamber.KEPT_VOICES }, "a candidate has a preset")
        assertTrue(ChamberKits.patches().all { it.voice in Chamber.KEPT_VOICES }, "a candidate is in the kit")
        for (voice in candidates) {
            assertTrue(ChamberPresets.forVoice(voice).isEmpty(), "$voice has presets")
            assertEquals(Chamber.macrosFor(ChamberVoice.HYBRID).map { it.name }, Chamber.macrosFor(voice).map { it.name })
            val saved = ChamberPatch("Candidate", voice, Chamber.defaults(voice) + ("STRIKE" to .3f), midi = 55, velocity = .6f)
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()), "$voice does not survive saving")
            for (model in 1 until Chamber.REGISTER_MODEL) assertFailsWith<IllegalArgumentException> { Chamber.render(voice, model = model) }
        }
    }

    @Test
    fun `candidates render finite, bounded, clean at both ends and the same twice`() {
        for (voice in candidates) {
            for (midi in listOf(36, 84)) for (velocity in listOf(.3f, 1f)) {
                val x = render(voice, midi, velocity)
                val peak = x.maxOf { abs(it) }
                assertTrue(x.all { it.isFinite() } && peak in .01f..1f, "$voice MIDI$midi v$velocity peak $peak")
                assertTrue(abs(x.average()) < .005, "$voice MIDI$midi v$velocity has an offset")
                assertTrue(abs(x[0]) < 5e-3f, "$voice MIDI$midi v$velocity starts open: ${x[0]}")
                assertTrue((0 until 11).maxOf { abs(x[it]) } < .05f * peak, "$voice MIDI$midi v$velocity starts with a click")
                assertTrue(abs(x.last()) < 1e-3f, "$voice MIDI$midi v$velocity ends open")
                val end = x.size / 44100.0
                assertTrue(rms(x, end - .02, end) < .05 * rms(x, .05, .5), "$voice MIDI$midi v$velocity ends abruptly")
            }
            assertContentEquals(render(voice), Chamber.render(voice).samples, "$voice is not deterministic")
        }
    }

    @Test
    fun `candidates sound the played note across the range`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(36, 42, 48, 60, 72, 84)) {
            val x = render(voice, midi)
            // A low note needs a longer look: a one-second window's lobe is a hundred cents wide at C2.
            val seconds = if (midi <= 48) 2.0 else 1.0
            val root = M.rootCents(x, hz(midi), .1, seconds)
            val share = M.rootShare(x, hz(midi), .1, seconds)
            println("CHAMBER $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        val close = ArrayList<String>()
        for (midi in listOf(36, 52, 68, 84)) {
            val shapes = ChamberVoice.entries.associateWith { M.bands(render(it, midi)) }
            for (a in candidates) for (b in ChamberVoice.entries) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("CHAMBER third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                if (d < M.DISTINCT_DB) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
    }

    @Test
    fun `soft, medium and hard are obviously different - in tone and in level`() {
        for (voice in candidates) for (midi in listOf(48, 60)) {
            val notes = TOUCH.map { render(voice, midi, it) }
            val shapes = notes.map { M.bands(it) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            val span = M.distance(shapes[0], shapes[2])
            val level = notes.map { loudnessDb(it) }
            println("CHAMBER $voice MIDI$midi touch: tone steps %.1f, %.1f dB, soft against hard %.1f dB; level soft %.1f, medium %.1f dB against hard"
                .format(steps[0], steps[1], span, level[0] - level[2], level[1] - level[2]))
            assertTrue(steps.all { it >= TOUCH_STEP_DB }, "$voice MIDI$midi: a touch step barely changes the tone: $steps")
            assertTrue(span >= TOUCH_SPAN_DB, "$voice MIDI$midi: soft and hard are too alike: $span dB")
            assertTrue(level[0] - level[2] <= -SOFT_QUIETER_DB, "$voice MIDI$midi: soft is not clearly quieter: ${level[0] - level[2]} dB")
            assertTrue(level[0] < level[1] && level[1] < level[2], "$voice MIDI$midi: level does not follow touch: $level")
        }
    }

    @Test
    fun `the top of the range stays no harsher than the brightest kept room`() {
        for (velocity in listOf(Chamber.NORMAL_VELOCITY, 1f)) {
            val ceiling = Chamber.KEPT_VOICES.maxOf { harshBandDb(render(it, 84, velocity), 84) } + HARSH_MARGIN_DB
            for (voice in candidates) {
                val band = harshBandDb(render(voice, 84, velocity), 84)
                println("CHAMBER $voice MIDI84 v$velocity 2-8 kHz against the root %.1f dB (ceiling %.1f)".format(band, ceiling))
                assertTrue(band < ceiling, "$voice MIDI84 v$velocity 2-8 kHz is $band dB against the root")
            }
        }
    }

    @Test
    fun `each candidate does what its room says`() {
        val f = hz(60)
        val quiet = Chamber.ProbeOptions(normalize = true, recordDiagnostics = false, recordStems = false)
        // TIMBER's resonators and MASS's hall live in the room: take the room away and the note changes.
        for (voice in listOf(ChamberVoice.TIMBER, ChamberVoice.MASS)) {
            val on = Chamber.probe(voice, midi = 60, options = quiet).samples
            val off = Chamber.probe(voice, midi = 60, options = quiet.copy(roomEnabled = false)).samples
            val d = M.distance(M.bands(on), M.bands(off))
            println("CHAMBER $voice with and without its room: %.1f dB".format(d))
            assertTrue(d > 2.5, "$voice's room does nothing")
        }
        val pan = render(ChamberVoice.ALLOY)
        val octave = 20 * log10(M.component(pan, 2 * f, .05, .5) / M.component(pan, f, .05, .5))
        val twelfth = 20 * log10(M.component(pan, 3 * f, .05, .5) / M.component(pan, f, .05, .5))
        println("CHAMBER ALLOY octave %.1f dB, twelfth %.1f dB against the root".format(octave, twelfth))
        assertTrue(octave > -14 && twelfth > -26, "ALLOY's tone field is not tuned")
        val bowl = wobble(render(ChamberVoice.GLAZE), f)
        val bar = wobble(render(ChamberVoice.TIMBER), f)
        println("CHAMBER GLAZE root wavers %.2f dB about its decay (TIMBER %.2f)".format(bowl, bar))
        assertTrue(bowl > 2.5 && bowl > 2 * bar, "GLAZE's mode pairs do not beat")
        // BOUNCE: the hammer's return is a second rise in the first 40 ms.
        val string = render(ChamberVoice.BOUNCE)
        println("CHAMBER BOUNCE 24-32 ms %.3f, 32-40 ms %.3f".format(rms(string, .024, .032), rms(string, .032, .040)))
        assertTrue(rms(string, .032, .040) > 1.2 * rms(string, .024, .032), "BOUNCE's hammer does not come back")
    }

    /** RMS deviation, dB, of the root's 100 ms amplitude from a straight-line (exponential) decay, .2 to 3 s. */
    private fun wobble(x: FloatArray, f: Double): Double {
        val t = ArrayList<Double>()
        val a = ArrayList<Double>()
        var s = .2
        while (s + .1 <= 3.0) { t += s; a += 20 * log10(M.component(x, f, s, .1) + 1e-12); s += .05 }
        val mt = t.average()
        val ma = a.average()
        val k = t.indices.sumOf { (t[it] - mt) * (a[it] - ma) } / t.indices.sumOf { (t[it] - mt).pow(2) }
        return sqrt(t.indices.sumOf { (a[it] - (ma + k * (t[it] - mt))).pow(2) } / t.size)
    }

    @Test
    fun `the kept rooms render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (voice in Chamber.KEPT_VOICES) {
            for (model in 1..Chamber.MAX_MODEL_VERSION) lines += "CHAMBER ${voice.name} m$model " +
                M.hash(Chamber.render(voice, emptyMap(), 60, .75f, model = model).samples)
            for (midi in listOf(36, 84)) for (v in listOf(.3f, 1f)) lines += "CHAMBER ${voice.name} midi $midi v $v " +
                M.hash(Chamber.render(voice, emptyMap(), midi, v).samples)
        }
        for (p in ChamberPresets.all() + ChamberPresets.original()) lines += "CHAMBER preset ${p.name} m${p.model} " + M.hash(p.render().samples)
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        val renders = HashMap<Triple<ChamberVoice, Int, Float>, FloatArray>()
        /** SHA-256 over the lines above, taken from the build before this round (7495ca56c). */
        const val KEPT_RENDERS = "a93155010715568b410b94a59bd2c2abb86698e14558e769ef0c263ed7556641"
        const val KEPT_LINES = 30
        const val ROOT_SHARE = .1
        /** Soft, medium (the default, the sound a KEEP keeps) and hard as the desk plays them. */
        val TOUCH = listOf(.3f, Chamber.NORMAL_VELOCITY, 1f)
        /** "All sound the same" (20 Nov 2026, every candidate round at 0.5 to 5 dB): each step at least 3.5 dB of tone... */
        const val TOUCH_STEP_DB = 3.5
        /** ...soft against hard at least 8 dB... */
        const val TOUCH_SPAN_DB = 8.0
        /** ...and soft clearly quieter (11 dB under hard by construction, 9 under the default). */
        const val SOFT_QUIETER_DB = 6.0
        /** A candidate's top note may be at most this much brighter than the brighter kept room's (AQUARIUM's). */
        const val HARSH_MARGIN_DB = 3.0
    }
}
