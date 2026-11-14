package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Source identity is tested at the same pitch, touch, travel and articulation. */
class OsteriaVoiceTest {
    private val voices = listOf(OsteriaVoice.BASS, OsteriaVoice.PLUCK, OsteriaVoice.REED, OsteriaVoice.PAD)
    private val still = mapOf("SHORE" to .5f, "WANDER" to 0f, "MEMORY" to 0f, "ROOM" to 0f)
    private val stationary = Osteria.ProbeOptions(motionEnabled = false)

    @Test
    fun `families have different normalized source spectra at the same root and articulation`() {
        val config = OsteriaConfig(initialShores = List(4) { 2.0 }, seconds = 2.0, releaseSeconds = .3)
        val spectra = voices.associateWith { voice ->
            spectrum(Osteria.probe(voice, still, midi = 48, velocity = .8f, config = config, options = stationary).samples,
                48, .10, .70)
        }
        for (i in voices.indices) for (j in i + 1 until voices.size) {
            val distance = spectra.getValue(voices[i]).indices.sumOf {
                abs(spectra.getValue(voices[i])[it] - spectra.getValue(voices[j])[it])
            } / 2.0
            assertTrue(distance > .035, "${voices[i]}/${voices[j]} differ only in level: spectral distance $distance")
        }
        val bass = spectra.getValue(OsteriaVoice.BASS)
        val reed = spectra.getValue(OsteriaVoice.REED)
        val pad = spectra.getValue(OsteriaVoice.PAD)
        assertTrue(bass[0] > .80, "bass is not anchored in its rounded fundamental: ${bass[0]}")
        assertTrue(reed[2] + reed[4] > reed[1] * 1.2, "reed lost its rounded odd-led body")
        assertTrue(pad[1] + pad[3] > reed[1] + reed[3], "pad lacks its distinct even-harmonic body")
    }


    @Test
    fun `reed has a mixed low body and restrained upper buzz at every shore`() {
        for (coast in 0..4) {
            val config = Osteria.configurationFor(OsteriaVoice.REED).copy(
                initialShores = List(4) { coast.toDouble() }, seconds = 2.0, releaseSeconds = .3)
            val macros = still + ("SHORE" to coast / 4f)
            val first = Osteria.probe(OsteriaVoice.REED, macros, midi = 48, velocity = .8f,
                config = config, options = stationary)
            val bands = spectrum(first.samples, 48, .3, 1.5)
            // The previous voice almost removed the even partials and concentrated
            // its color in rigid upper odd formants. A quieter output trim cannot
            // fix that ratio. Measure the normalized source body instead.
            val even = bands.indices.filter { (it + 1) % 2 == 0 }.sumOf { bands[it] }
            val upper = bands.drop(8).sum()
            assertTrue(even > .007, "reed coast $coast lost its even-partial body: $even")
            assertTrue(upper < .015, "reed coast $coast regained a hard upper buzz: $upper")
            assertTrue(bands[0] > .40, "reed coast $coast lost its common root: ${bands[0]}")
            assertTrue(first.samples.all { it.isFinite() && abs(it) < 1f })
            val replay = Osteria.probe(OsteriaVoice.REED, macros, midi = 48, velocity = .8f,
                config = config, options = stationary)
            assertContentEquals(first.samples, replay.samples, "reed breath/pressure did not reset at coast $coast")
        }
    }

    @Test
    fun `stationary reed pressure changes normalized body color while its root stays fixed`() {
        fun upperBody(bands: DoubleArray): DoubleArray {
            // Exclude the root, then normalize again: an overall level change or
            // one shared gain on all upper partials cannot pass this comparison.
            val upper = bands.drop(1).toDoubleArray()
            val total = upper.sum().coerceAtLeast(1e-24)
            return DoubleArray(upper.size) { upper[it] / total }
        }
        fun distance(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } / 2.0

        for (midi in intArrayOf(36, 48, 72)) {
            val config = Osteria.configurationFor(OsteriaVoice.REED).copy(
                initialShores = List(4) { 2.0 }, seconds = 3.4, releaseSeconds = .3)
            val on = Osteria.probe(OsteriaVoice.REED, still, midi, .8f, config, stationary)
            val off = Osteria.probe(OsteriaVoice.REED, still, midi, .8f, config,
                stationary.copy(reedPressureEnabled = false))
            assertEquals(on.shoreTrace, off.shoreTrace, "pressure changed the saved coastal journey")
            val differences = listOf(.75, 1.55, 2.35).map { start ->
                val sounding = spectrum(on.samples, midi, start, start + .40)
                val neutral = spectrum(off.samples, midi, start, start + .40)
                assertTrue(sounding.drop(8).sum() < .015, "reed MIDI $midi gained hard upper buzz")
                distance(upperBody(sounding), upperBody(neutral))
            }
            // C3 measured a maximum .0009997 and mean .0006787. This is a
            // restrained color change, not a claim about perceptual audibility.
            println("Reed pressure MIDI $midi normalized upper-body distances: $differences")
            assertTrue(differences.max() > .0008 && differences.average() > .0003,
                "reed MIDI $midi pressure only changes level, not body ratios: $differences")
            val snip = Snip(on.samples, channels = 2, sampleRate = Dsp.RATE)
            for (time in floatArrayOf(.85f, 2.35f)) {
                val pitch = Pitch.detect(snip, time, .30f)
                assertNotNull(pitch, "reed MIDI $midi lost its root under pressure")
                val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(abs(cents) < 20 && pitch.confidence >= .3f,
                    "reed pressure moved MIDI $midi by $cents cents, confidence ${pitch.confidence}")
            }
        }
    }

    @Test
    fun `one pluck loses energy while the other held families retain a sounding body`() {
        for (voice in voices) {
            val config = Osteria.configurationFor(voice).copy(initialShores = List(4) { 2.0 }, seconds = 5.0)
            val result = Osteria.probe(voice, still, velocity = .8f, config = config, options = stationary)
            val sample = result.samples
            val early = rms(sample, .85, 1.05)
            val held = rms(sample, 3.2, 3.5)
            if (voice == OsteriaVoice.PLUCK) {
                assertTrue(held < early * .25, "plucked vibration did not decay while held: $early -> $held")
            } else {
                assertTrue(held > early * .70, "$voice stopped sounding while held: $early -> $held")
            }
            // All contributors begin at the same note-on. After the one shared attack,
            // stationary colors cannot create a second articulation or a rhythmic pulse.
            val onset = rms(sample, 0.0, .001)
            val attacked = rms(sample, maxOf(.03, config.attackSeconds * 1.2), maxOf(.09, config.attackSeconds * 1.2 + .1))
            assertTrue(onset < attacked * .15, "$voice did not begin with a smooth shared onset")
            for ((role, stem) in result.stems.withIndex()) {
                val start = maxOf(.3, config.attackSeconds * 1.4)
                val last = config.seconds - config.releaseSeconds - .15
                var previous = rms(stem, start, start + .1)
                var t = start + .1
                while (t + .1 < last) {
                    val next = rms(stem, t, t + .1)
                    assertTrue(next < previous * 1.13, "$voice role $role retriggered at $t: $previous -> $next")
                    previous = next
                    t += .1
                }
            }
            assertTrue(sample.takeLast(2).all { abs(it) < .0001f }, "$voice has no finite ending")
        }
    }

    @Test
    fun `moving families keep one root across registers and early and late note windows`() {
        for (voice in voices) for (midi in intArrayOf(36, 48, 72)) {
            val config = Osteria.configurationFor(voice).copy(seconds = 3.5)
            val result = Osteria.probe(voice, midi = midi, velocity = .8f, config = config)
            assertTrue(result.samples.all { it.isFinite() && abs(it) < 1f }, "$voice MIDI $midi has invalid PCM")
            val snip = Snip(result.samples, channels = 2, sampleRate = Dsp.RATE)
            for (time in floatArrayOf(.85f, 1.8f)) {
                val pitch = Pitch.detect(snip, time, .30f)
                assertNotNull(pitch, "$voice MIDI $midi lost its root at $time")
                val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(abs(cents) < 20, "$voice MIDI $midi changed pitch by $cents cents at $time")
            }
            val sum = FloatArray(result.samples.size) { i ->
                var value = 0f
                for (stem in result.stems) value += stem[i]
                value
            }
            assertContentEquals(result.samples, sum, "$voice stems no longer reconstruct the shared performance")
        }
    }

    @Test
    fun `boosted pad retains production headroom across register and control corners`() {
        val config = Osteria.configurationFor(OsteriaVoice.PAD).copy(seconds = 1.5, releaseSeconds = .3)
        for (midi in intArrayOf(36, 84)) for (corner in listOf(0f, 1f)) {
            val macros = Osteria.defaults(OsteriaVoice.PAD).mapValues { (name, _) -> if (name == "HOLD") 0f else corner }
            val samples = Osteria.render(OsteriaVoice.PAD, macros, midi, 1f, config).samples
            assertTrue(samples.all { it.isFinite() && abs(it) <= .990001f }, "boosted PAD MIDI $midi corner $corner exceeds headroom")
            assertTrue(samples.any { abs(it) > .01f }, "boosted PAD MIDI $midi corner $corner is silent")
        }
    }

    @Test
    fun `new voice recipes retain edited touch and replay deterministically`() {
        for (voice in voices) {
            val patch = OsteriaPatch("Candidate ${voice.name}", voice,
                Osteria.defaults(voice) + mapOf("MEMORY" to .83f, "WANDER" to .71f), midi = 60, velocity = .63f,
                config = Osteria.configurationFor(voice).copy(seconds = 1.5, attackSeconds = .16, releaseSeconds = .4,
                    initialShores = listOf(.1, 1.8, 2.9, 3.8), seed = 717, direction = -1))
            val restored = OsteriaPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch.toJsonText(), restored.toJsonText())
            val savedPad = PadRecipe.fromJsonText(PadRecipe(patch = patch).toJsonText())
            assertEquals(patch, savedPad.patch)
            assertContentEquals(patch.render().samples, savedPad.render().samples)
            val edited = patch.withMacros(patch.macros + ("SHORE" to .9f)) as OsteriaPatch
            assertEquals(patch.config, edited.config)
            assertEquals(patch.voice, edited.voice)
            assertEquals(Osteria.defaults(OsteriaVoice.QUARTET).keys, Osteria.defaults(voice).keys)
            assertTrue(Osteria.probe(voice, velocity = 0f, config = patch.config).samples.all { it == 0f })
            assertFailsWith<IllegalArgumentException> {
                patch.copy(config = OsteriaConfig(model = 1))
            }
            assertFailsWith<IllegalArgumentException> { Osteria.probe(voice, config = OsteriaConfig(model = 1)) }
            val saved = patch.copy(config = OsteriaConfig()).toJsonValue()
            val legacyConfig = JsonValue.Obj(saved.entries.getValue("config").obj() + ("model" to JsonValue.Num(1.0)))
            val invalid = JsonValue.Obj(saved.entries + ("config" to legacyConfig))
            assertFailsWith<JsonException> { OsteriaPatch.fromJsonValue(invalid) }
        }
    }

    private fun rms(stereo: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt() * 2
        val end = minOf(stereo.size, (until * Dsp.RATE).toInt() * 2)
        return sqrt((start until end).sumOf { stereo[it].toDouble() * stereo[it] } / (end - start))
    }

    /** Individual harmonic bands, normalized in each frame so level cannot pass. */
    private fun spectrum(stereo: FloatArray, midi: Int, from: Double, until: Double): DoubleArray {
        val size = 8192
        val root = Keys.midiHz(midi).toDouble()
        val averaged = DoubleArray(16)
        var count = 0
        val first = (from * Dsp.RATE).toInt()
        val end = minOf(stereo.size / 2, (until * Dsp.RATE).toInt())
        for (start in first..end - size step size / 2) {
            val real = FloatArray(size) { i ->
                ((stereo[(start + i) * 2] + stereo[(start + i) * 2 + 1]) * .5 *
                    (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            val bands = DoubleArray(averaged.size)
            for (bin in 1..size / 2) {
                val harmonic = (bin * Dsp.RATE / (size * root) + .5).toInt()
                if (harmonic in 1..bands.size) {
                    bands[harmonic - 1] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                }
            }
            val total = bands.sum()
            if (total > 1e-24) {
                for (band in bands.indices) averaged[band] += bands[band] / total
                count++
            }
        }
        check(count > 0)
        return DoubleArray(averaged.size) { averaged[it] / count }
    }
}
