package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mechanism and exported-audio checks; the listening page supplies perceptual acceptance. */
class WickTest {
    private val timbral = listOf("SPARK", "FEED", "CASCADE", "TIMBRE", "RECOVERY")
    private val active = mapOf("SPARK" to .45f, "FEED" to .65f, "CASCADE" to .75f,
        "TIMBRE" to .4f, "RECOVERY" to .6f)

    @Test
    fun `finite and held renders reproduce samples and causal event ordering independent of map order`() {
        for (voice in WickVoice.entries) for (hold in listOf(0f, 1f)) {
            val macros = Wick.defaults(voice) + active + ("HOLD" to hold)
            val a = Wick.inspect(voice, macros, midi = 57, velocity = .7f)
            val b = Wick.inspect(voice, macros.entries.reversed().associate { it.key to it.value }, midi = 57, velocity = .7f)
            assertContentEquals(a.raw, b.raw, "$voice HOLD$hold raw replay")
            assertContentEquals(a.samples, b.samples, "$voice HOLD$hold exported replay")
            assertEquals(a.diagnostics.events, b.diagnostics.events, "$voice HOLD$hold causal replay")
            assertEquals(a.diagnostics.loop, b.diagnostics.loop)
            assertReplayProvenance(a)
        }
    }

    @Test
    fun `default and demanding settings remain bounded before leveling at supported register edges`() {
        for (voice in WickVoice.entries) for (midi in listOf(24, 48, 84)) for (demanding in listOf(false, true)) {
            val macros = if (demanding) timbral.associateWith { 1f } else Wick.defaults(voice)
            val probe = Wick.inspect(voice, macros, midi)
            val d = probe.diagnostics
            val label = "$voice MIDI$midi demanding$demanding"
            assertTrue(probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() }, "$label non-finite audio")
            assertTrue(d.rawPeak in 0.0001..4.0 && d.rawRms in 0.00001..2.0, "$label raw peak ${d.rawPeak}, RMS ${d.rawRms}")
            assertTrue(d.maxFilterState.isFinite() && d.maxFilterState < 4.0, "$label raw filter ${d.maxFilterState}")
            assertTrue(d.minAvailability >= -1e-9 && d.maxAvailability <= 1.000000001, "$label reserve bounds")
            assertTrue(d.maxCharge in 0.0..1.000000001 && d.maxOpening in 0.0..1.000000001, "$label gate bounds")
            assertTrue(d.maxQueueDepth in 0..64, "$label queue depth ${d.maxQueueDepth}")
            assertEquals(0, d.rejectedEvents, "$label exhausted a queue or event budget")
            assertTrue(d.events.size < 1000, "$label event explosion ${d.events.size}")
            assertTrue(probe.samples.maxOf { abs(it) } <= .991f, "$label output peak")
            assertTrue(abs(probe.samples.average()) < .015, "$label output DC")
            val duration = probe.samples.size.toFloat() / Dsp.RATE
            assertTrue(duration in 1f..8f, "$label finite duration $duration")
        }
    }

    @Test
    fun `zero velocity cannot create a note or a self-starting cascade`() {
        for (voice in WickVoice.entries) for (hold in listOf(0f, 1f)) {
            val probe = Wick.inspect(voice, active + ("HOLD" to hold), velocity = 0f)
            assertTrue(probe.raw.all { it == 0f } && probe.samples.all { it == 0f }, "$voice HOLD$hold zero velocity")
            assertEquals(0.0, probe.diagnostics.rawPeak)
            assertTrue(probe.diagnostics.events.none { it.admittedStrength > 0.0 }, "silent note admitted excitation")
        }
    }

    @Test
    fun `transmitted excitations follow observed parent releases on a later update`() {
        for (voice in listOf(WickVoice.BUBBLE, WickVoice.WOOD, WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE)) {
            val probe = Wick.inspect(voice, active + mapOf("FEED" to .25f, "CASCADE" to 1f))
            val events = probe.diagnostics.events
            val releases = events.filter { it.kind == "RELEASE" }
            val transmissions = events.filter { it.kind == "CASCADE" }
            assertTrue(releases.isNotEmpty(), "$voice no gate crossed its release threshold")
            assertTrue(transmissions.isNotEmpty(), "$voice no release reached a successor")
            assertEquals(events.sortedBy { it.timeSeconds }, events, "$voice event chronology")
            assertTrue(transmissions.any { it.destination == 2 }, "$voice cascade never reached the third stage")
            for (event in transmissions) {
                assertTrue(event.parentStage in 0..2 && event.destination in 0..2)
                assertTrue(event.timeSeconds > event.releaseSeconds, "same-update recursive ignition")
                assertTrue(event.timeSeconds - event.releaseSeconds in .005.. .5, "unbounded cascade delay")
                assertTrue(releases.any { it.parentStage == event.parentStage && abs(it.timeSeconds - event.releaseSeconds) < 1.0 / (Dsp.RATE * Dsp.OVERSAMPLE) },
                    "delivery ${event.timeSeconds} has no observed parent release at ${event.releaseSeconds}")
                assertTrue(releases.any { it.id == event.parentEventId }, "delivery lacks its actual release event identity")
                assertTrue(event.admittedStrength <= event.requestedStrength + 1e-9)
                assertTrue(event.availabilityAfter <= event.availabilityBefore + 1e-9, "an excitation replenished reserve")
            }
            val disconnected = Wick.inspect(voice, active + ("CASCADE" to 0f))
            assertTrue(disconnected.diagnostics.events.none { it.kind == "CASCADE" }, "$voice CASCADE zero still transmits")
            assertTrue(disconnected.diagnostics.rawRms > .0001, "$voice CASCADE zero also silenced the source gate")
        }
    }

    @Test
    fun `mechanism ablations change timbre or articulation beyond output gain`() {
        val normal = Wick.inspect(WickVoice.BUBBLE, active)
        val comparisons = listOf(
            "cascade disconnected" to Wick.Options(disableCascade = true),
            "constant excitation reserve" to Wick.Options(constantAvailability = true),
            "gain-only gates" to Wick.Options(gainOnly = true),
        )
        for ((label, options) in comparisons) {
            val ablated = Wick.inspect(WickVoice.BUBBLE, active, options = options)
            val distance = TangleMeasure.distance(normal.samples, ablated.samples)
            assertTrue(distance > .01, "$label has negligible gain-independent effect: $distance")
        }
        assertTrue(normal.diagnostics.minAvailability < .9, "network never spends its reserve")
    }

    @Test
    fun `recovery changes the reserve available to later pulses under repeated feeding`() {
        val base = active + mapOf("FEED" to .85f, "CASCADE" to .8f)
        val quick = Wick.inspect(WickVoice.BUBBLE, base + ("RECOVERY" to 0f))
        val slow = Wick.inspect(WickVoice.BUBBLE, base + ("RECOVERY" to 1f))
        fun laterReserve(p: Wick.Probe): Double {
            val pulses = p.diagnostics.events.filter { it.kind != "RELEASE" && it.timeSeconds > .4 && it.requestedStrength > 0.0 }
            assertTrue(pulses.size > 2, "no repeated excitation to expose recovery memory")
            return pulses.map { it.availabilityBefore }.average()
        }
        assertTrue(laterReserve(slow) < laterReserve(quick), "slower recovery does not reduce later reserve")
        assertTrue(TangleMeasure.distance(quick.samples, slow.samples) > .02, "recovery only changes metadata")
    }

    @Test
    fun `each timbral control moves a finite gesture and its held recurrent gesture`() {
        for (voice in listOf(WickVoice.BUBBLE, WickVoice.WOOD, WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE)) for (hold in listOf(0f, 1f)) for (macro in timbral) {
            fun at(value: Float): FloatArray {
                val probe = Wick.inspect(voice, active + mapOf("HOLD" to hold, macro to value))
                assertReplayProvenance(probe)
                return probe.samples
            }
            val low = at(0f)
            val high = at(1f)
            val distance = TangleMeasure.distance(low, high)
            println("WICK $voice HOLD$hold $macro endpoint shape distance $distance")
            assertTrue(distance > .01, "$voice HOLD$hold $macro only changes gain or the discarded preroll: $distance")
        }
    }

    @Test
    fun `an isolated bubble retains a pitched body after the initial pop`() {
        val controls = Wick.defaults(WickVoice.BUBBLE) + mapOf("FEED" to 0f, "CASCADE" to 0f)
        fun window(samples: FloatArray, from: Double, to: Double) =
            samples.copyOfRange((from * Dsp.RATE).toInt(), (to * Dsp.RATE).toInt())
        fun bodyRatio(samples: FloatArray): Double =
            TangleMeasure.rms(window(samples, .30, .50)) / TangleMeasure.rms(window(samples, .06, .16)).coerceAtLeast(1e-12)
        for (midi in listOf(48, 60, 72)) {
            val candidate = Wick.inspect(WickVoice.BUBBLE, controls, midi)
            val reference = Wick.inspect(WickVoice.BUBBLE, controls, midi,
                options = Wick.Options(legacyBubbleEnvelope = true))
            assertTrue(candidate.raw.all { it.isFinite() } && candidate.samples.all { it.isFinite() }, "MIDI$midi non-finite BUBBLE")
            val retained = bodyRatio(candidate.raw)
            val previous = bodyRatio(reference.raw)
            // These within-render ratios remove output gain as an explanation. C3 already had
            // a bass tail; C4/C5 must gain a body rather than merely another louder onset.
            assertTrue(retained > .10, "MIDI$midi late pitched body is too weak relative to its onset: $retained")
            assertTrue(retained >= previous * if (midi == 48) .9 else 2.0,
                "MIDI$midi late/early energy did not improve: $previous -> $retained")
            val late = window(candidate.raw, .30, .50)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(late, 1, Dsp.RATE), wanted, 0f, .2f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            assertTrue(abs(cents) < 25.0, "MIDI$midi late body lost the requested root: $cents cents")
            var real = 0.0
            var imaginary = 0.0
            var energy = 0.0
            for (i in late.indices) {
                val sample = late[i].toDouble()
                val phase = 2 * PI * wanted * i / Dsp.RATE
                real += sample * cos(phase)
                imaginary += sample * sin(phase)
                energy += sample * sample
            }
            val rootShare = 2 * (real * real + imaginary * imaginary) / (late.size * energy).coerceAtLeast(1e-30)
            assertTrue(rootShare > .10, "MIDI$midi late root is only a small spectral trace: $rootShare")
            println("WICK BUBBLE MIDI$midi isolated late/early RMS $previous -> $retained; late root $cents cents, share $rootShare")
        }
    }

    @Test
    fun `the bubble envelope comparison leaves the pluck raw render unchanged`() {
        val normal = Wick.inspect(WickVoice.PLUCK, midi = 60)
        val comparison = Wick.inspect(WickVoice.PLUCK, midi = 60, options = Wick.Options(legacyBubbleEnvelope = true))
        assertContentEquals(normal.raw, comparison.raw, "BUBBLE-only envelope changes reached PLUCK")
    }

    @Test
    fun `wood has a drier body and different harmonic balance under the same controls`() {
        val controls = Wick.defaults(WickVoice.WOOD) + mapOf("FEED" to 0f, "CASCADE" to 0f)
        val wood = Wick.inspect(WickVoice.WOOD, controls, midi = 60)
        fun bodyRatio(samples: FloatArray): Double {
            fun rms(from: Double, to: Double) = TangleMeasure.rms(
                samples.copyOfRange((from * Dsp.RATE).toInt(), (to * Dsp.RATE).toInt()))
            return rms(.22, .40) / rms(.04, .12).coerceAtLeast(1e-12)
        }
        val woodBody = bodyRatio(wood.raw)
        for (other in listOf(WickVoice.PLUCK, WickVoice.BUBBLE)) {
            val reference = Wick.inspect(other, controls, midi = 60)
            val otherBody = bodyRatio(reference.raw)
            val spectrumChange = TangleMeasure.spectralDistance(wood.raw, reference.raw)
            println("WICK WOOD vs $other matched controls: late/early RMS $woodBody / $otherBody, spectral distance $spectrumChange")
            // Within-render amplitude ratios and normalized spectral power cannot be changed
            // by output leveling or explained by the voices' different oscillator start phases.
            assertTrue(woodBody < otherBody * .9, "WOOD did not close more compactly than $other: $woodBody / $otherBody")
            assertTrue(spectrumChange > .005, "WOOD and $other have nearly the same harmonic balance: $spectrumChange")
        }
    }

    @Test
    fun `wood breath flicker and rattle velocity change finite and recurrent articulation while retaining the requested note`() {
        for (voice in listOf(WickVoice.WOOD, WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE)) for (hold in listOf(0f, 1f)) {
            val controls = Wick.defaults(voice) + ("HOLD" to hold)
            val soft = Wick.inspect(voice, controls, midi = 60, velocity = .25f)
            val hard = Wick.inspect(voice, controls, midi = 60, velocity = 1f)
            val distance = TangleMeasure.distance(soft.samples, hard.samples)
            assertTrue(distance > .02, "$voice HOLD$hold velocity only changes gain: $distance")
            for ((velocity, probe) in listOf(.25f to soft, 1f to hard)) {
                assertTrue(probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() })
                val wanted = Keys.midiHz(60)
                val measured = FineTuning.measuredHz(Snip(probe.samples, 1, Dsp.RATE), wanted, .08f, .25f)
                val cents = FineTuning.cents(measured, wanted.toDouble())
                assertTrue(abs(cents) < 25.0, "$voice HOLD$hold velocity$velocity lost its requested root: $cents cents")
            }
        }
    }

    @Test
    fun `breath swells more gently than struck voices and keeps a pitched body between openings`() {
        fun window(samples: FloatArray, from: Double, to: Double) =
            samples.copyOfRange((from * Dsp.RATE).toInt(), (to * Dsp.RATE).toInt())
        fun onsetRatio(samples: FloatArray): Double =
            TangleMeasure.rms(window(samples, .005, .025)) /
                TangleMeasure.rms(window(samples, .10, .20)).coerceAtLeast(1e-12)
        // Removing recurrent excitations isolates the opening law. Within-render RMS ratios
        // cannot be improved merely by making BREATH quieter than the struck voices.
        val isolated = Wick.defaults(WickVoice.BREATH) + mapOf("FEED" to 0f, "CASCADE" to 0f)
        val breath = Wick.inspect(WickVoice.BREATH, isolated, midi = 60)
        val softOnset = onsetRatio(breath.raw)
        for (other in listOf(WickVoice.WOOD, WickVoice.BUBBLE)) {
            val struck = Wick.inspect(other, isolated, midi = 60)
            val struckOnset = onsetRatio(struck.raw)
            println("WICK BREATH vs $other matched controls: onset/body RMS $softOnset / $struckOnset")
            // Opening time alone does not determine the acoustic rise: BREATH's shallower
            // VCA curve also carries its closing tails. Require at least 20% less relative
            // early energy than the already softened BUBBLE, as well as the dry WOOD voice.
            assertTrue(softOnset < struckOnset * .80,
                "BREATH lacks a gentler acoustic swell relative to $other: $softOnset / $struckOnset")
        }
        for (midi in listOf(48, 60, 72)) {
            val probe = Wick.inspect(WickVoice.BREATH, midi = midi)
            val windows = (5 until 35).map { index ->
                TangleMeasure.rms(window(probe.raw, index * .05, (index + 1) * .05))
            }
            val continuity = windows.min() / windows.max().coerceAtLeast(1e-12)
            println("WICK BREATH MIDI$midi 50ms envelope floor/peak $continuity")
            // The scored body must not fall into separated near-silent pops. The final finite
            // tail is deliberately excluded: an ordinary note must still be allowed to end.
            assertTrue(continuity > .04, "BREATH MIDI$midi has a gap between openings: $continuity")
            val body = window(probe.raw, .60, .90)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(body, 1, Dsp.RATE), wanted, 0f, .3f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            val rootShare = TangleMeasure.toneShare(body, wanted.toDouble())
            println("WICK BREATH MIDI$midi sustained root $cents cents, share $rootShare")
            assertTrue(abs(cents) < 25.0, "BREATH MIDI$midi sustained body lost the requested note: $cents cents")
            assertTrue(rootShare > .10, "BREATH MIDI$midi root is only a trace in the sustained body: $rootShare")
            assertTrue(probe.diagnostics.events.any { it.kind == "CASCADE" },
                "BREATH MIDI$midi sustained tone bypasses the answering gate network")
        }
    }

    @Test
    fun `flicker retains its bright requested note while exposing quiet half-order metal partials`() {
        val controls = Wick.defaults(WickVoice.FLICKER) + mapOf("FEED" to 0f, "CASCADE" to 0f)
        fun body(samples: FloatArray) = samples.copyOfRange((.05 * Dsp.RATE).toInt(), (.30 * Dsp.RATE).toInt())
        // These projections divide by each render's own energy, so a louder or quieter copy
        // cannot pass for metallic color. Isolating the first gate also prevents BUBBLE's
        // optional later 3:2 component from masquerading as this voice's primary source.
        fun coherentShare(samples: FloatArray, hz: Double): Double {
            var real = 0.0
            var imaginary = 0.0
            var energy = 0.0
            for (i in samples.indices) {
                val sample = samples[i].toDouble()
                val phase = 2 * PI * hz * i / Dsp.RATE
                real += sample * cos(phase)
                imaginary += sample * sin(phase)
                energy += sample * sample
            }
            return 2 * (real * real + imaginary * imaginary) / (samples.size * energy).coerceAtLeast(1e-30)
        }
        fun halfOrderShare(samples: FloatArray, hz: Double) = listOf(.5, 1.5, 2.5, 3.5, 5.5)
            .sumOf { ratio -> coherentShare(samples, hz * ratio) }
        for (midi in listOf(48, 60, 72)) {
            val probe = Wick.inspect(WickVoice.FLICKER, controls, midi)
            val excerpt = body(probe.raw)
            val wanted = Keys.midiHz(midi)
            val rootShare = coherentShare(excerpt, wanted.toDouble())
            val metalShare = halfOrderShare(excerpt, wanted.toDouble())
            val snip = Snip(excerpt, 1, Dsp.RATE)
            val measured = FineTuning.measuredHz(snip, wanted, 0f, .25f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            val detected = assertNotNull(Pitch.detect(snip, 0f, .25f), "FLICKER MIDI$midi bright body is unpitched")
            val detectedCents = FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())
            println("WICK FLICKER MIDI$midi root share $rootShare, half-order share $metalShare, root $cents cents, detector $detectedCents cents/confidence ${detected.confidence}")
            assertTrue(rootShare > .10, "FLICKER MIDI$midi requested root is only a trace: $rootShare")
            assertTrue(abs(cents) < 25.0, "FLICKER MIDI$midi bright body loses the requested root: $cents cents")
            assertTrue(detected.confidence > .3f && abs(detectedCents) < 35.0,
                "FLICKER MIDI$midi metallic color obscures the playing pitch: ${detected.hz} Hz, confidence ${detected.confidence}")
            assertTrue(metalShare > .002, "FLICKER MIDI$midi has negligible half-order color: $metalShare")
            for (other in listOf(WickVoice.PLUCK, WickVoice.BUBBLE)) {
                val reference = body(Wick.inspect(other, controls, midi).raw)
                val referenceShare = halfOrderShare(reference, wanted.toDouble())
                println("WICK FLICKER vs $other MIDI$midi matched-controls half-order share $metalShare / $referenceShare")
                assertTrue(metalShare > referenceShare * 2.0 + .001,
                    "FLICKER MIDI$midi half-order color is not distinct from $other: $metalShare / $referenceShare")
            }
        }
    }

    @Test
    fun `rattle keeps the requested note beneath repeated root openings across registers`() {
        for (midi in listOf(48, 60, 72)) {
            val probe = Wick.inspect(WickVoice.RATTLE, midi = midi)
            val wanted = Keys.midiHz(midi)
            val body = Snip(probe.raw, 1, Dsp.RATE)
            val measured = FineTuning.measuredHz(body, wanted, .05f, .25f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            val detected = assertNotNull(Pitch.detect(body, .05f, .25f), "RATTLE MIDI$midi opening body is unpitched")
            val detectedCents = FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())
            val rootShare = TangleMeasure.toneShare(probe.raw, wanted.toDouble())
            val rootAdmissions = probe.diagnostics.events.filter {
                it.destination == 0 && it.kind != "RELEASE" && it.admittedStrength > 0.0
            }
            println("WICK RATTLE MIDI$midi root $cents cents, detector $detectedCents cents/confidence ${detected.confidence}, root share $rootShare, root admissions ${rootAdmissions.size}")
            assertTrue(abs(cents) < 25.0, "RATTLE MIDI$midi loses its requested root: $cents cents")
            assertTrue(detected.confidence > .3f && abs(detectedCents) < 35.0,
                "RATTLE MIDI$midi pulse color obscures the playing pitch: ${detected.hz} Hz, confidence ${detected.confidence}")
            assertTrue(rootShare > .10, "RATTLE MIDI$midi underlying root is only a spectral trace: $rootShare")
            assertTrue(rootAdmissions.size >= 3, "RATTLE MIDI$midi default never develops a repeated pitched gesture")
        }
        val controls = Wick.defaults(WickVoice.RATTLE)
        val rattle = Wick.inspect(WickVoice.RATTLE, controls, midi = 60)
        val bubble = Wick.inspect(WickVoice.BUBBLE, controls, midi = 60)
        val difference = TangleMeasure.distance(rattle.samples, bubble.samples)
        fun earlyFeedCount(probe: Wick.Probe) = probe.diagnostics.events.count {
            it.destination == 0 && it.kind == "FEED" && it.timeSeconds <= 1.5 && it.admittedStrength > 0.0
        }
        val rattlePulses = earlyFeedCount(rattle)
        val bubblePulses = earlyFeedCount(bubble)
        println("WICK RATTLE/BUBBLE same-control first 1.5s admitted root feeds $rattlePulses / $bubblePulses, shape difference $difference")
        // A phase offset alone can change waveform distance. The distinct gesture must also
        // admit a denser sequence of genuine root openings in the same elapsed window.
        assertTrue(rattlePulses > bubblePulses,
            "RATTLE has no denser root gesture than BUBBLE at the same controls: $rattlePulses / $bubblePulses")
        assertTrue(difference > .02, "RATTLE is only a gain change of BUBBLE at the same controls: $difference")
    }

    @Test
    fun `rattle spends reserve on repeated pulses and recovery restores later admitted strength`() {
        val controls = Wick.defaults(WickVoice.RATTLE) + mapOf("FEED" to .85f, "CASCADE" to .80f)
        fun rootAdmissions(probe: Wick.Probe) = probe.diagnostics.events.filter {
            it.destination == 0 && it.kind != "RELEASE" && it.requestedStrength > 0.0 && it.admittedStrength > 0.0
        }
        fun laterRatios(probe: Wick.Probe): List<Double> {
            val later = rootAdmissions(probe).filter { it.kind == "FEED" && it.timeSeconds > .4 }
            assertTrue(later.size >= 2, "RATTLE has too few genuine recurrent admissions to reveal depletion")
            println("WICK RATTLE later root FEED admission times ${later.map { it.timeSeconds }}")
            return later.map { it.admittedStrength / it.requestedStrength }
        }
        val depleted = Wick.inspect(WickVoice.RATTLE, controls)
        val constant = Wick.inspect(WickVoice.RATTLE, controls, options = Wick.Options(constantAvailability = true))
        val first = rootAdmissions(depleted).first()
        val initialRatio = first.admittedStrength / first.requestedStrength
        val depletedRatios = laterRatios(depleted)
        val constantRatios = laterRatios(constant)
        println("WICK RATTLE requested/admitted ratios initial $initialRatio, depleted $depletedRatios, constant $constantRatios")
        // Ratios remove requested pulse strength and output leveling as explanations. These
        // are actual root admissions, not a synthetic amplitude pattern or release records.
        assertTrue(depletedRatios.min() < initialRatio * .85, "RATTLE repeats never become meaningfully depleted")
        assertTrue(constantRatios.average() > depletedRatios.average() + .05,
            "constant reserve fails to restore admitted excitation")
        assertTrue(TangleMeasure.distance(depleted.samples, constant.samples) > .01,
            "RATTLE reserve only changes diagnostics")
        for ((previous, next) in rootAdmissions(depleted).zipWithNext()) {
            if (next.timeSeconds > previous.timeSeconds) {
                assertTrue(next.availabilityBefore > previous.availabilityAfter,
                    "RATTLE idle time does not refill its spent root reserve")
            }
        }
        val quick = Wick.inspect(WickVoice.RATTLE, controls + ("RECOVERY" to 0f))
        val slow = Wick.inspect(WickVoice.RATTLE, controls + ("RECOVERY" to 1f))
        val quickRatios = laterRatios(quick)
        val slowRatios = laterRatios(slow)
        println("WICK RATTLE fast/slow recovery admission ratios $quickRatios / $slowRatios")
        assertTrue(quickRatios.average() > slowRatios.average() + .05,
            "faster reserve recovery does not restore later admitted strength")
    }

    @Test
    fun `moderate sources retain a substantial requested root across the supported pitch range`() {
        for (voice in WickVoice.entries) for (midi in listOf(24, 48, 84)) {
            val macros = Wick.defaults(voice) + mapOf("TIMBRE" to .25f, "SPARK" to .5f, "FEED" to .3f)
            val probe = Wick.inspect(voice, macros, midi)
            val snip = Snip(probe.samples, 1, Dsp.RATE)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .08f, .65f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            assertTrue(abs(cents) < 25.0, "$voice MIDI$midi requested spectral root differs by $cents cents")
            assertTrue(TangleMeasure.toneShare(probe.samples, wanted.toDouble()) > .025, "$voice MIDI$midi root is merely leakage")
            // The product detector begins at 40 Hz; MIDI 24 is 32.7 Hz and intentionally outside its range.
            if (midi >= 28) {
                val pitch = assertNotNull(Pitch.detect(snip, .08f, .3f), "$voice MIDI$midi unpitched body")
                assertTrue(pitch.confidence > .3f && abs(FineTuning.cents(pitch.hz.toDouble(), wanted.toDouble())) < 35,
                    "$voice MIDI$midi detected ${pitch.hz} Hz, confidence ${pitch.confidence}")
            }
        }
    }

    @Test
    fun `complete finite exports retain the declared pitched class and mono format`() {
        for (voice in WickVoice.entries) for (midi in listOf(24, 48, 84)) {
            val snip = Wick.render(voice, midi = midi, velocity = .7f)
            assertEquals(44_100, snip.sampleRate)
            assertEquals(1, snip.channels)
            val heard = Classifier.classify(snip).drumClass
            assertTrue(heard !in TangleMeasure.drumClasses, "$voice MIDI$midi classified as $heard")
            assertTrue(Wick.drumClassFor(voice) !in TangleMeasure.drumClasses)
        }
    }

    @Test
    fun `onset evidence avoids drum choke classes or proves sustained breath through the cropped boundary`() {
        val failures = mutableListOf<String>()
        // C1 exposes the extreme low-register limit; C2/C4/C6 exercise the ordinary pitched range.
        for (voice in WickVoice.entries) for (midi in listOf(24, 36, 60, 84)) for (moderate in listOf(false, true)) {
            val macros = Wick.defaults(voice) + if (moderate) mapOf("TIMBRE" to .25f, "SPARK" to .5f, "FEED" to .3f) else emptyMap()
            val full = Wick.render(voice, macros, midi, velocity = .7f)
            val onset = Snip(full.samples.copyOfRange(0, minOf(full.samples.size, (1.2 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            assertTrue(onset.durationSeconds <= 1.2f)
            val classification = Classifier.classify(onset)
            val f = classification.features
            val evidence = "$voice MIDI$midi moderate$moderate onset=${classification.drumClass} " +
                "decayMs=${f.decayMs} centroidHz=${f.centroidHz} lowRatio=${f.lowRatio} flatness=${f.flatness}"
            println("WICK $evidence")
            if (classification.drumClass in TangleMeasure.drumClasses) {
                if (voice == WickVoice.BREATH) {
                    // Features.decayMs measures from the first 99%-peak window. If no
                    // -20 dB crossing occurs, it returns the remaining buffer duration.
                    // A later overlapping BREATH answer can place that peak near the end
                    // of this 1.2-second crop, making sustained audio read as a short decay.
                    // Reproduce its peak windows and require direct envelope
                    // evidence; neither a label override nor a longer-file LOOP shortcut
                    // is accepted as proof that the underlying note remains sustained.
                    val hop = 256
                    val window = 1024
                    val envelope = FloatArray(maxOf(1, onset.samples.size / hop)) { index ->
                        val from = index * hop
                        (from until minOf(onset.samples.size, from + window)).maxOf { abs(onset.samples[it]) }
                    }
                    val peak = envelope.max()
                    val peakIndex = envelope.indexOfFirst { it >= peak * .99f }
                    val peakSeconds = peakIndex * hop.toDouble() / Dsp.RATE
                    val postPeakFloor = envelope.drop(peakIndex).min() / peak.coerceAtLeast(1e-12f)
                    val censoredMs = (envelope.size - peakIndex) * hop * 1000f / Dsp.RATE
                    println("WICK $evidence late peak ${peakSeconds}s, post-peak floor $postPeakFloor")
                    assertTrue(peakSeconds > .65, "$evidence has an early struck peak, not a late overlapping answer")
                    assertTrue(postPeakFloor > .20f, "$evidence actually decays after its peak: floor $postPeakFloor")
                    assertTrue(abs(f.decayMs - censoredMs) < .001f,
                        "$evidence reports an observed decay rather than the cropped remaining duration $censoredMs")
                    val wanted = Keys.midiHz(midi)
                    val body = Snip(onset.samples.copyOfRange((.65 * Dsp.RATE).toInt(), (1.05 * Dsp.RATE).toInt()), 1, Dsp.RATE)
                    val measured = FineTuning.measuredHz(body, wanted, 0f, .4f)
                    val cents = FineTuning.cents(measured, wanted.toDouble())
                    assertTrue(abs(cents) < 25.0, "$evidence late sustained body lost its root: $cents cents")
                    assertTrue(TangleMeasure.toneShare(body.samples, wanted.toDouble()) > .10,
                        "$evidence late sustained root is only a trace")
                } else failures.add(evidence)
            }
        }
        assertTrue(failures.isEmpty(), "Onsets entered drum choke classes:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `held loops close genuine predecessor audio and full network state including PCM export`() {
        val cases = listOf(emptyMap(), mapOf("FEED" to 0f), mapOf("FEED" to 0f, "TIMBRE" to 0f))
        for (voice in WickVoice.entries) for (sparse in cases) {
            val probe = Wick.inspect(voice, Wick.defaults(voice) + mapOf("HOLD" to 1f, "CASCADE" to .75f) + sparse)
            val loop = assertNotNull(probe.diagnostics.loop)
            assertTrue(loop.converged, "$voice returned unconverged HOLD")
            assertTrue(loop.attempts in 2..64, "$voice convergence search ${loop.attempts}")
            assertTrue(loop.stateError < 1e-3, "$voice full-state error ${loop.stateError}")
            assertTrue(loop.seam < 1e-3, "$voice independent-period seam ${loop.seam}")
            assertEquals(loop.periodFrames, probe.samples.size)
            assertTrue(loop.periodSeconds > 1.5, "$voice HOLD shorter than sustained-loop boundary")
            val context = assertNotNull(probe.seamContext, "$voice missing independently rendered predecessor")
            val start = context.size - loop.periodFrames
            assertTrue(start >= 256)
            assertTrue(probe.samples.indices.maxOf { abs(probe.samples[it] - context[start + it]) } < 1e-6f,
                "$voice seam evidence does not contain the exported loop")
            assertTrue(Keys.seamError(context, start) < 1e-3, "$voice context seam")
            val pcm = ByteArrayOutputStream().also { WavWriter.write(it, Snip(context, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16) }
            val exported = WavReader.read(pcm.toByteArray()).samples
            val energyBefore = (start - 256 until start).sumOf { exported[it].toDouble() * exported[it] }
            val exportedSeam = if (energyBefore == 0.0) {
                // A sparse loop can end in exact PCM silence. The relative metric is then 0/0;
                // prove both compared windows and the real playback wrap are silent instead.
                assertTrue((start - 256 until start).all { exported[it] == 0f && exported[it + loop.periodFrames] == 0f },
                    "$voice $sparse changed an independently rendered silent tail")
                assertEquals(0f, exported[start], "$voice $sparse begins with a nonzero sample after silence")
                assertEquals(0f, exported.last(), "$voice $sparse ends with a nonzero sample before silence")
                0.0
            } else Keys.seamError(exported, start)
            assertTrue(exportedSeam < 1e-3, "$voice $sparse exported independent-period seam $exportedSeam")
            assertTrue(probe.diagnostics.events.any { it.kind == "CASCADE" }, "$voice HOLD lost release transmission")
            assertReplayProvenance(probe)
        }
    }

    /** Replay is allowed, but its deliveries must still point back to actual measured causal releases. */
    private fun assertReplayProvenance(probe: Wick.Probe) {
        if (probe.diagnostics.loop?.strategy != "causal-schedule-replay") return
        val d = probe.diagnostics
        val sources = d.replaySourceEvents.associateBy { it.id }
        assertTrue(sources.isNotEmpty(), "HOLD replay lost the measured source event trace")
        val from = assertNotNull(d.replaySourcePeriodStartSeconds)
        val period = assertNotNull(d.loop).periodSeconds
        val replayed = d.events.filter { it.derivation == "causal-schedule-replay" }
        assertTrue(replayed.isNotEmpty(), "HOLD replay has no documented source deliveries")
        for (delivery in replayed) {
            val source = assertNotNull(sources[delivery.sourceEventId], "replayed delivery lost source ${delivery.sourceEventId}")
            assertEquals(source.kind, delivery.kind)
            assertEquals(source.destination, delivery.destination)
            assertEquals(source.parentStage, delivery.parentStage)
            assertEquals(source.requestedStrength, delivery.requestedStrength)
            assertTrue(source.timeSeconds >= from - 1e-9 && source.timeSeconds < from + period + 1e-9,
                "replayed source event lies outside the measured source period")
            if (source.kind == "CASCADE") {
                val release = assertNotNull(sources[source.parentEventId], "replayed cascade has no original release")
                assertEquals("RELEASE", release.kind)
                assertEquals(source.parentStage, release.parentStage)
                assertTrue(abs(source.releaseSeconds - release.timeSeconds) < 1e-9)
                assertTrue(source.timeSeconds > release.timeSeconds, "original replay source used same-update ignition")
            }
        }
    }
}
