package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks causal DSP behavior before the shared loudness stage can disguise it. */
class ThawTest {
    private val rawRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val timbral = listOf("CONTACT", "HEAT", "FREEZE", "CHANNELS", "THICKNESS")

    private fun rms(samples: FloatArray, rate: Int = rawRate, from: Float = 0f, to: Float = samples.size.toFloat() / rate): Double {
        val first = (from * rate).toInt().coerceIn(0, samples.size)
        val end = (to * rate).toInt().coerceIn(first, samples.size)
        var energy = 0.0
        for (i in first until end) energy += samples[i].toDouble() * samples[i]
        return if (end > first) sqrt(energy / (end - first)) else 0.0
    }

    /** Compare timbre at matched RMS, so a final-gain-only macro cannot satisfy this check. */
    private fun normalizedDifference(a: FloatArray, b: FloatArray, from: Float = 0.08f, to: Float = 0.8f): Double {
        val first = (from * rawRate).toInt().coerceAtMost(minOf(a.size, b.size))
        val end = (to * rawRate).toInt().coerceAtMost(minOf(a.size, b.size))
        val aRms = rms(a, from = from, to = to)
        val bRms = rms(b, from = from, to = to)
        if (aRms < 1e-10 || bRms < 1e-10 || first >= end) return 0.0
        var difference = 0.0
        for (i in first until end) {
            val d = a[i] / aRms - b[i] / bRms
            difference += d * d
        }
        return sqrt(difference / (end - first))
    }

    private data class Root(val hz: Double, val powerShare: Double)

    /** Phase and overall gain cannot make a nearly sinusoidal voice pass this check. */
    private fun upperPowerShare(samples: FloatArray, wanted: Float, from: Float, to: Float): Double {
        val n = 32768
        val re = FloatArray(n)
        val im = FloatArray(n)
        val first = (from * Dsp.RATE).toInt()
        val count = minOf((to * Dsp.RATE).toInt() - first, samples.size - first, n)
        assertTrue(count > 1000, "material spectrum window is missing")
        for (i in 0 until count) {
            re[i] = samples[first + i] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1))).toFloat()
        }
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val binHz = Dsp.RATE.toDouble() / n
        val bottom = (40 / binHz).toInt()
        val top = (15000 / binHz).toInt().coerceAtMost(power.lastIndex)
        val upper = (wanted * 1.5 / binHz).toInt().coerceIn(bottom, top)
        val total = (bottom..top).sumOf { power[it] }
        return (upper..top).sumOf { power[it] } / total.coerceAtLeast(1e-30)
    }

    /** A Hann-windowed spectrum of raw audio; no leveling, octave folding, or pitched-source substitution. */
    private fun rootSpectrum(raw: FloatArray, wanted: Float, rate: Int = rawRate): Root {
        val n = 32768
        val re = FloatArray(n)
        val im = FloatArray(n)
        val stride = if (rate == rawRate) Dsp.OVERSAMPLE else 1
        val analysisRate = rate / stride
        val first = (0.2f * rate).toInt()
        val count = minOf((0.45f * analysisRate).toInt(), (raw.size - first) / stride, n)
        assertTrue(count > 1000, "pitch window is missing")
        for (i in 0 until count) {
            re[i] = raw[first + i * stride] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1))).toFloat()
        }
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val binHz = analysisRate.toDouble() / n
        val lower = (wanted * 0.96 / binHz).toInt().coerceAtLeast(2)
        val upper = (wanted * 1.04 / binHz).toInt().coerceAtMost(power.size - 2)
        val best = (lower..upper).maxBy { power[it] }
        val a = sqrt(power[best - 1])
        val b = sqrt(power[best])
        val c = sqrt(power[best + 1])
        val denominator = a - 2 * b + c
        val offset = if (abs(denominator) < 1e-20) 0.0 else (0.5 * (a - c) / denominator).coerceIn(-1.0, 1.0)
        val rootPower = (lower..upper).sumOf { power[it] }
        // Ignore DC and the slowly changing carriage bias; count the audible spectrum.
        val totalPower = ((40 / binHz).toInt()..(15000 / binHz).toInt()).sumOf { power[it] }
        return Root((best + offset) * binHz, rootPower / totalPower.coerceAtLeast(1e-30))
    }

    private fun assertHeld(voice: ThawVoice, macros: Map<String, Float>, velocity: Float = 1f) {
        val label = "$voice $macros velocity $velocity"
        val loop = Thaw.renderLoopMeasured(voice, macros + ("HOLD" to 1f), velocity = velocity)
        assertTrue(loop.samples.isNotEmpty() && loop.samples.all { it.isFinite() }, "$label held loop is invalid")
        assertTrue(rms(loop.samples, Dsp.RATE) > 1e-4, "$label converged by becoming silent")
        assertTrue(loop.rawRms > 1e-5, "$label raw held contact failed to capture a singing state: ${loop.rawRms}")
        assertTrue(loop.converged,
            "$label material and modes failed to converge after ${loop.cycles} cycles (${loop.stateError}; ${loop.groupErrors})")
        assertTrue(loop.stateError.isFinite() && loop.stateError < 1e-3, "$label incompatible internal state at wrap: ${loop.stateError}")
        assertTrue(loop.seam.isFinite() && loop.seam < 1e-3, "$label loop seam ${loop.seam}")
        assertTrue(abs(loop.samples.average()) < 0.01, "$label held loop contains DC")
        val wanted = Thaw.frequencyFor(voice, (Thaw.defaults(voice) + macros).getValue("TUNE"))
        val root = rootSpectrum(loop.samples, wanted, Dsp.RATE)
        val cents = 1200 * ln(root.hz / wanted) / ln(2.0)
        assertTrue(abs(cents) <= 10.0 && root.powerShare > 0.03,
            "$label held material lost the pitched root: $cents cents, ${root.powerShare} spectral share")
    }

    @Test
    fun `every voice exposes five timbral macros plus tune and hold with finite defaults`() {
        for (voice in ThawVoice.entries) {
            val specs = Thaw.macrosFor(voice)
            assertEquals(listOf("TUNE") + timbral + "HOLD", specs.map { it.name }, "$voice macro layout")
            assertTrue(specs.all { it.default.isFinite() && it.default in 0f..1f }, "$voice macro defaults")
        }
    }

    @Test
    fun `identical inputs reproduce raw and exported samples and velocity changes gesture energy`() {
        val macros = Thaw.defaults(ThawVoice.RUNNER) + ("HOLD" to 0.1f)
        val probe = Thaw.Probe(durationSeconds = 0.9f, velocity = 0.7f)
        val first = Thaw.play(ThawVoice.RUNNER, macros, probe)
        val second = Thaw.play(ThawVoice.RUNNER, macros, probe)
        assertContentEquals(first.raw, second.raw, "raw contact/state evolution is nondeterministic")
        assertContentEquals(first.finalState, second.finalState, "material state is nondeterministic")
        assertContentEquals(Thaw.finish(first.raw), Thaw.finish(second.raw), "output processing is nondeterministic")
        val quiet = Thaw.play(ThawVoice.RUNNER, macros, Thaw.Probe(durationSeconds = 0.9f, velocity = 0.25f))
        val strong = Thaw.play(ThawVoice.RUNNER, macros, Thaw.Probe(durationSeconds = 0.9f, velocity = 1f))
        assertTrue(rms(strong.raw, to = 0.15f) > rms(quiet.raw, to = 0.15f) * 1.15, "velocity should increase attack energy before leveling")
    }

    @Test
    fun `default voices and mixed extreme states remain finite before limiting without numerical recovery`() {
        for (voice in ThawVoice.entries) {
            val cases = listOf(
                emptyMap(),
                timbral.associateWith { 0f },
                timbral.associateWith { 1f } + ("TUNE" to 1f),
                mapOf("CONTACT" to 1f, "HEAT" to 1f, "FREEZE" to 0f, "CHANNELS" to 1f, "THICKNESS" to 0f),
            )
            for (macros in cases) {
                val played = Thaw.play(voice, macros, Thaw.Probe(durationSeconds = 1.1f))
                assertTrue(played.raw.isNotEmpty() && played.raw.all { it.isFinite() }, "$voice $macros raw NaN/Inf")
                assertTrue(played.finalState.all { it.isFinite() }, "$voice $macros nonfinite material state")
                assertTrue(played.maxEnergy.isFinite() && played.maxEnergy < 100.0, "$voice $macros uncontrolled modal energy ${played.maxEnergy}")
                assertTrue(played.raw.maxOf { abs(it) } < 10f, "$voice $macros unstable raw peak")
                assertTrue(rms(played.raw) > 1e-6, "$voice $macros silent raw source")
                assertEquals(0, played.recoveredStates, "$voice $macros required numerical recovery")
            }
        }
    }

    @Test
    fun `ordinary raw voices carry a measurable root within ten cents at C3 C4 and C5`() {
        for (voice in ThawVoice.entries) for (midi in listOf(48, 60, 72)) {
            val tune = (midi - Thaw.rootMidi(voice)).toFloat() / Thaw.TUNE_SEMITONES
            assertTrue(tune in 0f..1f, "$voice does not support MIDI $midi")
            val wanted = Keys.midiHz(midi)
            val played = Thaw.play(voice, mapOf("TUNE" to tune), Thaw.Probe(durationSeconds = 0.85f))
            val measured = rootSpectrum(played.raw, wanted)
            val cents = 1200 * ln(measured.hz / wanted) / ln(2.0)
            assertTrue(abs(cents) <= 10.0, "$voice MIDI $midi root ${measured.hz}Hz, $cents cents")
            assertTrue(measured.powerShare > 0.03, "$voice MIDI $midi lacks root identity (${measured.powerShare} spectral share)")
        }
    }

    @Test
    fun `neutral material voices retain audible upper modes instead of collapsing to one sine`() {
        // The original collapse put only 0.009-0.031% of sounding energy above 1.5x
        // the root. A 2% floor (about -17 dB relative to total power) leaves headroom
        // below the revised gestures, while rejecting a near-monochromatic roster.
        // Smooth MELT and heavy SHEET are deliberately not required to be bright.
        val windows = listOf(
            Triple(ThawVoice.BRITTLE, 0f, 0.12f),
            Triple(ThawVoice.RUNNER, 0.18f, 0.65f),
            Triple(ThawVoice.CHANNEL, 0.35f, 1.05f),
        )
        for ((voice, from, to) in windows) {
            val neutral = Thaw.macrosFor(voice).associate { it.name to it.neutral }
            val played = Thaw.play(voice, neutral, Thaw.Probe(durationSeconds = 1.15f))
            val audible = Thaw.finish(played.raw, normalize = false)
            val upper = upperPowerShare(audible, Thaw.frequencyFor(voice, 0.5f), from, to)
            assertTrue(upper >= 0.02,
                "$voice lost its audible upper plate/contact spectrum: ${upper * 100}% above 1.5x root")
        }
    }

    @Test
    fun `neutral short brittle gesture withdraws before runner and heavy sheet at any overall gain`() {
        val lateToEarly = listOf(ThawVoice.BRITTLE, ThawVoice.RUNNER, ThawVoice.SHEET).associateWith { voice ->
            val neutral = Thaw.macrosFor(voice).associate { it.name to it.neutral }
            val played = Thaw.play(voice, neutral, Thaw.Probe(durationSeconds = 2.15f))
            // Each ratio cancels that voice's overall gain, including audition matching.
            rms(played.raw, from = 1.15f, to = 1.4f) /
                rms(played.raw, from = 0.18f, to = 0.45f).coerceAtLeast(1e-10)
        }
        val brittle = lateToEarly.getValue(ThawVoice.BRITTLE)
        assertTrue(lateToEarly.getValue(ThawVoice.RUNNER) > brittle * 3,
            "RUNNER no longer carries a longer contact gesture than BRITTLE: $lateToEarly")
        assertTrue(lateToEarly.getValue(ThawVoice.SHEET) > brittle * 5,
            "SHEET lost its slower heavy-plate gesture relative to BRITTLE: $lateToEarly")
    }

    @Test
    fun `powered friction sustains the same pitched plates beyond a strike-only resonance`() {
        for (voice in listOf(ThawVoice.RUNNER, ThawVoice.MELT)) {
            val macros = mapOf("HOLD" to 0.3f, "CONTACT" to 0.6f)
            val friction = Thaw.play(voice, macros, Thaw.Probe(record = true, thermal = false, durationSeconds = 1.3f))
            val strikeOnly = Thaw.play(voice, macros, Thaw.Probe(record = true, thermal = false, friction = false, durationSeconds = 1.3f))
            val sustained = rms(friction.raw, from = 0.7f, to = 1.2f)
            val passive = rms(strikeOnly.raw, from = 0.7f, to = 1.2f)
            assertTrue(sustained > passive * 2.0, "$voice late contact failed to sustain beyond the shared strike: $passive -> $sustained")
            assertTrue(requireNotNull(friction.trace).contactWork.any { it > 0f }, "$voice runner supplied no friction work")
            assertTrue(requireNotNull(strikeOnly.trace).contactWork.all { it == 0f }, "$voice strike-only probe retained friction")
            val root = rootSpectrum(friction.raw, Thaw.frequencyFor(voice, 0.5f))
            assertTrue(root.powerShare > 0.03, "$voice friction sustain became an unpitched scrape")
        }
    }

    @Test
    fun `heat causes a liquid transition and withdrawal cools it while frozen diagnostics remain dry`() {
        val macros = mapOf("CONTACT" to 0.7f, "HEAT" to 0.9f, "FREEZE" to 0.65f, "CHANNELS" to 0.45f, "HOLD" to 0.1f)
        val evolving = Thaw.play(ThawVoice.RUNNER, macros, Thaw.Probe(record = true, durationSeconds = 5f))
        val frozen = Thaw.play(ThawVoice.RUNNER, macros, Thaw.Probe(record = true, thermal = false, durationSeconds = 5f))
        val trace = requireNotNull(evolving.trace)
        val coldTrace = requireNotNull(frozen.trace)
        val liquid = trace.liquid[0]
        val peak = liquid.maxOrNull() ?: 0f
        assertTrue(liquid.first() < 0.01f, "independent render did not start frozen")
        assertTrue(peak > 0.15f, "powered contact never reached a meaningful melt layer: $peak")
        assertTrue(liquid.last() < peak * 0.6f, "cooling did not reverse the phase trajectory: $peak -> ${liquid.last()}")
        assertTrue(trace.temperature[0].maxOrNull()!! > trace.temperature[0].first() + 0.05f, "heat did not change temperature")
        assertTrue(trace.contactWork.any { it > 0f }, "contact supplied no recorded work")
        assertTrue(coldTrace.liquid.all { plate -> plate.all { abs(it) < 1e-7f } }, "frozen-state diagnostic changed liquid")
        assertTrue(normalizedDifference(evolving.raw, frozen.raw, 0.25f, 0.9f) > 0.05, "material evolution changed only level, or had no audible mechanism")
        for ((name, states) in mapOf("liquid" to trace.liquid, "channel" to trace.channel, "stress" to trace.stress)) {
            assertTrue(states.all { state -> state.all { it.isFinite() && it in 0f..1.00001f } },
                "$name left bounds: minimum ${states.minOf { it.minOrNull()!! }}, maximum ${states.maxOf { it.maxOrNull()!! }}")
        }
        for (i in trace.liquidTotal.indices) {
            assertTrue(abs(trace.liquidTotal[i] - trace.liquidBalance[i]) < 2e-5f,
                "liquid transport created or lost water at ${i.toFloat() / trace.rate}s: ${trace.liquidTotal[i]} vs explicit sources/sinks ${trace.liquidBalance[i]}")
        }
    }

    @Test
    fun `channel transport adds delayed loading and answering plates beyond the resting linkage`() {
        val macros = mapOf("CONTACT" to 0.6f, "HEAT" to 0.9f, "FREEZE" to 0.3f, "CHANNELS" to 0.9f, "HOLD" to 0.2f)
        val full = Thaw.play(ThawVoice.CHANNEL, macros, Thaw.Probe(record = true, durationSeconds = 3.5f))
        val local = Thaw.play(ThawVoice.CHANNEL, macros, Thaw.Probe(record = true, channelTransfer = false, durationSeconds = 3.5f))
        val trace = requireNotNull(full.trace)
        val localTrace = requireNotNull(local.trace)
        assertTrue(trace.channel.all { it.first() == 0f }, "channels were prefilled")
        assertTrue(trace.channel.any { it.maxOrNull()!! > 0.01f }, "melting never reached the channels")
        assertTrue(localTrace.channel.all { values -> values.all { it == 0f } }, "disabled transport still filled channels")
        val firstMelt = trace.liquid[0].indexOfFirst { it > 0.01f }
        val firstChannel = trace.channel[0].indexOfFirst { it > 0.005f }
        assertTrue(firstMelt >= 0 && firstChannel > firstMelt, "channel response did not follow local melting: $firstMelt -> $firstChannel")
        val neighbors = requireNotNull(full.neighbors)
        val localNeighbors = requireNotNull(local.neighbors)
        assertTrue(rms(neighbors, from = 0.25f, to = 1.2f) > 1e-7, "neighboring plates never answered")
        assertTrue(normalizedDifference(neighbors, localNeighbors, 0.4f, 1.4f) > 0.01, "transport did not alter the responding plates")
        assertTrue(rms(requireNotNull(full.enclosure)) > 1e-8, "mounting never excited the wooden enclosure")
        for (i in trace.liquidTotal.indices) {
            assertTrue(abs(trace.liquidTotal[i] - trace.liquidBalance[i]) < 2e-5f,
                "delayed channel transport violated its liquid balance at frame $i")
        }
    }

    @Test
    fun `warm saturated layers conserve liquid throughout long channel transport`() {
        val played = Thaw.play(ThawVoice.CHANNEL,
            mapOf("HEAT" to 1f, "FREEZE" to 0f, "CONTACT" to 1f, "CHANNELS" to 1f, "THICKNESS" to 0f, "HOLD" to 0.85f),
            Thaw.Probe(record = true, durationSeconds = 6f))
        val trace = requireNotNull(played.trace)
        assertTrue(trace.liquidTotal.any { it > 0.2f }, "extreme heat failed to exercise liquid transport")
        for (i in trace.liquidTotal.indices) {
            assertTrue(abs(trace.liquidTotal[i] - trace.liquidBalance[i]) < 2e-5f,
                "saturated contact recreated transported liquid at ${i.toFloat() / trace.rate}s: ${trace.liquidTotal[i]} vs ${trace.liquidBalance[i]}")
        }
        assertTrue(played.raw.all { it.isFinite() }, "long thermal extreme generated nonfinite audio")
        assertEquals(0, played.recoveredStates, "long thermal extreme required numerical recovery")
    }

    @Test
    fun `passive acoustic network decays after powered motion stops`() {
        val played = Thaw.play(
            ThawVoice.SHEET,
            mapOf("CONTACT" to 0.75f, "CHANNELS" to 1f, "THICKNESS" to 0.9f, "HOLD" to 0f),
            Thaw.Probe(record = true, thermal = false, durationSeconds = 4f),
        )
        val trace = requireNotNull(played.trace)
        // The velocity tap names the main carriage; contactForce includes the delayed linked runner.
        val lastDrive = maxOf(
            trace.runnerVelocity.indexOfLast { abs(it) > 1e-7f },
            trace.contactForce.indexOfLast { abs(it) > 1e-7f },
        )
        assertTrue(lastDrive > 0 && lastDrive + trace.rate / 2 < trace.energy.size, "no passive tail was measured")
        val start = (lastDrive + 2).coerceAtMost(trace.energy.lastIndex)
        val initial = trace.energy[start]
        assertTrue(initial > 0f, "passive network contained no stored energy")
        for (i in start + 1 until trace.energy.size) {
            assertTrue(trace.energy[i] <= trace.energy[i - 1] * 1.00001f + 1e-9f, "passive energy grew at ${i.toFloat() / trace.rate}s: ${trace.energy[i - 1]} -> ${trace.energy[i]}")
        }
        assertTrue(trace.energy.last() < initial * 0.25f, "passive resonance failed to decay")
        assertTrue(trace.events.all { it == 0f }, "frozen diagnostic created stress releases")
    }

    @Test
    fun `an undriven dry plate never heats melts fractures or makes audio even at macro extremes`() {
        val played = Thaw.play(
            ThawVoice.FROST,
            timbral.associateWith { 1f },
            Thaw.Probe(record = true, drive = false, durationSeconds = 3f),
        )
        val trace = requireNotNull(played.trace)
        assertTrue(played.raw.all { it == 0f }, "dry idle generated arbitrary audio")
        assertTrue(trace.liquid.all { values -> values.all { it == 0f } }, "unpowered heater melted an idle plate")
        assertTrue(trace.channel.all { values -> values.all { it == 0f } }, "dry idle filled channels")
        assertTrue(trace.stress.all { values -> values.all { it == 0f } }, "dry idle accumulated freezing stress")
        assertTrue(trace.events.all { it == 0f }, "dry idle generated arbitrary freeze crackle")
        assertEquals(0, played.recoveredStates)
    }

    @Test
    fun `fine frost releases follow prior melting and spend stored stress`() {
        val played = Thaw.play(ThawVoice.FROST,
            mapOf("HEAT" to 0.85f, "FREEZE" to 0.9f, "CHANNELS" to 0.9f, "CONTACT" to 0.7f),
            Thaw.Probe(record = true, durationSeconds = 5f))
        val trace = requireNotNull(played.trace)
        val firstMelt = trace.liquid[0].indexOfFirst { it > 0.01f }
        val releases = trace.events.indices.filter { trace.events[it] > 0f }
        assertTrue(releases.isNotEmpty(), "FROST developed no constrained restoration events")
        for (i in releases) {
            assertTrue(firstMelt >= 0 && i > firstMelt, "stress event preceded melting")
            if (i > 0) {
                val before = trace.stress.sumOf { it[i - 1].toDouble() }
                val after = trace.stress.sumOf { it[i].toDouble() }
                assertTrue(after < before, "release failed to reduce stored stress at ${i.toFloat() / trace.rate}s: $before -> $after")
            }
        }
        assertTrue(trace.events.takeLast(trace.rate).all { it == 0f }, "fracture excitation continued after restoration settled")
        assertTrue(played.raw.all { it.isFinite() } && played.recoveredStates == 0, "stress impulses destabilized the plate network")
    }

    @Test
    fun `every timbral macro changes the raw normalized waveform in every voice`() {
        for (voice in ThawVoice.entries) {
            val neutral = Thaw.macrosFor(voice).associate { it.name to it.neutral }
            for (macro in timbral) {
                val low = Thaw.play(voice, neutral + (macro to 0f), Thaw.Probe(record = macro == "HEAT", durationSeconds = 1.1f))
                val high = Thaw.play(voice, neutral + (macro to 1f), Thaw.Probe(record = macro == "HEAT", durationSeconds = 1.1f))
                val difference = normalizedDifference(low.raw, high.raw, 0.06f, 1f)
                assertTrue(difference > 0.02, "$voice $macro changes only level or is inactive: $difference")
                if (macro == "HEAT") {
                    val coolLiquid = requireNotNull(low.trace).liquid[0].maxOrNull()!!
                    val hotLiquid = requireNotNull(high.trace).liquid[0].maxOrNull()!!
                    assertTrue(hotLiquid > coolLiquid + 0.04f,
                        "$voice HEAT changed texture without a material transition: $coolLiquid -> $hotLiquid")
                }
            }
        }
    }

    @Test
    fun `held material converges completely and exported loops satisfy the seam contract`() {
        val cases = ThawVoice.entries.flatMap { voice ->
            listOf(0f, 0.5f, 1f).map { tune -> voice to mapOf("TUNE" to tune) }
        } + listOf(
            ThawVoice.MELT to mapOf("HEAT" to 0.7f, "FREEZE" to 0.5f),
            ThawVoice.FROST to mapOf("HEAT" to 0.75f, "FREEZE" to 0.9f, "CHANNELS" to 0.8f),
        )
        for ((voice, macros) in cases) assertHeld(voice, macros)
    }

    @Test
    fun `quiet held gestures keep pitched capture complete state closure and valid preset loops`() {
        val defaults = ThawVoice.entries.map { it to emptyMap<String, Float>() }
        val presets = ThawPresets.all().filter { Thaw.isLoop(it.macros.getValue("HOLD")) }
            .map { it.voice to it.macros }
        assertTrue(presets.isNotEmpty(), "quiet held preset coverage is missing")
        for ((voice, macros) in defaults + presets) assertHeld(voice, macros, velocity = 0.25f)
    }

    @Test
    fun `default auditions retain pitched classifier routing`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in ThawVoice.entries) {
            val snip = Thaw.render(voice)
            val measured = Classifier.classify(snip).drumClass
            assertTrue(measured !in forbidden, "$voice default audition classified as $measured")
            assertTrue(Thaw.drumClassFor(voice) !in forbidden, "$voice declared a drum route")
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1.00001f }, "$voice final output clipped")
            assertTrue(abs(snip.samples.average()) < 0.01, "$voice final output contains DC")
        }
    }
}
