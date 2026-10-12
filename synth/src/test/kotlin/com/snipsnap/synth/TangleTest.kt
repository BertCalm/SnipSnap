package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contracts chosen before listening/tuning: finite powered-source bounds, .10–.90 pulse width,
 * <.015 exported DC, <25-cent clean-root error and the project's <1e-3 exported HOLD seam.
 * Extreme textures retain their measurements in the audition; they do not inherit clean-tone
 * autocorrelation confidence requirements. Listening acceptance remains a separate owner decision.
 */
class TangleTest {
    private val timbral = listOf("TANGLE", "CHASE", "CATCH", "BITE", "WEIGHT")

    @Test
    fun `voice defaults and control order render sample-identically`() {
        for (voice in TangleVoice.entries) {
            val macros = Tangle.defaults(voice)
            val a = Tangle.probe(voice, macros, velocity = 0.7f, durationSeconds = 1.1f)
            val b = Tangle.probe(voice, macros.entries.reversed().associate { it.key to it.value }, velocity = 0.7f, durationSeconds = 1.1f)
            assertContentEquals(a.raw, b.raw, "$voice raw determinism")
            assertContentEquals(a.samples, b.samples, "$voice decimated determinism")
            assertEquals(a.diagnostics.captureEvents, b.diagnostics.captureEvents)
            assertEquals(a.diagnostics.heldEvents, b.diagnostics.heldEvents)
        }
    }

    @Test
    fun `default and all-high registers stay inside numerical budgets before leveling`() {
        for (voice in TangleVoice.entries) for (midi in listOf(24, 54, 84)) for (high in listOf(false, true)) {
            val macros = Tangle.defaults(voice) + if (high) timbral.associateWith { 1f } else emptyMap()
            val probe = Tangle.probe(voice, macros, midi, durationSeconds = 1.0f)
            val d = probe.diagnostics
            assertTrue(probe.raw.all { it.isFinite() }, "$voice/$midi/$high raw non-finite")
            assertTrue(probe.samples.all { it.isFinite() }, "$voice/$midi/$high output non-finite")
            assertTrue(d.rawPeak < 1.5, "$voice/$midi/$high raw peak ${d.rawPeak}")
            assertTrue(d.minFrequencyHz > 0.0 && d.maxFrequencyHz < probe.sampleRate * 0.5, "$voice instantaneous frequency ${d.minFrequencyHz}..${d.maxFrequencyHz}")
            assertTrue(d.minPulseWidth >= 0.10 - 1e-6 && d.maxPulseWidth <= 0.90 + 1e-6, "$voice pulse width ${d.minPulseWidth}..${d.maxPulseWidth}")
            assertTrue(d.maxFilterState.isFinite() && d.maxFilterState < 8.0, "$voice runaway filter ${d.maxFilterState}")
            assertTrue(abs(probe.samples.average()) < 0.015, "$voice/$midi/$high accumulating DC ${probe.samples.average()}")
            assertTrue(TangleMeasure.rms(probe.samples) > 1e-4, "$voice/$midi/$high silence")
        }
    }

    @Test
    fun `CATCH zero disables captures while reciprocal interaction and held events remain`() {
        for (voice in TangleVoice.entries) {
            val base = Tangle.defaults(voice) + mapOf("TANGLE" to 0.25f, "HOLD" to 0f)
            val free = Tangle.probe(voice, base + ("CATCH" to 0f), durationSeconds = 2.5f)
            val caught = Tangle.probe(voice, base + ("CATCH" to 1f), durationSeconds = 2.5f)
            assertTrue(free.diagnostics.captureEvents.none { it.state != Tangle.CaptureState.FREE }, "$voice capture active at CATCH 0")
            assertTrue(free.diagnostics.heldEvents.isNotEmpty(), "$voice CATCH 0 also disabled encounters")
            assertTrue(caught.diagnostics.captureEvents.any { it.state == Tangle.CaptureState.CAUGHT }, "$voice never captured")
            assertTrue(caught.diagnostics.captureEvents.any { it.state == Tangle.CaptureState.RELEASE }, "$voice never released")
            val distance = TangleMeasure.distance(free.samples, caught.samples)
            assertTrue(distance > 0.02, "$voice capture only changed gain: distance $distance")
        }
    }

    @Test
    fun `capture transitions and encounter-derived holds are chronological and bounded`() {
        for (voice in TangleVoice.entries) {
            val p = Tangle.probe(voice, Tangle.defaults(voice) + mapOf("CATCH" to 1f, "TANGLE" to 0.5f), durationSeconds = 3f)
            val events = p.diagnostics.captureEvents
            assertEquals(events.sortedBy { it.seconds }, events, "$voice out-of-order capture trace")
            val allowed = mapOf(Tangle.CaptureState.FREE to Tangle.CaptureState.APPROACH,
                Tangle.CaptureState.APPROACH to Tangle.CaptureState.CAUGHT,
                Tangle.CaptureState.CAUGHT to Tangle.CaptureState.RELEASE,
                Tangle.CaptureState.RELEASE to Tangle.CaptureState.FREE)
            for ((a, b) in events.zipWithNext()) {
                assertEquals(allowed[a.state], b.state, "$voice skipped capture state ${a.state}→${b.state}")
                assertTrue(b.seconds > a.seconds, "$voice zero-duration state")
                if (a.state == Tangle.CaptureState.CAUGHT) assertTrue(b.seconds - a.seconds in 0.025..1.0, "$voice dwell ${b.seconds - a.seconds}")
                if (a.state == Tangle.CaptureState.FREE) assertTrue(b.seconds - a.seconds >= .04, "$voice retriggered before refractory interval: ${b.seconds - a.seconds}s")
            }
            val approaches = events.count { it.state == Tangle.CaptureState.APPROACH }
            assertTrue(approaches in 1..36, "$voice imperceptibly rapid captures: $approaches/3s")
            for (event in events) {
                assertTrue(event.phaseError in -0.500001..0.500001, "$voice unwrapped capture error ${event.phaseError}")
                assertTrue(event.strength in 0.0..1.000001, "$voice capture strength ${event.strength}")
            }
            val holds = p.diagnostics.heldEvents
            assertTrue(holds.isNotEmpty(), "$voice no held controls")
            assertEquals(holds.sortedBy { it.seconds }, holds)
            assertTrue(holds.size <= 55, "$voice audio-rate held events: ${holds.size}/3s")
            for (event in holds) {
                assertTrue(event.value in -1.000001..1.000001, "$voice unbounded held value ${event.value}")
                assertTrue(event.sourceA in -1.000001..1.000001 && event.sourceB in -1.000001..1.000001)
                assertTrue(event.phaseError in -0.500001..0.500001)
                assertTrue(event.encounter in 1..64, "$voice held control lacks a bounded encounter divider")
            }
            assertTrue(holds.map { it.value }.distinct().size > 1, "$voice encounter controls never acquired a new value")
        }
    }

    @Test
    fun `each timbral macro moves both its first quarter and its endpoints without a gain explanation`() {
        for (voice in listOf(TangleVoice.BASS, TangleVoice.LEAD)) for (macro in timbral) {
            val neutral = mapOf("TANGLE" to .30f, "CHASE" to .50f, "CATCH" to .25f, "BITE" to .40f, "WEIGHT" to .45f, "HOLD" to 0f)
            fun at(value: Float) = Tangle.probe(voice, neutral + (macro to value), durationSeconds = 2f).samples
            val low = at(0f)
            val quarter = at(.25f)
            val high = at(1f)
            assertTrue(TangleMeasure.distance(low, quarter) > 0.001, "$voice/$macro first quarter is inactive")
            assertTrue(TangleMeasure.distance(low, high) > 0.02, "$voice/$macro endpoints only change gain")
            // BITE and WEIGHT must change support/brightness, independently of accumulated phase movement.
            if (macro in setOf("BITE", "WEIGHT")) assertTrue(TangleMeasure.spectralDistance(low, high) > 0.01, "$voice/$macro did not move the spectral balance")
        }
    }

    @Test
    fun `CHASE passes continuously through the midpoint`() {
        val base = Tangle.defaults(TangleVoice.LEAD) + mapOf("TANGLE" to 0.1f, "CATCH" to 0f)
        fun at(chase: Float) = Tangle.probe(TangleVoice.LEAD, base + ("CHASE" to chase), durationSeconds = 0.2f).samples
        val full = TangleMeasure.distance(at(0f), at(1f))
        val near = TangleMeasure.distance(at(.499f), at(.501f))
        assertTrue(full > .02, "CHASE endpoints inactive")
        assertTrue(near < full * .25, "CHASE abruptly swapped identities at midpoint: local $near/full $full")
    }

    @Test
    fun `clean moderate settings retain the requested root across the proposed pitch range`() {
        var worst = 0.0
        for (voice in TangleVoice.entries) for (midi in listOf(24, 36, 48, 60, 72, 84)) {
            val macros = Tangle.defaults(voice) + mapOf("TANGLE" to .10f, "CATCH" to .15f, "BITE" to .4f, "WEIGHT" to .8f)
            val p = Tangle.probe(voice, macros, midi, durationSeconds = 1.1f)
            val snip = Snip(p.samples, 1, Dsp.RATE)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .12f, .7f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            worst = maxOf(worst, abs(cents))
            assertTrue(abs(cents) < 25.0, "$voice MIDI$midi root component $cents cents from requested pitch")
            if (midi >= 28) {
                val pitch = Pitch.detect(snip, .12f, .35f)
                assertTrue(pitch != null && pitch.confidence >= .3f, "$voice MIDI$midi no sufficiently pitched body: $pitch")
            }
        }
        println("TANGLE clean/moderate worst root component error: $worst cents")
    }

    @Test
    fun `standard pitched defaults never enter drum choke classes at low middle high and three velocities`() {
        for (voice in TangleVoice.entries) for (midi in listOf(24, Tangle.rootMidi(voice), 84)) for (velocity in listOf(.35f, .7f, 1f)) {
            val snip = Tangle.render(voice, Tangle.defaults(voice), midi, velocity)
            val heard = Classifier.classify(snip).drumClass
            assertTrue(heard !in TangleMeasure.drumClasses, "$voice MIDI$midi velocity$velocity read as $heard")
            assertTrue(abs(snip.samples.average()) < .015, "$voice exported DC")
            assertTrue(snip.samples.all { it in -1f..1f }, "$voice clipping")
            assertTrue(Loudness.of(snip) >= Dsp.MELODIC_LOUDNESS_TARGET * .85f || snip.peak() > .95f, "$voice did not use shared melodic leveling")
        }
    }

    @Test
    fun `velocity changes early interaction and brightness before output leveling`() {
        for (voice in listOf(TangleVoice.BASS, TangleVoice.LEAD, TangleVoice.METAL)) {
            val soft = Tangle.probe(voice, velocity = .25f, durationSeconds = 1f)
            val hard = Tangle.probe(voice, velocity = 1f, durationSeconds = 1f)
            assertTrue(TangleMeasure.distance(soft.samples, hard.samples) > .02, "$voice velocity only changes gain")
            assertTrue(TangleMeasure.spectralDistance(soft.samples, hard.samples) > .005, "$voice velocity never changes brightness")
        }
    }

    @Test
    fun `HOLD converges the synthesis state and survives final PCM export without a seam`() {
        for (voice in TangleVoice.entries) {
            val macros = Tangle.defaults(voice) + mapOf("HOLD" to 1f, "CATCH" to .7f)
            val a = Tangle.renderLoopMeasured(voice, macros)
            val b = Tangle.renderLoopMeasured(voice, macros)
            assertTrue(a.loop.size.toDouble() / Dsp.RATE > 1.5, "$voice HOLD period is shorter than the house sustained-loop boundary")
            assertContentEquals(a.loop, b.loop, "$voice HOLD determinism")
            val loop = a.diagnostics.loop ?: error("$voice has no convergence diagnostic")
            assertTrue(loop.converged, "$voice returned an unconverged loop")
            assertTrue(a.attempts in 1..64, "$voice unbounded convergence search: ${a.attempts}")
            assertTrue(a.stateError < 1e-3, "$voice full-state error ${a.stateError}")
            assertTrue(loop.conditioningStateError < 1e-3, "$voice anti-alias / decimation state error ${loop.conditioningStateError}")
            assertTrue(a.seam < 1e-3, "$voice float seam ${a.seam}")
            assertTrue(a.diagnostics.captureEvents.any { it.state == Tangle.CaptureState.CAUGHT }, "$voice HOLD lost capture")
            val wanted = Keys.midiHz(Tangle.rootMidi(voice))
            val loopPitch = FineTuning.measuredHz(Snip(a.loop, 1, Dsp.RATE), wanted, .12f, .7f)
            assertTrue(abs(FineTuning.cents(loopPitch, wanted.toDouble())) < 25.0, "$voice HOLD displaced requested root")
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(a.loop, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16) }
            val exported = WavReader.read(bytes.toByteArray()).samples
            val context = ByteArrayOutputStream().also { WavWriter.write(it, Snip(a.seamContext, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16) }
            val exportedContext = WavReader.read(context.toByteArray()).samples
            assertTrue(Keys.seamError(exportedContext, a.loopStartFrame) < 1e-3, "$voice PCM16 independent-period seam changed")
            val derivativeRms = kotlin.math.sqrt((1 until exported.size).sumOf { i ->
                val difference = exported[i] - exported[i - 1].toDouble()
                difference * difference
            } / (exported.size - 1))
            assertTrue(abs(exported.first() - exported.last()) < derivativeRms * 8 + 1e-5, "$voice wrap is an exceptional discontinuity")
        }
    }

    @Test
    fun `zero velocity is silent for finite and held renders`() {
        for (voice in listOf(TangleVoice.BASS, TangleVoice.LEAD, TangleVoice.METAL)) {
            for (hold in listOf(0f, 1f)) {
                val snip = Tangle.render(voice, Tangle.defaults(voice) + ("HOLD" to hold), velocity = 0f)
                assertTrue(snip.samples.all { it == 0f }, "$voice HOLD$hold sounded at zero velocity")
            }
        }
    }
}
