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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Audio evidence for the dry pitched voice, before shared loudness can hide excitation changes. */
class MurmurTest {
    private val timbre = listOf("MOUTH", "EDGE", "BREATH", "MOTION", "BODY")
    /** The authored MOUTH, EDGE, BREATH, MOTION and BODY defaults of each voice; HOLD defaults to 0. */
    private val authoredDefaults = mapOf(
        MurmurVoice.BASS to listOf(.25f, .35f, .08f, .25f, .75f),
        MurmurVoice.KEY to listOf(.40f, .40f, .12f, .25f, .55f),
        MurmurVoice.PLUCK to listOf(.35f, .55f, .10f, .35f, .45f),
        MurmurVoice.LEAD to listOf(.55f, .45f, .20f, .45f, .50f),
        MurmurVoice.CHOIR to listOf(.45f, .20f, .30f, .30f, .65f),
        MurmurVoice.METAL to listOf(.35f, .75f, .15f, .40f, .40f),
    )

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

    private fun harmonicMass(power: DoubleArray, harmonic: Int): Double {
        val binHz = Dsp.RATE.toDouble() / 16384
        val center = Keys.midiHz(60) * harmonic
        return power.indices.filter { abs((it + 1) * binHz - center) < 2.5 * binHz }.sumOf { power[it] }
    }

    private fun contourTimes(voice: MurmurVoice): List<Double> =
        if (voice == MurmurVoice.LEAD || voice == MurmurVoice.CHOIR) listOf(.22, .50, .85, 1.15, 1.45)
        else listOf(.22, .30, .42, .55, .70)

    @Test
    fun `BODY retains audible harmonic support and useful quarter steps after shared leveling`() {
        val failures = mutableListOf<String>()
        for (voice in MurmurVoice.entries) {
            val sounds = listOf(0f, .25f, .5f, .75f, 1f).map { body ->
                Murmur.render(voice, mapOf("BODY" to body, "MOTION" to 0f)).samples
            }
            val profiles = sounds.map { spectrum(it, .60, fine = true) }
            val fullRoot = harmonicMass(profiles.last(), 1)
            val fullChest = harmonicMass(profiles.last(), 2) + harmonicMass(profiles.last(), 3)
            println("MURMUR BODY SUPPORT $voice: full H1 $fullRoot, H2+H3 $fullChest")
            // A gain-normalized spectrum can differ numerically while both ends remain nearly
            // pure sine waves. Require useful harmonic energy in the full/chesty endpoint.
            if (!(fullRoot < .94)) failures += "$voice full BODY is still an almost pure root: $fullRoot"
            if (!(fullChest > .05)) failures += "$voice full BODY has no useful pitched low/mid support: $fullChest"
            val endpoint = distance(profiles.first(), profiles.last())
            println("MURMUR BODY ENDPOINT $voice: normalized harmonic distance $endpoint")
            if (!(endpoint > .10)) failures += "$voice BODY remains a subtle endpoint change: $endpoint"
            for (step in 1 until profiles.size) {
                val change = distance(profiles[step - 1], profiles[step])
                println("MURMUR BODY QUARTER $voice step $step: normalized harmonic distance $change")
                if (!(change > .025)) failures += "$voice BODY quarter $step has little timbral activity after leveling: $change"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `MOTION travels after the onset rather than becoming a different static mouth`() {
        val failures = mutableListOf<String>()
        for (voice in MurmurVoice.entries) {
            val still = Murmur.render(voice, mapOf("MOTION" to 0f)).samples
            val moving = Murmur.render(voice, mapOf("MOTION" to 1f)).samples
            val times = contourTimes(voice)
            val firstStill = spectrum(still, times.first(), size = 4096)
            val firstMoving = spectrum(moving, times.first(), size = 4096)
            val travel = times.drop(1).maxOf { time ->
                val laterStill = spectrum(still, time, size = 4096)
                val laterMoving = spectrum(moving, time, size = 4096)
                firstStill.indices.sumOf { band ->
                    abs((laterMoving[band] - firstMoving[band]) - (laterStill[band] - firstStill[band]))
                }
            }
            println("MURMUR LATE TRAVEL $voice: broad spectral contour beyond 220 ms $travel")
            // Each frame is normalized separately, so louder/quieter frames or final-output
            // tremolo cannot satisfy this guard. Static FM articulation is subtracted too.
            if (!(travel > .06)) failures += "$voice MOTION stops developing before useful note frames: $travel"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `MOTION middle and upper quarter steps change the developing vowel profile`() {
        for (voice in MurmurVoice.entries) {
            val times = contourTimes(voice)
            val profiles = listOf(0f, .25f, .5f, .75f, 1f).map { motion ->
                val samples = Murmur.render(voice, mapOf("MOTION" to motion)).samples
                times.map { spectrum(samples, it, size = 4096) }
            }
            for (step in 1 until profiles.size) {
                val change = times.indices.map { frame -> distance(profiles[step - 1][frame], profiles[step][frame]) }.average()
                println("MURMUR MOTION QUARTER $voice step $step: mean normalized contour distance $change")
                assertTrue(change > .025, "$voice MOTION quarter $step is perceptually inactive across useful frames: $change")
            }
        }
    }

    @Test
    fun `the panel has five timbral macros and HOLD with valid defaults and neutrals`() {
        for (voice in MurmurVoice.entries) {
            val specs = Murmur.macrosFor(voice)
            assertEquals(timbre + "HOLD", specs.map { it.name })
            assertTrue(specs.all { it.default in 0f..1f && it.neutral in 0f..1f })
            // A saved patch may omit macros, which then render at the voice default, so these defaults are part of
            // what a saved pad sounds like. Comparing defaults() with the specs it is built from could never fail.
            assertEquals(timbre.zip(authoredDefaults.getValue(voice)).toMap() + ("HOLD" to 0f), Murmur.defaults(voice),
                "$voice authored defaults changed")
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
            // previousCycle is a non-null array that defaults to empty, so a null check could never fail.
            val previous = probe.previousCycle
            assertEquals(probe.samples.size, previous.size, "$voice HOLD did not retain a whole settled preceding cycle")
            assertTrue(previous.size >= 256 && probe.samples.size >= 256)
            val both = previous + probe.samples
            val seam = Keys.seamError(both, previous.size)
            // probe() reports this same expression on the same arrays, so comparing the two could never fail. What
            // can: the same measurement with the join moved half a root period. Cycles that genuinely repeat are
            // seamless only at the true join, so a misjoin must read far above the bar.
            val half = kotlin.math.round(Dsp.RATE / probe.rootHz / 2).toInt()
            val misjoined = Keys.seamError(previous.copyOfRange(0, previous.size - half) + probe.samples, previous.size - half)
            println("MURMUR HOLD $voice $extra: adjacent-cycle seam $seam, reported ${probe.seamError}, half a root period off $misjoined")
            assertTrue(seam.isFinite() && seam < 1e-3, "$voice held formants or breath do not repeat: $seam")
            assertTrue(misjoined > 100 * Keys.MAX_SEAM_ERROR,
                "$voice seam metric cannot tell its true join from one half a root period off: $misjoined")
            assertTrue(rms(probe.samples) > .001, "$voice closes its loop by going silent")
            assertTrue(probe.samples.maxOf { abs(it) } < 1f, "$voice held native output clips the audition PCM export")
            assertContentEquals(probe.samples, Murmur.probe(voice, macros).samples, "$voice held seed is nondeterministic")
        }
    }

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        // SynthScreen previews run in runInterruptible: a newer request interrupts the worker, and the
        // sample loop, the decimation, the seam and the level must stop on that flag instead of finishing.
        for (model in 1..3) for ((missing, render) in listOf<Pair<String, () -> Unit>>(
            "MURMUR finite render did not stop on the interrupt at its first look in the sample loop" to
                { Murmur.render(MurmurVoice.KEY, config = Murmur.configurationFor(MurmurVoice.KEY, model)) },
            "MURMUR held render did not stop on the interrupt at its first look in the sample loop" to
                { Murmur.render(MurmurVoice.KEY, mapOf("HOLD" to 1f), config = Murmur.configurationFor(MurmurVoice.KEY, model)) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException>(missing) { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(Murmur.render(MurmurVoice.KEY).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    @Test
    fun `a held render nobody wants any more after its decimation stops before the seam and the level`() {
        // The held synthesis runs two settling periods, three kept periods and the decimator padding, looking every
        // 8192 internal samples, then looks once more before the decimation; an interrupt that lands after those
        // looks must still stop the seam measurement and the level instead of letting them finish unwanted.
        // After the loop an uninterrupted render looks exactly three more times: before the decimation, before the
        // seam and before the level. Removing any one of those changes the count.
        for (model in 1..3) {
            val config = Murmur.configurationFor(MurmurVoice.KEY, model)
            val held = mapOf("HOLD" to 1f)
            val clear = LateInterrupt(clearLooks = Int.MAX_VALUE)
            val frames = clear.run { Murmur.render(MurmurVoice.KEY, held, config = config) }.samples.size
            val loopLooks = ((5 * frames + 512) * Dsp.OVERSAMPLE + 8191) / 8192
            assertEquals(loopLooks + 3, clear.looks,
                "an uninterrupted held render looks $loopLooks times in its sample loop, then before the decimation, the seam and the level")
            val late = LateInterrupt(clearLooks = loopLooks + 1)
            assertFailsWith<java.util.concurrent.CancellationException>(
                "MURMUR held render did not stop on the interrupt at its look after the decimation, before the seam",
            ) { late.run { Murmur.render(MurmurVoice.KEY, held, config = config) } }
        }
    }
}
