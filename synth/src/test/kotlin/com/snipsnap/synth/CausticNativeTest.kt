package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent audio acceptance for the finite native instrument. The mathematical
 * reference traces each delayed source separately and advances the existing modal bank;
 * it never calls the instrument's compressed forcing or closed-form modal solution.
 * Spectral probes remove gain, so neither leveling nor velocity gain can pass a timbre test.
 */
class CausticNativeTest {
    private val voice = CausticVoice.ALLOY
    private val rate = Dsp.RATE

    @Test
    fun `analytic render matches causal individual paths and discrete modal evolution`() {
        data class Case(val midi: Int, val body: Float, val seconds: Double)
        for (case in listOf(Case(36, 0f, .08), Case(60, 1f, .035), Case(84, 0f, .02), Case(84, 1f, .02))) {
            val macros = mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to case.body, "LENGTH" to 1f)
            val p = CausticInstrument.probe(voice, macros, midi = case.midi,
                seconds = case.seconds, normalize = false)
            val reference = individualPathReference(case.midi, .75, macros, p.internalRaw.size)
            assertEquals(reference.size, p.internalRaw.size)
            val error = relativeError(reference, p.internalRaw)
            val maxError = reference.indices.maxOf { abs(reference[it] - p.internalRaw[it]) } /
                reference.maxOf { abs(it) }.coerceAtLeast(1e-20f)
            println("CAUSTIC independent paths MIDI${case.midi} BODY${case.body}: rms=$error max=$maxError")
            assertTrue(error < 2e-5 && maxError < 5e-5,
                "MIDI${case.midi} BODY${case.body}: arrival/phase/modal response differs from direct paths: $error/$maxError")
            assertEquals(0f, p.internalRaw.first(), "a finite delayed source must start at zero")
        }
    }

    @Test
    fun `native notes retain pitched bodies through the register and geometry corners`() {
        for (midi in listOf(36, 60, 84)) for ((source, focus) in listOf(0f to .12f, .5f to .19f, 1f to .245f)) {
            val p = CausticInstrument.probe(voice, mapOf("SOURCE" to source, "FOCUS" to focus),
                midi = midi, velocity = 1f, seconds = 1.0)
            val pitch = assertNotNull(Pitch.detect(Snip(p.samples, 1, p.sampleRate), .5f, .35f),
                "MIDI$midi SOURCE$source FOCUS$focus lost its pitched body")
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val cents = 1200 * ln(pitch.hz / hz) / ln(2.0)
            println("CAUSTIC native pitch MIDI$midi SOURCE$source FOCUS$focus: $cents cents, confidence=${pitch.confidence}")
            assertTrue(pitch.confidence >= .3 && abs(cents) < 20,
                "MIDI$midi SOURCE$source FOCUS$focus: pitch $cents cents, confidence ${pitch.confidence}")
            assertTrue(p.samples.all { it.isFinite() } && p.internalRaw.all { it.isFinite() })
            assertTrue(p.samples.maxOf { abs(it) } in .01f.. .99001f)
            val relativeDc = abs(p.samples.sumOf { it.toDouble() } / p.samples.size) / rms(p.samples)
            assertTrue(relativeDc < .01, "MIDI$midi relative DC $relativeDc")
        }
    }

    @Test
    fun `every supported chromatic key retains the requested native pitch`() {
        var worstCents = 0.0
        var worstMidi = CausticInstrument.MIDI_MIN
        var lowestConfidence = 1f
        val failures = mutableListOf<String>()
        for (midi in CausticInstrument.MIDI_MIN..CausticInstrument.MIDI_MAX) {
            val p = CausticInstrument.probe(voice, midi = midi, seconds = .9)
            val pitch = Pitch.detect(Snip(p.samples, 1, p.sampleRate), .35f, .35f)
            if (pitch == null) {
                failures += "MIDI$midi has no detectable pitched body"
                continue
            }
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val cents = 1200 * ln(pitch.hz / hz) / ln(2.0)
            if (abs(cents) > abs(worstCents)) { worstCents = cents; worstMidi = midi }
            lowestConfidence = minOf(lowestConfidence, pitch.confidence)
            if (pitch.confidence < .3 || abs(cents) >= 20)
                failures += "MIDI$midi: $cents cents, confidence ${pitch.confidence}"
        }
        println("CAUSTIC native 49-key chromatic scan: worst=$worstCents cents at MIDI$worstMidi; minimum confidence=$lowestConfidence")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `source focus and body have low and middle gain independent authority`() {
        for (name in listOf("SOURCE", "FOCUS", "BODY")) {
            val profiles = listOf(0f, .25f, .5f).map { value ->
                val p = CausticInstrument.probe(voice, mapOf(name to value), seconds = 1.0, normalize = false)
                spectralProfile(p.prelevel)
            }
            for (i in 0..1) {
                val difference = totalVariation(profiles[i], profiles[i + 1])
                println("CAUSTIC native $name ${i * .25} to ${(i + 1) * .25}: normalized spectral TV=$difference")
                assertTrue(difference >= .015,
                    "$name first/middle quarter moves only $difference of signal power; gain alone is insufficient")
            }
        }
    }

    @Test
    fun `source focus and body length produce interactions rather than independent scalar gains`() {
        val spatial = listOf(0f, .5f).map { source -> listOf(.12f, .25f).map { focus ->
            spectralProfile(CausticInstrument.probe(voice,
                mapOf("SOURCE" to source, "FOCUS" to focus), seconds = 1.0, normalize = false).prelevel)
        } }
        val spatialInteraction = interaction(spatial)
        println("CAUSTIC SOURCE x FOCUS normalized spectral interaction=$spatialInteraction")
        assertTrue(spatialInteraction >= .015, "SOURCE does not change what FOCUS collects: $spatialInteraction")

        val temporal = listOf(0f, 1f).map { body -> listOf(.25f, .75f).map { length ->
            temporalProfile(CausticInstrument.probe(voice,
                mapOf("BODY" to body, "LENGTH" to length), seconds = 1.5, normalize = false).prelevel)
        } }
        val temporalInteraction = interaction(temporal)
        println("CAUSTIC BODY x LENGTH normalized temporal interaction=$temporalInteraction")
        assertTrue(temporalInteraction >= .01, "BODY/LENGTH share no measurable envelope interaction: $temporalInteraction")
    }

    @Test
    fun `velocity changes normalized body spectrum as well as effort`() {
        val soft = CausticInstrument.probe(voice, velocity = .25f, seconds = 1.0)
        val hard = CausticInstrument.probe(voice, velocity = 1f, seconds = 1.0)
        val tone = totalVariation(spectralProfile(soft.prelevel), spectralProfile(hard.prelevel))
        println("CAUSTIC native velocity normalized spectral TV=$tone prelevel RMS ratio=${rms(hard.prelevel) / rms(soft.prelevel)}")
        assertTrue(tone >= .01, "velocity changes too little normalized spectral power: $tone")
        assertTrue(rms(hard.prelevel) > 2 * rms(soft.prelevel), "hard effort did not drive the actual instrument harder")
        assertTrue(rms(hard.samples) > 1.5 * rms(soft.samples), "leveling removed the performance dynamics")
    }

    @Test
    fun `length changes decay before truncation and stays active across its first half`() {
        val captures = listOf(0f, .25f, .5f).map { length ->
            CausticInstrument.probe(voice, mapOf("LENGTH" to length), seconds = .49, normalize = false).internalRaw
        }
        fun lateToEarly(x: FloatArray): Double = windowRms(x, .30, .45, rate * 4) /
            windowRms(x, .025, .075, rate * 4)
        val ratios = captures.map(::lateToEarly)
        println("CAUSTIC LENGTH0/.25/.5 body-to-onset RMS ratios=$ratios")
        assertTrue(ratios[1] > ratios[0] * 1.5 && ratios[2] > ratios[1] * 1.1,
            "LENGTH does not progressively alter physical decay before the output cut: $ratios")
    }

    @Test
    fun `same native inputs are sample identical and zero velocity is actual silence`() {
        val macros = mapOf("SOURCE" to .73f, "FOCUS" to .24f, "BODY" to .37f, "LENGTH" to .2f)
        val a = CausticInstrument.probe(voice, macros, midi = 48, seconds = .6)
        val b = CausticInstrument.probe(voice, macros, midi = 48, seconds = .6)
        assertContentEquals(a.internalRaw, b.internalRaw)
        assertContentEquals(a.prelevel, b.prelevel)
        assertContentEquals(a.samples, b.samples)
        val silent = CausticInstrument.probe(voice, macros, velocity = 0f, seconds = .6)
        for (buffer in listOf(silent.samples, silent.prelevel, silent.internalRaw))
            assertTrue(buffer.all { it == 0f }, "zero velocity injected excitation or numerical residue")
    }

    @Test
    fun `finite source and free body decay before the final output fade`() {
        val p = CausticInstrument.probe(voice, normalize = false)
        val onset = windowRms(p.internalRaw, .2, .7, rate * 4)
        val tail = windowRms(p.internalRaw, 6.5, 7.5, rate * 4)
        val ratio = tail / onset
        println("CAUSTIC native independent late/onset RMS=$ratio")
        // Disjoint raw windows make this a growth test. The last-second fade and
        // the final peak cap cannot help it, and the reference is not the whole note peak.
        assertTrue(onset > 1e-5 && ratio < .05, "finite body did not decay before its fade: $ratio")
    }

    @Test
    fun `high register four times render follows sixteen times reference without excess spurs`() {
        for (body in listOf(0f, 1f)) {
            val macros = mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to body)
            val a = CausticInstrument.probe(voice, macros, midi = 84, velocity = 1f, seconds = .6, oversample = 4, normalize = false)
            val b = CausticInstrument.probe(voice, macros, midi = 84, velocity = 1f, seconds = .6, oversample = 16, normalize = false)
            val distance = totalVariation(spectralProfile(a.prelevel), spectralProfile(b.prelevel))
            val upper4 = upperGuardPower(a.prelevel)
            val upper16 = upperGuardPower(b.prelevel)
            println("CAUSTIC high-register BODY$body spectral TV4x16x=$distance upper-20kHz4x=$upper4 upper-20kHz16x=$upper16")
            assertTrue(distance < .01, "4x processing materially changes the high-register spectrum: $distance")
            assertTrue(upper4 < 1e-5 && upper4 - upper16 < 1e-6,
                "4x processing leaks excess upper-band power: $upper4 vs $upper16")
        }
    }

    private fun spectralProfile(samples: FloatArray): DoubleArray {
        val profiles = listOf(.04, .25, .55).map { seconds ->
            val n = 8192
            val start = (seconds * rate).toInt()
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until minOf(n, samples.size - start))
                re[i] = (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat()
            Fft.forward(re, im)
            val out = DoubleArray(64)
            for (bin in 1 until n / 2) {
                val hz = bin * rate.toDouble() / n
                val band = (ln(hz.coerceAtLeast(25.0) / 25.0) / ln(2.0) * 6).toInt().coerceIn(0, out.lastIndex)
                out[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            val total = out.sum().coerceAtLeast(1e-30)
            DoubleArray(out.size) { out[it] / total }
        }
        return DoubleArray(profiles.first().size) { i -> profiles.sumOf { it[i] } / profiles.size }
    }

    private fun upperGuardPower(samples: FloatArray): Double {
        val n = 16384
        val start = (.08 * rate).toInt()
        val re = FloatArray(n) { i ->
            (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var total = 0.0
        var upper = 0.0
        for (bin in 1 until n / 2) {
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (bin * rate.toDouble() / n > 20000) upper += power
        }
        return upper / total.coerceAtLeast(1e-30)
    }

    private fun temporalProfile(samples: FloatArray): DoubleArray {
        val out = DoubleArray(12)
        val start = (.04 * rate).toInt()
        val end = minOf(samples.size, (1.45 * rate).toInt())
        for (i in start until end) {
            val bucket = ((i - start).toLong() * out.size / (end - start)).toInt().coerceAtMost(out.lastIndex)
            out[bucket] += samples[i].toDouble() * samples[i]
        }
        val total = out.sum().coerceAtLeast(1e-30)
        return DoubleArray(out.size) { out[it] / total }
    }

    private fun totalVariation(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } / 2
    private fun interaction(corners: List<List<DoubleArray>>): Double = corners[0][0].indices.sumOf { i ->
        abs((corners[0][1][i] - corners[0][0][i]) - (corners[1][1][i] - corners[1][0][i]))
    } / 2
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun windowRms(samples: FloatArray, a: Double, b: Double, sampleRate: Int): Double {
        val start = (a * sampleRate).toInt()
        val end = minOf(samples.size, (b * sampleRate).toInt())
        return sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
    }
    private fun relativeError(a: FloatArray, b: FloatArray) = sqrt(a.indices.sumOf {
        val e = a[it] - b[it].toDouble(); e * e
    } / a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))

    /** The published Alloy profiles expressed as individual delayed sources. Delays are
     * evaluated at their actual fractional arrival, without replacing them by integer taps.
     * The only shared implementation is geometry and the pre-existing discrete modal primitive.
     */
    private fun individualPathReference(midi: Int, velocity: Double, macros: Map<String, Float>, frames: Int): FloatArray {
        val internalRate = rate * 4
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val body = macros.getValue("BODY").toDouble()
        val decay = .64 * (1.5 / .64).pow(body)
        val attack = .006 * (.035 / .006).pow(body)
        val force = 70.0 * 3.0.pow(body)
        val paths = CausticGeometry.prepare(macros.getValue("SOURCE").toDouble(), macros.getValue("FOCUS").toDouble()).paths
        val modes = Modes.Bank(24, internalRate)
        val gains = DoubleArray(24) { m ->
            val h = m % 12 + 1
            (if (h == 1) .22 else if (h == 2) 2.2 else 3.2) *
                (if (m < 12) 1.0 else if (h == 1) .10 else .45)
        }
        for (m in 0 until 24) {
            val h = m % 12 + 1
            val ratio = if (h == 1) (if (m < 12) 1.0 else 1.0018) else
                h * (1.0 + if (m < 12) .0015 * (h - 1.0).pow(1.20) else .005 + .0022 * sin(h * 1.7))
            modes.tune(m, hz * ratio, (if (h == 1) 2.7 else 5.0) / (1.0 + .025 * (h - 1)))
        }
        val spatial = Array(paths.size) { p -> DoubleArray(24) { m ->
            val h = m % 12 + 1
            val pickup = paths[p].pickup
            val y = (pickup - 4) / 4.0
            val participation = if (h == 1) .86 + .14 * cos(PI * y) else
                (.12 + .88 * cos(PI * (.55 + .19 * (h % 5)) * (y + .18 * cos(h.toDouble())))) *
                    (if (m < 12) 1.0 else .86 + .14 * sin(PI * y * (h % 3 + 1)))
            val band = 1.0 / (1.0 + (h * (.018 + .055 * pickup / 8.0)).pow(1.5))
            paths[p].weight * participation * band
        } }
        val tone = DoubleArray(12) { i ->
            val h = i + 1
            .24 * velocity * h.toDouble().pow(-(1.30 - .35 * velocity)) * sourceRolloff(h * hz)
        }
        val edge = DoubleArray(12) { i ->
            val h = i + 1
            .20 * velocity.pow(1.25) * h.toDouble().pow(-.60) * (if (h == 1) .10 else 2.4) * sourceRolloff(h * hz)
        }
        val forces = DoubleArray(24)
        return FloatArray(frames) { n ->
            forces.fill(0.0)
            val t = n.toDouble() / internalRate
            for (p in paths.indices) {
                val dt = t - paths[p].delayR * .65 / hz
                if (dt < 0.0) continue
                val tonal = force * (exp(-dt / decay) - exp(-dt * (1 / decay + 1 / attack)))
                val transient = 1500.0 * (exp(-dt / .006) - exp(-dt * (1 / .006 + 1 / .0005)))
                for (h in 0 until 12) {
                    val x = sin(2 * PI * (h + 1) * hz * dt) * (tone[h] * tonal + edge[h] * transient)
                    forces[h] += spatial[p][h] * x
                    forces[h + 12] += spatial[p][h + 12] * x
                }
            }
            modes.step()
            modes.drive(forces, 1.0)
            var sum = 0.0
            for (m in 0 until 24) sum += modes.velocity(m) * gains[m]
            val direct = .035 * velocity * sin(2 * PI * hz * t) *
                (exp(-t / decay) - exp(-t * (1 / decay + 1 / attack)))
            ((sum + direct) * .68).toFloat()
        }
    }

    private fun sourceRolloff(hz: Double): Double = when {
        hz < 16000 -> 1.0
        hz >= 20000 -> 0.0
        else -> .5 + .5 * cos(PI * (hz - 16000) / 4000)
    }
}
