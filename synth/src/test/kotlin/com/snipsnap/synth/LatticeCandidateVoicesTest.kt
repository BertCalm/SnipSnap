package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The voices of listening round lattice-voices-1, kept on 20 Nov 2026. Each hears a part of the
 * gate model the earlier voices leave still or sum away: VANE the analyzer angle in motion, RAKE
 * each gate's own rejection at its own time. BOW is the spare: a bowed contour and source on the
 * kept static path. The earlier voices' renders are pinned below against the build before this round.
 */
class LatticeCandidateVoicesTest {
    private val earlier = LatticeVoice.entries.take(4)
    private val candidates = LatticeVoice.entries.drop(4)
    private val notes = listOf(36, 48, 60, 72, 84)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }

    @Test
    fun `the kept new voices are appended and offered with their heard preset`() {
        assertEquals(listOf(LatticeVoice.VANE, LatticeVoice.RAKE, LatticeVoice.BOW), candidates)
        assertEquals(LatticeVoice.entries, Lattice.KEPT_VOICES)
        for (voice in candidates) {
            // One preset each: the default controls at C3, medium touch: the render kept in lattice-voices-1.
            val preset = Presets.forVoice("LATTICE", voice.name).single()
            assertEquals(LatticePatch(preset.name, voice, Lattice.defaults(voice)), preset)
            assertEquals(48, (preset as LatticePatch).midi)
            assertContentEquals(Lattice.render(voice).samples, preset.render().samples)
            val patch = LatticePatch("Candidate", voice, Lattice.defaults(voice), midi = 55, velocity = .6f)
            assertEquals(patch, LatticePatch.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            assertFalse(LatticeKeys.supports(patch))
        }
        assertEquals(listOf("TUNE", "SPEED", "HOLD"), Lattice.macrosFor(LatticeVoice.VANE).map { it.name })
        assertEquals(listOf("TUNE", "SPEED", "HOLD"), Lattice.macrosFor(LatticeVoice.RAKE).map { it.name })
        assertEquals(Lattice.macrosFor(LatticeVoice.WARM).map { it.name }, Lattice.macrosFor(LatticeVoice.BOW).map { it.name })
    }

    @Test
    fun `candidates render finite, bounded, closed and the same twice across the range`() {
        for (voice in candidates) {
            for (midi in notes) for (velocity in listOf(.3f, .75f, 1f)) {
                val x = Lattice.render(voice, midi = midi, velocity = velocity).samples
                assertTrue(x.all { it.isFinite() }, "$voice MIDI$midi v$velocity is not finite")
                assertTrue(x.maxOf { abs(it) } <= .99f, "$voice MIDI$midi v$velocity exceeds the engine's raw headroom")
                assertTrue(rms(x, 0.0, x.size.toDouble() / Dsp.RATE) > 1e-4, "$voice MIDI$midi v$velocity is silent")
                assertTrue(x.last() == 0f, "$voice MIDI$midi v$velocity ends open: ${x.last()}")
                assertTrue(abs(x.average()) < 1e-4, "$voice MIDI$midi v$velocity carries DC")
            }
            val macros = mapOf("SPEED" to .7f, "HOLD" to .4f, "TUNE" to .58f)
            assertContentEquals(Lattice.render(voice, macros, 50, .6f).samples, Lattice.render(voice, macros, 50, .6f).samples,
                "$voice is not deterministic")
            assertTrue(Lattice.render(voice, velocity = 0f).samples.all { it == 0f }, "$voice sounds at zero velocity")
            // Harder is louder: the fixed source gain is the only level control.
            val levels = listOf(.3f, .75f, 1f).map { v -> Lattice.render(voice, midi = 60, velocity = v).samples.let { rms(it, 0.0, 1.5) } }
            assertTrue(levels.zipWithNext().all { (a, b) -> b > a * 1.15 }, "$voice touch levels $levels")
        }
    }

    @Test
    fun `candidates sound the played note`() {
        for (voice in candidates) for (midi in notes) {
            val snip = Lattice.render(voice, midi = midi)
            val root = CandidateVoiceProbes.rootCents(snip.samples, hz(midi))
            val share = CandidateVoiceProbes.rootShare(snip.samples, hz(midi))
            val detected = assertNotNull(Pitch.detect(snip, .15f, .4f), "$voice MIDI$midi has no detectable pitch")
            val cents = 1200 * ln(detected.hz / hz(midi)) / ln(2.0)
            println("LATTICE $voice MIDI$midi root %+.0f cents (share %.2f), detector %+.1f cents (confidence %.2f)"
                .format(root, share, cents, detected.confidence))
            assertTrue(abs(root) <= 15.0, "$voice MIDI$midi root is $root cents off")
            assertTrue(share > .15, "$voice MIDI$midi has a weak root: $share")
            assertTrue(abs(cents) <= 15.0 && detected.confidence >= .5f, "$voice MIDI$midi detected $cents cents")
        }
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        for (midi in listOf(48, 60)) {
            val shapes = LatticeVoice.entries.associateWith { CandidateVoiceProbes.bands(Lattice.render(it, midi = midi).samples) }
            for (a in candidates) for (b in LatticeVoice.entries) if (a != b) {
                val d = CandidateVoiceProbes.distance(shapes.getValue(a), shapes.getValue(b))
                println("LATTICE third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                assertTrue(d >= CandidateVoiceProbes.DISTINCT_DB, "$a and $b are only $d dB apart at MIDI $midi")
            }
        }
    }

    /** Depth of the level swing at [rate] Hz in a 20 ms envelope, as a share of the mean level. */
    private fun swing(x: FloatArray, rate: Double, from: Double = .3, to: Double = 2.3): Double {
        val step = .02
        val count = ((to - from) / step).toInt()
        val env = DoubleArray(count) { ln(rms(x, from + it * step, from + (it + 1) * step) + 1e-9) }
        // Remove the decay, then read the envelope at the vane rate.
        val n = count.toDouble()
        val mx = (count - 1) / 2.0
        val my = env.average()
        val slope = env.indices.sumOf { (it - mx) * (env[it] - my) } / env.indices.sumOf { (it - mx) * (it - mx) }
        var re = 0.0
        var im = 0.0
        for (i in env.indices) {
            val v = env[i] - my - slope * (i - mx)
            re += v * kotlin.math.cos(2 * Math.PI * rate * i * step)
            im += v * kotlin.math.sin(2 * Math.PI * rate * i * step)
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    @Test
    fun `VANE trades the tone for its outline at the vane rate and SPEED sets that rate`() {
        assertEquals(4.0, Lattice.vaneHz(.5f), 1e-12)
        for ((speed, rate) in listOf(0f to 2.0, .5f to 4.0, 1f to 8.0)) {
            val x = Lattice.render(LatticeVoice.VANE, mapOf("SPEED" to speed), 60).samples
            val at = swing(x, rate)
            val off = listOf(rate * .62, rate * 1.45).maxOf { swing(x, it) }
            println("LATTICE VANE SPEED $speed: swing at $rate Hz %.2f, elsewhere %.2f".format(at, off))
            assertTrue(at > .25 && at > 2.5 * off, "VANE SPEED $speed does not turn at $rate Hz: $at against $off")
        }
        // A kept pluck has no such turn.
        assertTrue(swing(Lattice.render(LatticeVoice.TRACE, midi = 60).samples, 4.0, .5, 1.9) < .05)
        // The turn starts open: the first 30 ms carry the attack whole at every speed.
        val starts = listOf(0f, 1f).map { rms(Lattice.render(LatticeVoice.VANE, mapOf("SPEED" to it), 60).samples, 0.0, .03) }
        assertEquals(starts[0], starts[1], starts[0] * .2)
        // HOLD keeps the mallet's effort up for longer; the note stays finite.
        val held = Lattice.render(LatticeVoice.VANE, mapOf("HOLD" to 1f), 60)
        assertEquals(DrumClass.TONAL, Lattice.drumClassFor(LatticeVoice.VANE, mapOf("HOLD" to 1f)))
        assertTrue(held.samples.size > Lattice.render(LatticeVoice.VANE, midi = 60).samples.size + 3 * Dsp.RATE)
        assertFailsWith<IllegalArgumentException> { Lattice.renderLoop(LatticeVoice.VANE) }
    }

    @Test
    fun `RAKE gates speak in order, each at its own time, and a harder pick carries further`() {
        val f = hz(60)
        fun level(x: FloatArray, harmonic: Int, from: Double) = CandidateVoiceProbes.component(x, f * harmonic, from, .08)
        for ((speed, step) in listOf(0f to .090, .5f to .045, 1f to .0225)) {
            assertEquals(step, Lattice.rakeStepSeconds(speed), 1e-12)
            val x = Lattice.render(LatticeVoice.RAKE, mapOf("SPEED" to speed), 60).samples
            for (bar in listOf(6, 9, 12)) {
                val arrival = (bar - 1) * step
                // The pick's own short burst has gone by 0.17 s; the bar is quiet until the wave reaches its gate.
                val before = level(x, bar, maxOf(.17, arrival - .1))
                val after = level(x, bar, arrival + .08)
                println("LATTICE RAKE SPEED $speed bar $bar: before %.5f, after %.5f".format(before, after))
                if (arrival - .1 >= .17) assertTrue(after > 6 * before, "RAKE bar $bar did not wait for its gate at SPEED $speed")
                assertTrue(after > 1e-4, "RAKE bar $bar never spoke at SPEED $speed")
            }
        }
        // Touch changes how far up the row the rake carries, not only the level.
        fun reach(velocity: Float): Double {
            val x = Lattice.render(LatticeVoice.RAKE, midi = 60, velocity = velocity).samples
            return level(x, 12, .65) / level(x, 1, .65)
        }
        val soft = reach(.3f)
        val hard = reach(1f)
        println("LATTICE RAKE bar 12 against bar 1: soft %.3f, hard %.3f".format(soft, hard))
        assertTrue(hard > 3 * soft, "RAKE touch did not change its reach: $soft, $hard")
        assertFailsWith<IllegalArgumentException> { Lattice.renderLoop(LatticeVoice.RAKE) }
        assertFalse(Lattice.isLoop(LatticeVoice.RAKE, 1f))
    }

    @Test
    fun `BOW holds its effort, starts slower when played softly and loops at the top of HOLD`() {
        val x = Lattice.render(LatticeVoice.BOW, midi = 60).samples
        assertEquals(1.0, rms(x, .6, .8) / rms(x, .3, .5), .05)
        // A kept pluck at the same controls has already decayed there.
        val warm = Lattice.render(LatticeVoice.WARM, Lattice.defaults(LatticeVoice.BOW), 60).samples
        assertTrue(rms(warm, .6, .8) < .2 * rms(warm, .05, .15))
        fun rise(velocity: Float): Double {
            val y = Lattice.render(LatticeVoice.BOW, midi = 60, velocity = velocity).samples
            val full = rms(y, .3, .5)
            return (0 until 400).first { rms(y, it * .001, it * .001 + .01) >= .7 * full } * .001
        }
        val soft = rise(.3f)
        val hard = rise(1f)
        println("LATTICE BOW reaches 70 percent after %.3f s soft, %.3f s hard".format(soft, hard))
        assertTrue(soft > hard + .03, "BOW touch did not change its start: $soft, $hard")
        assertTrue(Lattice.isLoop(LatticeVoice.BOW, 1f))
        assertEquals(DrumClass.LOOP, Lattice.drumClassFor(LatticeVoice.BOW, mapOf("HOLD" to 1f)))
        val loop = Lattice.heldProbe(Lattice.settingsFor(LatticeVoice.BOW, mapOf("HOLD" to 1f), 60))
        assertTrue(loop.seamError < Keys.MAX_SEAM_ERROR)
        assertContentEquals(loop.snip.samples, Lattice.renderLoop(LatticeVoice.BOW, midi = 60))
    }

    /**
     * The owner on the candidates' soft, medium, hard clips (20 Nov 2026): "I didn't hear a
     * difference" / "Very little difference". Touch now leans the source: the brightness measure
     * is the share of the note's energy in its sample-to-sample change, which loudness cannot move.
     */
    @Test
    fun `touch changes the tone of every candidate and leaves the medium touch as it was kept`() {
        fun brightness(x: FloatArray): Double {
            var energy = 0.0
            var change = 0.0
            for (i in 0 until x.size - 1) { val d = x[i + 1] - x[i].toDouble(); energy += x[i].toDouble() * x[i]; change += d * d }
            return 10 * kotlin.math.log10(change / energy)
        }
        for (voice in candidates) for (midi in listOf(48, 60)) {
            val now = listOf(.3f, .75f, 1f).map { brightness(Lattice.render(voice, midi = midi, velocity = it).samples) }
            val before = listOf(.3f, .75f, 1f).map { brightness(Lattice.renderPlayed(voice, midi = midi, velocity = it, human = null, touch = 0.0).samples) }
            println("LATTICE $voice MIDI$midi brightness soft/medium/hard now %.1f %.1f %.1f dB, before %.1f %.1f %.1f dB"
                .format(now[0], now[1], now[2], before[0], before[1], before[2]))
            assertTrue(now[1] - now[0] > 3.0 && now[2] - now[1] > 1.5, "$voice MIDI$midi touch does not change the tone: $now")
            assertContentEquals(Lattice.renderPlayed(voice, midi = midi, velocity = .75f, human = null, touch = 0.0).samples,
                Lattice.render(voice, midi = midi, velocity = .75f).samples, "$voice MIDI$midi medium touch moved")
        }
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        // Six presets are left out: the held WARM and PLUCK presets, which the owner asked to be
        // humanized on 20 Nov 2026. LatticeHoldDriftTest pins what they play now, and proves that
        // with the drift off they still render these exact old bytes.
        val lines = ArrayList<String>()
        for (voice in earlier) {
            for (midi in listOf(41, 60)) for (v in listOf(.4f, .75f)) lines +=
                "LATTICE ${voice.name} default midi $midi v $v ${CandidateVoiceProbes.hash(Lattice.render(voice, midi = midi, velocity = v).samples)}"
            for (p in LatticePresets.forVoice(voice)) if (p.name !in HUMANIZED_PRESETS)
                lines += "LATTICE preset ${p.name} ${CandidateVoiceProbes.hash(p.render().samples)}"
        }
        assertEquals(25, lines.size)
        assertEquals(KEPT_RENDERS, CandidateVoiceProbes.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /**
         * SHA-256 over the 25 lines above, taken from the build before the candidates (fbfda867d):
         * the same per-line hashes as the earlier 31-line pin, less the six humanized presets.
         */
        const val KEPT_RENDERS = "ae7e8d79bf083ae24f5ce5a1ac1b80ca5eedf81015000416d2a1d1c513abef58"
        val HUMANIZED_PRESETS = setOf("Close Weave", "Soft Outline", "Warm Thread", "Plucked Outline", "Split Pluck", "Pluck Thread")
    }
}
