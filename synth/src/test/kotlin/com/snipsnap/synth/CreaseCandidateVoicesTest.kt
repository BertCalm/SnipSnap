package com.snipsnap.synth

import com.snipsnap.audio.Fft
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
 * Candidate voices for listening round crease-voices-1. Each reads a part of the two-layer pressure
 * model that ROUND, PINCH and RUBBER leave unused: PLEAT the deformation as two moving resonances,
 * SNAP the deformation as the pick itself, TWOFOLD the layers' load as an octave that blooms, CUP
 * (the spare) PINCH's half-cycle map turned the other way. Every candidate has the same held-key
 * path as the kept voices. Nothing here is heard. The kept renders are pinned below against the
 * build before this round.
 */
class CreaseCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = CreaseVoice.entries.filter { it !in Crease.KEPT_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceIn(0, x.size - 1)
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: CreaseVoice, midi: Int = 48, velocity: Float = .75f, macros: Map<String, Float> = emptyMap()) =
        Crease.render(voice, macros, midi, velocity).samples
    private fun patch(voice: CreaseVoice) = CreasePatch("Candidate", voice, Crease.defaults(voice))

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
    fun `candidates are appended, model three only and offered nowhere`() {
        assertEquals(listOf(CreaseVoice.PLEAT, CreaseVoice.SNAP, CreaseVoice.TWOFOLD, CreaseVoice.CUP), candidates)
        assertEquals(CreaseVoice.entries.take(3), Crease.KEPT_VOICES)
        assertTrue(CreasePresets.all().all { it.voice in Crease.KEPT_VOICES }, "a candidate has a preset")
        for (voice in candidates) {
            assertTrue(CreasePresets.forVoice(voice).isEmpty(), "$voice has presets")
            assertEquals(Crease.macrosFor(CreaseVoice.ROUND).map { it.name }, Crease.macrosFor(voice).map { it.name })
            val saved = patch(voice).copy(macros = Crease.defaults(voice) + ("FOLD" to .3f))
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()), "$voice does not survive saving")
            assertTrue(CreaseKeys.supports(saved), "$voice has no held key")
            for (model in 1..2) assertFailsWith<IllegalArgumentException> { Crease.render(voice, model = model) }
        }
    }

    @Test
    fun `candidates render finite, bounded, clean at both ends and the same twice`() {
        for (voice in candidates) {
            for (midi in listOf(24, 48, 84)) for (velocity in listOf(.35f, 1f)) {
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
            assertContentEquals(render(voice), render(voice), "$voice is not deterministic")
        }
    }

    @Test
    fun `candidates sound the played note across the range`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(24, 36, 48, 60, 72, 84)) {
            val x = render(voice, midi)
            val root = M.rootCents(x, hz(midi), .15, .5)
            val share = M.rootShare(x, hz(midi), .15, .5)
            println("CREASE $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        val close = ArrayList<String>()
        for (midi in listOf(36, 48, 60, 72)) {
            val shapes = CreaseVoice.entries.associateWith { M.bands(render(it, midi)) }
            for (a in candidates) for (b in CreaseVoice.entries) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("CREASE third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
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
            val layers = CreaseTouchKeys.render(patch(voice), 60)
                .map { M.bands(it.snip.samples.copyOfRange(it.loopStartFrame.toInt(), it.snip.samples.size)) }
            val held = listOf(M.distance(layers[0], layers[1]), M.distance(layers[1], layers[2]))
            println("CREASE $voice level-matched touch steps: finite %.1f, %.1f dB; held %.1f, %.1f dB".format(steps[0], steps[1], held[0], held[1]) + "; soft against hard %.1f dB".format(span))
            assertTrue(steps.all { it > TOUCH_DB }, "$voice touch barely changes the finite tone: $steps")
            assertTrue(span > TOUCH_SPAN_DB, "$voice soft and hard are too alike: $span dB")
            assertTrue(held.all { it > TOUCH_DB }, "$voice touch barely changes the held tone: $held")
        }
    }

    @Test
    fun `held keys close, keep the upper band no harsher than the kept voices and keep moving`() {
        for (voice in candidates) for ((midi, ceiling) in HARSH_CEILINGS) {
            val note = CreaseKeys.render(patch(voice), midi)
            val samples = note.snip.samples
            val start = note.loopStartFrame.toInt()
            assertTrue(samples.all { it.isFinite() } && start > 0 && start < samples.size, "$voice MIDI$midi held key")
            assertTrue(Keys.seamError(samples, start) < 1e-3, "$voice MIDI$midi loop does not close")
            val band = harshBandDb(note, midi)
            println("CREASE $voice held MIDI$midi 2-8 kHz %.1f dB (ceiling %.1f)".format(band, ceiling))
            assertTrue(band < ceiling, "$voice MIDI$midi held 2-8 kHz is $band dB against the root")
            if (midi == 60) {
                // The sustain is not one frozen cycle: its tone wanders across the loop.
                val loop = samples.copyOfRange(start, samples.size)
                val quarter = loop.size / 4
                val shapes = (0 until 4).map { M.bands(loop.copyOfRange(it * quarter, (it + 1) * quarter)) }
                val moved = (1 until 4).maxOf { M.distance(shapes[0], shapes[it]) }
                println("CREASE $voice held tone change across the loop %.1f dB".format(moved))
                assertTrue(moved > MOVING_DB, "$voice held loop sits still: $moved dB")
            }
        }
    }

    @Test
    fun `SNAP fades as the crease recovers and TWOFOLD's octave blooms with pressure`() {
        val snap = render(CreaseVoice.SNAP)
        val early = rms(snap, .01, .08)
        val late = rms(snap, .7, .8)
        println("CREASE SNAP level early %.4f, at .7 s %.4f".format(early, late))
        assertTrue(early > 2.5 * late, "SNAP does not die away")
        val f = hz(48)
        fun octave(x: FloatArray) = M.component(x, 2 * f, .3, .4) / M.component(x, f, .3, .4)
        val light = octave(render(CreaseVoice.TWOFOLD, macros = mapOf("PRESSURE" to 0f, "FOLD" to 0f)))
        val pressed = octave(render(CreaseVoice.TWOFOLD, macros = mapOf("PRESSURE" to 1f)))
        println("CREASE TWOFOLD octave against root: light %.3f, pressed %.3f".format(light, pressed))
        assertTrue(pressed > 2 * light, "TWOFOLD's octave does not bloom")
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        fun note(n: KeyNote) = M.hash(n.snip.samples) + " loop " + n.loopStartFrame
        for (voice in Crease.KEPT_VOICES) {
            for (midi in listOf(24, 48, 84)) for (v in listOf(.4f, 1f)) lines +=
                "CREASE ${voice.name} m3 midi $midi v $v " + M.hash(Crease.render(voice, emptyMap(), midi, v).samples)
            lines += "CREASE ${voice.name} m2 " + M.hash(Crease.render(voice, emptyMap(), 48, .75f, model = 2).samples)
            lines += "CREASE ${voice.name} m1 " + M.hash(Crease.render(voice, emptyMap(), 48, .75f, model = 1).samples)
            lines += "CREASE ${voice.name} HOLD " + M.hash(Crease.render(voice, mapOf("HOLD" to 1f), 48, .75f).samples)
            for (p in CreasePresets.patchesForVoice(voice)) {
                lines += "CREASE preset ${p.name} ${M.hash(p.render().samples)}"
                for (midi in listOf(48, 72)) lines += "CREASE key ${p.name} $midi " + note(CreaseKeys.render(p, midi))
            }
        }
        val paper = CreasePresets.patches().first { it.name == "Paper Keys" }
        lines += "CREASE touch Paper Keys " + CreaseTouchKeys.render(paper, 60).joinToString(" ") { note(it) }
        assertEquals(KEPT_LINES, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the lines above, taken from the build before this round (c8df28195). */
        const val KEPT_RENDERS = "1c77f44d70c87509fdf26710118b4575c656fc72c6be52d12c005948de64d9c0"
        const val KEPT_LINES = 46
        const val ROOT_SHARE = .3
        /** The owner rejected touch that made "very little difference": a clear step, not a trace. */
        const val TOUCH_DB = 1.5
        /** After "All sound the same" on four engines (20 Nov): soft against hard at least 6 dB of tone. */
        const val TOUCH_SPAN_DB = 6.0
        const val MOVING_DB = .5
        /** HeldKeyHarshBandTest's ceilings for held Ink Ribbon, the brightest kept voice (20 Nov 2026). */
        val HARSH_CEILINGS = listOf(36 to -30.0, 60 to -8.5, 84 to 0.0)
    }
}
