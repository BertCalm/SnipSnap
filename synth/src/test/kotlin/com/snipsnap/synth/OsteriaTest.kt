package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Performance gates use trajectories and unit-energy spectra, so changing gain cannot pass them. */
class OsteriaTest {
    private val voice = OsteriaVoice.QUARTET

    @Test
    fun `approved note presets retain their exact raw and leveled float PCM`() {
        // Captured from the accepted one-note renderer before adding touch controls.
        val references = mapOf(
            "Anchored Note" to ("9d948531966b27006d4f50b7bfa8e9773b0c23ecbfd1066de2df892768ef06aa" to
                "8c7d2f08625f0eb0163bd09a1ff2efab6bbb33678e2ddc244264627c2a63efc8"),
            "Evolving Note" to ("141d79af990d2fa51fe5dd3d2bd6b9d1e3060ffb7bd2edd98b7bdc17063cf895" to
                "e7b8867519bf9ec958aec2f0467b874171c7d38f576333c4cb015ab49eb6820d"),
            "Lingering Color" to ("e109ca50566a5fca9a84c082ec512ee133b15b32203ae1f0b8fed7fc562ee231" to
                "5d8439ad79356e7b93a1b9310be7b378d15ad780fae88a8eae2a2922d35a5c91"),
        )
        for (patch in OsteriaPresets.original()) {
            val (raw, leveled) = references.getValue(patch.name)
            assertEquals(raw, pcmHash(Osteria.probe(voice, patch.macros, patch.midi, patch.velocity, patch.config).samples),
                "approved raw ${patch.name} changed")
            assertEquals(leveled, pcmHash(patch.render().samples), "approved leveled ${patch.name} changed")
        }
    }

    @Test
    fun `explicit model one saved phrases retain their original float PCM`() {
        // Captured before the model-two implementation from the original renderer.
        val references = listOf(
            Triple(OsteriaConfig(model = 1), emptyMap<String, Float>(), 48) to
                "7b4f9cb37eed4a203d3f84417f84ca4f9b87ba2afc3af77ea013d527baa977ed",
            Triple(OsteriaConfig(seed = 8241, seconds = 5.5, direction = -1, model = 1),
                mapOf("MEMORY" to .79f, "ROOM" to .3f, "HOLD" to .4f), 36) to
                "7fa36ea470014c8b6c7a08911dd61a7b51f8f54966198cae5228629141421787",
            Triple(OsteriaConfig(model = 1), Osteria.defaults(voice).mapValues { 1f }, 84) to
                "7fb32187500c1f6b2ce7cb972729a9a87f9d565cdab0159b207b5316f6453499",
        )
        for ((recipe, expected) in references) {
            val (config, macros, midi) = recipe
            val samples = Osteria.probe(voice, macros, midi, .8f, config).samples
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(bytes::putFloat)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, hash, "saved model-one phrase changed at MIDI $midi")
        }
    }

    @Test
    fun `note model has one shared onset and a sustained body without later attacks`() {
        assertEquals(2, OsteriaConfig().model)
        val stationary = Osteria.probe(voice,
            mapOf("SHORE" to .5f, "MEMORY" to 0f, "ROOM" to 0f),
            config = OsteriaConfig(initialShores = List(4) { 2.0 }),
            options = Osteria.ProbeOptions(motionEnabled = false),
        )
        for ((role, stem) in stationary.stems.withIndex()) {
            val onset = windowRms(stem, 0.0, .01)
            val body = windowRms(stem, .06, .16)
            assertTrue(onset < body * .15, "role $role lacks the shared smooth onset: $onset / $body")
            val bins = (3 until 49).map { bin -> windowRms(stem, bin / 10.0, (bin + 1) / 10.0) }
            for ((a, b) in bins.zipWithNext()) {
                assertTrue(b < a * 1.12, "role $role retriggered during the hold: $a -> $b")
            }
            val sustain = bins.drop(7)
            assertTrue(sustain.min() > body * .6, "role $role decayed into silence while held")
            assertTrue(sustain.max() < sustain.min() * 1.12,
                "stationary role $role has a pulsing sustain: ${sustain.min()}..${sustain.max()}")
        }
        // Motion must continue changing the note's color even though its articulation is shared.
        val early = spectrum(defaultProbe.samples, 48, .5, 1.4)
        val late = spectrum(defaultProbe.samples, 48, 3.8, 4.8)
        val evolution = distance(early, late)
        assertTrue(evolution > .025, "fixed-root note stopped evolving spectrally: $evolution")
    }

    @Test
    fun `short note releases after a single tap and legacy durations remain restricted`() {
        val short = Osteria.probe(voice, config = OsteriaConfig(seconds = 1.0))
        assertEquals(Dsp.RATE * 2, short.samples.size)
        assertTrue(windowRms(short.samples, .10, .20) > .01, "tap did not sound before release")
        assertTrue(windowRms(short.samples, .90, 1.0) < windowRms(short.samples, .10, .20) * .06,
            "tap did not release after its 200 ms press")
        assertTrue(short.samples.takeLast(2).all { abs(it) < .0001f })
        val held = Osteria.probe(voice)
        val prefix = (.18 * Dsp.RATE).toInt() * 2
        assertContentEquals(held.samples.copyOfRange(0, prefix), short.samples.copyOfRange(0, prefix),
            "the same note-on changed its travel before the short note was released")
        kotlin.test.assertFailsWith<IllegalArgumentException> { OsteriaConfig(model = 1, seconds = 1.0) }
    }

    @Test
    fun `one saved performance is deterministic and control corners remain finite bounded PCM`() {
        val first = Osteria.render(voice)
        val second = Osteria.render(voice)
        assertContentEquals(first.samples, second.samples)
        assertEquals(2, first.channels)
        assertEquals(Dsp.RATE, first.sampleRate)
        assertEquals(6f, first.durationSeconds)
        for (snip in listOf(first,
            Osteria.render(voice, Osteria.defaults(voice).mapValues { 0f }, midi = 36),
            Osteria.render(voice, Osteria.defaults(voice).mapValues { 1f }, midi = 84))) {
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(snip.peak() in .01f..1f, "peak ${snip.peak()}")
            assertTrue(abs(mono(snip.samples).average()) < .001, "large DC offset")
            assertTrue(snip.samples.takeLast(2).all { abs(it) < .0001f }, "finite gesture must release")
        }
    }

    @Test
    fun `four distinct full ensemble stems sum to the raw quartet and survive mono folding`() {
        val p = defaultProbe
        assertEquals(4, p.stems.size)
        val summed = FloatArray(p.samples.size) { i ->
            var sum = 0f
            for (stem in p.stems) sum += stem[i]
            sum
        }
        assertContentEquals(p.samples, summed)
        val spectra = p.stems.map { spectrum(it, 48, .1, 5.1) }
        val envelopes = p.stems.map { temporalEnergy(it) }
        for (i in p.stems.indices) {
            assertTrue(energy(p.stems[i]) > 1e-5, "role $i is silent")
            for (j in i + 1 until p.stems.size) {
                val timbre = distance(spectra[i], spectra[j])
                val articulation = distance(envelopes[i], envelopes[j])
                // At least five percent of energy moves between bands or time bins.
                assertTrue(maxOf(timbre, articulation) > .05,
                    "roles $i/$j lack contrast: spectral $timbre temporal $articulation")
            }
        }
        val folded = mono(p.samples)
        val foldRatio = energy(folded) / energy(p.samples)
        assertTrue(foldRatio > .5, "mono lost more than 3 dB of mean power: ratio $foldRatio")
        assertTrue(energy(FloatArray(folded.size) { p.samples[it * 2] - p.samples[it * 2 + 1] }) > 1e-6,
            "quartet has no stereo placement")
    }

    @Test
    fun `velocity changes physical excitation before leveling and zero velocity is silent`() {
        val silence = Osteria.probe(voice, velocity = 0f)
        assertTrue(silence.samples.all { it == 0f })
        assertTrue(silence.stems.all { stem -> stem.all { it == 0f } })
        assertTrue(Osteria.render(voice, velocity = 0f).samples.all { it == 0f })
        val soft = Osteria.probe(voice, velocity = .25f)
        val hard = defaultProbe
        assertEquals(soft.shoreTrace.map { it.positions }, hard.shoreTrace.map { it.positions })
        assertTrue(energy(hard.samples) > energy(soft.samples) * 4,
            "raw excitation power did not increase: ${energy(soft.samples)} -> ${energy(hard.samples)}")
        val sourceChange = distance(spectrum(soft.samples, 48, .1, 5.1), spectrum(hard.samples, 48, .1, 5.1))
        assertTrue(sourceChange > .01, "velocity only changed level: unit-power spectral change $sourceChange")
    }

    @Test
    fun `soft medium and hard touches keep one root and the same tap onset across registers`() {
        for (midi in intArrayOf(36, 60, 84)) {
            var previousPower = 0.0
            var previousSpectrum: DoubleArray? = null
            for (velocity in floatArrayOf(.25f, .6f, 1f)) {
                val label = "MIDI $midi velocity $velocity"
                val held = Osteria.probe(voice, midi = midi, velocity = velocity, config = OsteriaConfig(seconds = 2.0))
                val tap = Osteria.probe(voice, midi = midi, velocity = velocity, config = OsteriaConfig(seconds = 1.0))
                assertTrue(held.samples.all { it.isFinite() && abs(it) < 1f }, "$label has invalid PCM")
                val prefix = (.18 * Dsp.RATE).toInt() * 2
                assertContentEquals(held.samples.copyOfRange(0, prefix), tap.samples.copyOfRange(0, prefix),
                    "$label changes before the tap is released")
                val power = energy(held.samples.copyOfRange((.1 * Dsp.RATE).toInt() * 2, Dsp.RATE * 2))
                val color = spectrum(held.samples, midi, .1, 1.1)
                if (previousPower > 0.0) {
                    assertTrue(power > previousPower * 1.5, "$label did not increase excitation power")
                    val change = distance(previousSpectrum!!, color)
                    assertTrue(change > .003, "$label changed only level: normalized spectral distance $change")
                }
                previousPower = power
                previousSpectrum = color
                val pitch = Pitch.detect(Snip(held.samples, channels = 2, sampleRate = held.sampleRate), .3f, .35f)
                assertNotNull(pitch, "$label has no stable held root")
                val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(abs(cents) < 20, "$label root changed by $cents cents")
                assertTrue(windowRms(tap.samples, .9, 1.0) < windowRms(tap.samples, .1, .2) * .06,
                    "$label does not finish its tap release")
                assertTrue(tap.samples.takeLast(2).all { abs(it) < .0001f }, "$label has no finite ending")
            }
        }
    }

    @Test
    fun `saved attack and release alter touch while retaining one note and its travel`() {
        val quick = Osteria.probe(voice, config = OsteriaConfig(seconds = 2.0, attackSeconds = .005))
        val rounded = Osteria.probe(voice, config = OsteriaConfig(seconds = 2.0, attackSeconds = .4))
        assertEquals(quick.shoreTrace, rounded.shoreTrace, "attack must not alter travel")
        assertTrue(windowRms(quick.samples, .01, .025) > windowRms(rounded.samples, .01, .025) * 10,
            "attack control did not round the shared onset")
        assertTrue(windowRms(rounded.samples, .6, .8) > windowRms(rounded.samples, .01, .025) * 10,
            "long attack did not reach a held body")

        // Both notes are released at one second: only the time spent releasing differs.
        val short = Osteria.probe(voice, config = OsteriaConfig(seconds = 1.25, releaseSeconds = .25))
        val long = Osteria.probe(voice, config = OsteriaConfig(seconds = 2.0, releaseSeconds = 1.0))
        val prefix = (.98 * Dsp.RATE).toInt() * 2
        assertContentEquals(short.samples.copyOfRange(0, prefix), long.samples.copyOfRange(0, prefix),
            "release length changed the held note before note-off")
        assertTrue(windowRms(long.samples, 1.18, 1.24) > windowRms(short.samples, 1.18, 1.24) * 8,
            "release control did not extend the sounding tail")
        assertTrue(short.samples.takeLast(2).all { abs(it) < .0001f })
        assertTrue(long.samples.takeLast(2).all { abs(it) < .0001f })
    }

    @Test
    fun `scrambling preserves finite duration and room choice and every hold remains tonal`() {
        val macros = Osteria.defaults(voice)
        val dry = Osteria.scramble(voice, Random(37))
        assertEquals(macros.keys, dry.keys)
        assertTrue(dry.values.all { it.isFinite() && it in 0f..1f })
        assertEquals(0f, dry.getValue("ROOM"))
        assertEquals(0f, dry.getValue("HOLD"))
        assertTrue(dry.filterKeys { it != "ROOM" && it != "HOLD" } != macros.filterKeys { it != "ROOM" && it != "HOLD" })
        val patch = OsteriaPresets.original().first().copy(macros = macros + mapOf("ROOM" to .7f, "HOLD" to .4f))
        val near = Osteria.scramble(voice, Random(37), near = patch)
        assertEquals(.7f, near.getValue("ROOM"))
        assertEquals(.4f, near.getValue("HOLD"))
        assertEquals(patch.macros, Osteria.scramble(voice, Random(37), temperature = 0f, near = patch))
        for (hold in floatArrayOf(0f, .5f, 1f)) {
            assertEquals(DrumClass.TONAL, Osteria.drumClassFor(voice, mapOf("HOLD" to hold)))
        }
    }

    @Test
    fun `coupling pulls independent travellers together and changes their audible relationship`() {
        val macros = mapOf("TENSION" to .95f, "WANDER" to .9f, "MEMORY" to 0f, "ROOM" to 0f)
        val together = Osteria.probe(voice, macros)
        val apart = Osteria.probe(voice, macros, options = Osteria.ProbeOptions(couplingEnabled = false))
        val gathered = spread(together.shoreTrace, .5, 3.5)
        val independent = spread(apart.shoreTrace, .5, 3.5)
        assertTrue(gathered < independent * .8, "coupling did not gather players: $gathered / $independent")
        val audible = distance(spectrum(together.samples, 48, .5, 3.5), spectrum(apart.samples, 48, .5, 3.5))
        assertTrue(audible > .01, "coupling has no substantial spectral effect: $audible")
    }

    @Test
    fun `memory retains different travels after opposite directions reach the same destination`() {
        val macros = mapOf("SHORE" to .5f, "TENSION" to .2f, "WANDER" to .9f, "MEMORY" to 1f, "ROOM" to 0f)
        val config = Osteria.configurationFor(voice)
        val forward = Osteria.probe(voice, macros, config = config.copy(direction = 1))
        val backward = Osteria.probe(voice, macros, config = config.copy(direction = -1))
        val noMemory = Osteria.ProbeOptions(memoryEnabled = false)
        val forwardDry = Osteria.probe(voice, macros, config = config.copy(direction = 1), options = noMemory)
        val backwardDry = Osteria.probe(voice, macros, config = config.copy(direction = -1), options = noMemory)
        assertEquals(forward.shoreTrace.map { it.positions }, forwardDry.shoreTrace.map { it.positions })
        assertEquals(backward.shoreTrace.map { it.positions }, backwardDry.shoreTrace.map { it.positions })
        val reunionForward = forward.shoreTrace.filter { it.timeSeconds in 4.0..4.8 }
        val reunionBackward = backward.shoreTrace.filter { it.timeSeconds in 4.0..4.8 }
        assertTrue(reunionForward.isNotEmpty())
        assertEquals(reunionForward.size, reunionBackward.size)
        assertTrue((reunionForward + reunionBackward).all { frame -> frame.positions.all { abs(it - 2.0) < 1e-8 } })
        val historyDistance = reunionForward.indices.map { frame ->
            (0..3).map { role -> abs(reunionForward[frame].remembered[role] - reunionBackward[frame].remembered[role]) }.average()
        }.average()
        assertTrue(historyDistance > .1, "reunited travellers lost their history: $historyDistance shores")
        val remembered = distance(spectrum(forward.samples, 48, 4.0, 4.8), spectrum(backward.samples, 48, 4.0, 4.8))
        val forgotten = distance(spectrum(forwardDry.samples, 48, 4.0, 4.8), spectrum(backwardDry.samples, 48, 4.0, 4.8))
        assertTrue(remembered > .01 && remembered > forgotten * 4,
            "different histories must color the same destination: remembered $remembered forgotten $forgotten")
        val enabled = distance(spectrum(forward.samples, 48, 1.0, 4.8), spectrum(forwardDry.samples, 48, 1.0, 4.8))
        assertTrue(enabled > .01, "MEMORY changed the trace without changing timbre: $enabled")
    }

    @Test
    fun `shore palettes and wander endpoints move unit energy spectra and actual travel`() {
        val still = mapOf("WANDER" to 0f, "MEMORY" to 0f, "ROOM" to 0f)
        val coast0 = Osteria.probe(voice, still + ("SHORE" to 0f))
        val coast4 = Osteria.probe(voice, still + ("SHORE" to 1f))
        val coastChange = distance(spectrum(coast0.samples, 48, 3.9, 4.9), spectrum(coast4.samples, 48, 3.9, 4.9))
        assertTrue(coastChange > .04, "shore endpoints lack a palette change: $coastChange")
        val macros = mapOf("TENSION" to .2f, "MEMORY" to 0f, "ROOM" to 0f)
        val quiet = Osteria.probe(voice, macros + ("WANDER" to 0f))
        val wandering = Osteria.probe(voice, macros + ("WANDER" to 1f))
        val quietTravel = travel(quiet.shoreTrace)
        val wanderingTravel = travel(wandering.shoreTrace)
        assertTrue(wanderingTravel > quietTravel * 1.2, "WANDER did not expand travel: $quietTravel -> $wanderingTravel")
        val wanderChange = distance(spectrum(quiet.samples, 48, .5, 3.5), spectrum(wandering.samples, 48, .5, 3.5))
        assertTrue(wanderChange > .01, "WANDER changed motion without timbre: $wanderChange")
        val lowTension = Osteria.probe(voice, mapOf("TENSION" to 0f, "WANDER" to .9f, "MEMORY" to 0f))
        val highTension = Osteria.probe(voice, mapOf("TENSION" to 1f, "WANDER" to .9f, "MEMORY" to 0f))
        assertTrue(spread(highTension.shoreTrace, .5, 3.5) < spread(lowTension.shoreTrace, .5, 3.5),
            "TENSION endpoint did not pull travellers together")
        val tensionChange = distance(spectrum(lowTension.samples, 48, .5, 3.5), spectrum(highTension.samples, 48, .5, 3.5))
        assertTrue(tensionChange > .01, "TENSION endpoints changed positions without audible timbre: $tensionChange")
    }

    @Test
    fun `the fixed room leaves travel intact and hold extends a finite gesture`() {
        val dry = defaultProbe
        val room = Osteria.probe(voice, mapOf("ROOM" to 1f))
        assertEquals(dry.shoreTrace, room.shoreTrace)
        val roomChange = distance(spectrum(dry.samples, 48, .1, 5.1), spectrum(room.samples, 48, .1, 5.1))
        assertTrue(roomChange > .005, "ROOM has no spectral presence: $roomChange")
        val held = Osteria.render(voice, mapOf("HOLD" to 1f))
        assertEquals(8f, held.durationSeconds)
        assertTrue(held.samples.takeLast(2).all { abs(it) < .0001f })
        val halves = held.samples.size / 2
        assertTrue(distance(temporalEnergy(held.samples.copyOfRange(0, halves)),
            temporalEnergy(held.samples.copyOfRange(halves, held.samples.size))) > .05,
            "HOLD must contain a continuing gesture rather than duplicate a loop")
    }

    @Test
    fun `every note contributor retains a strong fixed root across the held register range`() {
        assertEquals(36, Osteria.MIN_MIDI)
        assertEquals(84, Osteria.MAX_MIDI)
        for (midi in intArrayOf(36, 60, 84)) {
            val p = Osteria.probe(voice, midi = midi)
            for ((name, samples) in p.stems.mapIndexed { role, stem -> "role $role" to stem } + ("note" to p.samples)) {
                for (from in listOf(.12f, 1.4f, 2.8f, 4.4f)) {
                    val estimate = Pitch.detect(Snip(samples, channels = 2, sampleRate = p.sampleRate), from, .35f)
                    assertNotNull(estimate, "$name MIDI $midi at $from has no detectable root")
                    val cents = 1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                    assertTrue(abs(cents) < 20, "$name MIDI $midi at $from root is $cents cents off")
                    assertTrue(estimate.confidence > .7f, "$name MIDI $midi at $from pitch confidence ${estimate.confidence}")
                    val rootShare = spectrum(samples, midi, from.toDouble(), from + .35)[1]
                    assertTrue(rootShare > .25, "$name MIDI $midi at $from weak fundamental: $rootShare")
                }
            }
        }
    }

    private fun spread(trace: List<Osteria.ShoreFrame>, from: Double, until: Double): Double =
        trace.filter { it.timeSeconds in from..until }.map { frame ->
            val centre = frame.positions.average()
            sqrt(frame.positions.sumOf { (it - centre) * (it - centre) } / 4)
        }.average()

    private fun travel(trace: List<Osteria.ShoreFrame>): Double =
        trace.zipWithNext().sumOf { (a, b) -> (0..3).sumOf { role -> abs(a.positions[role] - b.positions[role]) } }

    private fun mono(stereo: FloatArray) = FloatArray(stereo.size / 2) { (stereo[it * 2] + stereo[it * 2 + 1]) / 2 }
    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it } / samples.size
    private fun pcmHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    private fun windowRms(stereo: FloatArray, from: Double, until: Double): Double {
        val begin = (from * Dsp.RATE).toInt() * 2
        val end = minOf(stereo.size, (until * Dsp.RATE).toInt() * 2)
        return sqrt((begin until end).sumOf { stereo[it].toDouble() * stereo[it] } / (end - begin))
    }
    private fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } / 2

    /** Distribution of energy in 100 ms bins, normalized over the whole gesture. */
    private fun temporalEnergy(samples: FloatArray): DoubleArray {
        val binSamples = Dsp.RATE / 10 * 2
        val bins = DoubleArray((samples.size + binSamples - 1) / binSamples)
        for (i in samples.indices) bins[i / binSamples] += samples[i].toDouble() * samples[i]
        val sum = bins.sum().coerceAtLeast(1e-30)
        return DoubleArray(bins.size) { bins[it] / sum }
    }

    /** Root-relative bands, with every FFT frame normalized before averaging. */
    private fun spectrum(stereo: FloatArray, midi: Int, from: Double, until: Double): DoubleArray {
        val samples = mono(stereo)
        val size = 4096
        val root = Keys.midiHz(midi).toDouble()
        val edges = doubleArrayOf(0.0, .75, 1.22, 1.8, 2.3, 3.15, 4.2, 5.15, 6.9, 9.2, 13.0, 20.0, Double.POSITIVE_INFINITY)
        val averaged = DoubleArray(edges.size - 1)
        var count = 0
        val first = (from * Dsp.RATE).toInt()
        val end = minOf(samples.size, (until * Dsp.RATE).toInt())
        for (start in first..end - size step size / 2) {
            val real = FloatArray(size) { i -> (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat() }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            val bands = DoubleArray(averaged.size)
            for (bin in 1..size / 2) {
                val ratio = bin * Dsp.RATE / (size * root)
                var band = 0
                while (ratio >= edges[band + 1]) band++
                bands[band] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            }
            val total = bands.sum()
            if (total > 1e-24) {
                for (band in bands.indices) averaged[band] += bands[band] / total
                count++
            }
        }
        check(count > 0) { "no audible FFT frames in $from..$until" }
        return DoubleArray(averaged.size) { averaged[it] / count }
    }

    private companion object {
        val defaultProbe by lazy { Osteria.probe(OsteriaVoice.QUARTET) }
    }
}
