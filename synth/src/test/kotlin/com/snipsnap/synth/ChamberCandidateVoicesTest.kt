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
 * Candidate voices (ChamberCandidates). Round chamber-voices-1 (21 Nov 2026) kept GLAZE (a singing bowl's split mode
 * pairs in a glass room) and asked for the rest to be "More distinctive". Round chamber-voices-2 gives each its own
 * instrument layer over its room network (ChamberCandidateLayers): TIMBER (a marimba), MASS (a huge low drum and its
 * hall), ALLOY (a steel pan) and BOUNCE (a cimbalom whose hammer rebounds). Round fix-chamber-voices-1 (21 Nov 2026) kept all
 * four. CHAMBER is finite: no held keys. HYBRID and AQUARIUM are pinned against the build before round 1, the five new
 * voices against the builds the owner heard.
 */
class ChamberCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = ChamberVoice.entries.filter { it !in Chamber.ORIGINAL_VOICES }

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
    fun `the new voices are appended, register model only, kept, and each has one recipe at the note the owner heard`() {
        assertEquals(listOf(ChamberVoice.TIMBER, ChamberVoice.MASS, ChamberVoice.ALLOY, ChamberVoice.GLAZE, ChamberVoice.BOUNCE), candidates)
        assertEquals(listOf(ChamberVoice.HYBRID, ChamberVoice.AQUARIUM), Chamber.ORIGINAL_VOICES)
        assertEquals(Chamber.ORIGINAL_VOICES, ChamberVoice.entries.take(2))
        assertEquals(Chamber.ORIGINAL_VOICES + listOf(ChamberVoice.GLAZE, ChamberVoice.TIMBER, ChamberVoice.MASS, ChamberVoice.ALLOY,
            ChamberVoice.BOUNCE), Chamber.KEPT_VOICES)
        assertEquals(ChamberVoice.entries.toSet(), Chamber.KEPT_VOICES.toSet())
        assertTrue(ChamberPresets.original().all { it.voice in Chamber.ORIGINAL_VOICES }, "a new voice is in the published six")
        assertTrue(ChamberKits.patches().all { it.voice in Chamber.ORIGINAL_VOICES }, "a new voice is in the kit")
        for (voice in candidates) {
            val preset = ChamberPresets.forVoice(voice).single()
            assertEquals(ChamberPatch(preset.name, voice, Chamber.defaults(voice), model = Chamber.REGISTER_MODEL), preset)
            assertEquals(60, preset.midi)
            assertEquals(Chamber.NORMAL_VELOCITY, preset.velocity)
            // The medium note of the touch clip the owner kept (FD2 for GLAZE, GE2 to GH2 for the rest).
            assertContentEquals(render(voice), preset.render().samples, "$voice's recipe is not the note the owner heard")
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
                val bar = if (ChamberCandidateLayers.layered(a)) REWORKED_DB else M.DISTINCT_DB
                if (d < bar) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
    }

    @Test
    fun `GLAZE, kept on 21 Nov 2026, renders exactly what the owner heard`() {
        val lines = ArrayList<String>()
        for (midi in listOf(36, 52, 60, 68, 84)) for (v in listOf(.3f, .75f, 1f)) lines += "CHAMBER GLAZE midi $midi v $v " +
            M.hash(Chamber.render(ChamberVoice.GLAZE, emptyMap(), midi, v).samples)
        lines += "CHAMBER GLAZE macros " + M.hash(Chamber.render(ChamberVoice.GLAZE, mapOf("STRIKE" to .8f, "MATERIAL" to .2f), 55, .6f).samples)
        assertEquals(16, lines.size)
        assertEquals(GLAZE_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    @Test
    fun `TIMBER, MASS, ALLOY and BOUNCE, kept on 21 Nov 2026, render exactly what the owner heard`() {
        val lines = ArrayList<String>()
        for (voice in REWORKED) {
            // The new half of each before/after clip (GE1 to GH1), then its touch clip (GE2 to GH2).
            for (midi in listOf(36, 52)) lines += "CHAMBER $voice midi $midi v ${Chamber.NORMAL_VELOCITY} " +
                M.hash(Chamber.render(voice, emptyMap(), midi, Chamber.NORMAL_VELOCITY).samples)
            for (v in TOUCH) lines += "CHAMBER $voice midi 60 v $v " + M.hash(Chamber.render(voice, emptyMap(), 60, v).samples)
        }
        for (voice in candidates) lines += "CHAMBER preset $voice " + M.hash(ChamberPresets.forVoice(voice).single().render().samples)
        assertEquals(25, lines.size)
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
            val ceiling = Chamber.ORIGINAL_VOICES.maxOf { harshBandDb(render(it, 84, velocity), 84) } + HARSH_MARGIN_DB
            for (voice in candidates) {
                val band = harshBandDb(render(voice, 84, velocity), 84)
                println("CHAMBER $voice MIDI84 v$velocity 2-8 kHz against the root %.1f dB (ceiling %.1f)".format(band, ceiling))
                assertTrue(band < ceiling, "$voice MIDI84 v$velocity 2-8 kHz is $band dB against the root")
            }
        }
    }

    @Test
    fun `each candidate carries its own instrument`() {
        val f = hz(60)
        val quiet = Chamber.ProbeOptions(normalize = true, recordDiagnostics = false, recordStems = false)
        // The room network still plays under every layer: take TIMBER's resonating walls away and the note changes.
        val on = Chamber.probe(ChamberVoice.TIMBER, midi = 60, options = quiet).samples
        val off = Chamber.probe(ChamberVoice.TIMBER, midi = 60, options = quiet.copy(roomEnabled = false)).samples
        val room = M.distance(M.bands(on), M.bands(off))
        println("CHAMBER TIMBER with and without its room: %.1f dB".format(room))
        assertTrue(room > 2.5, "TIMBER's room does nothing")
        // TIMBER: a marimba bar's second mode (3.93) dies long before its tube-held root.
        val bar = render(ChamberVoice.TIMBER, 60, 1f)
        val second = 3.93 * f
        val rootFall = 20 * log10(M.component(bar, f, .05, .1) / M.component(bar, f, .6, .1))
        val secondFall = 20 * log10(M.component(bar, second, .05, .1) / M.component(bar, second, .6, .1))
        println("CHAMBER TIMBER .05 to .6 s: root falls %.1f dB, the 3.93 mode %.1f dB".format(rootFall, secondFall))
        assertTrue(secondFall > rootFall + 10, "TIMBER's bar modes do not die before its root")
        // MASS: the skin's pitch starts high and drops; the hall's low modes boom under the note.
        val drum = render(ChamberVoice.MASS, 72, 1f)
        val early = peakCents(drum, hz(72), .004, .03)
        val late = peakCents(drum, hz(72), .3, .03)
        println("CHAMBER MASS MIDI72 hard: pitch %+.0f cents at the strike, %+.0f cents at .3 s".format(early, late))
        assertTrue(early - late > 60, "MASS's pitch does not drop")
        val hall = render(ChamberVoice.MASS, 60, 1f)
        val boom = M.component(hall, 53.0, .1, .6) / M.component(hall, 47.0, .1, .6)
        println("CHAMBER MASS hall mode at 53 Hz against 47 Hz: %.1f".format(boom))
        assertTrue(boom > 3, "MASS's hall does not boom")
        // ALLOY: a pan's tuned tone field, its root and octave trading energy (the shimmer).
        val pan = render(ChamberVoice.ALLOY)
        val octave = 20 * log10(M.component(pan, 2 * f, .05, .5) / M.component(pan, f, .05, .5))
        val twelfth = 20 * log10(M.component(pan, 3 * f, .05, .5) / M.component(pan, f, .05, .5))
        val shimmer = wobble(pan, f, 1.5)
        println("CHAMBER ALLOY octave %.1f dB, twelfth %.1f dB against the root; root wavers %.2f dB".format(octave, twelfth, shimmer))
        assertTrue(octave > -14 && twelfth > -26, "ALLOY's tone field is not tuned")
        assertTrue(shimmer > 1.0, "ALLOY does not shimmer")
        // GLAZE (kept, unchanged): its mode pairs beat.
        val bowl = wobble(render(ChamberVoice.GLAZE), f)
        println("CHAMBER GLAZE root wavers %.2f dB about its decay".format(bowl))
        assertTrue(bowl > 2.5, "GLAZE's mode pairs do not beat")
        // BOUNCE: the hammer rebounds: two clear rises after the first strike, more of them when hard.
        for (velocity in listOf(Chamber.NORMAL_VELOCITY, 1f)) {
            val string = render(ChamberVoice.BOUNCE, 60, velocity)
            val before = rms(string, .045, .055)
            val after = (0 until 30).maxOf { rms(string, .055 + .001 * it, .060 + .001 * it) }
            println("CHAMBER BOUNCE v$velocity 45-55 ms %.4f, loudest 5 ms after it %.4f".format(before, after))
            assertTrue(after > 1.3 * before, "BOUNCE's hammer does not come back at v$velocity")
        }
    }

    /** The strongest component's offset from [hz], cents, -200 to +500, in a short window. */
    private fun peakCents(x: FloatArray, hz: Double, from: Double, seconds: Double): Double =
        (-200..500 step 5).maxBy { M.component(x, hz * 2.0.pow(it / 1200.0), from, seconds) }.toDouble()

    /** RMS deviation, dB, of the root's 100 ms amplitude from a straight-line (exponential) decay, .2 to [until] s. */
    private fun wobble(x: FloatArray, f: Double, until: Double = 3.0): Double {
        val t = ArrayList<Double>()
        val a = ArrayList<Double>()
        var s = .2
        while (s + .1 <= until) { t += s; a += 20 * log10(M.component(x, f, s, .1) + 1e-12); s += .05 }
        val mt = t.average()
        val ma = a.average()
        val k = t.indices.sumOf { (t[it] - mt) * (a[it] - ma) } / t.indices.sumOf { (t[it] - mt).pow(2) }
        return sqrt(t.indices.sumOf { (a[it] - (ma + k * (t[it] - mt))).pow(2) } / t.size)
    }

    @Test
    fun `the kept rooms render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (voice in Chamber.ORIGINAL_VOICES) {
            for (model in 1..Chamber.MAX_MODEL_VERSION) lines += "CHAMBER ${voice.name} m$model " +
                M.hash(Chamber.render(voice, emptyMap(), 60, .75f, model = model).samples)
            for (midi in listOf(36, 84)) for (v in listOf(.3f, 1f)) lines += "CHAMBER ${voice.name} midi $midi v $v " +
                M.hash(Chamber.render(voice, emptyMap(), midi, v).samples)
        }
        for (p in (ChamberPresets.all() + ChamberPresets.original()).filter { it.voice in Chamber.ORIGINAL_VOICES }) lines += "CHAMBER preset ${p.name} m${p.model} " + M.hash(p.render().samples)
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        val renders = HashMap<Triple<ChamberVoice, Int, Float>, FloatArray>()
        /** SHA-256 over the lines above, taken from the build before this round (7495ca56c). */
        const val KEPT_RENDERS = "a93155010715568b410b94a59bd2c2abb86698e14558e769ef0c263ed7556641"
        const val KEPT_LINES = 30
        /** SHA-256 over GLAZE's lines, taken from the build the owner heard in round chamber-voices-1 (c42bc8e89). */
        const val GLAZE_RENDERS = "f76065e863620b220a36e2922d8460d6a044095f7965fc26e756be093d4e8cae"
        val REWORKED = listOf(ChamberVoice.TIMBER, ChamberVoice.MASS, ChamberVoice.ALLOY, ChamberVoice.BOUNCE)
        /** SHA-256 over the reworked voices' lines, taken from the build the owner heard in round fix-chamber-voices-1 (2b294f616). */
        const val REWORKED_RENDERS = "e4f7244db22ff14f234c95674c2a1c948017b5e8be10d3506bf02908f18a7fc7"
        /**
         * The reworked voices (round chamber-voices-2, "More distinctive") must stand at least this far from every other
         * voice; the rest keep ETCH's bar. Measured 10.5 to 17.3 dB.
         */
        const val REWORKED_DB = 9.0
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
