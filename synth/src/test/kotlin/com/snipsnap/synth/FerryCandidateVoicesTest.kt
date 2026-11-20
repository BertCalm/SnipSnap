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
 * Candidate voices for listening round ferry-voices-1 (FerryCandidates). Each is PASSAGE's passive coupled network
 * with a new graph: TWIN (Weinreich's unison strings on a lossy bridge), SWELL (the struck wood is mute, only what
 * crosses into the wire is heard), TARAB (sympathetic strings), CHIME (a handbell's split mode pairs), RELAY (wood to
 * wire to glass) and TINE, the spare (a kalimba tine on a box with fixed pitches). FERRY is finite: no held keys.
 * Nothing here is heard. The kept renders are pinned below against the build before this round.
 */
class FerryCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = FerryVoice.entries.filter { it !in Ferry.KEPT_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceIn(0, x.size - 1)
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: FerryVoice, midi: Int = 48, velocity: Float = 1f, macros: Map<String, Float> = emptyMap()) =
        Ferry.render(voice, macros, midi, velocity).samples
    private fun loudnessDb(x: FloatArray) = 20 * log10(Loudness.of(x, 1, Dsp.RATE).toDouble())

    /** Power between 2 and 8 kHz over the power within 3 % of the root, dB, over the whole note. */
    private fun harshBandDb(x: FloatArray, midi: Int): Double {
        val size = 131_072
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
    fun `candidates are appended, model four only and offered nowhere`() {
        assertEquals(listOf(FerryVoice.TWIN, FerryVoice.SWELL, FerryVoice.TARAB, FerryVoice.CHIME, FerryVoice.RELAY, FerryVoice.TINE), candidates)
        assertEquals(listOf(FerryVoice.PASSAGE), Ferry.KEPT_VOICES)
        assertEquals(FerryVoice.PASSAGE, FerryVoice.entries.first())
        assertTrue(FerryPresets.all().all { it.voice in Ferry.KEPT_VOICES }, "a candidate has a preset")
        for (voice in candidates) {
            assertTrue(FerryPresets.forVoice(voice).isEmpty(), "$voice has presets")
            assertEquals(Ferry.macrosFor(FerryVoice.PASSAGE).map { it.name }, Ferry.macrosFor(voice).map { it.name })
            val saved = FerryPatch("Candidate", voice, Ferry.defaults(voice) + ("HANDOFF" to .3f), midi = 55, velocity = .6f)
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()), "$voice does not survive saving")
            assertContentEquals(saved.render().samples, (Patches.fromJsonText(saved.toJsonText()) as FerryPatch).render().samples)
            for (model in 1..3) assertFailsWith<IllegalArgumentException> { Ferry.render(voice, model = model) }
        }
    }

    @Test
    fun `candidates render finite, bounded, clean at both ends and the same twice`() {
        for (voice in candidates) {
            for (midi in listOf(36, 72)) for (velocity in listOf(.35f, 1f)) {
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
            for (loss in listOf(0f, 1f)) {
                val x = render(voice, macros = mapOf("LOSS" to loss))
                val end = x.size / 44100.0
                assertTrue(x.all { it.isFinite() } && rms(x, end - .02, end) < .05 * rms(x, .05, .5), "$voice LOSS $loss ends abruptly")
            }
            assertContentEquals(render(voice), render(voice), "$voice is not deterministic")
        }
    }

    @Test
    fun `candidates sound the played note across the range`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(36, 42, 48, 54, 60, 66, 72)) {
            val x = render(voice, midi)
            // A low note needs a longer look: a one-second window's lobe is a hundred cents wide at C2.
            val seconds = if (midi <= 48) 2.0 else 1.0
            val root = M.rootCents(x, hz(midi), .1, seconds)
            val share = M.rootShare(x, hz(midi), .1, seconds)
            println("FERRY $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        val close = ArrayList<String>()
        for (midi in listOf(36, 48, 60, 72)) {
            val shapes = FerryVoice.entries.associateWith { M.bands(render(it, midi)) }
            for (a in candidates) for (b in FerryVoice.entries) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("FERRY third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
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
            println("FERRY $voice MIDI$midi touch: tone steps %.1f, %.1f dB, soft against hard %.1f dB; level soft %.1f, medium %.1f dB against hard"
                .format(steps[0], steps[1], span, level[0] - level[2], level[1] - level[2]))
            assertTrue(steps.all { it >= TOUCH_STEP_DB }, "$voice MIDI$midi: a touch step barely changes the tone: $steps")
            assertTrue(span >= TOUCH_SPAN_DB, "$voice MIDI$midi: soft and hard are too alike: $span dB")
            assertTrue(level[0] - level[2] <= -SOFT_QUIETER_DB, "$voice MIDI$midi: soft is not clearly quieter: ${level[0] - level[2]} dB")
            assertTrue(level[0] < level[1] && level[1] < level[2], "$voice MIDI$midi: level does not follow touch: $level")
        }
    }

    @Test
    fun `the top of the range stays no harsher than a bright struck note`() {
        for (voice in candidates) for (velocity in listOf(.7f, 1f)) {
            val band = harshBandDb(render(voice, 72, velocity), 72)
            println("FERRY $voice MIDI72 v$velocity 2-8 kHz against the root %.1f dB (ceiling %.1f)".format(band, HARSH_CEILING_DB))
            assertTrue(band < HARSH_CEILING_DB, "$voice MIDI72 v$velocity 2-8 kHz is $band dB against the root")
        }
    }

    @Test
    fun `each candidate does what its graph says`() {
        val f = hz(60)
        val swell = render(FerryVoice.SWELL, 60)
        println("FERRY SWELL level at the strike %.4f, at .15 to .25 s %.4f".format(rms(swell, 0.0, .03), rms(swell, .15, .25)))
        assertTrue(rms(swell, .15, .25) > 3 * rms(swell, 0.0, .03), "SWELL does not grow out of the strike")
        val twin = render(FerryVoice.TWIN, 60)
        val prompt = 20 * log10(rms(twin, .05, .1) / rms(twin, .45, .5)) / .4
        val after = 20 * log10(rms(twin, 1.0, 1.1) / rms(twin, 2.5, 2.6)) / 1.5
        println("FERRY TWIN decay: prompt %.1f dB/s, aftersound %.1f dB/s".format(prompt, after))
        assertTrue(prompt > 3 * after, "TWIN has no prompt sound and aftersound")
        val relay = render(FerryVoice.RELAY, 60)
        fun upper(x: FloatArray, at: Double) = (2..6).sumOf { M.component(x, f * it, at, .3).pow(2) } / M.component(x, f, at, .3).pow(2)
        println("FERRY RELAY upper partials against the root: at the strike %.3f, at 1.5 s %.4f".format(upper(relay, .02), upper(relay, 1.5)))
        assertTrue(upper(relay, 1.5) < .1 * upper(relay, .02), "RELAY does not end on the glass tone")
        // CHIME's root pair beats; TARAB's sympathetic octave string beats against the played string's octave.
        for ((voice, partial) in listOf(FerryVoice.CHIME to 1, FerryVoice.TARAB to 2)) {
            val w = wobble(render(voice, 60), f * partial)
            println("FERRY $voice partial $partial wavers %.2f dB about its decay".format(w))
            assertTrue(w > WAVER_DB, "$voice's partial $partial does not waver")
        }
        // The box keeps its pitches whatever the note: its 205 Hz mode sounds under both notes.
        for (midi in listOf(62, 72)) {
            val tine = render(FerryVoice.TINE, midi)
            val box = M.component(tine, 205.0, .01, .1)
            val gap = M.component(tine, 160.0, .01, .1)
            println("FERRY TINE MIDI$midi box mode %.4f against 160 Hz %.4f".format(box, gap))
            assertTrue(box > 2 * gap, "TINE MIDI$midi has no fixed box mode")
        }
    }

    /** RMS deviation, dB, of the root's 100 ms amplitude from a straight-line (exponential) decay, .2 to 2.5 s. */
    private fun wobble(x: FloatArray, f: Double): Double {
        val t = ArrayList<Double>()
        val a = ArrayList<Double>()
        var s = .2
        while (s + .1 <= 2.5) { t += s; a += 20 * log10(M.component(x, f, s, .1) + 1e-12); s += .05 }
        val mt = t.average()
        val ma = a.average()
        val k = t.indices.sumOf { (t[it] - mt) * (a[it] - ma) } / t.indices.sumOf { (t[it] - mt).pow(2) }
        return sqrt(t.indices.sumOf { (a[it] - (ma + k * (t[it] - mt))).pow(2) } / t.size)
    }

    @Test
    fun `the kept voice renders exactly what it rendered before the candidates`() {
        val lines = ArrayList<String>()
        val voice = FerryVoice.PASSAGE
        for (model in 1..Ferry.MODEL_VERSION) lines += "FERRY ${voice.name} m$model " +
            M.hash(Ferry.render(voice, emptyMap(), 48, 1f, model = model).samples)
        for (midi in listOf(36, 60, 72)) for (v in listOf(.35f, .7f, 1f)) lines += "FERRY ${voice.name} midi $midi v $v " +
            M.hash(Ferry.render(voice, emptyMap(), midi, v).samples)
        for (loss in listOf(0f, 1f)) lines += "FERRY ${voice.name} LOSS $loss " +
            M.hash(Ferry.render(voice, mapOf("LOSS" to loss), 48, 1f).samples)
        lines += "FERRY ${voice.name} seed " + M.hash(Ferry.render(voice, mapOf("MATCH" to .5f), 60, .8f, seed = -3L).samples)
        for (p in FerryPresets.all()) lines += "FERRY preset ${p.name} " + M.hash(p.render().samples)
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the lines above, taken from the build before this round (7495ca56c). */
        const val KEPT_RENDERS = "1cf7c87f37a059e46037afc1e5439f63721d102a998c77c23e1b91f244176196"
        const val KEPT_LINES = 19
        const val ROOT_SHARE = .1
        /** Soft, medium and hard as the desk plays them. */
        val TOUCH = listOf(.35f, .7f, 1f)
        /** "All sound the same" (20 Nov 2026, every candidate round at 0.5 to 5 dB): each step at least 3.5 dB of tone... */
        const val TOUCH_STEP_DB = 3.5
        /** ...soft against hard at least 8 dB (measured 12 to 38 dB)... */
        const val TOUCH_SPAN_DB = 8.0
        /** ...and soft clearly quieter (measured 8 to 9 dB under hard). */
        const val SOFT_QUIETER_DB = 6.0
        /** PASSAGE at MIDI 72 measures -15 dB; the brightest candidate (TARAB, a pluck) about -11. */
        const val HARSH_CEILING_DB = -6.0
        const val WAVER_DB = 2.0
    }
}
