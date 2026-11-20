package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round filament-voices-1. Each reads a part of the strand network
 * that THREAD, PULSE and CURL leave unused: BRAID the strands as separate drifting players, HALO
 * the halo modes as the instrument, FRAY breath escaping at the bends, SPRING (the spare) the
 * collector through a dispersive cascade. Every candidate has the same held-key path as the kept
 * voices. Nothing here is heard. The kept renders are pinned below against the build before this round.
 */
class FilamentCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = FilamentVoice.entries.filter { it !in Filament.KEPT_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceIn(0, x.size - 1)
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: FilamentVoice, midi: Int = 60, velocity: Float = 1f, macros: Map<String, Float> = emptyMap()) =
        Filament.render(voice, macros, midi, velocity).samples
    private fun patch(voice: FilamentVoice, midi: Int = 60) = FilamentPatch("Candidate", voice, Filament.defaults(voice), midi = midi)

    /** Power between 2 and 8 kHz over the power within 3 % of the root, dB, on the held loop (as HeldKeyHarshBandTest). */
    private fun harshBandDb(note: KeyNote, midi: Int): Double {
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val loop = samples.size - start
        val size = 65_536
        val spectrum = Fft.magnitudeSpectrum(FloatArray(size) { samples[start + it % loop] }, size)
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
    fun `candidates are appended and offered nowhere`() {
        assertEquals(listOf(FilamentVoice.BRAID, FilamentVoice.HALO, FilamentVoice.FRAY, FilamentVoice.SPRING), candidates)
        assertEquals(FilamentVoice.entries.take(3), Filament.KEPT_VOICES)
        assertTrue(FilamentPresets.all().all { it.voice in Filament.KEPT_VOICES }, "a candidate has a preset")
        for (voice in candidates) {
            assertTrue(FilamentPresets.forVoice(voice).isEmpty(), "$voice has presets")
            assertEquals(Filament.macrosFor(FilamentVoice.THREAD).map { it.name }, Filament.macrosFor(voice).map { it.name })
            val saved = patch(voice).copy(macros = Filament.defaults(voice) + ("BEND" to .3f))
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()), "$voice does not survive saving")
            assertTrue(FilamentKeys.supports(saved), "$voice has no held key")
        }
    }

    @Test
    fun `candidates render finite, bounded, clean at both ends and the same twice`() {
        for (voice in candidates) {
            for (midi in listOf(36, 60, 84)) for (velocity in listOf(.35f, 1f)) {
                val x = render(voice, midi, velocity)
                val peak = x.maxOf { abs(it) }
                assertTrue(x.all { it.isFinite() } && peak in .01f..PEAK, "$voice MIDI$midi v$velocity peak $peak")
                assertTrue(abs(x.average()) < .005, "$voice MIDI$midi v$velocity has an offset")
                assertTrue(abs(x[0]) < 5e-3f, "$voice MIDI$midi v$velocity starts open: ${x[0]}")
                assertTrue((0 until 11).maxOf { abs(x[it]) } < .05f * peak, "$voice MIDI$midi v$velocity starts with a click")
                assertTrue(abs(x.last()) < 1e-3f, "$voice MIDI$midi v$velocity ends open")
                val end = x.size / 44100.0
                assertTrue(rms(x, end - .02, end) < .05 * rms(x, .05, .6), "$voice MIDI$midi v$velocity ends abruptly")
            }
            assertContentEquals(render(voice), render(voice), "$voice is not deterministic")
            assertTrue(render(voice, velocity = 0f).all { it == 0f }, "$voice sounds at zero velocity")
        }
    }

    @Test
    fun `candidates sound the played note across the range`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(36, 48, 60, 72, 84)) {
            val x = render(voice, midi)
            val root = M.rootCents(x, hz(midi), ROOT_FROM, .5)
            val share = M.rootShare(x, hz(midi), ROOT_FROM, .5)
            println("FILAMENT $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        val close = ArrayList<String>()
        for (midi in listOf(48, 60, 72)) {
            val shapes = FilamentVoice.entries.associateWith { M.bands(render(it, midi)) }
            for (a in candidates) for (b in FilamentVoice.entries) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("FILAMENT third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                if (d < M.DISTINCT_DB) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
    }

    @Test
    fun `soft, medium and hard differ clearly in tone, finite and held`() {
        for (voice in candidates) {
            val shapes = listOf(.35f, .7f, 1f).map { M.bands(render(voice, velocity = it)) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            val span = M.distance(shapes[0], shapes[2])
            val layers = FilamentTouchKeys.render(patch(voice))
                .map { M.bands(it.snip.samples.copyOfRange(it.loopStartFrame.toInt(), it.snip.samples.size)) }
            val held = listOf(M.distance(layers[0], layers[1]), M.distance(layers[1], layers[2]))
            println("FILAMENT $voice level-matched touch steps: finite %.1f, %.1f dB; held %.1f, %.1f dB".format(steps[0], steps[1], held[0], held[1]) + "; soft against hard %.1f dB".format(span))
            assertTrue(steps.all { it > TOUCH_DB }, "$voice touch barely changes the finite tone: $steps")
            assertTrue(span > TOUCH_SPAN_DB, "$voice soft and hard are too alike: $span dB")
            assertTrue(held.all { it > TOUCH_DB }, "$voice touch barely changes the held tone: $held")
        }
    }

    @Test
    fun `held keys close, keep the upper band no harsher than the kept voices and keep moving`() {
        for (voice in candidates) for ((midi, ceiling) in HARSH_CEILINGS) {
            val note = FilamentKeys.layer(patch(voice, midi))
            val samples = note.snip.samples
            val start = note.loopStartFrame.toInt()
            assertTrue(samples.all { it.isFinite() } && start > 0 && start < samples.size, "$voice MIDI$midi held key")
            assertTrue(Keys.seamError(samples, start) < 1e-3, "$voice MIDI$midi loop does not close")
            val band = harshBandDb(note, midi)
            println("FILAMENT $voice held MIDI$midi 2-8 kHz %.1f dB (ceiling %.1f)".format(band, ceiling))
            assertTrue(band < ceiling, "$voice MIDI$midi held 2-8 kHz is $band dB against the root")
            if (midi == 60) {
                // The sustain is not one frozen cycle: its level wanders across the loop.
                val loop = samples.copyOfRange(start, samples.size)
                val parts = 8
                val level = (0 until parts).map { k ->
                    val a = k * loop.size / parts
                    val b = (k + 1) * loop.size / parts
                    sqrt((a until b).sumOf { loop[it].toDouble() * loop[it] } / (b - a))
                }
                val swing = 20 * log10(level.max() / level.min())
                println("FILAMENT $voice held level swing across the loop %.1f dB".format(swing))
                assertTrue(swing > MOVING_DB, "$voice held loop sits still: $swing dB")
            }
        }
    }

    @Test
    fun `HALO keeps ringing after the rub and FRAY breathes between its partials`() {
        val halo = render(FilamentVoice.HALO)
        val rubEnds = FilamentCandidates.tables(FilamentVoice.HALO).sourceSeconds
        val ring = rms(halo, rubEnds + .2, rubEnds + .7) / rms(halo, .5, 1.0)
        println("FILAMENT HALO ring-out after the rub: %.2f of the sung level".format(ring))
        assertTrue(ring > .15, "HALO stops with the rub")
        fun between(x: FloatArray): Double {
            val f = hz(60)
            val partials = (1..4).sumOf { M.component(x, it * f, .4, .5) }
            val gaps = (1..4).sumOf { M.component(x, (it + .5) * f, .4, .5) }
            return gaps / partials
        }
        val fray = between(render(FilamentVoice.FRAY))
        val thread = between(render(FilamentVoice.THREAD))
        println("FILAMENT energy between partials: FRAY %.4f, THREAD %.4f".format(fray, thread))
        assertTrue(fray > 4 * thread, "FRAY has no breath")
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        fun note(n: KeyNote) = M.hash(n.snip.samples) + " loop " + n.loopStartFrame
        for (voice in Filament.KEPT_VOICES) {
            for (midi in listOf(36, 60, 84)) for (v in listOf(.4f, 1f)) lines +=
                "FILAMENT ${voice.name} m3 midi $midi v $v " + M.hash(Filament.render(voice, emptyMap(), midi, v).samples)
            for (model in listOf(1, 2)) lines += "FILAMENT ${voice.name} m$model " +
                M.hash(Filament.render(voice, emptyMap(), 48, .8f, model = model).samples)
            lines += "FILAMENT ${voice.name} HOLD " + M.hash(Filament.render(voice, mapOf("HOLD" to 1f), 60, .8f).samples)
            val presets = FilamentPresets.forVoice(voice).filter { it.model == Filament.MODEL_VERSION }
            for (p in presets) lines += "FILAMENT preset ${p.name} ${M.hash(p.render().samples)}"
            val first = presets.first()
            for (midi in listOf(48, 72)) lines += "FILAMENT key ${voice.name} $midi " + note(FilamentKeys.layer(first.copy(midi = midi)))
            lines += "FILAMENT touch ${voice.name} " + FilamentTouchKeys.render(first, 60).joinToString(" ") { note(it) }
        }
        val curl = FilamentPresets.forVoice(FilamentVoice.CURL).first { it.model == Filament.MODEL_VERSION }
        val c = FilamentHaloKeys.capture(curl, 60)
        lines += "FILAMENT halo-keys " + note(c.body) + " " + note(c.halo)
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the lines above, taken from the build before this round (c8df28195). */
        const val KEPT_RENDERS = "8ade1ac08f31e22238c9f8b264c0b2a999e61228d2c691f823a835a3e716eb78"
        const val KEPT_LINES = 49
        const val PEAK = .95f
        const val ROOT_FROM = .3
        const val ROOT_SHARE = .3
        /** The owner rejected touch that made "very little difference": a clear step, not a trace. */
        const val TOUCH_DB = 1.5
        /** After "All sound the same" on four engines (20 Nov): soft against hard at least 6 dB of tone. */
        const val TOUCH_SPAN_DB = 6.0
        const val MOVING_DB = .5
        /** HeldKeyHarshBandTest's ceilings for the kept voices (20 Nov 2026), by root. */
        val HARSH_CEILINGS = listOf(36 to -60.0, 60 to -23.5, 84 to -5.5)
    }
}
