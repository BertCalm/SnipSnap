package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatticeKeysTest {
    private data class Example(val midi: Int, val speed: Float, val probe: LatticeKeyProbe)

    companion object {
        private fun patch(speed: Float = .5f, velocity: Float = .75f) = LatticePatch("Trace Keys",
            LatticeVoice.TRACE, Lattice.defaults(LatticeVoice.TRACE) + ("SPEED" to speed), velocity = velocity)

        // Evidence is immutable between checks; every case runs its own source and collector.
        private val examples by lazy {
            listOf(48, 60, 72).flatMap { midi -> listOf(0f, .5f, 1f).map { speed ->
                Example(midi, speed, LatticeKeys.probe(patch(speed), midi))
            } }
        }
    }

    @Test
    fun `legacy held model keeps its original pluck and floor`() {
        val velocity = .75f
        val attack = .025 - .013 * velocity
        assertEquals(0.0, LatticeKeys.sourceEffort(0.0, velocity, 1), "the causal source must start at rest")
        for (i in 1..300) {
            val t = i / 1000.0
            if (t >= attack) assertEquals(exp(-t / .23), LatticeKeys.sourceEffort(t, velocity, 1), 1e-15)
        }
        var previous = LatticeKeys.sourceEffort(.30, velocity, 1)
        for (i in 1..1500) {
            val value = LatticeKeys.sourceEffort(.30 + i / 10_000.0, velocity, 1)
            assertTrue(value <= previous + 1e-14, "source effort rose during transition at $i")
            assertTrue(value >= .20 - 1e-14, "source effort undershot the floor at $i")
            previous = value
        }
        assertEquals(.20, LatticeKeys.sourceEffort(.45, velocity, 1))
        assertEquals(.20, LatticeKeys.sourceEffort(20.0, velocity, 1))
        val epsilon = 1e-7
        val before = (LatticeKeys.sourceEffort(.30, velocity, 1) - LatticeKeys.sourceEffort(.30 - epsilon, velocity, 1)) / epsilon
        val after = (LatticeKeys.sourceEffort(.30 + epsilon, velocity, 1) - LatticeKeys.sourceEffort(.30, velocity, 1)) / epsilon
        assertEquals(before, after, 2e-5, "the decay slope jumped at the transition")
        assertTrue(abs((LatticeKeys.sourceEffort(.45, velocity, 1) - LatticeKeys.sourceEffort(.45 - epsilon, velocity, 1)) / epsilon) < 2e-5)
    }

    @Test
    fun `new held pressure preserves onset and joins its sustained effort without a step or undershoot`() {
        for (velocity in listOf(0f, .2f, .75f, 1f)) {
            for (i in 0..350) {
                val t = i / 10_000.0
                assertEquals(LatticeKeys.sourceEffort(t, velocity, 1), LatticeKeys.sourceEffort(t, velocity, 2),
                    "the held source changed the preserved onset at $t")
            }
            var previous = LatticeKeys.sourceEffort(LatticeKeys.TRANSITION_START, velocity)
            for (i in 1..1000) {
                val t = LatticeKeys.TRANSITION_START +
                    (LatticeKeys.TRANSITION_END - LatticeKeys.TRANSITION_START) * i / 1000.0
                val effort = LatticeKeys.sourceEffort(t, velocity)
                assertTrue(effort <= previous + 1e-14, "held source rose at $t")
                assertTrue(effort >= LatticeKeys.SOURCE_FLOOR - 1e-14, "held source undershot at $t")
                previous = effort
            }
            val epsilon = 1e-7
            val t = LatticeKeys.TRANSITION_START
            val before = (LatticeKeys.sourceEffort(t, velocity) - LatticeKeys.sourceEffort(t - epsilon, velocity)) / epsilon
            val after = (LatticeKeys.sourceEffort(t + epsilon, velocity) - LatticeKeys.sourceEffort(t, velocity)) / epsilon
            assertEquals(before, after, 3e-5, "held source slope jumped at transition")
            val end = LatticeKeys.TRANSITION_END
            assertTrue(abs((LatticeKeys.sourceEffort(end, velocity) - LatticeKeys.sourceEffort(end - epsilon, velocity)) / epsilon) < 3e-5)
            assertEquals(LatticeKeys.SOURCE_FLOOR, LatticeKeys.sourceEffort(20.0, velocity))
        }
    }

    @Test
    fun `saved held model one replays the frozen native samples at every measured corner`() {
        val fixture = Json.parse(requireNotNull(javaClass.getResource("/lattice-held-v1.json")).readText()).obj()
        assertEquals(1, fixture.getValue("heldModel").int())
        for (value in fixture.getValue("examples").arr()) {
            val row = value.obj()
            val midi = row.getValue("midi").int()
            val speed = row.getValue("speed").num().toFloat()
            val velocity = row.getValue("velocity").num().toFloat()
            val note = LatticeKeys.note(patch(speed, velocity), midi, heldModel = 1)
            assertEquals(row.getValue("loopStartFrame").num().toLong(), note.loopStartFrame)
            assertEquals(row.getValue("frameCount").int(), note.snip.frameCount)
            val bytes = ByteBuffer.allocate(note.snip.samples.size * 4).order(ByteOrder.BIG_ENDIAN)
            note.snip.samples.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(row.getValue("float32Sha256").str(), hash, "legacy MIDI$midi SPEED$speed velocity$velocity changed")
        }
        for (bad in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            assertFalse(LatticeKeys.supportsHeldModel(bad))
            assertFailsWith<IllegalArgumentException> { LatticeKeys.note(patch(), heldModel = bad) }
        }
    }

    @Test
    fun `held model two keeps the handoff audible without normalizing the finished note`() {
        for (midi in listOf(48, 60, 72)) for (speed in listOf(0f, .5f, 1f)) for (velocity in listOf(.2f, .75f, 1f)) {
            val p = if (velocity == .75f) examples.single { it.midi == midi && it.speed == speed }.probe
                else LatticeKeys.probe(patch(speed, velocity), midi)
            val samples = p.note.snip.samples
            val steady = rms(samples.copyOfRange(p.prefixFrames, samples.size))
            val attackBody = windowRms(samples, .010, .025)
            val levelFromAttack = 20 * log10(steady / attackBody)
            val label = "MIDI$midi SPEED$speed velocity$velocity"
            assertTrue(levelFromAttack in -6.0..1.0, "$label lost held body relative to its attack: $levelFromAttack dB")
            assertTrue(samples.maxOf { abs(it) } < .4f, "$label lost fixed-gain headroom")
            // The accepted early gate closure may fall rapidly. This guard isolates the
            // handoff that used to cancel, using reference time so SPEED cannot skip it.
            val scale = LatticeMotion.timeScale(speed)
            val start = LatticeMotion.physicalTime(.20, scale)
            val end = LatticeMotion.physicalTime(.50, scale)
            var t = start
            var worstDip = 0.0
            var largestDrop = 0.0
            while (t + .020 <= end) {
                val db = 20 * log10(windowRms(samples, t, .020) / steady)
                assertTrue(db in -6.5..2.5, "$label handoff window at $t departed from held level by $db dB")
                worstDip = minOf(worstDip, db)
                if (t + .100 <= end) {
                    val before = windowRms(samples, t, .050)
                    val after = windowRms(samples, t + .050, .050)
                    val drop = 20 * log10(before / after)
                    largestDrop = maxOf(largestDrop, drop)
                    assertTrue(drop <= 2.0, "$label handoff dropped $drop dB between adjacent 50 ms windows at $t")
                }
                t += .005
            }
            println("LATTICE KEYS V2 $label held-v-attack=$levelFromAttack dB handoffDip=$worstDip dB adjacentDrop=$largestDrop dB")
        }
    }

    @Test
    fun `three roots and all speed extremes keep a causal onset and real periodic rejected sustain`() {
        for ((midi, speed, p) in examples) {
            val label = "MIDI$midi SPEED$speed"
            val samples = p.note.snip.samples
            val loop = samples.copyOfRange(p.prefixFrames, samples.size)
            assertEquals(1, p.note.snip.channels, label)
            assertEquals(Dsp.RATE, p.note.snip.sampleRate, label)
            assertEquals(p.loopFrames, loop.size, label)
            assertTrue(p.prefixFrames >= p.loopFrames, "$label discarded its onset")
            assertTrue(p.prefixSeconds > p.transitionEndSeconds, "$label loops a changing source")
            assertTrue(samples.size.toDouble() / Dsp.RATE <= LatticeKeys.MAX_SAMPLE_SECONDS, label)
            assertTrue(samples.all { it.isFinite() && abs(it) <= .99f }, label)
            assertTrue(rms(loop) > .001, "$label did not sustain actual collector output")
            val onsetRms = rms(samples.copyOfRange(441, 4410))
            // The first millisecond belongs to the direct pluck. Compare it with that
            // onset, rather than the much quieter and pitch-dependent rejected sustain.
            assertTrue(rms(samples.copyOfRange(0, 44)) < onsetRms * .02, "$label began without a quiet attack")
            assertTrue(onsetRms > .001, "$label onset was padded")
            assertTrue(p.directSustainPeak < 1e-12, "$label sustained a bypass source: ${p.directSustainPeak}")
            assertTrue(abs(samples.average()) < .005, "$label excessive DC")
            assertTrue(abs(p.pitchErrorCents) < .02, "$label root fit moved pitch ${p.pitchErrorCents} cents")
            val detected = FineTuning.measuredHz(Snip(loop, 1, Dsp.RATE), Keys.midiHz(midi), .12f, .5f)
            assertTrue(abs(FineTuning.cents(detected, p.requestedHz)) < 10.0, "$label lost root pitch: $detected")
            assertEquals(LatticeMotion.physicalTime(LatticeKeys.TRANSITION_END, LatticeMotion.timeScale(speed)), p.transitionEndSeconds, 1e-12)
            println("LATTICE KEYS $label prefix=${p.prefixSeconds}s loop=${p.loopFrames} " +
                "state=${p.stateError} cycle=${p.cycleError} seam=${p.seamError} " +
                "pcm16=${p.pcm16MaxCycleDifference} pcm24=${p.pcm24MaxCycleDifference} renderMs=${p.renderMillis}")
        }
        for (midi in listOf(48, 60, 72)) {
            val rendered = examples.filter { it.midi == midi }.map { it.probe.note.snip.samples }
            for (samples in rendered.drop(1)) assertContentEquals(rendered.first().copyOf(1102), samples.copyOf(1102),
                "SPEED changed the preserved first 25 ms of MIDI$midi")
        }
    }

    @Test
    fun `actual preceding cycles and native states recur through the real PCM writer`() {
        for ((midi, speed, p) in examples) {
            val samples = p.note.snip.samples
            assertContentEquals(samples.copyOfRange(p.prefixFrames - p.loopFrames, p.prefixFrames), p.previousCycle)
            assertTrue(p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) } < LatticeKeys.STATE_TOLERANCE)
            assertTrue(p.cycleError < LatticeKeys.CYCLE_TOLERANCE)
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR)
            val loop = samples.copyOfRange(p.prefixFrames, samples.size)
            for (depth in WavWriter.BitDepth.entries) {
                val previous = encoded(p.previousCycle, depth)
                val current = encoded(loop, depth)
                // WavReader uses the writer's positive full-scale divisor (32767/8388607).
                // Recover integer counts so decoded Float rounding cannot move the LSB bar.
                val scale = if (depth == WavWriter.BitDepth.PCM_16) 32767f else 8_388_607f
                val allowedCounts = if (depth == WavWriter.BitDepth.PCM_16) 1 else 2
                assertTrue(previous.indices.maxOf { abs((previous[it] * scale).roundToInt() - (current[it] * scale).roundToInt()) } <= allowedCounts,
                    "MIDI$midi SPEED$speed $depth lost full-cycle recurrence")
                val persisted = encoded(samples, depth)
                assertTrue(Keys.seamError(persisted, p.prefixFrames) < Keys.MAX_SEAM_ERROR,
                    "MIDI$midi SPEED$speed $depth does not close at the exported marker")
            }
        }
    }

    @Test
    fun `absolute MIDI and held key own tune and duration without rewriting the finite patch`() {
        val original = patch().copy(midi = 36, macros = patch().macros + mapOf("TUNE" to 1f, "HOLD" to 1f))
        val saved = original.toJsonText()
        val rendered = LatticeKeys.note(original, 60)
        val expected = examples.single { it.midi == 60 && it.speed == .5f }.probe.note
        assertEquals(expected.loopStartFrame, rendered.loopStartFrame)
        assertContentEquals(expected.snip.samples, rendered.snip.samples)
        assertEquals(saved, original.toJsonText())
        assertTrue(LatticeKeys.supports(original))
        for (voice in LatticeVoice.entries.filter { it != LatticeVoice.TRACE }) {
            val other = LatticePatch("Static", voice, Lattice.defaults(voice))
            assertFalse(LatticeKeys.supports(other))
            assertFailsWith<IllegalArgumentException> { LatticeKeys.note(other) }
        }
        for (midi in listOf(47, 73)) assertFailsWith<IllegalArgumentException> { LatticeKeys.note(original, midi) }
    }

    @Test
    fun `velocity silence is valid and sample layers remove only the host gain once`() {
        for (velocity in listOf(0f, .000001f, .2f, 1f)) {
            val patch = patch(velocity = velocity)
            val native = LatticeKeys.note(patch)
            val layer = LatticeKeys.layer(patch)
            assertEquals(native.loopStartFrame, layer.loopStartFrame)
            assertTrue(layer.snip.samples.all { it.isFinite() && abs(it) <= .99f }, "velocity $velocity layer headroom")
            if (velocity == 0f) {
                assertTrue(native.snip.samples.all { it == 0f })
                assertTrue(layer.snip.samples.all { it == 0f })
            } else {
                val gain = velocity.toDouble().pow(.72).toFloat()
                for (i in native.snip.samples.indices step 97) assertEquals(native.snip.samples[i] / gain,
                    layer.snip.samples[i], "velocity timbre/sample changed at frame $i")
            }
        }
    }

    @Test
    fun `the frozen finite B remains exact and expensive held generation stays cancellable`() {
        val finite = patch().render().samples
        val bytes = ByteBuffer.allocate(finite.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        finite.forEach { bytes.putFloat(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        assertEquals("fc748c8679d79173cc15b2fe2718dcf56ba343f7bbb1465be8b5cd0ef64acb67", hash)
        assertFailsWith<CancellationException> { LatticeKeys.note(patch(), cancelled = { true }) }
        var polls = 0
        assertFailsWith<CancellationException> { LatticeKeys.note(patch(), cancelled = { ++polls >= 200 }) }
        assertTrue(polls <= 200)
        val ordinary = patch().render().samples
        assertContentEquals(finite, ordinary, "held setup changed subsequent finite rendering")
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun windowRms(samples: FloatArray, from: Double, seconds: Double): Double {
        val start = (from * Dsp.RATE).roundToInt()
        val end = minOf(samples.size, start + (seconds * Dsp.RATE).roundToInt())
        require(start >= 0 && end > start)
        return sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
    }

    private fun encoded(samples: FloatArray, depth: WavWriter.BitDepth): FloatArray =
        WavReader.read(ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE), depth) }.toByteArray()).samples
}
