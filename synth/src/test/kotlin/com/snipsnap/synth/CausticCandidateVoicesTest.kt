package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round caustic-voices-1. Each reads a part of the reflected-path
 * geometry the kept voices discard: BOWL where on the rim each ray landed, SCATTER when each ray
 * arrived. TONGUE is the spare: a new mode table on the kept reception. Nothing here is heard.
 * The kept renders are pinned below against the build before this round.
 */
class CausticCandidateVoicesTest {
    private val candidates = CausticVoice.entries.filter { it !in CausticInstrument.KEPT_VOICES }
    private val notes = listOf(36, 48, 60, 72, 84)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = .75f) =
        CausticInstrument.render(voice, macros, midi, velocity).samples

    @Test
    fun `candidates are appended, finite only and offered nowhere`() {
        assertEquals(listOf(CausticVoice.BOWL, CausticVoice.SCATTER, CausticVoice.TONGUE), candidates)
        assertEquals(CausticVoice.entries.take(4), CausticInstrument.KEPT_VOICES)
        assertTrue(CausticPresets.all().all { it.voice in CausticInstrument.KEPT_VOICES }, "a candidate has a factory preset")
        for (voice in candidates) {
            assertTrue(Presets.forVoice("CAUSTIC", voice.name).isEmpty(), "$voice reaches the preset picker")
            for (model in listOf(1, 2)) assertEquals(listOf("SOURCE", "FOCUS", "BODY", "LENGTH", "HOLD"),
                CausticInstrument.macrosFor(voice, model).map { it.name })
            val patch = CausticPatch("Candidate", voice, mapOf("SOURCE" to .3f, "HOLD" to .5f), midi = 55, velocity = .6f)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            // HOLD lengthens the finite note at every step; there is no sustained loop or MOTION yet.
            assertFalse(CausticInstrument.isLoop(voice, mapOf("HOLD" to 1f)))
            val length = CausticInstrument.defaults(voice).getValue("LENGTH")
            assertEquals(.5 + 7.5 * length + 4.0, CausticInstrument.durationSeconds(voice, mapOf("HOLD" to 1f)), 1e-5)
            assertEquals(DrumClass.LOOP, CausticInstrument.drumClassFor(voice, mapOf("HOLD" to 1f)))
            assertFailsWith<IllegalArgumentException> { CausticInstrument.renderLoop(voice, mapOf("HOLD" to 1f)) }
            assertFailsWith<IllegalArgumentException> { CausticInstrument.heldProbe(voice, mapOf("HOLD" to 1f)) }
            assertContentEquals(render(voice, mapOf("LENGTH" to .1f, "HOLD" to 1f)),
                CausticInstrument.keyNote(voice, mapOf("LENGTH" to .1f, "HOLD" to 1f)).snip.samples)
        }
    }

    @Test
    fun `candidates render finite, bounded and the same twice across the range`() {
        for (voice in candidates) {
            for (midi in notes) for (velocity in listOf(.3f, .75f, 1f)) {
                val probe = CausticInstrument.probe(voice, midi = midi, velocity = velocity)
                val x = probe.samples
                assertTrue(x.all { it.isFinite() }, "$voice MIDI$midi v$velocity is not finite")
                assertTrue(x.maxOf { abs(it) } <= .99f, "$voice MIDI$midi v$velocity exceeds the engine's ceiling")
                assertTrue(probe.diagnostics.internalPeak < 8.0, "$voice MIDI$midi v$velocity raw state ${probe.diagnostics.internalPeak}")
                assertTrue(rms(x, 0.0, 1.0) > 1e-3, "$voice MIDI$midi v$velocity is silent")
                assertTrue(abs(x.last()) < 1e-3f, "$voice MIDI$midi v$velocity ends open: ${x.last()}")
                assertEquals(Math.round(CausticInstrument.durationSeconds(voice) * Dsp.RATE).toInt(), x.size)
            }
            val macros = mapOf("SOURCE" to .31f, "FOCUS" to .42f, "BODY" to .7f, "LENGTH" to .2f, "HOLD" to .25f)
            assertContentEquals(render(voice, macros, 50, .6f), render(voice, macros, 50, .6f), "$voice is not deterministic")
            assertContentEquals(CausticInstrument.render(voice, mapOf("LENGTH" to .2f), 50, .6f, model = 1).samples,
                CausticInstrument.render(voice, mapOf("LENGTH" to .2f), 50, .6f, model = 2).samples, "$voice differs between models")
            assertTrue(render(voice, velocity = 0f).all { it == 0f }, "$voice sounds at zero velocity")
            // Every corner of the geometry and body still sounds and stays bounded.
            for (source in listOf(0f, 1f)) for (focus in listOf(0f, 1f)) for (body in listOf(0f, 1f)) {
                val x = render(voice, mapOf("SOURCE" to source, "FOCUS" to focus, "BODY" to body, "LENGTH" to .1f), 43)
                assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= .99f, "$voice corner $source/$focus/$body")
                assertTrue(rms(x, 0.0, .5) > 1e-3, "$voice corner $source/$focus/$body is silent")
            }
        }
    }

    @Test
    fun `candidates sound the played note`() {
        for (voice in candidates) for (midi in notes) {
            val x = render(voice, midi = midi)
            // After the contact (and SCATTER's spill) the body rings alone.
            val root = CandidateVoiceProbes.rootCents(x, hz(midi), .7, 1.0)
            val share = CandidateVoiceProbes.rootShare(x, hz(midi), .7, 1.0)
            println("CAUSTIC $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            assertTrue(abs(root) <= 15.0, "$voice MIDI$midi root is $root cents off")
            assertTrue(share > .3, "$voice MIDI$midi has a weak root: $share")
        }
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        for (midi in listOf(48, 60)) {
            val shapes = CausticVoice.entries.associateWith { CandidateVoiceProbes.bands(render(it, midi = midi)) }
            for (a in candidates) for (b in CausticVoice.entries) if (a != b) {
                val d = CandidateVoiceProbes.distance(shapes.getValue(a), shapes.getValue(b))
                println("CAUSTIC third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                assertTrue(d >= CandidateVoiceProbes.DISTINCT_DB, "$a and $b are only $d dB apart at MIDI $midi")
            }
        }
    }

    /** Loudest over quietest level of one partial in successive 80 ms windows. */
    private fun swell(x: FloatArray, partialHz: Double, from: Double = .2, to: Double = 2.6): Double {
        val levels = generateSequence(from) { it + .04 }.takeWhile { it + .08 <= to }
            .map { CandidateVoiceProbes.component(x, partialHz, it, .08) }.toList()
        // Compare neighbours half a beat apart at most, so the slow decay does not count as a swell.
        return levels.indices.maxOf { i -> (i..minOf(i + 12, levels.lastIndex)).maxOf { j ->
            maxOf(levels[i], levels[j]) / minOf(levels[i], levels[j]) } }
    }

    @Test
    fun `BOWL beats, and the lit arc of the rim decides which partials are struck and how deeply they beat`() {
        val f = hz(60)
        val default = render(CausticVoice.BOWL)
        val parallel = render(CausticVoice.BOWL, mapOf("SOURCE" to 0f))
        val defaultSwell = swell(default, f)
        val parallelSwell = swell(parallel, f)
        println("CAUSTIC BOWL root swell: default %.2f, SOURCE 0 %.2f".format(defaultSwell, parallelSwell))
        assertTrue(defaultSwell > 1.6, "BOWL root does not beat: $defaultSwell")
        assertTrue(defaultSwell > 1.25 * parallelSwell, "SOURCE did not change the root's beat depth")
        // A struck bar of the kept ALLOY has no such pair on its root partial's slow envelope.
        fun second(x: FloatArray) = CandidateVoiceProbes.component(x, 2.71 * f, .05, .4) / CandidateVoiceProbes.component(x, f, .05, .4)
        println("CAUSTIC BOWL second partial against root: default %.3f, SOURCE 0 %.3f".format(second(default), second(parallel)))
        assertTrue(second(default) > 2 * second(parallel) || second(parallel) > 2 * second(default),
            "SOURCE did not change which partials are struck")
        // A harder mallet is brighter, not only louder.
        val touch = listOf(.3f, 1f).map { second(render(CausticVoice.BOWL, velocity = it)) }
        assertTrue(touch[1] > 1.3 * touch[0], "BOWL touch did not brighten: $touch")
    }

    @Test
    fun `SCATTER arrivals are separate in time until FOCUS gathers them into one strike`() {
        fun halfway(x: FloatArray): Double {
            val env = DoubleArray(160) { rms(x, it * .005, it * .005 + .005) }
            return env.indexOfFirst { it >= .5 * env.max() } * .005
        }
        val spilled = halfway(render(CausticVoice.SCATTER))
        val gathered = halfway(render(CausticVoice.SCATTER, mapOf("FOCUS" to 1f)))
        println("CAUSTIC SCATTER reaches half its level after %.3f s spilled, %.3f s gathered".format(spilled, gathered))
        assertTrue(spilled >= .05, "SCATTER default is one strike: $spilled")
        assertTrue(gathered <= .02, "FOCUS 1 did not gather the spill: $gathered")
        // LENGTH scales the spill with the ring.
        val long = halfway(render(CausticVoice.SCATTER, mapOf("LENGTH" to 1f)))
        assertTrue(long > 1.5 * spilled, "LENGTH did not stretch the spill: $spilled, $long")
    }

    @Test
    fun `TONGUE has a short high tick over a long root`() {
        val f = hz(60)
        val x = render(CausticVoice.TONGUE)
        fun tick(from: Double) = CandidateVoiceProbes.component(x, 6.27 * f, from, .1) / CandidateVoiceProbes.component(x, f, from, .1)
        println("CAUSTIC TONGUE tick against root: start %.3f, after one second %.5f".format(tick(0.0), tick(1.0)))
        assertTrue(tick(0.0) > .03, "TONGUE has no tick")
        assertTrue(tick(0.0) > 20 * tick(1.0), "TONGUE's tick does not die before its root")
        val touch = listOf(.3f, 1f).map { v -> render(CausticVoice.TONGUE, velocity = v).let {
            CandidateVoiceProbes.component(it, 6.27 * f, 0.0, .1) / CandidateVoiceProbes.component(it, f, 0.0, .1) } }
        assertTrue(touch[1] > 1.2 * touch[0], "TONGUE touch did not brighten: $touch")
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (voice in CausticInstrument.KEPT_VOICES) {
            for (model in listOf(1, 2)) for (midi in listOf(43, 60)) for (v in listOf(.4f, .75f)) lines +=
                "CAUSTIC ${voice.name} model $model LENGTH .15 midi $midi v $v " +
                    CandidateVoiceProbes.hash(CausticInstrument.render(voice, mapOf("LENGTH" to .15f), midi, v, model).samples)
            lines += "CAUSTIC ${voice.name} model 2 motion " + CandidateVoiceProbes.hash(CausticInstrument.render(voice,
                mapOf("LENGTH" to .1f, "MOTION" to .5f, "HOLD" to .3f), 55, .6f, 2).samples)
            lines += "CAUSTIC ${voice.name} model 2 held " + CandidateVoiceProbes.hash(CausticInstrument.render(voice,
                mapOf("LENGTH" to .1f, "MOTION" to .4f, "HOLD" to 1f), 55, .6f, 2).samples)
            for (p in CausticPresets.forVoice(voice).take(2)) lines += "CAUSTIC preset ${p.name} ${CandidateVoiceProbes.hash(p.render().samples)}"
        }
        assertEquals(45, lines.size)
        assertEquals(KEPT_RENDERS, CandidateVoiceProbes.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the 45 lines above, taken from the build before this round (fbfda867d). */
        const val KEPT_RENDERS = "d4d94c9f6d4452e2af16833e56a1fcebba75ade7b043451468148ebe571e8900"
    }
}
