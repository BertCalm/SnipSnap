package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlockTest {
    private fun quick(
        voice: FlockVoice = FlockVoice.RIBBON,
        macros: Map<String, Float> = Flock.defaults(voice) + ("HOLD" to 0f),
        midi: Int = 60, velocity: Float = 1f, seed: Long = 42001,
        mode: FlockMode = FlockMode.FLOCK, perturb: Boolean = false,
        model: Int = Flock.MODEL_VERSION,
    ) = Flock.renderDetailed(voice, macros, midi, velocity, seed, mode, perturb, oversample = 1, model = model)

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        return sqrt((0 until n).sumOf { (a[it].toDouble() - b[it]).let { d -> d * d } } / n)
    }

    @Test
    fun `saved seed and macro order reproduce the complete performance`() {
        for (voice in FlockVoice.entries) {
            val macros = Flock.defaults(voice) + ("HOLD" to 0f)
            val a = quick(voice, macros, velocity = .67f, seed = Long.MIN_VALUE)
            val b = quick(voice, macros.entries.reversed().associate { it.key to it.value }, velocity = .67f, seed = Long.MIN_VALUE)
            assertContentEquals(a.rawSamples, b.rawSamples, "$voice raw determinism")
            assertContentEquals(a.samples, b.samples, "$voice leveled determinism")
            assertEquals(a.trace, b.trace)
            assertEquals(a.diagnostics, b.diagnostics)
            assertFalse(a.rawSamples.contentEquals(quick(voice, macros, velocity = .67f, seed = Long.MAX_VALUE).rawSamples), "$voice seed has no effect")
        }
    }

    @Test
    fun `single particle impulse reaches others only through neighbor interaction`() {
        val macros = Flock.defaults(FlockVoice.RIBBON) + mapOf("SPREAD" to .60f, "PULL" to .35f, "FLOW" to .85f, "SCATTER" to .35f, "HOLD" to 0f)
        val reference = quick(macros = macros)
        val impulse = quick(macros = macros, perturb = true)
        val independent = quick(macros = macros, mode = FlockMode.INDEPENDENT)
        val independentImpulse = quick(macros = macros, mode = FlockMode.INDEPENDENT, perturb = true)
        for ((a, b) in reference.trace.zip(impulse.trace)) {
            if (a.seconds <= 1.0) assertEquals(a, b, "impulse leaked before its declared time")
        }
        for ((a, b) in independent.trace.zip(independentImpulse.trace)) {
            for (i in 1 until Flock.PARTICLES) {
                assertEquals(a.pitchSemitones[i], b.pitchSemitones[i], "independent particle $i received another particle's impulse")
                assertEquals(a.energy[i], b.energy[i])
            }
        }
        val affected = (1 until Flock.PARTICLES).count { i ->
            reference.trace.zip(impulse.trace).any { (a, b) -> a.seconds in 1.05..1.8 && abs(a.pitchSemitones[i] - b.pitchSemitones[i]) > .001 }
        }
        assertTrue(affected >= 8, "the disturbance reached only $affected neighbors")
        val indirect = (1 until Flock.PARTICLES).filter { i ->
            val first = reference.trace.zip(impulse.trace).firstOrNull { (a, b) ->
                a.seconds > 1.0 && abs(a.pitchSemitones[i] - b.pitchSemitones[i]) > 1e-10
            }?.first?.seconds
            // Compare with every directed graph snapshot before that response. A moving
            // neighborhood must not make a later direct encounter look like propagation.
            first != null && first <= 1.25 && (impulse.diagnostics.perturbationNeighbors + reference.diagnostics.perturbationNeighbors)
                .filter { it.seconds < first - .5 / Flock.CONTROL_RATE }
                .none { i in it.receiversOfZero }
        }
        assertTrue(indirect.isNotEmpty(), "no particle responded through an indirect neighbor path")
        assertTrue(impulse.diagnostics.directNeighborsAtImpulse.size < Flock.PARTICLES / 2,
            "the impulse was connected directly to most of the flock")
        // Shared raw gain is necessary for the published listening pair: per-clip leveling
        // would change the earlier audio even though the causal state is identical there.
        assertContentEquals(reference.rawSamples.copyOfRange(0, Dsp.RATE), impulse.rawSamples.copyOfRange(0, Dsp.RATE))
        assertTrue(distance(reference.rawSamples, impulse.rawSamples) > .002, "causal motion has no audible output")
        assertTrue(reference.diagnostics.neighborMean in 3.0..4.0)
        assertEquals(0.0, independent.diagnostics.neighborMean)
    }

    @Test
    fun `fixed detuning and independent motion preserve source while isolating group behavior`() {
        val flock = quick()
        val fixed = quick(mode = FlockMode.FIXED)
        val independent = quick(mode = FlockMode.INDEPENDENT)
        assertEquals(flock.trace.first(), fixed.trace.first())
        assertEquals(flock.trace.first(), independent.trace.first())
        assertTrue(fixed.trace.all { it.pitchSemitones == fixed.trace.first().pitchSemitones }, "FIXED moved")
        assertTrue(independent.trace.any { it.pitchSemitones != independent.trace.first().pitchSemitones }, "INDEPENDENT froze")
        assertTrue(distance(flock.rawSamples, independent.rawSamples) > .01)
        assertTrue(distance(flock.rawSamples, fixed.rawSamples) > .01)
        assertTrue(distance(independent.rawSamples, fixed.rawSamples) > .01)
    }

    @Test
    fun `six macros have authority and HOLD remains finite at its maximum`() {
        val voice = FlockVoice.RIBBON
        val base = Flock.defaults(voice) + ("HOLD" to 0f)
        for (macro in listOf("SPREAD", "PULL", "FLOW", "SCATTER", "EDGE")) {
            val low = quick(voice, base + (macro to 0f))
            val high = quick(voice, base + (macro to 1f))
            assertTrue(distance(low.rawSamples, high.rawSamples) > .002, "$macro has no audible authority")
            if (macro == "EDGE") assertEquals(low.trace, high.trace, "EDGE changed the flock instead of its carrier")
            else assertTrue(low.trace != high.trace, "$macro did not change the group state")
        }
        val short = quick(voice, base)
        val long = quick(voice, base + ("HOLD" to 1f))
        assertEquals(2 * Dsp.RATE, short.samples.size)
        assertEquals(6 * Dsp.RATE, long.samples.size)
        assertEquals(short.trace, long.trace.take(short.trace.size), "HOLD changed earlier simulated behavior")
        assertEquals(DrumClass.TONAL, Flock.drumClassFor(voice, base + ("HOLD" to 1f)))
        assertEquals(0f, long.samples.last())
    }

    @Test
    fun `velocity zero is silent and changes the scatter at positive energy`() {
        for (voice in FlockVoice.entries) {
            val zero = quick(voice, velocity = 0f)
            val soft = quick(voice, velocity = .25f)
            val hard = quick(voice, velocity = 1f)
            assertTrue(zero.samples.all { it == 0f })
            assertTrue(zero.rawSamples.all { it == 0f })
            assertTrue(hard.trace.first() != soft.trace.first(), "$voice velocity did not disturb the initial state")
            assertTrue(hard.diagnostics.rawRms > soft.diagnostics.rawRms)
        }
    }

    @Test
    fun `edge registers and extreme coordinates stay finite with audible mono carriers`() {
        for (voice in FlockVoice.entries) for (midi in listOf(36, 84)) {
            val high = Flock.defaults(voice).mapValues { (k, _) -> if (k == "HOLD") 0f else 1f }
            val p = quick(voice, high, midi)
            assertEquals(1, p.snip.channels)
            assertEquals(44_100, p.sampleRate)
            assertTrue(p.samples.all { it.isFinite() })
            assertTrue(p.diagnostics.rawPeak < .50, "$voice/$midi raw peak ${p.diagnostics.rawPeak}")
            assertTrue(p.diagnostics.rawRms > .005, "$voice/$midi lost its carrier")
            assertTrue(p.diagnostics.outputPeak <= .940001)
            assertTrue(abs(p.samples.average()) < .01)
            assertTrue(p.trace.all { f -> f.pitchSemitones.all { it.isFinite() } && f.energy.all { it.isFinite() } })
            if (voice == FlockVoice.SPLIT) {
                val last = p.trace.last().pitchSemitones
                assertTrue(last.slice(8..15).average() - last.slice(0..7).average() > 5.0)
                assertTrue(last.slice(16..23).average() - last.slice(0..7).average() > 10.0)
            }
        }
    }

    @Test
    fun `four times audio rendering retains the same control trajectory and obeys cancellation`() {
        val macros = Flock.defaults(FlockVoice.GATHER) + ("HOLD" to 0f)
        val native = quick(FlockVoice.GATHER, macros)
        val four = Flock.renderDetailed(FlockVoice.GATHER, macros, seed = 42001, oversample = 4)
        assertEquals(native.trace, four.trace)
        assertEquals(native.samples.size, four.samples.size)
        assertTrue(distance(native.rawSamples, four.rawSamples) < .01, "oversampling changed the instrument's motion")
        var checks = 0
        assertFailsWith<CancellationException> {
            Flock.renderDetailed(FlockVoice.GATHER, macros, cancelled = { ++checks > 3 })
        }
    }

    @Test
    fun `FLOW clock is monotonic and exactly preserves each accepted voice default`() {
        for (voice in FlockVoice.entries) {
            val base = Flock.defaults(voice) + ("HOLD" to 0f)
            val defaultFlow = base.getValue("FLOW")
            assertEquals(.12, Flock.motionRate(voice, 0f))
            assertEquals(1.0, Flock.motionRate(voice, defaultFlow))
            assertEquals(4.0, Flock.motionRate(voice, 1f))
            val rates = (0..100).map { Flock.motionRate(voice, it / 100f) }
            assertTrue(rates.zipWithNext().all { (a, b) -> b > a }, "$voice rate is not monotonic")
            assertTrue((0..100).all { Flock.motionRate(voice, it / 100f, model = 1) == 1.0 })
            val original = quick(voice, base, seed = Long.MAX_VALUE, model = 1)
            val current = quick(voice, base, seed = Long.MAX_VALUE, model = 2)
            assertContentEquals(original.rawSamples, current.rawSamples, "$voice default raw sound changed")
            assertContentEquals(original.samples, current.samples, "$voice default leveled sound changed")
            assertEquals(original.trace, current.trace, "$voice default group behavior changed")
            assertEquals(original.diagnostics, current.diagnostics)
        }
        assertFailsWith<IllegalArgumentException> { Flock.motionRate(FlockVoice.RIBBON, .5f, model = 3) }
        assertFailsWith<IllegalArgumentException> { quick(model = 0) }
    }

    @Test
    fun `FLOW changes sustained group rate as well as attack timing`() {
        val companion = mapOf("SPREAD" to .55f, "PULL" to .55f, "FLOW" to .65f,
            "SCATTER" to .55f, "EDGE" to .4f, "HOLD" to 1f)
        data class Motion(val speed: Double, val depth: Double, val characteristicHz: Double)
        fun motion(probe: Flock.Probe): Motion {
            val frames = probe.trace.filter { it.seconds in 2.5..5.5 }
            val centers = (0 until Flock.PARTICLES).map { i -> frames.map { it.pitchSemitones[i] }.average() }
            val depth = sqrt(frames.sumOf { f -> centers.indices.sumOf { i ->
                val d = f.pitchSemitones[i] - centers[i]; d * d
            } } / (frames.size * Flock.PARTICLES))
            var speedSquaredTime = 0.0
            var duration = 0.0
            for ((a, b) in frames.zipWithNext()) {
                val dt = b.seconds - a.seconds
                speedSquaredTime += centers.indices.sumOf { i ->
                    val v = (b.pitchSemitones[i] - a.pitchSemitones[i]) / dt; v * v
                } * dt
                duration += dt
            }
            val speed = sqrt(speedSquaredTime / (duration * Flock.PARTICLES))
            return Motion(speed, depth, speed / depth / (2.0 * kotlin.math.PI))
        }
        val measured = listOf(0f, .25f, .5f, .75f, 1f).map { flow ->
            motion(quick(macros = companion + ("FLOW" to flow), seed = Flock.DEFAULT_SEED))
        }
        assertTrue(measured.all { it.depth > .003 && it.speed.isFinite() && it.characteristicHz.isFinite() }, "$measured")
        assertTrue(measured.last().characteristicHz > 2.0 * measured.first().characteristicHz,
            "FLOW did not increase sustained motion rate: $measured")
        assertTrue(measured.last().characteristicHz > 1.4 * measured[2].characteristicHz,
            "FLOW upper half has little sustained rate authority: $measured")

        fun halfway(probe: Flock.Probe, voice: FlockVoice): Double {
            fun excursion(f: Flock.TraceFrame): Double = sqrt(f.pitchSemitones.indices.sumOf { i ->
                val anchor = if (voice != FlockVoice.SPLIT) 0.0 else when (i / 8) { 1 -> 7.01955; 2 -> 12.0; else -> 0.0 }
                val d = f.pitchSemitones[i] - anchor; d * d
            } / Flock.PARTICLES)
            val half = excursion(probe.trace.first()) * .5
            return probe.trace.firstOrNull { excursion(it) <= half }?.seconds ?: probe.diagnostics.durationSeconds
        }
        for (voice in FlockVoice.entries) {
            val macros = Flock.defaults(voice) + ("HOLD" to 1f)
            val slow = quick(voice, macros + ("FLOW" to 0f), seed = Flock.DEFAULT_SEED)
            val fast = quick(voice, macros + ("FLOW" to 1f), seed = Flock.DEFAULT_SEED)
            val slowHalf = halfway(slow, voice)
            val fastHalf = halfway(fast, voice)
            assertTrue(fastHalf < 1.0 && slowHalf > fastHalf * 4.0,
                "$voice FLOW did not change gathering time: slow=$slowHalf fast=$fastHalf")
            assertEquals(slow.samples.size, fast.samples.size, "$voice FLOW changed wall-clock duration")
            assertEquals(slow.trace.first(), fast.trace.first(), "$voice FLOW changed seeded scatter")
        }
    }
}
