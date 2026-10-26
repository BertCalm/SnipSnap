package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Native evidence for the authored capture, separate from the finite mechanics' work budget. */
class SkirlHoldTest {
    private data class Wrap(val stepRatio: Double, val slopeRatio: Double, val levelRatio: Double)

    private fun rms(x: FloatArray, from: Int = 0, end: Int = x.size): Double {
        var energy = 0.0
        for (i in from until end) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (end - from).coerceAtLeast(1))
    }

    private fun assertWrap(x: FloatArray, label: String): Wrap {
        val window = minOf(Skirl.RATE / 20, x.size / 4)
        var largestStep = 0.0
        var largestCurvature = 0.0
        for (i in x.size - window + 2 until x.size) {
            largestStep = max(largestStep, abs(x[i] - x[i - 1]).toDouble())
            largestCurvature = max(largestCurvature, abs(x[i] - 2.0 * x[i - 1] + x[i - 2]))
        }
        for (i in 2 until window) {
            largestStep = max(largestStep, abs(x[i] - x[i - 1]).toDouble())
            largestCurvature = max(largestCurvature, abs(x[i] - 2.0 * x[i - 1] + x[i - 2]))
        }
        val step = abs(x[0] - x.last()).toDouble()
        val incoming = x.last() - x[x.lastIndex - 1]
        val outgoing = x[1] - x[0]
        assertTrue(step <= largestStep * 1.10 + 1e-5,
            "$label: wrap step $step exceeds neighboring waveform steps $largestStep")
        assertTrue(abs(outgoing - incoming) <= largestCurvature * 2.2 + 2e-5,
            "$label: wrap slopes disagree beyond neighboring waveform curvature")
        // A fade to silence can pass the step test. Compare an independent nearby
        // window with the actual wrap, rather than the peak of the whole render.
        val wrap = sqrt((rms(x, 0, window).let { it * it } +
            rms(x, x.size - window, x.size).let { it * it }) / 2)
        val nearby = max(rms(x, window, window * 2), rms(x, x.size - window * 2, x.size - window))
        assertTrue(wrap >= nearby * .25, "$label: wrap loses more than 12 dB against nearby material")
        return Wrap(step / largestStep.coerceAtLeast(1e-20),
            abs(outgoing - incoming) / largestCurvature.coerceAtLeast(1e-20),
            wrap / nearby.coerceAtLeast(1e-20))
    }

    private fun assertLoop(voice: SkirlVoice, midi: Int, velocity: Float,
        macros: Map<String, Float> = emptyMap()): Double {
        val p = Skirl.inspect(voice, macros + ("HOLD" to 1f), midi, velocity)
        val label = "$voice MIDI $midi velocity $velocity $macros"
        val hold = assertNotNull(p.hold)
        val s = p.snip
        assertEquals(1, s.channels, label)
        assertEquals(44_100, s.sampleRate, label)
        assertTrue(s.durationSeconds > 1.5f, "$label: exported held span is too short to file as LOOP")
        assertEquals(DrumClass.LOOP, Classifier.classify(s).drumClass,
            "$label: actual captured span must file as a loop")
        assertTrue(hold.exportSpanCycles >= 3, "$label: expected a real multi-capture span")
        assertEquals(s.frameCount, (hold.exportSpanSeconds * Skirl.RATE).roundToInt(), label)
        assertTrue(s.samples.all { it.isFinite() }, label)
        assertTrue(s.peak() in .01f..1f, "$label: peak ${s.peak()}")
        assertTrue(abs(s.samples.average()) < .005, "$label: held DC")
        assertTrue(Loudness.of(s) >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || s.peak() >= .95f,
            "$label: held level ${Loudness.of(s)}")
        assertTrue(hold.seamError.isFinite() && hold.seamError < Keys.MAX_SEAM_ERROR,
            "$label: independent preceding-cycle seam ${hold.seamError}")
        assertTrue(hold.cycleDifference.isFinite() && hold.cycleDifference < Keys.MAX_SEAM_ERROR,
            "$label: independent whole-cycle difference ${hold.cycleDifference}")
        assertEquals(p.raw.size, p.previousCycle.size, label)
        assertTrue(p.raw !== p.previousCycle, "$label: preceding cycle cannot alias current output")
        assertTrue(SkirlHold.cycleDifference(p.previousCycle, p.raw) < Keys.MAX_SEAM_ERROR, label)
        assertTrue(Keys.seamError(p.previousCycle + p.raw, p.previousCycle.size) < Keys.MAX_SEAM_ERROR,
            "$label: independently recomputed seam")
        val wrap = assertWrap(s.samples, label)
        val detected = assertNotNull(Pitch.detect(s, .10f, .30f), "$label: no held root")
        val cents = 1200 * ln(detected.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
        assertTrue(abs(cents) < 50, "$label: held root $cents cents")
        assertTrue(detected.confidence >= .30f, "$label: held confidence ${detected.confidence}")
        println("SKIRL HOLD $label: cents=$cents confidence=${detected.confidence} " +
            "seam=${hold.seamError} cycle=${hold.cycleDifference} " +
            "dc=${abs(s.samples.average())} loudness=${Loudness.of(s)} peak=${s.peak()} " +
            "wrapStepRatio=${wrap.stepRatio} wrapSlopeRatio=${wrap.slopeRatio} wrapLevelRatio=${wrap.levelRatio}")
        return abs(cents)
    }

    @Test
    fun `captured loops keep their root and close from C2 through C6 at soft and firm touch`() {
        var worst = 0.0
        for (voice in SkirlVoice.entries) for (midi in listOf(36, 48, 60, 72, 84)) {
            for (velocity in listOf(.25f, 1f)) worst = max(worst, assertLoop(voice, midi, velocity))
        }
        println("SKIRL HOLD register matrix worst cents=$worst")
    }

    @Test
    fun `low held notes retain a whole cycle root while short window refusals remain visible`() {
        for (voice in SkirlVoice.entries) for (velocity in listOf(.25f, 1f)) {
            val loop = Skirl.renderLoop(voice, midi = 36, velocity = velocity)
            // Repetition permits pitch windows that cross the wrap. This is
            // playback material, not independent evidence for the loop seam.
            val playback = Snip(FloatArray(loop.size * 3) { loop[it % loop.size] }, 1, Skirl.RATE)
            val target = Keys.midiHz(36).toDouble()
            val whole = assertNotNull(Pitch.detect(playback, 0f, loop.size.toFloat() / Skirl.RATE),
                "$voice C2 velocity $velocity: no whole-cycle root")
            val wholeCents = 1200 * ln(whole.hz / target) / ln(2.0)
            assertTrue(abs(wholeCents) < 50 && whole.confidence >= .30f,
                "$voice C2 velocity $velocity: whole-cycle root $wholeCents cents / ${whole.confidence}")
            val refusals = ArrayList<Float>()
            var worstDetected = 0.0
            for (index in 0 until 12) {
                val from = index * .05f
                val estimate = Pitch.detect(playback, from, .30f)
                if (estimate == null) {
                    refusals += from
                } else {
                    val cents = 1200 * ln(estimate.hz / target) / ln(2.0)
                    worstDetected = max(worstDetected, abs(cents))
                    assertTrue(abs(cents) < 50 && estimate.confidence >= .30f,
                        "$voice C2 velocity $velocity at $from: detected root $cents cents / ${estimate.confidence}")
                }
            }
            // Do not pin a particular refusal pattern: it is a disclosed
            // limitation of this relative-peak detector, not desired behavior.
            println("SKIRL HOLD C2 PHASES $voice velocity=$velocity wholeCents=$wholeCents " +
                "wholeConfidence=${whole.confidence} worstDetectedCents=$worstDetected " +
                "refusedStarts=$refusals windowSeconds=.30")
        }
    }

    @Test
    fun `capture closes and keeps its root at the contact and material corners`() {
        for (voice in SkirlVoice.entries) for (midi in listOf(36, 84)) {
            for (edge in listOf(0f, 1f)) for (plate in listOf(0f, 1f)) {
                assertLoop(voice, midi, .65f, mapOf("EDGE" to edge, "PLATE" to plate))
            }
        }
    }

    @Test
    fun `finite hold adds stepped captured cycles then restores the original physical runout`() {
        for (voice in SkirlVoice.entries) {
            val original = Skirl.inspect(voice)
            var previousFrames = original.snip.frameCount
            for (amount in listOf(.15f, .50f, .95f)) {
                val extended = Skirl.inspect(voice, mapOf("HOLD" to amount))
                val held = assertNotNull(extended.hold)
                assertSame(original.motion, extended.motion, "HOLD must not rerun or repair mechanics")
                assertEquals(original.acoustics, extended.acoustics,
                    "capture does not get added to the finite trajectory's work ledger")
                val inserted = (held.insertedSeconds * Skirl.RATE).roundToInt()
                assertEquals(original.snip.frameCount + inserted, extended.snip.frameCount)
                assertTrue(extended.snip.frameCount > previousFrames)
                assertTrue(held.insertedSeconds in 0.0..4.0)
                assertEquals(0, extended.loopStartFrame)
                assertEquals(DrumClass.TONAL, Skirl.drumClassFor(voice, mapOf("HOLD" to amount)))
                // The raw conditioned tail should be the original free decay,
                // shifted in time. The splice/filter transient is long gone.
                val tail = Skirl.RATE / 4
                val a = original.raw.copyOfRange(original.raw.size - tail, original.raw.size)
                val b = extended.raw.copyOfRange(extended.raw.size - tail, extended.raw.size)
                assertTrue(SkirlHold.cycleDifference(a, b) < .001,
                    "$voice HOLD $amount: resumed tail changed instead of being shifted")
                assertTrue(extended.snip.samples.all { it.isFinite() })
                assertTrue(abs(extended.snip.samples.average()) < .005)
                previousFrames = extended.snip.frameCount
            }
        }
    }

    @Test
    fun `held key notes retain the attack and mark an independently closed final span`() {
        for (voice in SkirlVoice.entries) {
            val p = Skirl.inspectHeld(voice, midi = 57, velocity = .65f)
            val key = Skirl.renderHeld(voice, midi = 57, velocity = .65f)
            assertTrue(p.loopStartFrame >= 256)
            assertEquals(p.loopStartFrame.toLong(), key.loopStartFrame)
            assertContentEquals(p.snip.samples, key.snip.samples)
            val length = p.snip.frameCount - p.loopStartFrame
            assertTrue(length > 1.5 * Skirl.RATE)
            assertEquals(length, p.previousCycle.size)
            assertTrue(p.loopStartFrame > length, "capture must retain the original attack")
            assertTrue(Keys.seamError(key.snip.samples, key.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            val rawCycle = p.raw.copyOfRange(p.loopStartFrame, p.raw.size)
            assertTrue(SkirlHold.cycleDifference(p.previousCycle, rawCycle) < Keys.MAX_SEAM_ERROR)
            assertEquals(DrumClass.LOOP, Skirl.drumClassFor(voice, mapOf("HOLD" to 1f)))
            assertWrap(key.snip.samples.copyOfRange(key.loopStartFrame.toInt(), key.snip.frameCount), "$voice key")
        }
    }

    @Test
    fun `held material is deterministic and responds to every original sound control`() {
        for (voice in SkirlVoice.entries) {
            val base = Skirl.renderLoop(voice)
            assertContentEquals(base, Skirl.renderLoop(voice))
            for (macro in listOf("TUNE", "LEAN", "BRAKE", "EDGE", "PLATE", "COLLAPSE")) {
                val low = Skirl.renderLoop(voice, mapOf(macro to .15f))
                val high = Skirl.renderLoop(voice, mapOf(macro to .85f))
                assertTrue(!low.contentEquals(high), "$voice $macro does not move the captured sound")
                // Compare gain-normalized material so leveling cannot manufacture the difference.
                val a = rms(low).coerceAtLeast(1e-15)
                val b = rms(high).coerceAtLeast(1e-15)
                val n = minOf(low.size, high.size)
                var difference = 0.0
                for (i in 0 until n) {
                    val d = low[i] / a - high[i] / b
                    difference += d * d
                }
                assertTrue(sqrt(difference / n) > .01, "$voice $macro is only a gain change")
            }
        }
    }

    @Test
    fun `silence fallback scramble and cancellation retain their held contracts`() {
        for (voice in SkirlVoice.entries) {
            val silent = Skirl.inspect(voice, mapOf("HOLD" to 1f), velocity = 0f)
            assertTrue(silent.snip.samples.all { it == 0f })
            assertEquals(0.0, assertNotNull(silent.hold).seamError)
            assertEquals(0.0, silent.hold.cycleDifference)
            assertContentEquals(Skirl.renderLoop(voice),
                Skirl.renderLoop(voice, mapOf("EDGE" to Float.NaN, "UNKNOWN" to .75f)))
            val random = Random(108)
            repeat(128) {
                val macros = Skirl.scramble(voice, random, 1f)
                assertTrue(macros.values.all { it.isFinite() && it in 0f..1f })
                assertTrue(!Skirl.isLoop(macros.getValue("HOLD")), "SCRAMBLE should not choose endless sustain")
            }
        }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Skirl.renderLoop(SkirlVoice.ROLL) }
        } finally { Thread.interrupted() }
    }

    @Test
    fun `independent cycle and seam probes reject a deliberately disturbed preceding cycle`() {
        val p = Skirl.inspect(SkirlVoice.ROLL, mapOf("HOLD" to 1f))
        val previous = p.previousCycle.copyOf()
        // Raw excitation has physical-proxy units, before house leveling. A
        // fixed PCM-sized offset can be negligible here; make the mutation
        // relative to the independent unmodified cycle's measured energy.
        val disturbance = (10 * rms(previous)).toFloat()
        for (i in previous.size - 256 until previous.size) previous[i] += disturbance
        val difference = SkirlHold.cycleDifference(previous, p.raw)
        val seam = Keys.seamError(previous + p.raw, previous.size)
        println("SKIRL HOLD disturbed previous cycle: difference=$difference seam=$seam")
        assertTrue(difference > Keys.MAX_SEAM_ERROR, "cycle probe ignored a relative-energy disturbance")
        assertTrue(seam > Keys.MAX_SEAM_ERROR, "seam probe ignored a relative-energy disturbance")
    }
}
