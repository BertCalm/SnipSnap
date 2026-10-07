package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mechanical and acoustic checks use the dry network before shared loudness processing. */
class SutureTest {
    private val rawRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val timbral = listOf("GAP", "STITCH", "CORD", "SEAM", "CAVITY")
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    private fun rms(samples: FloatArray, rate: Int = rawRate, from: Float = 0f,
        to: Float = samples.size.toFloat() / rate): Double {
        val first = (from * rate).toInt().coerceIn(0, samples.size)
        val end = (to * rate).toInt().coerceIn(first, samples.size)
        var energy = 0.0
        for (i in first until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - first).coerceAtLeast(1))
    }

    /** Matching RMS prevents a control that changes only final gain from satisfying activity. */
    private fun normalizedDifference(a: FloatArray, b: FloatArray, from: Float = .04f, to: Float = 1.15f): Double {
        val first = (from * rawRate).toInt().coerceAtMost(minOf(a.size, b.size))
        val end = (to * rawRate).toInt().coerceAtMost(minOf(a.size, b.size))
        val ra = rms(a, from = from, to = to)
        val rb = rms(b, from = from, to = to)
        if (ra < 1e-10 || rb < 1e-10 || end <= first) return 0.0
        var difference = 0.0
        for (i in first until end) {
            val d = a[i] / ra - b[i] / rb
            difference += d * d
        }
        return sqrt(difference / (end - first))
    }

    private data class Spectrum(val rootShare: Double, val upperShare: Double)

    private data class Shape(val bands: DoubleArray, val envelope: DoubleArray)

    /** Broad spectral bands and energy trajectories disregard waveform phase. */
    private fun shape(raw: FloatArray, rate: Int = rawRate, seconds: Float = 1.2f): Shape {
        val edges = doubleArrayOf(40.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 15000.0)
        val bands = DoubleArray(edges.size - 1)
        val n = 8192
        val windows = generateSequence(.04f) { it + .25f }.takeWhile { it + n.toFloat() / Dsp.RATE <= seconds }
        for (from in windows) {
            val first = (from * rate).toInt()
            val re = FloatArray(n) { i ->
                val index = first + i * (rate / Dsp.RATE)
                if (index < raw.size) raw[index] * (.5 - .5 * cos(2 * PI * i / (n - 1))).toFloat() else 0f
            }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (k in 1 until n / 2) {
                val hz = k.toDouble() * Dsp.RATE / n
                for (band in bands.indices) if (hz >= edges[band] && hz < edges[band + 1]) {
                    bands[band] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                    break
                }
            }
        }
        val total = bands.sum().coerceAtLeast(1e-30)
        for (i in bands.indices) bands[i] /= total
        val envelope = DoubleArray(((seconds - .1f) / .02f).toInt().coerceAtLeast(1)) { i ->
            val level = rms(raw, rate, from = .04f + i * .02f, to = .06f + i * .02f)
            level * level
        }
        val envelopeTotal = envelope.sum().coerceAtLeast(1e-30)
        for (i in envelope.indices) envelope[i] /= envelopeTotal
        return Shape(bands, envelope)
    }

    private fun spectralChange(a: Shape, b: Shape): Double {
        val useful = a.bands.indices.filter { maxOf(a.bands[it], b.bands[it]) >= .001 }
        return useful.map { abs(10 * log10((a.bands[it] + 1e-8) / (b.bands[it] + 1e-8))) }.average()
    }

    private fun temporalChange(a: Shape, b: Shape): Double =
        a.envelope.indices.sumOf { abs(a.envelope[it] - b.envelope[it]) }

    /** Narrow comb lines distinguish a numerical tick from broadband wooden contact. */
    private fun cadenceShare(samples: FloatArray, cadence: Double): Double {
        var n = 1
        while (n < samples.size) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in samples.indices) re[i] = samples[i] *
            (.5 - .5 * cos(2 * PI * i / (samples.size - 1))).toFloat()
        Fft.forward(re, im)
        var total = 0.0
        var comb = 0.0
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * Dsp.RATE / n
            if (hz < 40.0 || hz > 15000.0) continue
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += power
            val harmonic = (hz / cadence + .5).toInt()
            if (harmonic >= 1 && abs(hz - harmonic * cadence) <= 2.0) comb += power
        }
        return comb / total.coerceAtLeast(1e-30)
    }

    /** The requested root must carry energy, rather than merely exist as an inaudible pilot tone. */
    private fun spectrum(samples: FloatArray, wanted: Float, from: Float = .12f, seconds: Float = .45f): Spectrum {
        val n = 32768
        val first = (from * Dsp.RATE).toInt()
        val count = minOf((seconds * Dsp.RATE).toInt(), samples.size - first, n)
        assertTrue(count > 1000, "missing spectral analysis window")
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val binHz = Dsp.RATE.toDouble() / n
        var total = 0.0
        var root = 0.0
        var upper = 0.0
        for (k in 1 until n / 2) {
            val hz = k * binHz
            if (hz < 40.0 || hz > 15000.0) continue
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += power
            if (hz in wanted * .96..wanted * 1.04) root += power
            if (hz > wanted * 1.5) upper += power
        }
        return Spectrum(root / total.coerceAtLeast(1e-30), upper / total.coerceAtLeast(1e-30))
    }

    @Test
    fun `all voices expose the pitched controls with valid defaults`() {
        for (voice in SutureVoice.entries) {
            val macros = Suture.macrosFor(voice)
            assertEquals(listOf("TUNE") + timbral + "HOLD", macros.map { it.name }, "$voice macro layout")
            assertTrue(macros.all { it.default.isFinite() && it.default in 0f..1f &&
                it.neutral.isFinite() && it.neutral in 0f..1f }, "$voice invalid macro value")
        }
    }

    @Test
    fun `identical voices reproduce audio and mechanical state and velocity supplies finite energy`() {
        for (voice in SutureVoice.entries) {
            val probe = Suture.Probe(record = true, durationSeconds = .85f, velocity = .7f)
            val a = Suture.play(voice, emptyMap(), probe)
            val b = Suture.play(voice, emptyMap(), probe)
            assertContentEquals(a.raw, b.raw, "$voice raw render is nondeterministic")
            assertContentEquals(a.finalState, b.finalState, "$voice final state is nondeterministic")
            assertContentEquals(requireNotNull(a.trace).slipEvents, requireNotNull(b.trace).slipEvents,
                "$voice friction event ordering is nondeterministic")
            assertContentEquals(Suture.finish(a.raw), Suture.finish(b.raw), "$voice export is nondeterministic")
        }
        val quiet = Suture.play(SutureVoice.BLOOM, emptyMap(), Suture.Probe(durationSeconds = .3f, velocity = .2f))
        val strong = Suture.play(SutureVoice.BLOOM, emptyMap(), Suture.Probe(durationSeconds = .3f, velocity = 1f))
        assertTrue(rms(strong.raw, to = .12f) > 2 * rms(quiet.raw, to = .12f), "velocity lost its finite release energy")
    }

    @Test
    fun `zero velocity supplies neither an opening gesture nor powered acoustic work`() {
        for (voice in SutureVoice.entries) {
            val played = Suture.play(voice, emptyMap(),
                Suture.Probe(record = true, durationSeconds = .7f, velocity = 0f))
            val trace = requireNotNull(played.trace)
            assertTrue(played.raw.all { it == 0f }, "$voice silent event generated audio")
            assertTrue(trace.gap.all { gap -> gap.all { it == 0f } }, "$voice silent event opened the vessel")
            assertTrue(trace.actuatorWork.all { it == 0f } && trace.poweredAcousticWork.all { it == 0f },
                "$voice silent event supplied mechanical or acoustic work")
        }
    }

    @Test
    fun `ordinary voices retain the requested root and pitched routing at three registers`() {
        var worstCents = 0.0
        var leastRoot = 1.0
        val failures = mutableListOf<String>()
        for (voice in SutureVoice.entries) for (tune in listOf(0f, .5f, 1f)) {
            val macros = mapOf("TUNE" to tune)
            val wanted = Suture.frequencyFor(voice, tune)
            val played = Suture.play(voice, macros, Suture.Probe(durationSeconds = .85f))
            val dry = Suture.finish(played.raw, normalize = false)
            val cents = FineTuning.cents(FineTuning.measuredHz(dry, Dsp.RATE, wanted, .12f, .45f), wanted.toDouble())
            val material = spectrum(dry, wanted)
            worstCents = maxOf(worstCents, abs(cents))
            leastRoot = minOf(leastRoot, material.rootShare)
            if (abs(cents) > 10.0) failures += "$voice TUNE $tune: $cents cents from $wanted Hz"
            if (material.rootShare < .08) failures += "$voice TUNE $tune: root has only ${material.rootShare} of spectral energy"
            // Full exports exceed the classifier's duration-only LOOP rule. This shorter
            // dry observation also exercises its pitched-versus-drum spectral guards.
            val shortClass = Classifier.classify(Snip(dry, channels = 1, sampleRate = Dsp.RATE))
            if (shortClass.drumClass in drums) failures += "$voice TUNE $tune short dry material classified as ${shortClass.drumClass}"
            val snip = Suture.render(voice, macros)
            assertEquals(Suture.renderFrames(voice, macros), snip.samples.size, "$voice TUNE $tune duration contract")
            val heard = Classifier.classify(snip)
            assertTrue(heard.drumClass !in drums, "$voice TUNE $tune classified as ${heard.drumClass}")
            val filed = Suture.drumClassFor(voice, macros)
            if (heard.drumClass == DrumClass.LOOP || filed == DrumClass.LOOP)
                assertEquals(filed, heard.drumClass, "$voice TUNE $tune loop routing differs from classifier")
            val detected = Pitch.detect(snip)
            if (detected == null) failures += "$voice TUNE $tune has no detected pitch"
            else if (abs(FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())) >= 50.0)
                failures += "$voice TUNE $tune detector lost the root: ${detected.hz} for $wanted"
            println("SUTURE $voice TUNE $tune: $cents cents, root share ${material.rootShare}, short class ${shortClass.drumClass}")
        }
        println("SUTURE ordinary pitch: worst $worstCents cents, minimum root spectral share $leastRoot")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every normal voice retains active timbral controls at matched level`() {
        var leastChange = Double.POSITIVE_INFINITY
        val failures = mutableListOf<String>()
        for (voice in SutureVoice.entries) {
            val companions = Suture.macrosFor(voice).associate { it.name to it.neutral }
            for (macro in timbral) {
                // Closure can take two seconds. A 1.2-second observation missed the
                // mechanism this control is meant to change, so include the full gesture.
                val seconds = 3f
                val low = Suture.play(voice, companions + (macro to .15f), Suture.Probe(durationSeconds = seconds))
                val high = Suture.play(voice, companions + (macro to .85f), Suture.Probe(durationSeconds = seconds))
                val difference = normalizedDifference(low.raw, high.raw, to = seconds - .05f)
                val lowShape = shape(low.raw, seconds = seconds)
                val highShape = shape(high.raw, seconds = seconds)
                val spectral = spectralChange(lowShape, highShape)
                val temporal = temporalChange(lowShape, highShape)
                leastChange = minOf(leastChange, difference)
                if (difference <= .04) failures += "$voice $macro changed only level or was inactive: $difference"
                if (spectral <= .35 && temporal <= .05) failures +=
                    "$voice $macro changed phase without useful material or timing change: $spectral dB bands, $temporal energy redistribution"
                println("SUTURE $voice $macro: $spectral dB bands, $temporal temporal redistribution, $difference matched waveform")
                if (macro == "STITCH") println("SUTURE $voice STITCH low/high closure: ${low.closureSeconds}/${high.closureSeconds}")
            }
        }
        println("SUTURE least matched-level macro difference: $leastChange")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `neutral bronze thread and strain retain upper modes and the voice roster remains distinct`() {
        val shapes = mutableMapOf<SutureVoice, Shape>()
        val failures = mutableListOf<String>()
        for (voice in SutureVoice.entries) {
            val neutral = Suture.macrosFor(voice).associate { it.name to it.neutral }
            val played = Suture.play(voice, neutral, Suture.Probe(durationSeconds = 1.2f))
            shapes[voice] = shape(played.raw)
            if (voice in setOf(SutureVoice.BLOOM, SutureVoice.THREAD, SutureVoice.STRAIN)) {
                val dry = Suture.finish(played.raw, normalize = false)
                val upper = spectrum(dry, Suture.frequencyFor(voice, .5f), .04f, .3f).upperShare
                if (upper < .02) failures += "$voice became almost a single sine: $upper energy above 1.5x root"
                println("SUTURE $voice neutral upper share: $upper")
            }
        }
        for (a in SutureVoice.entries) for (b in SutureVoice.entries.filter { it.ordinal > a.ordinal }) {
            val sa = shapes.getValue(a)
            val sb = shapes.getValue(b)
            val spectral = spectralChange(sa, sb)
            val temporal = temporalChange(sa, sb)
            if (spectral <= .15 && temporal <= .03) failures +=
                "$a and $b lost their voice distinction: $spectral dB bands, $temporal energy redistribution"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `passive acoustic root excitation settles and reciprocal coupling transfers energy`() {
        val macros = timbral.associateWith { .85f }
        val probe = Suture.Probe(record = true, gesture = false, drive = false,
            durationSeconds = 3.2f, initialEnergy = .02)
        val played = Suture.play(SutureVoice.STRAIN, macros, probe)
        val trace = requireNotNull(played.trace)
        assertTrue(trace.actuatorWork.all { it == 0f }, "passive probe retained powered stitch work")
        assertTrue(trace.poweredAcousticWork.all { it == 0f }, "passive probe retained acoustic input")
        assertTrue(played.maxEnergy <= .02001, "passive coupling created energy: ${played.maxEnergy}")
        assertTrue(trace.energy.last() < .02 * .01, "passive vessel did not settle: ${trace.energy.last()}")
        for (i in 1 until trace.energy.size)
            assertTrue(trace.energy[i] <= trace.energy[i - 1] + 2e-7f, "passive energy grew at trace frame $i")
        assertTrue(abs(trace.energy.last() + trace.passiveLoss.last() - .02) < 2e-5,
            "passive energy accounting does not balance stored energy and losses")
        assertTrue(rms(played.raw, from = 2.8f, to = 3.2f) < .15 * rms(played.raw, from = .02f, to = .3f),
            "passive tail failed to decay acoustically")
        val isolated = Suture.play(SutureVoice.STRAIN, macros,
            Suture.Probe(record = true, gesture = false, drive = false, coupling = false,
                durationSeconds = .7f, initialEnergy = .02))
        val answering = rms(requireNotNull(played.cords), to = .7f) + rms(requireNotNull(played.cavity), to = .7f)
        val disconnected = rms(requireNotNull(isolated.cords)) + rms(requireNotNull(isolated.cavity))
        assertTrue(answering > 1e-6, "root plate never excited the cords or cavity through coupling")
        assertTrue(disconnected < 1e-12, "disconnected cords or cavity generated their own passive energy")
        println("SUTURE passive acoustic energy: .02 -> ${trace.energy.last()}, maximum ${played.maxEnergy}, " +
            "balance error ${abs(trace.energy.last() + trace.passiveLoss.last() - .02)}, coupled branch RMS $answering")
    }

    @Test
    fun `ringing slows closure through resistance rather than a velocity timing envelope`() {
        val macros = mapOf("GAP" to .7f, "STITCH" to .55f, "CORD" to .5f, "SEAM" to .3f)
        fun close(velocity: Float, resistance: Boolean): Double {
            val played = Suture.play(SutureVoice.STRAIN, macros,
                Suture.Probe(record = true, durationSeconds = 3f, velocity = velocity, resistance = resistance))
            return requireNotNull(played.closureSeconds) { "closure stalled at velocity $velocity resistance $resistance" }
        }
        val quiet = close(.2f, true)
        val strong = close(1f, true)
        val quietUnresisted = close(.2f, false)
        val strongUnresisted = close(1f, false)
        val extraDelay = (strong - quiet) - (strongUnresisted - quietUnresisted)
        println("SUTURE closure quiet/strong: $quiet / $strong, no resistance $quietUnresisted / $strongUnresisted")
        assertTrue(strong > quiet, "stronger plate vibration did not delay closure")
        assertTrue(extraDelay > .015, "closure timing has no measurable acoustic resistance: $extraDelay seconds")

        // Fixed velocity separates measured acoustic resistance from an event-velocity lookup.
        fun initiallyRinging(energy: Double, resistance: Boolean): Double = requireNotNull(Suture.play(
            SutureVoice.STRAIN, macros, Suture.Probe(durationSeconds = 3f, velocity = .2f,
                initialEnergy = energy, resistance = resistance)).closureSeconds)
        val seededDelay = initiallyRinging(.04, true) - initiallyRinging(0.0, true)
        val unresistedSeededDelay = initiallyRinging(.04, false) - initiallyRinging(0.0, false)
        println("SUTURE fixed-velocity stored-energy delay: $seededDelay seconds, resistance off $unresistedSeededDelay")
        assertTrue(seededDelay - unresistedSeededDelay > .015,
            "fixed-velocity stored vibration did not affect closure: $seededDelay vs $unresistedSeededDelay")
    }

    @Test
    fun `stationary cords and disabled contacts cannot generate new friction or seam activity`() {
        val macros = mapOf("GAP" to .7f, "STITCH" to .7f, "CORD" to .9f, "SEAM" to .9f)
        val live = Suture.play(SutureVoice.THREAD, macros, Suture.Probe(record = true, durationSeconds = 1.4f))
        val stopped = Suture.play(SutureVoice.THREAD, macros,
            Suture.Probe(record = true, durationSeconds = 1.4f, cordMotion = false, seam = false))
        val trace = requireNotNull(live.trace)
        val still = requireNotNull(stopped.trace)
        assertTrue(trace.frictionWork.any { it > 0f } && trace.slipEvents.any { it > 0f }, "moving threads produced no friction work or slips")
        assertTrue(trace.seamActivity.any { it > 0f }, "closing edges never contacted")
        assertTrue(trace.seamLoss.last() > 0f, "seam failed to dissipate acoustic energy")
        assertTrue(trace.actuatorWork.last() > 0f, "closure supplied no explicit actuator work")
        assertTrue(trace.poweredAcousticWork.last() > 0f, "motion supplied no explicit acoustic work")
        assertTrue(abs(trace.energy.last() + trace.passiveLoss.last() - trace.poweredAcousticWork.last()) < 2e-5,
            "powered network energy does not balance its explicit input and losses")
        for (i in 1 until trace.seamLoss.size)
            assertTrue(trace.seamLoss[i] >= trace.seamLoss[i - 1], "seam returned dissipated energy at frame $i")
        for (i in trace.actuatorWork.indices)
            assertTrue(trace.actuatorAcousticWork[i] <= trace.actuatorWork[i] + 1e-6f,
                "acoustic contact borrowed powered work before the actuator delivered it at frame $i")
        assertTrue(still.frictionWork.all { it == 0f } && still.slipEvents.all { it == 0f }, "stationary cords retained a friction source")
        assertTrue(still.seamActivity.all { it == 0f } && still.seamLoss.all { it == 0f }, "disabled seam retained contacts")
        assertTrue(requireNotNull(stopped.seam).all { it == 0f }, "disabled seam retained an audio branch")
        val zero = Suture.play(SutureVoice.THREAD, macros + ("SEAM" to 0f),
            Suture.Probe(record = true, durationSeconds = 1.4f))
        assertTrue(requireNotNull(zero.trace).seamActivity.all { it == 0f }, "SEAM zero is not a true contact off state")
    }

    @Test
    fun `one shot stops new carriage work after closure while its material rings down`() {
        val played = Suture.play(SutureVoice.THREAD,
            mapOf("GAP" to .5f, "STITCH" to .75f, "CORD" to .85f),
            Suture.Probe(record = true, durationSeconds = 2.5f))
        val closed = requireNotNull(played.closureSeconds) { "ordinary thread failed to close" }
        val trace = requireNotNull(played.trace)
        val settledFrame = ((closed + .25) * trace.rate).toInt().coerceAtMost(trace.frames - 1)
        assertTrue(settledFrame < trace.frames - trace.rate / 4, "no room to observe the closed vessel")
        val workAfterClosure = trace.frictionWork.last() - trace.frictionWork[settledFrame]
        assertTrue(workAfterClosure <= 1e-8,
            "closed stationary links continued receiving carriage work: $workAfterClosure")
        assertTrue(trace.actuatorWork.last() - trace.actuatorWork[settledFrame] <= 1e-8,
            "closed one-shot kept a powered actuator running")
        assertTrue(trace.travelSpeed.all { cord -> (settledFrame until trace.frames).all { abs(cord[it]) < 1e-5f } },
            "closed one-shot retained cord travel")
    }

    @Test
    fun `unpowered opening spends only finite gesture work and the vessel settles`() {
        val played = Suture.play(SutureVoice.BLOOM, mapOf("GAP" to .7f, "CORD" to .7f),
            Suture.Probe(record = true, drive = false, durationSeconds = 4f))
        val trace = requireNotNull(played.trace)
        assertTrue(trace.actuatorWork.all { it == 0f } && trace.actuatorAcousticWork.all { it == 0f },
            "disabled stitcher supplied powered work")
        assertTrue(trace.gestureAcousticWork.last() > 0f, "finite opening supplied no acoustic energy")
        assertTrue(trace.storedMechanicalWork.all { it >= 0f && it.isFinite() }, "finite opening work reservoir became invalid")
        val mostStored = trace.storedMechanicalWork.maxOrNull() ?: 0f
        assertTrue(trace.storedMechanicalWork.last() < mostStored * .15f,
            "unpowered opening retained or replenished its work reservoir: $mostStored -> ${trace.storedMechanicalWork.last()}")
        assertTrue(trace.energy.last() < played.maxEnergy * .01, "unpowered opening did not settle acoustically")
    }

    @Test
    fun `ordinary full one shots end forty decibels below their early bloom`() {
        var worstRatio = 0.0
        for (voice in SutureVoice.entries) {
            val played = Suture.play(voice, emptyMap())
            val early = (0 until 6).maxOf { rms(played.raw, from = it * .05f, to = (it + 1) * .05f) }
            val seconds = played.raw.size.toFloat() / rawRate
            val tail = rms(played.raw, from = seconds - .05f, to = seconds)
            val ratio = tail / early.coerceAtLeast(1e-20)
            worstRatio = maxOf(worstRatio, ratio)
            assertTrue(ratio <= .01, "$voice raw one-shot was cut off above -40dB: tail/early RMS $ratio")
        }
        println("SUTURE largest raw end/early RMS ratio: $worstRatio")
    }

    @Test
    fun `one shots including mixed extremes stay bounded before limiting and terminate`() {
        var largestEnergy = 0.0
        var largestPeak = 0.0
        for (voice in SutureVoice.entries) for (macros in listOf(
            emptyMap(), timbral.associateWith { 0f }, timbral.associateWith { 1f } + ("TUNE" to 1f),
            mapOf("GAP" to 1f, "STITCH" to 0f, "CORD" to 1f, "SEAM" to 1f, "CAVITY" to 1f),
        )) {
            val played = Suture.play(voice, macros)
            assertTrue(played.raw.all { it.isFinite() } && played.finalState.all { it.isFinite() }, "$voice $macros nonfinite state")
            val peak = played.raw.maxOf { abs(it) }.toDouble()
            largestPeak = maxOf(largestPeak, peak)
            largestEnergy = maxOf(largestEnergy, played.maxEnergy)
            assertTrue(peak < 4.0 && played.maxEnergy < 1.0, "$voice $macros uncontrolled peak/energy: $peak / ${played.maxEnergy}")
            assertEquals(0, played.recoveredStates, "$voice $macros required numerical recovery")
            assertTrue(rms(played.raw) > 1e-6, "$voice $macros silent vessel")
            val frames = Suture.renderFrames(voice, macros)
            assertTrue(frames in (2 * Dsp.RATE)..(6 * Dsp.RATE), "$voice $macros unbounded render duration $frames")
            assertEquals(frames * Dsp.OVERSAMPLE, played.raw.size, "$voice $macros full network duration")
        }
        println("SUTURE extreme raw peak $largestPeak; maximum acoustic energy $largestEnergy")
    }

    @Test
    fun `held mechanical cycles remain pitched bounded and close their full state`() {
        val failures = mutableListOf<String>()
        val cases = listOf(
            SutureVoice.BLOOM to emptyMap(),
            SutureVoice.THREAD to emptyMap(),
            SutureVoice.MURMUR to emptyMap(),
            SutureVoice.STRAIN to (timbral.associateWith { 1f } + ("TUNE" to 1f)),
            SutureVoice.SHELL to mapOf("STITCH" to 0f, "CORD" to 1f, "SEAM" to 1f, "CAVITY" to 1f, "TUNE" to 0f),
        )
        for ((voice, macros) in cases) {
            val held = Suture.renderLoopMeasured(voice, macros + ("HOLD" to 1f))
            val label = "$voice $macros"
            assertTrue(held.samples.isNotEmpty() && held.samples.all { it.isFinite() }, "$label invalid loop")
            val outputRms = rms(held.samples, Dsp.RATE)
            if (held.rawRms <= 1e-5 || outputRms <= 1e-4) failures += "$label loop converged by falling silent: raw ${held.rawRms}, output $outputRms"
            if (!held.converged || held.stateError >= 1e-3) failures += "$label mechanical cycle did not converge after ${held.cycles}: ${held.stateError} ${held.groupErrors}"
            if (held.seam >= 1e-3) failures += "$label held seam ${held.seam}"
            if (abs(held.samples.average()) >= .01 || held.samples.maxOf { abs(it) } > 1f) failures += "$label held DC or uncontrolled peak"
            if (held.actuatorWorkPerCycle <= 0.0 || held.acousticWorkPerCycle <= 0.0)
                failures += "$label held material lacks explicit powered work"
            if (!held.maxEnergy.isFinite() || held.maxEnergy >= .5)
                failures += "$label held acoustic energy escaped its bound: ${held.maxEnergy}"
            val wanted = Suture.frequencyFor(voice, (Suture.defaults(voice) + macros).getValue("TUNE"))
            val cents = FineTuning.cents(FineTuning.measuredHz(held.samples, Dsp.RATE, wanted, .05f, .4f), wanted.toDouble())
            val rootShare = spectrum(held.samples, wanted, .05f, .4f).rootShare
            if (abs(cents) >= 15.0) failures += "$label held root lost pitch: $cents cents"
            if (rootShare <= .03) failures += "$label held root became inaudible: $rootShare"
            val detected = Pitch.detect(Snip(held.samples, channels = 1, sampleRate = Dsp.RATE))
            if (macros.isEmpty() && (detected == null ||
                    abs(FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())) >= 50.0))
                failures += "$label ordinary held material lost the detected root: ${detected?.hz} for $wanted"
            println("SUTURE held $label: raw RMS ${held.rawRms}, state ${held.stateError} ${held.groupErrors}, seam ${held.seam}, cycles ${held.cycles}, cents $cents, root share $rootShare, detected ${detected?.hz}, " +
                "actuator/acoustic work ${held.actuatorWorkPerCycle}/${held.acousticWorkPerCycle}, energy ${held.maxEnergy}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `physically settled quiet held corner stays finite without artificial amplification`() {
        val macros = timbral.associateWith { 0f } + mapOf("TUNE" to 1f, "HOLD" to 1f)
        val dry = Suture.renderLoopMeasured(SutureVoice.BLOOM, macros, normalize = false)
        assertTrue(dry.converged && dry.stateError < 1e-3 && dry.seam < 1e-3,
            "quiet corner failed physical convergence: ${dry.stateError} ${dry.groupErrors}, seam ${dry.seam}")
        assertTrue(dry.samples.isNotEmpty() && dry.samples.all { it.isFinite() }, "quiet corner has invalid audio")
        assertTrue(dry.rawRms in 1e-9..1e-6,
            "quiet-corner fixture changed its dry audibility: ${dry.rawRms}")
        assertTrue(dry.maxEnergy.isFinite() && dry.maxEnergy < .5 && dry.samples.maxOf { abs(it) } <= 1f,
            "quiet corner escaped its energy or output bounds")
        val exported = Suture.render(SutureVoice.BLOOM, macros)
        assertContentEquals(dry.samples, exported.samples,
            "public rendering artificially amplified or altered the physically valid quiet cycle")
        println("SUTURE quiet held corner: raw RMS ${dry.rawRms}, output RMS ${rms(exported.samples, Dsp.RATE)}, " +
            "state ${dry.stateError} ${dry.groupErrors}, seam ${dry.seam}, cycles ${dry.cycles}, energy ${dry.maxEnergy}")
    }

    @Test
    fun `normal factory held presets retain their requested root and complete mechanical cycle`() {
        val presets = SuturePresets.all().filter { Suture.isLoop(it.macros.getValue("HOLD")) }
        assertTrue(presets.isNotEmpty(), "factory roster lost its powered vessel cycles")
        val failures = mutableListOf<String>()
        for (preset in presets) {
            val held = Suture.renderLoopMeasured(preset.voice, preset.macros)
            val label = "${preset.voice} ${preset.name}"
            if (held.samples.isEmpty() || held.samples.any { !it.isFinite() }) {
                failures += "$label has invalid held audio"
                continue
            }
            val outputRms = rms(held.samples, Dsp.RATE)
            if (held.rawRms <= 1e-5 || outputRms <= 1e-4)
                failures += "$label loop converged by falling silent: raw ${held.rawRms}, output $outputRms"
            if (!held.converged || held.stateError >= 1e-3)
                failures += "$label mechanical cycle did not converge after ${held.cycles}: ${held.stateError} ${held.groupErrors}"
            if (held.seam >= 1e-3) failures += "$label held seam ${held.seam}"
            if (abs(held.samples.average()) >= .01 || held.samples.maxOf { abs(it) } > 1f)
                failures += "$label held DC or uncontrolled peak"
            if (!held.actuatorWorkPerCycle.isFinite() || held.actuatorWorkPerCycle <= 0.0 ||
                !held.acousticWorkPerCycle.isFinite() || held.acousticWorkPerCycle <= 0.0)
                failures += "$label held material lacks finite explicit powered work"
            if (!held.maxEnergy.isFinite() || held.maxEnergy >= .5)
                failures += "$label held acoustic energy escaped its bound: ${held.maxEnergy}"
            val wanted = Suture.frequencyFor(preset.voice, preset.macros.getValue("TUNE"))
            val cents = FineTuning.cents(
                FineTuning.measuredHz(held.samples, Dsp.RATE, wanted, .05f, .4f), wanted.toDouble())
            val rootShare = spectrum(held.samples, wanted, .05f, .4f).rootShare
            if (abs(cents) >= 15.0) failures += "$label held root lost pitch: $cents cents"
            if (rootShare < .08) failures += "$label held root became inaudible: $rootShare"
            val detected = Pitch.detect(Snip(held.samples, channels = 1, sampleRate = Dsp.RATE))
            if (detected == null || abs(FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())) >= 50.0)
                failures += "$label ordinary factory held material lost the detected root: ${detected?.hz} for $wanted"
            println("SUTURE factory held $label: raw RMS ${held.rawRms}, state ${held.stateError} ${held.groupErrors}, " +
                "seam ${held.seam}, cycles ${held.cycles}, cents $cents, root share $rootShare, detected ${detected?.hz}, " +
                "actuator/acoustic work ${held.actuatorWorkPerCycle}/${held.acousticWorkPerCycle}, energy ${held.maxEnergy}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `held velocity changes actual powered work and zero velocity remains silent`() {
        val voice = SutureVoice.THREAD
        val macros = Suture.defaults(voice) + ("HOLD" to 1f)
        val quiet = Suture.renderLoopMeasured(voice, macros, velocity = .2f)
        val strong = Suture.renderLoopMeasured(voice, macros, velocity = 1f)
        println("SUTURE held quiet/strong: raw RMS ${quiet.rawRms}/${strong.rawRms}, " +
            "actuator work ${quiet.actuatorWorkPerCycle}/${strong.actuatorWorkPerCycle}, " +
            "acoustic work ${quiet.acousticWorkPerCycle}/${strong.acousticWorkPerCycle}, " +
            "state ${quiet.stateError}/${strong.stateError}, converged ${quiet.converged}/${strong.converged}")
        assertTrue(quiet.converged && strong.converged, "held velocity mapping lost its stable cycle")
        assertTrue(strong.actuatorWorkPerCycle > 1.2 * quiet.actuatorWorkPerCycle,
            "held velocity changed only output gain: actuator work ${quiet.actuatorWorkPerCycle}/${strong.actuatorWorkPerCycle}")
        assertTrue(abs(strong.rawRms - quiet.rawRms) > .05 * maxOf(strong.rawRms, quiet.rawRms),
            "held velocity left the dry acoustic result unchanged: ${quiet.rawRms}/${strong.rawRms}")
        val zero = Suture.renderLoopMeasured(voice, macros, velocity = 0f)
        assertTrue(zero.converged && zero.samples.all { it == 0f } && zero.maxEnergy == 0.0,
            "zero velocity measured HOLD retained a powered or acoustic state")
        assertTrue(zero.actuatorWorkPerCycle == 0.0 && zero.acousticWorkPerCycle == 0.0,
            "zero velocity HOLD supplied powered work")
        assertTrue(Suture.render(voice, macros, velocity = 0f).samples.all { it == 0f },
            "zero velocity public HOLD was not silent")
    }

    @Test
    fun `closure and work converge when the mechanical clock is doubled`() {
        val macros = mapOf("GAP" to .65f, "STITCH" to .6f, "CORD" to .65f, "SEAM" to .6f)
        val a = Suture.play(SutureVoice.CLOSE, macros,
            Suture.Probe(record = true, durationSeconds = 2f, controlStride = Suture.CONTROL_STRIDE))
        val b = Suture.play(SutureVoice.CLOSE, macros,
            Suture.Probe(record = true, durationSeconds = 2f, controlStride = Suture.CONTROL_STRIDE / 2))
        val ca = requireNotNull(a.closureSeconds)
        val cb = requireNotNull(b.closureSeconds)
        assertTrue(abs(ca - cb) < .015, "closure depends on the mechanical clock: $ca vs $cb")
        val wa = requireNotNull(a.trace).actuatorWork.last().toDouble()
        val wb = requireNotNull(b.trace).actuatorWork.last().toDouble()
        assertTrue(abs(wa - wb) < .05 * maxOf(wa, wb) + 1e-7,
            "actuator work failed control-clock convergence: $wa vs $wb")
        println("SUTURE mechanical clocks: closure $ca/$cb, actuator work $wa/$wb")
    }

    @Test
    fun `rough high register cord and seam contacts converge from four to eight times oversampling`() {
        val macros = mapOf("TUNE" to 1f, "GAP" to .4f, "STITCH" to .75f,
            "CORD" to .85f, "SEAM" to .85f, "CAVITY" to .6f)
        fun render(oversample: Int) = Suture.play(SutureVoice.STRAIN, macros,
            Suture.Probe(record = true, durationSeconds = 3f, stopSeconds = 3f,
                oversample = oversample, controlStride = Suture.CONTROL_STRIDE))
        val four = render(4)
        val eight = render(8)
        assertEquals(Dsp.RATE * 4, four.internalSampleRate)
        assertEquals(Dsp.RATE * 8, eight.internalSampleRate)
        val a = requireNotNull(four.trace)
        val b = requireNotNull(eight.trace)
        assertEquals(a.rate, b.rate, "contact clock comparison also changed the mechanical clock")
        assertTrue(abs(requireNotNull(four.closureSeconds) - requireNotNull(eight.closureSeconds)) < .015,
            "contact-clock change shifted closure timing")
        val compared = listOf(
            Triple("actuator work", a.actuatorWork.last().toDouble(), b.actuatorWork.last().toDouble()),
            Triple("friction work", a.frictionWork.last().toDouble(), b.frictionWork.last().toDouble()),
            Triple("seam loss", a.seamLoss.last().toDouble(), b.seamLoss.last().toDouble()),
            Triple("maximum energy", four.maxEnergy, eight.maxEnergy),
        )
        for ((name, x, y) in compared) assertTrue(abs(x - y) < .08 * maxOf(abs(x), abs(y)) + 1e-7,
            "$name depends on contact sample rate: 4x $x, 8x $y")
        fun audible(played: Suture.Played) = Resampler.resample(
            Snip(played.raw, channels = 1, sampleRate = played.internalSampleRate), Dsp.RATE).samples
        val sa = shape(audible(four), Dsp.RATE)
        val sb = shape(audible(eight), Dsp.RATE)
        val spectral = spectralChange(sa, sb)
        val temporal = temporalChange(sa, sb)
        assertTrue(spectral < .75 && temporal < .05,
            "contact sound failed clock convergence: $spectral dB bands, $temporal energy redistribution")
        println("SUTURE 4x/8x contacts: $spectral dB bands, $temporal temporal redistribution, " +
            "energy ${four.maxEnergy}/${eight.maxEnergy}, closure ${four.closureSeconds}/${eight.closureSeconds}")
        println("SUTURE 4x/8x relative work and energy differences: " +
            compared.joinToString { (name, x, y) -> "$name ${abs(x - y) / maxOf(abs(x), abs(y)).coerceAtLeast(1e-30)}" })
    }

    @Test
    fun `settled factory wood and vessel sound converge across three mechanical clocks`() {
        val preset = SuturePresets.forVoice(SutureVoice.SHELL).single { it.name == "RETURNING GAP" }
        val failures = mutableListOf<String>()
        val production = Suture.CONTROL_STRIDE
        val finest = production / 4
        val clocks = listOf(production, production / 2, finest).associateWith { stride ->
            Suture.renderLoopMeasured(preset.voice, preset.macros, normalize = false,
                controlStride = stride, recordBranches = true)
        }
        for ((stride, played) in clocks) {
            if (!played.converged || played.stateError >= 1e-3 || played.seam >= 1e-3)
                failures += "stride $stride failed complete cycle convergence: ${played.stateError}, ${played.seam}"
            val wood = requireNotNull(played.eyelets) { "missing captured eyelet branch at stride $stride" }
            assertEquals(played.samples.size, wood.size, "branch did not capture the same material cycle")
            if (wood.any { !it.isFinite() } || rms(wood, Dsp.RATE) <= 1e-6)
                failures += "stride $stride removed or invalidated its wooden contact"
            val cadence = rawRate.toDouble() / stride
            val branchComb = cadenceShare(wood, cadence)
            if (branchComb >= .10) failures +=
                "stride $stride wood follows the mechanical clock: $branchComb power near $cadence Hz harmonics"
            println("SUTURE mechanical audio stride $stride: wood cadence share $branchComb, " +
                "total cadence share ${cadenceShare(played.samples, cadence)}, wood RMS ${rms(wood, Dsp.RATE)}, " +
                "state ${played.stateError}, seam ${played.seam}, work ${played.actuatorWorkPerCycle}/${played.acousticWorkPerCycle}")
        }
        val reference = clocks.getValue(finest)
        for ((stride, played) in clocks.filterKeys { it != finest }) {
            assertEquals(reference.samples.size, played.samples.size, "mechanical clock changed loop duration")
            for ((branch, a, b) in listOf(
                Triple("vessel", played.samples, reference.samples),
                Triple("eyelets", requireNotNull(played.eyelets), requireNotNull(reference.eyelets)),
            )) {
                val seconds = minOf(a.size, b.size).toFloat() / Dsp.RATE
                val sa = shape(a, Dsp.RATE, seconds)
                val sb = shape(b, Dsp.RATE, seconds)
                val spectral = spectralChange(sa, sb)
                val temporal = temporalChange(sa, sb)
                if (spectral >= .75 || temporal >= .05) failures +=
                    "$branch stride $stride/$finest changes audible material: $spectral dB bands, $temporal energy redistribution"
                println("SUTURE mechanical audio $branch $stride/$finest: $spectral dB bands, $temporal temporal redistribution")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
