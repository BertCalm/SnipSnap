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
 * Candidate voices (FerryCandidates). Round ferry-voices-1 (21 Nov 2026) kept TINE (a kalimba tine on a box with fixed
 * pitches) and asked for the rest to be "More distinctive". Round ferry-voices-2 gives each its own instrument layer over
 * its network (FerryCandidateLayers): TWIN (a piano's beating double string), SWELL (a bowed swell), TARAB (a sitar's
 * sweeping jawari and sympathetic strings), PEAL (a handbell, was CHIME), BATON (wood, wire, glass, was RELAY) and VANE,
 * the spare (a vibraphone's motor tremolo). Round fix-ferry-voices-1 (21 Nov 2026) kept all six. FERRY is finite: no held
 * keys. PASSAGE is pinned against the build before round 1, the seven new voices against the builds the owner heard.
 */
class FerryCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = FerryVoice.entries.filter { it !in Ferry.ORIGINAL_VOICES }

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
    fun `the new voices are appended, model four only, kept, and each has one recipe at the note the owner heard`() {
        assertEquals(listOf(FerryVoice.TWIN, FerryVoice.SWELL, FerryVoice.TARAB, FerryVoice.PEAL, FerryVoice.BATON, FerryVoice.TINE, FerryVoice.VANE), candidates)
        assertEquals(listOf(FerryVoice.PASSAGE), Ferry.ORIGINAL_VOICES)
        assertEquals(FerryVoice.PASSAGE, FerryVoice.entries.first())
        assertEquals(listOf(FerryVoice.PASSAGE, FerryVoice.TINE, FerryVoice.TWIN, FerryVoice.SWELL, FerryVoice.TARAB, FerryVoice.PEAL,
            FerryVoice.BATON, FerryVoice.VANE), Ferry.KEPT_VOICES)
        assertEquals(3 + candidates.size, FerryPresets.all().size)
        for (voice in candidates) {
            val preset = FerryPresets.forVoice(voice).single()
            assertEquals(FerryPatch(preset.name, voice, Ferry.defaults(voice)), preset)
            assertEquals(48, preset.midi)
            assertEquals(1f, preset.velocity)
            // C3 at full touch: a note of the range clip the owner kept (FL1 for TINE, GJ1 to GN1's new half, GO1 for VANE).
            assertContentEquals(render(voice), preset.render().samples, "$voice's recipe is not the note the owner heard")
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
                val bar = if (FerryCandidateLayers.layered(a)) REWORKED_DB else M.DISTINCT_DB
                if (d < bar) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
    }

    @Test
    fun `TINE, kept on 21 Nov 2026, renders exactly what the owner heard`() {
        val lines = ArrayList<String>()
        for (midi in listOf(36, 48, 60, 72)) for (v in listOf(.35f, .7f, 1f)) lines += "FERRY TINE midi $midi v $v " +
            M.hash(Ferry.render(FerryVoice.TINE, emptyMap(), midi, v).samples)
        for (loss in listOf(0f, 1f)) lines += "FERRY TINE LOSS $loss " + M.hash(Ferry.render(FerryVoice.TINE, mapOf("LOSS" to loss), 48, 1f).samples)
        lines += "FERRY TINE macros " + M.hash(Ferry.render(FerryVoice.TINE, mapOf("HANDOFF" to .2f, "MATCH" to .4f), 55, .8f).samples)
        assertEquals(15, lines.size)
        assertEquals(TINE_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    @Test
    fun `TWIN, SWELL, TARAB, PEAL, BATON and VANE, kept on 21 Nov 2026, render exactly what the owner heard`() {
        val lines = ArrayList<String>()
        for (voice in REWORKED) {
            // The new half of each before/after clip (GJ1 to GN1), VANE's range clip (GO1), then each touch clip (GJ2 to GO2).
            val range = if (voice == FerryVoice.VANE) listOf(36, 48, 60, 72) else listOf(36, 48)
            for (midi in range) lines += "FERRY $voice midi $midi v 1 " + M.hash(Ferry.render(voice, emptyMap(), midi, 1f).samples)
            for (v in TOUCH) lines += "FERRY $voice midi 60 v $v " + M.hash(Ferry.render(voice, emptyMap(), 60, v).samples)
        }
        for (voice in candidates) lines += "FERRY preset $voice " + M.hash(FerryPresets.forVoice(voice).single().render().samples)
        assertEquals(39, lines.size)
        assertEquals(REWORKED_RENDERS, M.hash(lines), lines.joinToString("\n"))
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
    fun `each candidate carries its own instrument`() {
        val f = hz(60)
        val swell = render(FerryVoice.SWELL, 60)
        println("FERRY SWELL level at the strike %.4f, at .15 to .25 s %.4f".format(rms(swell, 0.0, .03), rms(swell, .15, .25)))
        assertTrue(rms(swell, .15, .25) > 3 * rms(swell, 0.0, .03), "SWELL does not grow out of the strike")
        // SWELL blooms for about half a second; every struck voice peaks within a few tens of milliseconds.
        val peaks = candidates.associateWith { peakSeconds(render(it, 60)) }
        println("FERRY loudest 10 ms at middle C: " + peaks.entries.joinToString { "${it.key} %.2f s".format(it.value) })
        assertTrue(peaks.getValue(FerryVoice.SWELL) > .35, "SWELL does not swell")
        for (voice in listOf(FerryVoice.TWIN, FerryVoice.TARAB, FerryVoice.TINE, FerryVoice.VANE))
            assertTrue(peaks.getValue(voice) < .05, "$voice is not struck")
        val twin = render(FerryVoice.TWIN, 60)
        val prompt = 20 * log10(rms(twin, .05, .1) / rms(twin, .45, .5)) / .4
        val after = 20 * log10(rms(twin, 1.0, 1.1) / rms(twin, 2.5, 2.6)) / 1.5
        println("FERRY TWIN decay: prompt %.1f dB/s, aftersound %.1f dB/s".format(prompt, after))
        assertTrue(prompt > 3 * after, "TWIN has no prompt sound and aftersound")
        val beating = wobble(twin, f)
        println("FERRY TWIN root wavers %.2f dB about its decay (the two strings beat)".format(beating))
        assertTrue(beating > WAVER_DB, "TWIN's strings do not beat")
        // BATON: a dark knock, then the bright wire, then the pure glass.
        val baton = render(FerryVoice.BATON, 60)
        fun upper(x: FloatArray, at: Double, s: Double = .3) = (2..8).sumOf { M.component(x, f * it, at, s).pow(2) } / M.component(x, f, at, s).pow(2)
        val stages = listOf(upper(baton, .0, .08), upper(baton, .3, .2), upper(baton, 1.6, .3))
        println("FERRY BATON upper partials against the root: knock %.3f, wire %.3f, glass %.4f".format(stages[0], stages[1], stages[2]))
        assertTrue(stages[1] > 2 * stages[0] && stages[2] < .05 * stages[1], "BATON does not pass wood to wire to glass")
        // TARAB: the jawari's resonance sweeps down through the partials over the first second.
        val sitar = render(FerryVoice.TARAB, 60)
        val sweep = listOf(.03, 1.0).map { centroid(sitar, f, it) }
        println("FERRY TARAB centre of partials 2 to 12: %.1f at the pluck, %.1f at 1 s".format(sweep[0], sweep[1]))
        assertTrue(sweep[0] > 1.25 * sweep[1], "TARAB's jawari does not sweep")
        // VANE: the motor's vanes pulse the root about five and a half times a second.
        val vibes = render(FerryVoice.VANE, 60)
        val pulse = tremoloDb(vibes, f)
        println("FERRY VANE root pulses %.1f dB peak to trough".format(pulse))
        assertTrue(pulse > 6, "VANE has no tremolo")
        // CHIME's root pair beats; TARAB's sympathetic octave string beats against the played string's octave.
        for ((voice, partial) in listOf(FerryVoice.PEAL to 1, FerryVoice.TARAB to 2)) {
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

    private fun peakSeconds(x: FloatArray): Double = (0 until x.size / 441 - 1).maxBy { rms(x, it * .01, it * .01 + .01) } * .01

    /** The amplitude-weighted mean partial number among partials 2 to 12 over 80 ms from [at]: where the buzz sits. */
    private fun centroid(x: FloatArray, f: Double, at: Double): Double {
        val a = (2..12).map { M.component(x, f * it, at, .08) }
        return a.indices.sumOf { (it + 2) * a[it] } / a.sum()
    }

    /** Peak-to-trough swing, dB, of the root's 40 ms amplitude between .3 and 1 s, about its trend. */
    private fun tremoloDb(x: FloatArray, f: Double): Double {
        val a = (0 until 70).map { 20 * log10(M.component(x, f, .3 + .01 * it, .04) + 1e-12) }
        val k = (a.last() - a.first()) / (a.size - 1)
        val d = a.indices.map { a[it] - k * it }
        return d.max() - d.min()
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
        for (p in FerryPresets.forVoice(FerryVoice.PASSAGE)) lines += "FERRY preset ${p.name} " + M.hash(p.render().samples)
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the lines above, taken from the build before this round (7495ca56c). */
        const val KEPT_RENDERS = "1cf7c87f37a059e46037afc1e5439f63721d102a998c77c23e1b91f244176196"
        const val KEPT_LINES = 19
        /** SHA-256 over TINE's lines, taken from the build the owner heard in round ferry-voices-1 (c42bc8e89). */
        const val TINE_RENDERS = "4abbc5bbd5126963a3498ae108c348e6d92a43016d6a0185271040a383dd4b49"
        val REWORKED = listOf(FerryVoice.TWIN, FerryVoice.SWELL, FerryVoice.TARAB, FerryVoice.PEAL, FerryVoice.BATON, FerryVoice.VANE)
        /** SHA-256 over the reworked voices' lines, taken from the build the owner heard in round fix-ferry-voices-1 (2b294f616). */
        const val REWORKED_RENDERS = "e25cff6e5d769c9cfa8969e08c33b32a61cc4cb1af1285fc5f408edec3a72537"
        /**
         * The reworked voices (round ferry-voices-2, "More distinctive") must stand at least this far from every other
         * voice; the rest keep ETCH's bar. Measured 8.5 (TWIN against SWELL, which swells where TWIN strikes) to 27 dB.
         */
        const val REWORKED_DB = 8.0
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
