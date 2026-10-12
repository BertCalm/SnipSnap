package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Audio measurements shared by CONTOUR's finite, loop and export acceptance probes. */
internal object ContourTestAudio {
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        require(to > from && from >= 0 && to <= x.size)
        return sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
    }

    fun mean(x: FloatArray): Double = x.sumOf { it.toDouble() } / x.size

    /** Blackman-Harris window keeps a static harmonic source from reading as broadband aliasing. */
    fun power(x: FloatArray, from: Int, size: Int): DoubleArray {
        require(from >= 0 && from + size <= x.size)
        val re = FloatArray(size) { i ->
            val phase = 2 * PI * i / (size - 1)
            val w = .35875 - .48829 * cos(phase) + .14128 * cos(2 * phase) - .01168 * cos(3 * phase)
            (x[from + i] * w).toFloat()
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    /** Relative octave energies: a scalar gain cannot pass this measurement as a timbral change. */
    fun bands(x: FloatArray, from: Int, size: Int = 16_384, rate: Int = Dsp.RATE): DoubleArray {
        val p = power(x, from, size)
        val edges = doubleArrayOf(25.0, 100.0, 200.0, 400.0, 800.0, 1_600.0, 3_200.0, 6_400.0, 12_800.0, 20_000.0)
        val energy = DoubleArray(edges.size - 1)
        var total = 0.0
        for (b in 1 until p.size) {
            val hz = b.toDouble() * rate / size
            if (hz !in edges.first()..edges.last()) continue
            total += p[b]
            for (j in energy.indices) if (hz >= edges[j] && hz < edges[j + 1]) energy[j] += p[b]
        }
        require(total > 1e-12) { "spectral probe is silent" }
        return DoubleArray(energy.size) { 10 * log10(energy[it] / total + 1e-6) }
    }

    fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } / a.size

    /** Overtones can move in opposite directions within one octave. Judge both resolutions. */
    fun spectralDistance(a: FloatArray, b: FloatArray, from: Int, hz: Double, size: Int = 8_192): Double {
        val broad = distance(bands(a, from, size), bands(b, from, size))
        val ha = harmonics(a, from, hz, size)
        val hb = harmonics(b, from, hz, size)
        // Silent high partials are not evidence of a useful macro; at least one
        // side must carry the partial within 40 dB of the harmonic energy.
        val active = ha.indices.filter { maxOf(ha[it], hb[it]) > -40 }
        val detailed = if (active.isEmpty()) 0.0 else active.sumOf { abs(ha[it] - hb[it]) } / active.size
        return maxOf(broad, detailed)
    }

    /** Harmonic energy shares at a known root, avoiding octave-band cancellations during a scan. */
    fun harmonics(x: FloatArray, from: Int, hz: Double, size: Int = 16_384, rate: Int = Dsp.RATE, count: Int = 16): DoubleArray {
        val p = power(x, from, size)
        val binHz = rate.toDouble() / size
        val e = DoubleArray(count) { h ->
            val center = (hz * (h + 1) / binHz).toInt()
            val radius = maxOf(4, (hz * .008 / binHz).toInt())
            (maxOf(1, center - radius)..minOf(p.lastIndex, center + radius)).sumOf { p[it] }
        }
        val total = e.sum().coerceAtLeast(1e-20)
        return DoubleArray(count) { 10 * log10(e[it] / total + 1e-6) }
    }

    fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val e = (0 until n).sumOf { a[it].toDouble() * a[it] }.coerceAtLeast(1e-30)
        return sqrt((0 until n).sumOf { val d = a[it] - b[it].toDouble(); d * d } / e)
    }

    fun pitch(s: Snip, hz: Float, from: Float = .08f, seconds: Float = .35f): Double =
        FineTuning.cents(FineTuning.measuredHz(s, hz, from, seconds), hz.toDouble())

    fun clarity(x: FloatArray, hz: Float, from: Int, size: Int = 65_536): Double {
        val p = power(x, from, size)
        var on = 0.0
        var off = 0.0
        for (b in 1 until p.size) {
            val f = b.toDouble() * Dsp.RATE / size
            if (f < hz / 2 || f > 20_000) continue
            val nearest = kotlin.math.round(f / hz)
            if (abs(f - nearest * hz) <= 10) on += p[b] else off += p[b]
        }
        return 10 * log10(on / off.coerceAtLeast(1e-30))
    }
}

/** CONTOUR's claims are measured before shared loudness makeup as well as on finished audio. */
class ContourEngineTest {
    private val timbre = listOf("SCAN", "OFFSET", "MOTION", "FILTER", "DRIVE")

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        for (render in listOf<() -> Unit>(
            { ContourEngine.render(ContourVoice.KEY) },
            { ContourEngine.renderLoop(ContourVoice.KEY) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException> { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(ContourEngine.renderLoop(ContourVoice.KEY).any { it != 0f }, "an uninterrupted held render still sounds")
    }

    @Test
    fun `body partial weighting preserves root energy and changes only the overtones`() {
        for (voice in listOf(ContourVoice.BASS, ContourVoice.KEY)) {
            val macros = ContourEngine.defaults(voice) + mapOf("MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 0f, "HOLD" to 1f)
            val config = ContourConfig.legacyForVoice(voice).copy(subLevel = 0f)
            val plain = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY, macros, config = config)
            val weighted = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY, macros,
                config = config.copy(bodyHarmonics = 2f))
            val size = 32768
            val p = ContourTestAudio.power(plain, 4096, size)
            val q = ContourTestAudio.power(weighted, 4096, size)
            val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
            fun energy(power: DoubleArray, harmonic: Int): Double {
                val center = (harmonic * hz * size / Dsp.RATE).toInt()
                return (center - 4..center + 4).sumOf { power[it] }
            }
            val rootRatio = energy(q, 1) / energy(p, 1)
            val overtoneRatio = (2..16).sumOf { energy(q, it) } / (2..16).sumOf { energy(p, it) }
            println("CONTOUR $voice body harmonics 1 to 2: root power $rootRatio, overtone power $overtoneRatio")
            assertTrue(abs(rootRatio - 1) < .001, "$voice: weighting changed the requested root energy")
            assertTrue(abs(overtoneRatio - 4) < .03, "$voice: weighting did not independently double partial amplitudes")
        }
    }

    @Test
    fun `edge excitation has its own bounded transient and settles without changing the body`() {
        val voice = ContourVoice.KEY
        val macros = ContourEngine.defaults(voice) + mapOf("MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 0f)
        val legacy = ContourConfig.legacyForVoice(voice)
        val projected = legacy.copy(edgeAttack = .005f, edgeDecay = .22f, edgeSustain = .4f)
        val plain = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, macros, config = legacy)
        val moving = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, macros, config = projected)
        fun ratio(from: Float, to: Float): Double {
            val a = (from * Dsp.RATE).toInt(); val b = (to * Dsp.RATE).toInt()
            return ContourTestAudio.rms(moving, a, b) / ContourTestAudio.rms(plain, a, b)
        }
        val attack = ratio(.05f, .10f)
        val rest = ratio(.6f, .7f)
        assertTrue(attack in .75..1.0 && rest in .39.. .47 && attack > 1.6 * rest,
            "edge projection is a uniform gain: attack $attack, rest $rest")
        assertContentEquals(
            ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY, macros, config = legacy),
            ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY, macros, config = projected))
        val held = macros + ("HOLD" to 1f)
        val heldPlain = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, held, config = legacy)
        val heldEdge = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, held, config = projected)
        assertTrue(abs(ContourTestAudio.rms(heldEdge) / ContourTestAudio.rms(heldPlain) - .4) < .001,
            "held edge did not converge to its configured sustain input level")
    }

    @Test
    fun `edge root weighting leaves its moving overtones available to the shared filter`() {
        val voice = ContourVoice.KEY
        val macros = ContourEngine.defaults(voice) + mapOf("MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 0f, "HOLD" to 1f)
        val config = ContourConfig.legacyForVoice(voice)
        val plain = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, macros, config = config)
        val colored = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, macros,
            config = config.copy(edgeRootLevel = 0f))
        val size = 32768
        val p = ContourTestAudio.power(plain, 4096, size)
        val q = ContourTestAudio.power(colored, 4096, size)
        val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
        fun energy(power: DoubleArray, harmonic: Int): Double {
            val center = (harmonic * hz * size / Dsp.RATE).toInt()
            return (center - 4..center + 4).sumOf { power[it] }
        }
        assertTrue(energy(q, 1) / energy(p, 1) < 1e-5, "edge root projection left a root component")
        val overtoneRatio = (2..16).sumOf { energy(q, it) } / (2..16).sumOf { energy(p, it) }
        assertTrue(abs(overtoneRatio - 1) < .001, "edge root projection changed other partials: $overtoneRatio")
    }

    @Test
    fun `authored richer recipes remain recognizably pitched and avoid drum hit classification`() {
        for (patch in ContourPresets.all()) {
            val snip = patch.render()
            val estimated = assertNotNull(Pitch.detect(snip, .08f, .35f), patch.name)
            val expected = ContourEngine.frequencyFor(patch.voice, patch.macros.getValue("TUNE"))
            val cents = FineTuning.cents(estimated.hz.toDouble(), expected.toDouble())
            val heard = Classifier.classify(snip).drumClass
            println("CONTOUR ${patch.name}: detector $cents cents, confidence ${estimated.confidence}, class $heard")
            assertTrue(abs(cents) < 15 && estimated.confidence > .6f,
                "${patch.name}: stronger partials obscured the root: $estimated, $cents cents")
            // A short tonal pluck is filed as generic PERC by classifyBright;
            // the root/confidence probe above establishes its pitched identity.
            val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
                DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
            assertTrue(heard !in drums,
                "${patch.name}: stronger partials caused $heard classification")
        }
    }

    @Test
    fun `allowed structural extremes remain bounded before normalization and in held state`() {
        for (voice in ContourVoice.entries) {
            val config = ContourConfig.forVoice(voice).copy(bodyHarmonics = 2.5f, edgeRootLevel = 0f,
                edgeSustain = 0f, edgeAttack = .001f, edgeDecay = .02f)
            val macros = ContourEngine.defaults(voice) + timbre.associateWith { 1f }
            val raw = ContourEngine.renderInternalRaw(voice, macros, config = config)
            assertTrue(raw.all { it.isFinite() && abs(it) < 4f }, "$voice: structurally extreme raw state")
            val loop = ContourEngine.renderLoopMeasured(voice, macros, config = config)
            assertTrue(loop.periodicError < 1e-3 && loop.seamBeforeBlend < 1e-3 && loop.stateError < 1e-3,
                "$voice: structurally extreme held state failed to converge")
        }
    }

    @Test
    fun `the voice roster and the five timbral controls have ordinary defaults`() {
        assertEquals(listOf("BASS", "KEY", "PLUCK", "LEAD", "HOLLOW", "SWELL"), ContourVoice.entries.map { it.name })
        for (voice in ContourVoice.entries) {
            val specs = ContourEngine.macrosFor(voice)
            assertEquals(listOf("TUNE") + timbre + "HOLD", specs.map { it.name })
            for (name in timbre) assertTrue(ContourEngine.defaults(voice).getValue(name) in .1f.. .8f, "$voice $name")
        }
    }

    @Test
    fun `each voice retains its requested root and safe raw energy across register and velocity`() {
        for (voice in ContourVoice.entries) for (tune in listOf(.25f, .5f, .75f)) for (velocity in listOf(.25f, 1f)) {
            val macros = ContourEngine.defaults(voice) + ("TUNE" to tune)
            val raw = ContourEngine.renderRaw(voice, macros, velocity)
            val peak = raw.maxOf { abs(it) }
            val rms = ContourTestAudio.rms(raw)
            val dc = abs(ContourTestAudio.mean(raw)) / rms
            val hz = ContourEngine.frequencyFor(voice, tune)
            val cents = ContourTestAudio.pitch(Snip(raw, 1, Dsp.RATE), hz, if (voice == ContourVoice.SWELL) .35f else .08f, .4f)
            val window = Dsp.RATE / 20
            val loudest = (0 until raw.size / window).maxOf { ContourTestAudio.rms(raw, it * window, (it + 1) * window) }
            val endDb = 20 * log10(ContourTestAudio.rms(raw, raw.size - window, raw.size).coerceAtLeast(1e-30) / loudest)
            println("CONTOUR $voice TUNE $tune velocity $velocity: peak $peak RMS $rms DC/RMS $dc pitch $cents cents end $endDb dB")
            assertTrue(raw.all { it.isFinite() }, "$voice $tune $velocity: non-finite raw sample")
            assertTrue(peak in .001f..3f && rms > 1e-5, "$voice $tune $velocity: peak $peak, RMS $rms")
            assertTrue(dc < .015, "$voice $tune $velocity: DC is $dc of RMS")
            assertTrue(abs(cents) < 5, "$voice $tune $velocity: root is $cents cents off")
            assertTrue(endDb < -35, "$voice $tune $velocity: raw tail ends $endDb dB down")
        }
    }

    @Test
    fun `nonlinear filter state remains bounded at the difficult macro corners before normalization`() {
        val corners = listOf(
            timbre.associateWith { 0f },
            timbre.associateWith { 1f },
            mapOf("SCAN" to 0f, "OFFSET" to 1f, "MOTION" to 1f, "FILTER" to 0f, "DRIVE" to 1f),
            mapOf("SCAN" to 1f, "OFFSET" to 1f, "MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 1f),
        )
        for (voice in ContourVoice.entries) for (tune in listOf(0f, 1f)) for (corner in corners) {
            val macros = ContourEngine.defaults(voice) + corner + ("TUNE" to tune)
            val raw = ContourEngine.renderInternalRaw(voice, macros)
            assertTrue(raw.all { it.isFinite() }, "$voice $tune $corner: non-finite internal state")
            val peak = raw.maxOf { abs(it) }
            val rms = ContourTestAudio.rms(raw)
            assertTrue(peak < 4f && rms > 1e-6, "$voice $tune $corner: pre-condition peak $peak RMS $rms")
            val finished = ContourEngine.render(voice, macros)
            assertEquals(1, finished.channels)
            assertEquals(Dsp.RATE, finished.sampleRate)
            assertTrue(finished.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice $tune $corner: finished output")
        }
    }

    @Test
    fun `fresh concurrent renders reproduce every sample without sharing filter or scan state`() {
        val voices = ContourVoice.entries.toList()
        val expected = voices.associateWith { ContourEngine.render(it).samples }
        // Interleave contrasting settings between repeats to catch retained transient state.
        voices.parallelStream().forEach { voice ->
            ContourEngine.render(voice, mapOf("SCAN" to .93f, "OFFSET" to .88f, "DRIVE" to .95f))
            assertContentEquals(expected.getValue(voice), ContourEngine.render(voice).samples, voice.name)
        }
        for (voice in voices) assertContentEquals(expected.getValue(voice), ContourEngine.render(voice).samples, voice.name)
    }

    @Test
    fun `all five controls move normalized spectra at ordinary settings on every voice`() {
        for (voice in ContourVoice.entries) for (name in timbre) {
            val macros = ContourEngine.defaults(voice)
            val center = macros.getValue(name)
            val low = maxOf(0f, center - .25f)
            val high = minOf(1f, center + .25f)
            val a = ContourEngine.renderRaw(voice, macros + (name to low))
            val b = ContourEngine.renderRaw(voice, macros + (name to high))
            // A .37-second window averages much of PLUCK's short movement away.
            // The .19-second probe also reads onset/body/rest, and measures named
            // harmonic shares as well as octave bands for within-octave travel.
            val positions = listOf(.02, .08, .25, .4, .75).filter { (it * Dsp.RATE).toInt() + 8_192 <= minOf(a.size, b.size) }
            val hz = ContourEngine.frequencyFor(voice, macros.getValue("TUNE")).toDouble()
            val shift = positions.maxOf { sec ->
                ContourTestAudio.spectralDistance(a, b, (sec * Dsp.RATE).toInt(), hz)
            }
            println("CONTOUR $voice $name $low to $high: normalized spectral shares move $shift dB")
            assertTrue(shift > .35, "$voice $name: ordinary settings move spectral shares only $shift dB")
        }
    }

    @Test
    fun `static scan and offset remain audible with motion disabled`() {
        for (voice in ContourVoice.entries) for (name in listOf("SCAN", "OFFSET")) {
            val base = ContourEngine.defaults(voice) + mapOf("MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 0f)
            val a = ContourEngine.renderRaw(voice, base + (name to .15f))
            val b = ContourEngine.renderRaw(voice, base + (name to .75f))
            val from = (.1 * Dsp.RATE).toInt()
            val hz = ContourEngine.frequencyFor(voice, base.getValue("TUNE")).toDouble()
            val shift = ContourTestAudio.spectralDistance(a, b, from, hz)
            assertTrue(shift > .5, "$voice $name with MOTION 0: spectral shift $shift dB")
        }
    }

    @Test
    fun `spectral activity measurements reject a gain only positive control`() {
        for (voice in ContourVoice.entries) {
            val audio = ContourEngine.renderRaw(voice)
            val quieter = FloatArray(audio.size) { audio[it] * .37f }
            val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
            val shift = ContourTestAudio.spectralDistance(audio, quieter, Dsp.RATE / 10, hz)
            assertTrue(shift < 1e-4, "$voice: gain-only control falsely moved spectra by $shift dB")
        }
    }

    @Test
    fun `motion is stationary at zero and begins travelling at a low nonzero value`() {
        for (voice in ContourVoice.entries) {
            val base = ContourEngine.defaults(voice) + ("MOTION" to 0f)
            val frozen = (0..20).map { ContourEngine.scanCoordinates(voice, base, it * .05) }
            assertTrue(frozen.all { it == frozen.first() }, "$voice: MOTION 0 moved a scan coordinate")
            val moving = (0..20).map { ContourEngine.scanCoordinates(voice, base + ("MOTION" to .05f), it * .05) }
            val span = moving.maxOf { it.first } - moving.minOf { it.first } + moving.maxOf { it.second } - moving.minOf { it.second }
            assertTrue(span > .01, "$voice: MOTION .05 travels only $span in one second")
            val open = base + mapOf("FILTER" to 1f, "DRIVE" to 0f)
            val still = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.OPEN, open)
            val scan = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.OPEN, open + ("MOTION" to .15f))
            val shift = listOf(.08, .4, .75).maxOf { sec ->
                val from = (sec * Dsp.RATE).toInt()
                ContourTestAudio.distance(ContourTestAudio.bands(still, from), ContourTestAudio.bands(scan, from))
            }
            assertTrue(shift > .2, "$voice: low motion moves the open dry spectrum only $shift dB")
        }
    }

    @Test
    fun `velocity changes harmonic emphasis after matching event energy`() {
        for (voice in ContourVoice.entries) {
            val a = ContourEngine.renderRaw(voice, velocity = .25f)
            val b = ContourEngine.renderRaw(voice, velocity = 1f)
            val from = (.08 * Dsp.RATE).toInt()
            val shift = ContourTestAudio.distance(ContourTestAudio.bands(a, from), ContourTestAudio.bands(b, from))
            println("CONTOUR $voice soft to hard: normalized spectrum $shift dB")
            assertTrue(shift > .2, "$voice: velocity is only level, spectral shift $shift dB")
        }
    }

    @Test
    fun `the body anchors the root while the edge travels through a different harmonic contour`() {
        for (voice in ContourVoice.entries) {
            val macros = ContourEngine.defaults(voice) + mapOf("HOLD" to 1f, "MOTION" to .55f, "OFFSET" to .65f, "FILTER" to 1f, "DRIVE" to 0f)
            // Removing the optional root sine proves that the wavetable body owns the pitch.
            val config = ContourConfig.forVoice(voice).copy(subLevel = 0f)
            val body = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY, macros, config = config)
            val edge = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY, macros, config = config)
            val combined = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.COMBINED, macros, config = config)
            val hz = ContourEngine.frequencyFor(voice, .5f)
            val starts = listOf(body.size / 10, body.size / 3, body.size * 3 / 5)
            val bodySeries = starts.map { ContourTestAudio.harmonics(body, it, hz.toDouble()) }
            val edgeSeries = starts.map { ContourTestAudio.harmonics(edge, it, hz.toDouble()) }
            // A periodic scan can revisit exactly the same phase at the first
            // and last probes. Use the most separated measured edge spectra,
            // then compare the body's movement over that very same interval.
            val pair = bodySeries.indices.flatMap { a -> (a + 1 until bodySeries.size).map { b -> a to b } }
                .maxBy { (a, b) -> ContourTestAudio.distance(edgeSeries[a], edgeSeries[b]) }
            val bodyChange = DoubleArray(16) { bodySeries[pair.second][it] - bodySeries[pair.first][it] }
            val edgeChange = DoubleArray(16) { edgeSeries[pair.second][it] - edgeSeries[pair.first][it] }
            val different = ContourTestAudio.distance(bodyChange, edgeChange)
            val edgeTravel = ContourTestAudio.distance(edgeSeries[pair.first], edgeSeries[pair.second])
            println("CONTOUR $voice: edge harmonic travel $edgeTravel dB; body/edge contour difference $different dB")
            assertTrue(bodySeries.all { it[0] > -18 }, "$voice: the body lost its fundamental without the anchor sine")
            assertTrue(edgeTravel > .6, "$voice: the isolated edge barely moves, $edgeTravel dB")
            assertTrue(different > .5, "$voice: body and edge follow the same harmonic contour, $different dB apart")
            for (start in starts) {
                val cents = ContourTestAudio.pitch(Snip(combined, 1, Dsp.RATE), hz, start.toFloat() / Dsp.RATE, .35f)
                assertTrue(abs(cents) < 5, "$voice: the body root moved $cents cents during edge travel")
            }
        }
    }

    @Test
    fun `static high register nonlinear output keeps nonharmonic foldback below the measured gate`() {
        for (voice in ContourVoice.entries) for (drive in listOf(0f, 1f)) {
            // Unison/no sub and frozen scans give a stationary exactly harmonic fixture:
            // energy off the harmonic grid now measures artifacts rather than legitimate motion.
            val config = ContourConfig.forVoice(voice).copy(edgeRatio = 1f, subLevel = 0f)
            val macros = ContourEngine.defaults(voice) + mapOf("TUNE" to 1f, "SCAN" to 1f, "OFFSET" to 1f, "MOTION" to 0f, "FILTER" to 1f, "DRIVE" to drive, "HOLD" to 1f)
            val result = ContourEngine.renderLoopMeasured(voice, macros, config = config, normalize = false)
            val f0 = result.actualHz.toFloat()
            val clarity = ContourTestAudio.clarity(result.samples, f0, (Dsp.RATE * .1).toInt())
            println("CONTOUR $voice high register DRIVE $drive: harmonic/off-grid clarity $clarity dB before loudness makeup")
            assertTrue(clarity > 35, "$voice DRIVE $drive: harmonic/off-grid clarity only $clarity dB")
        }
        // A positive control ensures that an intentionally aliased native-rate saw
        // fails the gate; otherwise an insensitive FFT measurement could pass anything.
        val hz = Keys.midiHz(84)
        val aliased = FloatArray(Dsp.RATE * 2) { i ->
            val phase = (i.toDouble() * hz / Dsp.RATE) % 1.0
            (2 * phase - 1).toFloat()
        }
        val bad = ContourTestAudio.clarity(aliased, hz, Dsp.RATE / 10)
        println("CONTOUR alias probe control: native saw clarity $bad dB")
        assertTrue(bad < 30, "alias probe did not identify its intentionally aliased control: $bad dB")
    }
}
