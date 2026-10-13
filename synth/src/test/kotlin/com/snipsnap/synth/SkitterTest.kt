package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The raw coupled circuit is tested separately from final melodic leveling. */
class SkitterTest {
    @Test
    fun `six voices share exactly the five timbral controls and HOLD with no pitch knob`() {
        assertEquals(listOf("BASS", "LEAD", "HOLLOW", "FLUTTER", "CHIRP", "FRAY"), SkitterVoice.entries.map { it.name })
        for (voice in SkitterVoice.entries) {
            val names = Skitter.macrosFor(voice).map { it.name }
            assertEquals(listOf("BUZZ", "SKITTER", "AGITATE", "VENT", "STATIC", "HOLD"), names)
            assertEquals(names.toSet(), Skitter.defaults(voice).keys)
            assertTrue(Skitter.defaults(voice).values.all { it.isFinite() && it in 0f..1f })
            assertEquals(0f, Skitter.defaults(voice).getValue("HOLD"))
            assertFalse(Skitter.isLoop(0f))
            assertFalse(Skitter.isLoop(.95f))
            assertTrue(Skitter.isLoop(1f))
            assertEquals(DrumClass.TONAL, Skitter.drumClassFor(voice, Skitter.defaults(voice)))
            assertEquals(DrumClass.LOOP, Skitter.drumClassFor(voice, Skitter.defaults(voice) + ("HOLD" to 1f)))
        }
    }

    @Test
    fun `finite HOLD extends the excitation and duration before the top step becomes a loop`() {
        for (voice in SkitterVoice.entries) {
            val notes = listOf(0f, .5f, .95f).map {
                Skitter.renderProbe(voice, Skitter.defaults(voice) + ("HOLD" to it))
            }
            for ((earlier, later) in notes.zipWithNext()) {
                assertTrue(later.snip.frameCount > earlier.snip.frameCount, "$voice HOLD does not extend the note")
                assertEquals(-1, later.loopStart)
                val from = earlier.raw.size - Dsp.RATE / 4
                val end = earlier.raw.size
                assertTrue(SkitterTestMeasure.rms(later.raw, from, end) > SkitterTestMeasure.rms(earlier.raw, from, end),
                    "$voice HOLD adds a silent tail instead of holding excitation")
            }
        }
    }

    @Test
    fun `voice note velocity and seed fully determine raw and leveled samples`() {
        for (voice in SkitterVoice.entries) {
            val macros = Skitter.defaults(voice) + ("STATIC" to .6f)
            val a = Skitter.renderProbe(voice, macros, midi = 57, velocity = .63f, seed = 882, seconds = 1.2f)
            val b = Skitter.renderProbe(voice, macros, midi = 57, velocity = .63f, seed = 882, seconds = 1.2f)
            assertContentEquals(a.raw, b.raw, "$voice deterministic circuit")
            assertContentEquals(a.snip.samples, b.snip.samples, "$voice deterministic leveling")
            assertEquals(a.trace, b.trace, "$voice deterministic state history")
            val otherSeed = Skitter.renderProbe(voice, macros, midi = 57, velocity = .63f, seed = 883, seconds = 1.2f)
            assertFalse(a.raw.contentEquals(otherSeed.raw), "$voice ignores the explicit noise seed")
            val soft = Skitter.renderProbe(voice, macros, midi = 57, velocity = .25f, seed = 882, seconds = 1.2f)
            assertTrue(SkitterTestMeasure.distance(a.raw, soft.raw) > VELOCITY_DISTANCE, "$voice velocity only changes gain")
            assertTrue(soft.diagnostics.pitchOffsetPeakCents < a.diagnostics.pitchOffsetPeakCents, "$voice velocity does not affect provocation")
        }
        assertTrue(Skitter.render(SkitterVoice.BASS, velocity = 0f).samples.all { it == 0f }, "velocity zero is silent")
        for (voice in SkitterVoice.entries) {
            val held = Skitter.renderProbe(voice, Skitter.defaults(voice) + mapOf("HOLD" to 1f, "STATIC" to 1f), velocity = 0f)
            assertTrue(held.raw.all { it == 0f }, "$voice held velocity zero leaves noise in the raw circuit")
            assertTrue(held.snip.samples.all { it == 0f }, "$voice held velocity zero is audible")
        }
    }

    @Test
    fun `raw circuit and detector states remain finite and bounded across register velocity and all high controls`() {
        for (voice in SkitterVoice.entries) for (midi in 24..84 step 12) for (velocity in listOf(.2f, .6f, 1f)) for (high in listOf(false, true)) {
            val macros = if (high) Skitter.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } else Skitter.defaults(voice)
            val label = "$voice MIDI$midi velocity$velocity ${if (high) "all high" else "defaults"}"
            val result = Skitter.renderProbe(voice, macros, midi = midi, velocity = velocity, seconds = 1.2f)
            val d = result.diagnostics
            assertEquals(Dsp.RATE, result.snip.sampleRate)
            assertEquals(1, result.snip.channels)
            assertTrue(result.raw.all { it.isFinite() }, "$label nonfinite raw audio")
            assertTrue(result.snip.samples.all { it.isFinite() && it in -1f..1f }, "$label invalid exported audio")
            assertEquals(0, d.boundHits, "$label uses emergency bounds")
            assertTrue(d.rawPeak in MIN_RAW_PEAK..MAX_RAW_PEAK, "$label raw peak ${d.rawPeak}")
            assertTrue(d.rawRms.isFinite() && d.rawRms > 0.0, "$label silent raw circuit")
            assertTrue(abs(d.rawDc) / d.rawRms < MAX_DC_RATIO, "$label DC ${d.rawDc} against RMS ${d.rawRms}")
            assertTrue(d.filterStatePeak in 0.0..Skitter.FILTER_STATE_LIMIT, "$label filter ${d.filterStatePeak}")
            assertTrue(d.historyPeak in 0.0..Skitter.HISTORY_LIMIT, "$label detector ${d.historyPeak}")
            assertTrue(d.signedHistoryPeak in 0.0..Skitter.SIGNED_HISTORY_LIMIT, "$label signed detector ${d.signedHistoryPeak}")
            assertTrue(d.agitationPeak in 0.0..Skitter.AGITATION_LIMIT, "$label agitation ${d.agitationPeak}")
            assertTrue(d.agitationVelocityPeak in 0.0..Skitter.AGITATION_VELOCITY_LIMIT, "$label agitation velocity ${d.agitationVelocityPeak}")
            assertTrue(d.pitchOffsetPeakCents in 0.0..Skitter.PITCH_OFFSET_LIMIT_CENTS, "$label pitch ${d.pitchOffsetPeakCents}")
            assertEquals(result.raw.maxOf { abs(it.toDouble()) }, d.rawPeak, 1e-6, "$label raw diagnostic peak")
            assertEquals(SkitterTestMeasure.rms(result.raw), d.rawRms, 1e-6, "$label raw diagnostic RMS")
            assertEquals(-1, result.loopStart)
        }
    }

    @Test
    fun `timbral controls change gain matched audio over every quarter and at low nonzero settings`() {
        for (voice in SkitterVoice.entries) for (name in SkitterTestMeasure.timbral) {
            val renders = listOf(0f, .02f, .25f, .5f, .75f, 1f).map {
                Skitter.renderProbe(voice, Skitter.defaults(voice) + (name to it), seconds = 1.2f).raw
            }
            val low = SkitterTestMeasure.distance(renders[0], renders[1])
            assertTrue(low > LOW_MACRO_DISTANCE, "$voice $name 0 to .02 is inactive ($low)")
            val quarters = listOf(renders[0], renders[2], renders[3], renders[4], renders[5])
            for ((i, pair) in quarters.zipWithNext().withIndex()) {
                val moved = SkitterTestMeasure.distance(pair.first, pair.second)
                assertTrue(moved > QUARTER_MACRO_DISTANCE, "$voice $name quarter $i changes only gain ($moved)")
            }
        }
    }

    @Test
    fun `memory agitation unequal recovery and matched noise forcing change the coupled circuit`() {
        for (voice in SkitterVoice.entries) {
            val macros = Skitter.defaults(voice) + mapOf("SKITTER" to .55f, "AGITATE" to .7f, "STATIC" to .55f)
            val full = Skitter.renderProbe(voice, macros, seconds = 1.2f)
            val probes = listOf(
                "memory" to SkitterProbe(memoryFrozen = true),
                "agitation" to SkitterProbe(agitationDisabled = true),
                "unequal recovery" to SkitterProbe(equalRecovery = true),
                "matched noise forcing" to SkitterProbe(noiseForcingDisabled = true),
            )
            for ((name, probe) in probes) {
                val ablated = Skitter.renderProbe(voice, macros, seconds = 1.2f, probe = probe)
                val distance = SkitterTestMeasure.distance(full.raw, ablated.raw)
                assertTrue(distance > ABLATION_DISTANCE, "$voice $name has no timbral contribution ($distance)")
                val cutoffChange = full.trace.zip(ablated.trace).maxOf { (a, b) -> abs(a.cutoffHz.toDouble() - b.cutoffHz.toDouble()) }
                assertTrue(cutoffChange > MIN_ABLATION_CUTOFF_CHANGE, "$voice $name does not influence the filter ($cutoffChange Hz)")
                if (probe.agitationDisabled) assertEquals(0.0, ablated.diagnostics.agitationPeak)
                if (probe.memoryFrozen) {
                    assertEquals(0.0, ablated.diagnostics.historyPeak, "$voice frozen memory still learns")
                    assertEquals(0.0, ablated.diagnostics.signedHistoryPeak)
                    assertTrue(full.diagnostics.historyPeak > 0.0, "$voice signal never enters memory")
                }
                if (probe.noiseForcingDisabled) {
                    val agitationChange = full.trace.zip(ablated.trace).maxOf { (a, b) -> abs(a.agitation.toDouble() - b.agitation.toDouble()) }
                    assertTrue(agitationChange > MIN_NOISE_AGITATION_CHANGE, "$voice STATIC only contributes audible hiss")
                }
            }
        }
    }

    @Test
    fun `mechanism ablations preserve their matched source when the corresponding control is off`() {
        for (voice in SkitterVoice.entries) {
            val quietCircuit = Skitter.defaults(voice) + mapOf("AGITATE" to 0f, "STATIC" to .55f)
            val normal = Skitter.renderProbe(voice, quietCircuit, seconds = .5f)
            val noForcing = Skitter.renderProbe(voice, quietCircuit, seconds = .5f, probe = SkitterProbe(noiseForcingDisabled = true))
            assertContentEquals(normal.raw, noForcing.raw, "$voice noise ablation also changes the audible noise source")
            val frozen = Skitter.renderProbe(voice, quietCircuit, seconds = .5f, probe = SkitterProbe(memoryFrozen = true))
            assertContentEquals(normal.raw, frozen.raw, "$voice AGITATE zero leaves dynamic memory modulation")
            val noDeparture = Skitter.defaults(voice) + ("SKITTER" to 0f)
            val unequal = Skitter.renderProbe(voice, noDeparture, seconds = .5f)
            val equal = Skitter.renderProbe(voice, noDeparture, seconds = .5f, probe = SkitterProbe(equalRecovery = true))
            assertContentEquals(unequal.raw, equal.raw, "$voice equal-recovery ablation changes the base oscillator source")
        }
    }

    @Test
    fun `waveform recovery noise and vent each expose an agitation interaction beyond gain`() {
        for (voice in SkitterVoice.entries) for (name in listOf("BUZZ", "SKITTER", "STATIC", "VENT")) {
            val corners = listOf(0f to 0f, .75f to 0f, 0f to .75f, .75f to .75f).map { (other, agitate) ->
                Skitter.renderProbe(voice, Skitter.defaults(voice) + mapOf(name to other, "AGITATE" to agitate), seconds = 1.2f).raw
            }
            val interaction = SkitterTestMeasure.interaction(corners[0], corners[1], corners[2], corners[3])
            assertTrue(interaction > MIN_INTERACTION, "$voice $name x AGITATE is independent ($interaction)")
        }
    }

    @Test
    fun `low recovery departs then settles and the released powered circuit returns to rest`() {
        for (voice in SkitterVoice.entries) {
            val macros = Skitter.defaults(voice) + mapOf("SKITTER" to .18f, "AGITATE" to .4f, "STATIC" to 0f)
            val result = Skitter.renderProbe(voice, macros, seconds = 7f)
            val early = SkitterTestMeasure.motion(result.trace, 0.0, .4)
            val late = SkitterTestMeasure.motion(result.trace, 6.0, 7.0)
            val pitchEarly = SkitterTestMeasure.pitchDeparture(result.trace, 0.0, .1)
            val pitchLate = SkitterTestMeasure.pitchDeparture(result.trace, 6.0, 7.0)
            assertTrue(early > MIN_ATTACK_AGITATION, "$voice attack does not provoke flutter ($early)")
            assertTrue(late < early * MAX_SETTLED_MOTION_RATIO, "$voice agitation does not settle ($early to $late)")
            assertTrue(pitchEarly > MIN_RECOVERY_CENTS, "$voice low SKITTER has no onset departure")
            assertTrue(pitchLate < pitchEarly * MAX_SETTLED_MOTION_RATIO, "$voice offsets do not recover")
            val tail = SkitterTestMeasure.rms(result.raw, result.raw.size - Dsp.RATE / 2)
            assertTrue(tail < result.diagnostics.rawRms * MAX_RELEASE_ENERGY_RATIO, "$voice excitation-free output does not settle ($tail)")
            val still = Skitter.renderProbe(voice, macros + ("SKITTER" to 0f), seconds = .5f)
            assertEquals(0.0, still.diagnostics.pitchOffsetPeakCents, "$voice SKITTER zero leaves a departure")
        }
    }

    @Test
    fun `moderate sounds retain a detected requested root and actual pitched classification`() {
        for (voice in SkitterVoice.entries) for (midi in listOf(36, 48, 60, 72)) for (velocity in listOf(.25f, .6f, 1f)) {
            val macros = SkitterTestMeasure.moderate(voice)
            val snip = Skitter.render(voice, macros, midi = midi, velocity = velocity)
            val label = "$voice MIDI$midi velocity$velocity"
            val found = assertNotNull(Pitch.detect(snip, .55f, .4f), "$label has no detected root")
            val cents = SkitterTestMeasure.cents(found.hz.toDouble(), Keys.midiHz(midi).toDouble())
            assertTrue(found.confidence >= MIN_PITCH_CONFIDENCE, "$label pitch confidence ${found.confidence}")
            assertTrue(abs(cents) < MAX_MODERATE_PITCH_CENTS, "$label ${found.hz} Hz is $cents cents from root")
            assertTrue(Classifier.classify(snip).drumClass !in DRUMS, "$label full sample is a drum")
            // Crop labels are diagnostics: the drum heuristic can call a harmonic lead a snare and
            // measure a sustained bass's late beating peak as a short decay. Check identity directly.
            val first = Snip(snip.samples.copyOf(minOf(snip.samples.size, (1.4 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            println("$label first1.4s classifier: ${Classifier.classify(first).drumClass}")
            val facts = SkitterTestMeasure.bodyFacts(snip)
            println("$label sustainRatio=${facts[0]} onsetFlatness=${facts[1]} bodyFlatness=${facts[2]}")
            assertTrue(facts[0] >= MIN_SUSTAIN_RATIO, "$label pre-release body dies away (${facts[0]})")
            assertTrue(facts[1] <= MAX_ONSET_FLATNESS, "$label onset is noise dominated (${facts[1]})")
            assertTrue(facts[2] <= MAX_BODY_FLATNESS, "$label body is noise dominated (${facts[2]})")
        }
    }

    @Test
    fun `held defaults and difficult bright noisy extremes close after real coupled preroll and WAV export`() {
        for (voice in SkitterVoice.entries) for ((midi, high) in listOf(48 to false, 24 to true, 84 to true)) {
            val macros = if (high) Skitter.defaults(voice).mapValues { 1f } else Skitter.defaults(voice) + ("HOLD" to 1f)
            val held = Skitter.renderProbe(voice, macros, midi = midi, seed = 917)
            val d = held.diagnostics
            val label = "$voice MIDI$midi ${if (high) "all high" else "defaults"}"
            assertTrue(held.loopStart >= 256, "$label no measured loop region")
            assertTrue(d.loopConverged, "$label coupled state never converged (${d.loopStateError})")
            assertTrue(d.loopStateError <= Skitter.LOOP_STATE_TOLERANCE, "$label convergence tolerance exceeded")
            assertTrue(d.loopPrerollPeriods in 2..Skitter.LOOP_PREROLL_CAP, "$label missing or unbounded state preroll")
            assertEquals(0, d.boundHits, "$label state bounds failed while searching")
            assertTrue(d.agitationPeak > MIN_ATTACK_AGITATION, "$label HOLD disabled flutter")
            assertTrue(d.pitchOffsetPeakCents > MIN_RECOVERY_CENTS, "$label HOLD disabled recovery")
            assertTrue(Keys.seamError(held.snip.samples, held.loopStart) < 1e-3, "$label leveled seam")
            val smpl = SmplChunk(midi, SmplChunk.Loop(held.loopStart.toLong(), held.snip.frameCount.toLong()))
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, held.snip, WavWriter.BitDepth.PCM_24, smpl) }.toByteArray()
            assertEquals(smpl, WavReader.readSmpl(bytes), "$label WAV loop metadata")
            val exported = WavReader.read(bytes)
            assertTrue(Keys.seamError(exported.samples, held.loopStart) < 1e-3, "$label exported seam")
        }
    }

    @Test
    fun `seeded scramble stays finite varied and outside the held loop step`() {
        for (voice in SkitterVoice.entries) {
            val a = Skitter.scramble(voice, Random(928))
            assertEquals(a, Skitter.scramble(voice, Random(928)))
            assertEquals(a, Skitter.scramble(voice, Random(928), temperature = Float.NaN), "$voice nonfinite temperature fallback")
            assertEquals(Skitter.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it.isFinite() && it in 0f..1f })
            assertFalse(Skitter.isLoop(a.getValue("HOLD")))
            assertFalse(a == Skitter.defaults(voice))
            val result = Skitter.renderProbe(voice, a, seconds = 1.2f)
            assertTrue(result.raw.all { it.isFinite() })
            assertTrue(result.diagnostics.rawRms > 0.0)
            assertEquals(0, result.diagnostics.boundHits)
        }
    }

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        // SynthScreen previews run in runInterruptible: a newer request interrupts the worker, and the
        // sample loops, the forcing, the decimation and the level must stop on that flag instead of finishing.
        for ((missing, render) in listOf<Pair<String, () -> Unit>>(
            "SKITTER finite render did not stop on the interrupt at its first look in the sample loop" to
                { Skitter.render(SkitterVoice.BASS) },
            "SKITTER held render did not stop on the interrupt at its first look in the held forcing" to
                { Skitter.render(SkitterVoice.BASS, mapOf("HOLD" to 1f)) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException>(missing) { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(Skitter.render(SkitterVoice.BASS).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    @Test
    fun `the decimation stops on its own when the render is no longer wanted`() {
        // The sample loops look every 8192 samples, but the sinc decimation that follows them convolves the whole
        // oversampled buffer, so it must look for itself rather than run to the end unwanted.
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException>(
                "SKITTER's decimation did not stop on the interrupt at its first look in the convolution loop",
            ) { Skitter.decimate(FloatArray(4 * 4096), 4, false) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(4096, Skitter.decimate(FloatArray(4 * 4096), 4, false).size, "an uninterrupted decimation still returns every frame")
    }

    private companion object {
        // Round-three register sweep (216 cases): peak .0386.. .5307, DC/RMS <= .00456,
        // no emergency state-bound hits. Margins preserve the pre-level gain budget.
        const val MIN_RAW_PEAK = .01
        const val MAX_RAW_PEAK = .8
        const val MAX_DC_RATIO = .01
        const val VELOCITY_DISTANCE = .01
        // Six voices/five controls: low .02 distance >= .00144; each quarter >= .0166.
        const val LOW_MACRO_DISTANCE = .001
        const val QUARTER_MACRO_DISTANCE = .01
        // Mechanism minima: matched-noise distance .0195, four-corner interaction .0440,
        // cutoff change 102 Hz, and noise-forcing agitation change .0972.
        const val ABLATION_DISTANCE = .01
        const val MIN_INTERACTION = .02
        const val MIN_ABLATION_CUTOFF_CHANGE = 50.0
        const val MIN_NOISE_AGITATION_CHANGE = .04
        // Low recovery: attack motion >= .0866 and departure 7.126 cents; late/early
        // motion <= 3.39e-5 and final raw audio below float resolution.
        const val MIN_ATTACK_AGITATION = .04
        const val MIN_RECOVERY_CENTS = 3.0
        const val MAX_SETTLED_MOTION_RATIO = .0001
        const val MAX_RELEASE_ENERGY_RATIO = .001
        // Moderate pitch matrix (72 cases): confidence >= .8190, root error <= 1.50 cents.
        const val MIN_PITCH_CONFIDENCE = .75f
        const val MAX_MODERATE_PITCH_CENTS = 5.0
        // Focused onset/body matrix (48 cases): pre-release/early RMS >= .6894,
        // onset flatness <= .1187 and body flatness <= .0525.
        const val MIN_SUSTAIN_RATIO = .55
        const val MAX_ONSET_FLATNESS = .16
        const val MAX_BODY_FLATNESS = .08
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
