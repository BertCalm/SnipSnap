package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Audio evidence for the dry pitched voice, before shared loudness can hide excitation changes. */
class MurmurTest {
    private val timbre = listOf("MOUTH", "EDGE", "BREATH", "MOTION", "BODY")

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double =
        sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from).coerceAtLeast(1))

    /** Normalized spectral mass: output gain cannot make a timbral comparison pass. */
    private fun spectrum(samples: FloatArray, startSeconds: Double = .12, size: Int = 16384, fine: Boolean = false): DoubleArray {
        val start = (startSeconds * Dsp.RATE).toInt()
        val re = FloatArray(size) { i ->
            if (start + i < samples.size) (samples[start + i] * (.5 - .5 * cos(2 * PI * i / size))).toFloat() else 0f
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        if (fine) {
            // Breath replaces harmonic lines with noise inside the same formant bands; broad
            // band totals can remain unchanged while the harmonic/noise balance moves strongly.
            val powers = DoubleArray(size / 2 - 1) { i ->
                val bin = i + 1
                re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            val total = powers.sum().coerceAtLeast(1e-30)
            return DoubleArray(powers.size) { powers[it] / total }
        }
        // Narrow enough to see a moving spectral envelope; wide enough to avoid reading phase as timbre.
        val edges = doubleArrayOf(0.0, 125.0, 250.0, 500.0, 750.0, 1000.0, 1500.0,
            2000.0, 3000.0, 4500.0, 6500.0, 10000.0, 16000.0, 22051.0)
        val bands = DoubleArray(edges.size - 1)
        for (bin in 1 until size / 2) {
            val hz = bin.toDouble() * Dsp.RATE / size
            val band = edges.indexOfFirst { it > hz } - 1
            if (band >= 0) bands[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
        }
        val total = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / total }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) }

    @Test
    fun `the panel has five timbral macros and HOLD with valid defaults and neutrals`() {
        for (voice in MurmurVoice.entries) {
            val specs = Murmur.macrosFor(voice)
            assertEquals(timbre + "HOLD", specs.map { it.name })
            assertTrue(specs.all { it.default in 0f..1f && it.neutral in 0f..1f })
            assertEquals(specs.associate { it.name to it.default }, Murmur.defaults(voice))
        }
    }

    @Test
    fun `independent renders reproduce audio and do not inherit another pads state`() {
        for (voice in MurmurVoice.entries) {
            val macros = Murmur.defaults(voice) + mapOf("BREATH" to .7f, "MOTION" to .65f)
            val first = Murmur.renderRaw(voice, macros, midi = 60)
            Murmur.renderRaw(MurmurVoice.METAL, mapOf("EDGE" to 1f, "BREATH" to 1f), midi = 84)
            assertContentEquals(first, Murmur.renderRaw(voice, macros, midi = 60), "$voice has shared state or unstable seeding")
            assertContentEquals(Murmur.render(voice, macros).samples, Murmur.render(voice, macros).samples)
        }
    }

    @Test
    fun `dry defaults have a bounded source and safe leveled mono output`() {
        for (voice in MurmurVoice.entries) {
            val raw = Murmur.renderRaw(voice)
            val output = Murmur.render(voice)
            val rawPeak = raw.maxOf { abs(it) }
            val peak = output.samples.maxOf { abs(it) }
            val dc = output.samples.sumOf { it.toDouble() } / output.samples.size
            println("MURMUR DEFAULT $voice: raw peak $rawPeak RMS ${rms(raw)}, output peak $peak DC $dc")
            assertTrue(raw.isNotEmpty() && raw.all { it.isFinite() })
            assertTrue(rawPeak in .005f..4f, "$voice has an inaudible or unbounded native source: $rawPeak")
            assertTrue(rms(raw) > .001, "$voice is effectively silent before leveling")
            assertEquals(1, output.channels)
            assertEquals(44100, output.sampleRate)
            assertTrue(output.samples.all { it.isFinite() })
            assertTrue(peak <= .991f, "$voice exceeds the shared output ceiling: $peak")
            assertTrue(abs(dc) < .003, "$voice has DC $dc")
            assertTrue(output.samples.size <= 8 * Dsp.RATE)
            assertTrue(abs(output.samples.last()) < 1e-5f, "$voice ends with a discontinuity")
        }
    }

    @Test
    fun `mouth movement preserves the requested root in low middle and high registers`() {
        for (voice in MurmurVoice.entries) for (midi in listOf(36, 60, 84)) {
            val sound = Murmur.render(voice, mapOf("MOUTH" to .8f, "MOTION" to .75f), midi)
            val detected = assertNotNull(Pitch.detect(sound, fromSec = .10f, windowSec = .32f), "$voice MIDI $midi loses periodic pitch")
            val wanted = Keys.midiHz(midi)
            val cents = 1200 * ln(detected.hz / wanted.toDouble()) / ln(2.0)
            println("MURMUR ROOT $voice MIDI $midi: ${detected.hz} Hz, $cents cents, confidence ${detected.confidence}")
            assertTrue(abs(cents) < 15.0, "$voice MIDI $midi is $cents cents from its root")
            assertTrue(detected.confidence > .45f, "$voice MIDI $midi has weak pitch confidence ${detected.confidence}")
        }
    }

    @Test
    fun `all five timbral macros change normalized spectral shape on every voice`() {
        for (voice in MurmurVoice.entries) for (macro in timbre) {
            val defaults = Murmur.defaults(voice)
            val rawLow = Murmur.renderRaw(voice, defaults + (macro to 0f))
            val rawHigh = Murmur.renderRaw(voice, defaults + (macro to 1f))
            val low = spectrum(rawLow, fine = true)
            val high = spectrum(rawHigh, fine = true)
            val change = distance(low, high)
            val broad = distance(spectrum(rawLow), spectrum(rawHigh))
            println("MURMUR MACRO $voice $macro: fine spectral mass distance $change, broad distance $broad")
            assertTrue(change > .015, "$voice $macro changes level or phase without useful spectral activity: $change")
        }
    }

    @Test
    fun `mouth source body and motion controls interact inside the voice`() {
        val pairs = listOf("MOUTH" to "EDGE", "MOUTH" to "BREATH", "MOTION" to "BREATH", "BODY" to "MOUTH")
        for ((first, second) in pairs) {
            val spectra = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (a, b) ->
                spectrum(Murmur.renderRaw(MurmurVoice.KEY, mapOf(first to a, second to b)))
            }
            val residual = spectra[0].indices.sumOf { bin ->
                abs((spectra[3][bin] - spectra[2][bin]) - (spectra[1][bin] - spectra[0][bin]))
            }
            println("MURMUR INTERACTION $first/$second: spectral residual $residual")
            assertTrue(residual > .002, "$first/$second are independent output treatments: $residual")
        }
    }

    @Test
    fun `small motion has audible activity and larger motion develops during a finite note`() {
        val voice = MurmurVoice.LEAD
        val macros = Murmur.defaults(voice) + ("MOUTH" to .4f)
        val still = Murmur.renderRaw(voice, macros + ("MOTION" to 0f))
        val small = Murmur.renderRaw(voice, macros + ("MOTION" to .05f))
        val relative = sqrt(still.indices.sumOf { i ->
            val d = still[i] - small[i].toDouble(); d * d
        } / still.sumOf { it.toDouble() * it })
        assertTrue(relative > .005, "the first motion stretch is dead: $relative")
        val moving = Murmur.renderRaw(voice, macros + ("MOTION" to 1f))
        val early = spectrum(moving, .12)
        val later = spectrum(moving, .70)
        val stillEarly = spectrum(still, .12)
        val stillLater = spectrum(still, .70)
        val development = early.indices.sumOf { abs((later[it] - early[it]) - (stillLater[it] - stillEarly[it])) }
        println("MURMUR MOTION: low-end relative difference $relative, moving contour residual $development")
        assertTrue(development > .015, "motion does not develop the spectrum during an ordinary note: $development")
    }

    @Test
    fun `cyclic trajectory direction changes spectral development and leaves a stationary mouth alone`() {
        for (voice in listOf(MurmurVoice.LEAD, MurmurVoice.CHOIR)) {
            val config = Murmur.configurationFor(voice)
            val reversed = config.copy(direction = -config.direction)
            val moving = Murmur.defaults(voice) + ("MOTION" to .8f)
            val forwards = Murmur.renderRaw(voice, moving, config = config)
            val backwards = Murmur.renderRaw(voice, moving, config = reversed)
            val change = distance(spectrum(forwards, .12), spectrum(backwards, .12))
            println("MURMUR DIRECTION $voice: spectral mass distance $change")
            assertTrue(change > .01, "$voice stores direction without changing its cyclic trajectory: $change")
            val stationary = moving + ("MOTION" to 0f)
            assertContentEquals(Murmur.renderRaw(voice, stationary, config = config),
                Murmur.renderRaw(voice, stationary, config = reversed), "$voice direction changes a static mouth")
        }
    }

    @Test
    fun `velocity changes excitation energy and timbre and zero velocity is silent`() {
        for (voice in listOf(MurmurVoice.BASS, MurmurVoice.KEY, MurmurVoice.CHOIR)) {
            val soft = Murmur.renderRaw(voice, velocity = .25f)
            val hard = Murmur.renderRaw(voice, velocity = 1f)
            val ratio = rms(soft) / rms(hard)
            val timbralChange = distance(spectrum(soft, .04, fine = true), spectrum(hard, .04, fine = true))
            println("MURMUR VELOCITY $voice: native RMS ratio $ratio, spectral mass distance $timbralChange")
            assertTrue(ratio < .8, "$voice velocity has no excitation-energy effect: $ratio")
            assertTrue(timbralChange > .005, "$voice velocity only scales final output: $timbralChange")
            assertTrue(Murmur.render(voice, velocity = 0f).samples.all { it == 0f }, "$voice responds to note-off velocity")
        }
    }

    @Test
    fun `low and all-high finite corners stay bounded through the supported note range`() {
        for (voice in MurmurVoice.entries) for (midi in listOf(Murmur.MIN_MIDI, Murmur.MAX_MIDI)) for (value in listOf(0f, 1f)) {
            val macros = timbre.associateWith { value } + ("HOLD" to .95f)
            val raw = Murmur.renderRaw(voice, macros, midi)
            val output = Murmur.render(voice, macros, midi)
            val topShare = spectrum(raw, .1).last()
            val rawPeak = raw.maxOf { abs(it) }
            println("MURMUR CORNER $voice MIDI $midi macro $value: peak $rawPeak, top-band share $topShare")
            assertTrue(raw.all { it.isFinite() } && rawPeak < 4f, "$voice MIDI $midi macro $value is unstable")
            assertTrue(output.samples.all { it.isFinite() && abs(it) <= .991f })
            assertTrue(topShare < .02, "$voice leaves substantial energy near output Nyquist: $topShare")
            if (midi == Murmur.MAX_MIDI) {
                // The shared autocorrelation detector stops at 1200 Hz; the supported top root is higher.
                val wanted = Keys.midiHz(midi)
                val measured = FineTuning.measuredHz(output, wanted, fromSec = .10f, bodySeconds = .35f)
                val cents = FineTuning.cents(measured, wanted.toDouble())
                val from = (Dsp.RATE * .1).toInt()
                val frames = minOf((Dsp.RATE * .35).toInt(), raw.size - from)
                var real = 0.0; var imaginary = 0.0; var windowSum = 0.0
                for (i in 0 until frames) {
                    val window = .5 - .5 * cos(2 * PI * i / (frames - 1))
                    val phase = 2 * PI * wanted * i / Dsp.RATE
                    real += raw[from + i] * window * cos(phase)
                    imaginary += raw[from + i] * window * sin(phase)
                    windowSum += window
                }
                val fundamentalRatio = (2 * hypot(real, imaginary) / windowSum) / rms(raw, from, from + frames)
                println("MURMUR TOP ROOT $voice macro $value: $measured Hz, $cents cents, root amplitude/RMS $fundamentalRatio")
                assertTrue(abs(cents) < 15.0, "$voice top-register macro $value loses its root: $cents cents")
                assertTrue(fundamentalRatio > .03, "$voice top-register macro $value has no substantial root: $fundamentalRatio")
            }
        }
    }

    @Test
    fun `held formants breath and high EDGE converge across independently captured adjacent cycles`() {
        for (voice in MurmurVoice.entries) for (extra in listOf(emptyMap(), mapOf("EDGE" to 1f, "BREATH" to 1f, "MOTION" to 1f))) {
            val macros = Murmur.defaults(voice) + extra + ("HOLD" to 1f)
            val probe = Murmur.probe(voice, macros)
            val previous = assertNotNull(probe.previousCycle, "$voice HOLD did not retain a settled preceding cycle")
            assertTrue(previous.size >= 256 && probe.samples.size >= 256)
            val both = previous + probe.samples
            val seam = Keys.seamError(both, previous.size)
            println("MURMUR HOLD $voice $extra: adjacent-cycle seam $seam, reported ${probe.seamError}")
            assertTrue(seam.isFinite() && seam < 1e-3, "$voice held formants or breath do not repeat: $seam")
            assertTrue(abs(seam - probe.seamError) < 1e-9, "$voice reports a different seam than its captured cycles")
            assertTrue(rms(probe.samples) > .001, "$voice closes its loop by going silent")
            assertContentEquals(probe.samples, Murmur.probe(voice, macros).samples, "$voice held seed is nondeterministic")
        }
    }
}
