package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Output acceptance for the three adopted character studies. Power spectra and RMS
 * envelopes remove scalar gain and do not reward carrier phase differences. These shape
 * thresholds guard measured distinctions; they do not establish listening acceptance.
 */
class CausticCharactersTest {
    private val voices = listOf(CausticVoice.HOLLOW_MALLET, CausticVoice.RUBBED_RING,
        CausticVoice.TRAVELLING_CATCHES)
    private val rate = CausticInstrument.RATE

    @Test
    fun `new voices retain finite controls with explicit defaults and refuse positive HOLD`() {
        for (voice in voices) {
            val body = if (voice == CausticVoice.TRAVELLING_CATCHES) 0f else .5f
            assertEquals(mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to body,
                "LENGTH" to 1f, "HOLD" to 0f), CausticInstrument.defaults(voice), "$voice defaults")
            assertEquals(listOf("SOURCE", "FOCUS", "BODY", "LENGTH", "HOLD"),
                CausticInstrument.macrosFor(voice).map { it.name })
            assertEquals(.5, CausticInstrument.durationSeconds(voice, mapOf("LENGTH" to 0f)))
            assertEquals(8.0, CausticInstrument.durationSeconds(voice))
            for (hold in listOf(.01f, 1f)) {
                val macros = mapOf("HOLD" to hold)
                assertFailsWith<IllegalArgumentException>("$voice HOLD$hold render") { CausticInstrument.render(voice, macros) }
                assertFailsWith<IllegalArgumentException> { CausticInstrument.probe(voice, macros, seconds = .01) }
                assertFailsWith<IllegalArgumentException> { CausticInstrument.durationSeconds(voice, macros) }
                assertFailsWith<IllegalArgumentException> { CausticInstrument.drumClassFor(voice, macros) }
            }
        }
    }

    @Test
    fun `nonfinite controls fall back and unknown keys leave actual audio unchanged`() {
        val bad = mapOf("SOURCE" to Float.NaN, "FOCUS" to Float.POSITIVE_INFINITY,
            "BODY" to Float.NEGATIVE_INFINITY, "LENGTH" to Float.NaN,
            "HOLD" to Float.NaN, "unknown" to .9f)
        for (voice in voices) {
            assertEquals(CausticInstrument.defaults(voice), CausticInstrument.settled(voice, bad))
            assertFalse("unknown" in CausticInstrument.settled(voice, bad))
            val a = capture(voice, seconds = .2)
            val b = capture(voice, bad, seconds = .2)
            assertContentEquals(a.internalRaw, b.internalRaw, "$voice nonfinite fallback raw")
            assertContentEquals(a.samples, b.samples, "$voice nonfinite fallback output")
            assertContentEquals(a.samples, capture(voice, mapOf("unknown" to .7f), seconds = .2).samples)
        }
    }

    @Test
    fun `each new voice renders deterministically through normal and diagnostic entry points`() {
        val macros = mapOf("SOURCE" to .73f, "FOCUS" to .24f, "BODY" to .37f, "LENGTH" to .02f)
        for (voice in voices) {
            val a = CausticInstrument.probe(voice, macros, midi = 48)
            val b = CausticInstrument.probe(voice, macros, midi = 48)
            assertContentEquals(a.internalRaw, b.internalRaw, "$voice raw determinism")
            assertContentEquals(a.prelevel, b.prelevel, "$voice prelevel determinism")
            assertContentEquals(a.samples, b.samples, "$voice output determinism")
            assertContentEquals(a.samples, CausticInstrument.render(voice, macros, midi = 48).samples,
                "$voice normal render differs from probe")
        }
    }

    @Test
    fun `zero velocity is exact silence and greater effort survives house leveling`() {
        for (voice in voices) {
            val zero = CausticInstrument.probe(voice, velocity = 0f, seconds = .8)
            for (buffer in listOf(zero.internalRaw, zero.raw, zero.prelevel, zero.samples))
                assertTrue(buffer.all { it == 0f }, "$voice velocity0 injected energy")
            val soft = CausticInstrument.probe(voice, velocity = .25f, seconds = .8)
            val hard = CausticInstrument.probe(voice, velocity = 1f, seconds = .8)
            val driveRatio = rms(hard.internalRaw) / rms(soft.internalRaw)
            val outputRatio = rms(hard.samples) / rms(soft.samples)
            println("CAUSTIC $voice velocity raw/output RMS ratios=$driveRatio/$outputRatio")
            assertTrue(driveRatio > 1.5, "$voice velocity did not increase the actual excitation: $driveRatio")
            assertTrue(outputRatio > 1.2, "$voice house level removed performance dynamics: $outputRatio")
        }
    }

    @Test
    fun `new pitched bodies remain finite bounded and in key at C2 C4 and C6`() {
        for (voice in voices) for (midi in listOf(36, 60, 84)) {
            val p = CausticInstrument.probe(voice, midi = midi, seconds = 1.05)
            val context = "$voice MIDI$midi"
            assertTrue(p.internalRaw.all { it.isFinite() } && p.prelevel.all { it.isFinite() } && p.samples.all { it.isFinite() }, context)
            val rawPeak = p.internalRaw.maxOf { abs(it) }
            assertTrue(rawPeak > 1e-5 && rawPeak < 8f, "$context unconditioned peak=$rawPeak")
            assertTrue(p.samples.maxOf { abs(it) } in .01f.. .99001f, "$context native output peak")
            assertTrue(abs(p.samples.sumOf { it.toDouble() } / p.samples.size) < .005, "$context DC")
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            // Hollow's voiced body is brief. Its late, nearly silent tail is not a
            // tuning reference; also retain a broad first-second octave/root guard.
            val pitchWindows = if (voice == CausticVoice.HOLLOW_MALLET)
                listOf(.025f to .2f, 0f to 1f) else listOf(.5f to .35f)
            for ((start, duration) in pitchWindows) {
                val pitch = assertNotNull(Pitch.detect(Snip(p.samples, 1, rate), start, duration),
                    "$context broad pitch missing at $start..${start + duration}")
                val cents = 1200 * ln(pitch.hz / hz) / ln(2.0)
                println("CAUSTIC $context pitch=$cents cents confidence=${pitch.confidence} window=$start/$duration rawPeak=$rawPeak")
                assertTrue(pitch.confidence >= .3 && abs(cents) < 20,
                    "$context pitch=$cents confidence=${pitch.confidence} window=$start/$duration")
            }
            assertTrue(p.diagnostics.modeFrequencies.all { it.isFinite() && it > 0 && it < 20000 }, "$context invalid mode frequencies")
        }
    }

    @Test
    fun `short bass stays pitched and decays with authored tonal placement`() {
        for (voice in voices) for (body in listOf(0f, .5f, 1f)) {
            val macros = mapOf("BODY" to body, "LENGTH" to 0f)
            val p = CausticInstrument.probe(voice, macros, midi = 36)
            val context = "$voice MIDI36 LENGTH0 BODY$body"
            assertEquals(rate / 2, p.samples.size, context)
            assertTrue(p.internalRaw.all { it.isFinite() } && p.internalRaw.maxOf { abs(it) } < 8f, context)
            val pitch = assertNotNull(Pitch.detect(Snip(p.samples, 1, rate), 0f, .25f), "$context broad pitch missing")
            val hz = 440.0 * 2.0.pow((36 - 69) / 12.0)
            val cents = 1200 * ln(pitch.hz / hz) / ln(2.0)
            val classification = Classifier.classify(Snip(p.samples, 1, rate)).drumClass
            val attack = windowRms(p.internalRaw, .01, .08, rate * 4)
            val ending = windowRms(p.internalRaw, .35, .45, rate * 4)
            println("CAUSTIC $context pitch=$cents confidence=${pitch.confidence} class=$classification rawEnd/Attack=${ending / attack}")
            assertTrue(pitch.confidence >= .3 && abs(cents) < 20,
                "$context pitch=$cents confidence=${pitch.confidence}")
            assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(voice, macros),
                "$context must retain authored native tonal placement")
            if (voice == CausticVoice.HOLLOW_MALLET && body == 0f) {
                // Measured matrix corner: lowRatio=.5754 (> .55), centroid152Hz,
                // decay75ms. Both root and third partial lie below the bass
                // heuristic's200Hz boundary. A broad demand for PERC here was
                // unsupported, as in the existing low-register ARCO precedent.
                // Record actual TOM; do not skip pitch/decay or alter the tone to
                // satisfy that heuristic. Nearby continuous settings may share
                // this limitation; this assertion covers this measured recipe.
                assertEquals(DrumClass.TOM, classification, "$context measured bass-heuristic limitation changed")
            } else {
                assertTrue(classification in setOf(DrumClass.PERC, DrumClass.TONAL), "$context classified as $classification")
            }
            // Disjoint raw windows precede the native output fade. A zero final
            // sample, normalization or the whole-note peak cannot satisfy this.
            assertTrue(attack > 1e-6 && ending < attack * .1,
                "$context did not decay20dB before the output fade: attack=$attack ending=$ending")
        }
    }

    @Test
    fun `short Hollow bass retains independently measured pitch across length body and effort`() {
        var detectorNulls = 0
        for (length in listOf(0f, .05f, .125f)) for (body in listOf(0f, .5f, 1f))
            for (velocity in listOf(.25f, .75f, 1f)) {
                val macros = mapOf("LENGTH" to length, "BODY" to body)
                val p = CausticInstrument.probe(CausticVoice.HOLLOW_MALLET, macros, midi = 36, velocity = velocity)
                val context = "HOLLOW_MALLET MIDI36 LENGTH$length BODY$body velocity$velocity"
                assertEquals(((.5 + 7.5 * length) * rate).roundToInt(), p.samples.size, context)
                for (buffer in listOf(p.internalRaw, p.raw, p.prelevel, p.samples))
                    assertTrue(buffer.all { it.isFinite() }, "$context nonfinite audio")
                assertTrue(p.internalRaw.maxOf { abs(it) } in 1e-6f.. 7.99999f, "$context raw peak")
                assertTrue(p.samples.maxOf { abs(it) } <= .99001f, "$context native peak")
                assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(CausticVoice.HOLLOW_MALLET, macros), context)

                val detector = Pitch.detect(Snip(p.samples, 1, rate), 0f, .25f)
                val evidence = if (detector != null) detector.hz.toDouble() to detector.confidence.toDouble() else {
                    detectorNulls++
                    // Pitch can reject a valid low root when correlation at its
                    // minimum lag raises the .85*rmax cutoff above the period
                    // peak. This independent fallback receives no requested note:
                    // an octave/third selected by its broad FFT will fail below.
                    unconstrainedSpectralPitch(p.samples)
                }
                val requested = 440.0 * 2.0.pow((36 - 69) / 12.0)
                val cents = 1200 * ln(evidence.first / requested) / ln(2.0)
                val actualClass = Classifier.classify(Snip(p.samples, 1, rate)).drumClass
                println("CAUSTIC $context broadPitch=${detector?.hz} measuredHz=${evidence.first} cents=$cents periodicity=${evidence.second} heuristicClass=$actualClass filedClass=TONAL")
                assertTrue(abs(cents) < 20 && evidence.second >= .3,
                    "$context pitch=$cents confidence/correlation=${evidence.second} broadPitch=${detector?.hz}")
            }
        println("CAUSTIC short Hollow matrix recorded $detectorNulls nullable house-detector results; independent broad-spectrum evidence checked each null")
    }

    @Test
    fun `duration class and independent raw release windows follow finite LENGTH`() {
        for (voice in voices) {
            val short = CausticInstrument.render(voice, mapOf("LENGTH" to 0f))
            val long = capture(voice, seconds = 8.0)
            assertEquals(rate / 2, short.samples.size, "$voice short frames")
            assertEquals(8 * rate, long.samples.size, "$voice long frames")
            assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(voice, mapOf("LENGTH" to 0f)))
            assertEquals(DrumClass.LOOP, CausticInstrument.drumClassFor(voice))
            assertEquals(DrumClass.LOOP, Classifier.classify(Snip(long.samples, 1, rate)).drumClass)
            assertTrue(Classifier.classify(short).drumClass in setOf(DrumClass.PERC, DrumClass.TONAL),
                "$voice short render was classified as a drum")
            // For Hollow/Rubbed, both windows follow the last force/path arrival.
            // Catches has exponential forcing, so its check establishes finite raw
            // decay, rather than claiming an exactly force-free state.
            val windows = when (voice) {
                CausticVoice.HOLLOW_MALLET -> doubleArrayOf(.10, .20, .60, .80)
                CausticVoice.RUBBED_RING -> doubleArrayOf(1.65, 1.95, 3.0, 3.4)
                else -> doubleArrayOf(1.0, 1.5, 6.5, 7.5)
            }
            val earlier = windowRms(long.internalRaw, windows[0], windows[1], rate * 4)
            val later = windowRms(long.internalRaw, windows[2], windows[3], rate * 4)
            println("CAUSTIC $voice disjoint unconditioned release ratio=${later / earlier}")
            assertTrue(earlier > 1e-6 && later < earlier * .05,
                "$voice failed raw release before the output fade: earlier=$earlier later=$later")
        }
    }

    @Test
    fun `LENGTH changes the sounding body before a shared capture endpoint`() {
        for (voice in voices) {
            val ratios = listOf(0f, .25f, .5f).map { length ->
                val p = capture(voice, mapOf("LENGTH" to length), seconds = .49)
                windowRms(p.internalRaw, .30, .45, rate * 4) /
                    windowRms(p.internalRaw, .025, .075, rate * 4).coerceAtLeast(1e-20)
            }
            println("CAUSTIC $voice LENGTH0/.25/.5 body/onset=$ratios")
            assertTrue(ratios[1] > ratios[0] * 1.5 && ratios[2] > ratios[1] * 1.1,
                "$voice LENGTH only truncated an unchanged source/body: $ratios")
        }
    }

    @Test
    fun `adopted defaults preserve main window character differences from Fuller Body`() {
        val anchor = capture(CausticVoice.ALLOY).prelevel
        for (voice in voices) {
            val candidate = capture(voice).prelevel
            val spectral = l1(spectrum(anchor), spectrum(candidate))
            val temporal = envelopeResidual(anchor, candidate)
            println("CAUSTIC ALLOY/$voice main spectralL1=$spectral envelopeResidual=$temporal")
            assertTrue(spectral >= .35 || temporal >= .25,
                "$voice collapsed to an Alloy gain/phase variation: spectrum=$spectral envelope=$temporal")
        }
    }

    @Test
    fun `source focus body and length change normalized audible window shapes`() {
        val steps = mapOf("SOURCE" to listOf(0f, .25f, .5f), "FOCUS" to listOf(.12f, .38f, .85f),
            "BODY" to listOf(0f, .25f, .5f), "LENGTH" to listOf(.25f, .5f, 1f))
        for (voice in voices) for ((name, values) in steps) {
            val audio = values.map { capture(voice, mapOf(name to it)).prelevel }
            for (i in 0 until audio.lastIndex) {
                val spectral = l1(spectrum(audio[i]), spectrum(audio[i + 1]))
                val temporal = envelopeResidual(audio[i], audio[i + 1])
                println("CAUSTIC $voice $name ${values[i]}..${values[i + 1]} spectralL1=$spectral envelope=$temporal")
                assertTrue(spectral >= .03 || temporal >= .03,
                    "$voice $name ${values[i]}..${values[i + 1]} changed only gain/phase: $spectral/$temporal")
            }
        }
    }

    @Test
    fun `spatial and temporal macro pairs have measured interactions`() {
        for (voice in voices) {
            val spatial = listOf(0f, .75f).map { source -> listOf(.12f, .85f).map { focus ->
                spectrum(capture(voice, mapOf("SOURCE" to source, "FOCUS" to focus)).prelevel)
            } }
            val spatialInteraction = interaction(spatial)
            val temporal = listOf(0f, 1f).map { body -> listOf(.25f, .75f).map { length ->
                normalizedEnvelope(capture(voice, mapOf("BODY" to body, "LENGTH" to length)).prelevel)
            } }
            val temporalInteraction = interaction(temporal)
            println("CAUSTIC $voice SOURCE x FOCUS=$spatialInteraction BODY x LENGTH=$temporalInteraction")
            assertTrue(spatialInteraction >= .02, "$voice SOURCE/FOCUS act as separable scalar gains: $spatialInteraction")
            assertTrue(temporalInteraction >= .02, "$voice BODY/LENGTH have no envelope interaction: $temporalInteraction")
        }
    }

    @Test
    fun `C4 raw checkpoints preserve the independent published Python study`() {
        // Frozen from study DSP SHA256 e7c92b04ce18795069428aeab116345924df6b96d5ca16d7f81889438e9d4fb6
        // and exported geometry SHA256 05501ae20258720db821324332f2ce3aebb80c66e12ad63197652b2c67838b2d.
        // Catches uses the published native basis, L1-normalized contact forces and
        // whole-period event times. Expected values never call the new Kotlin DSP.
        val indices = intArrayOf(1187, 2067, 3058, 4218, 6900, 11144, 18896, 30537,
            49628, 87126, 125549, 182281, 224224, 264091, 305837)
        val references = mapOf(
            CausticVoice.HOLLOW_MALLET to doubleArrayOf(.0002152789405617221, .013130978627489296,
                -.11809087183651241, -.10688618840707831, -.09580268570454567, -.060047984421643726,
                .02187765737839543, -.007077692435956957, .0009649181395146322, -.0000894529986614051,
                -.0000034273270965457342, -.00000003504247658538526, -.00000000016766485568233036,
                .000000000019611979777299424, .00000000000009920456413011671),
            CausticVoice.RUBBED_RING to doubleArrayOf(1.4376788513923017e-9, 1.5855770669201718e-7,
                -.000011877182911328192, -.00002248260100984176, -.00020060486667641106,
                -.0012377757586469749, .007335999973509099, -.015165197303431759, .02753112778764323,
                -.1123350537289054, -.12338281932103681, -.034915025395698705, -.08484173126706118,
                .04283299068783714, .0006504133276328271),
            CausticVoice.TRAVELLING_CATCHES to doubleArrayOf(-.0030280763166956604, .0020646869961638004,
                -.14244632795453072, .026947767473757267, .04574174527078867, .01034570625051856,
                .041794562712311745, -.009828736539930098, -.0075137547003881365, .01762161900599797,
                -.018056158674880862, .0028146658053932096, -.0047823434870224455,
                .006746044513420202, .007056528561709759),
        )
        for ((voice, expected) in references) {
            val actual = capture(voice, seconds = 1.75).internalRaw
            val error = sqrt(indices.indices.sumOf { i -> (actual[indices[i]] - expected[i]).pow(2) } /
                expected.sumOf { it * it })
            println("CAUSTIC $voice independent prototype checkpoints relative RMS=$error")
            assertTrue(error < .005, "$voice changed the published default raw study: $error")
        }
    }

    @Test
    fun `old Alloy model one remains byte identical at representative pitches and corners`() {
        // Captured before adding the voices: little-endian Float32 SHA256 of
        // native output, native prelevel, and unconditioned 4x audio, respectively.
        data class Case(val midi: Int, val macros: Map<String, Float>, val velocity: Float, val hashes: List<String>)
        val cases = listOf(
            Case(36, mapOf("BODY" to 0f), .75f, listOf(
                "34e9ab97f157f9ee48edd351014309442887a5f0749543b622d7a77d14e78ce5",
                "99f2c2e2ec3bd1b3f45e5bcf6b46dab4a4bad1e2f660a8f6d9a940e03501c738",
                "1bcc333d8188f14e21644b3e6a29388060574fc9e23b3ee854958f567ce784d3")),
            Case(84, mapOf("BODY" to 1f), .75f, listOf(
                "1be2e5ba31cab0affa8660411a79fd7f5be059b77ff621d4e88056d8e8259130",
                "9b1ed3123903b63ef8dd6036331fb771ec651af56226244f4c3af1c4d2201319",
                "a35055cf0c41f417b2a18c7cc10e03f5d6508129d75101df83ccb942d179a193")),
            Case(60, mapOf("SOURCE" to 0f, "FOCUS" to .12f, "LENGTH" to 0f), .2f, listOf(
                "6edd40b48618618f8151c9ced32bae0d3a2369a6daf0387b68aa450654827de2",
                "0c1b2b09b029b23ca989b137fa38a0097756124788a8728c3d61dfbe1179aaa8",
                "f068c0b6bf35db25900d3b59b8fbe6943afcab49bfd6bfd1fdd1fa1f58e65a63")),
            Case(60, mapOf("SOURCE" to 1f, "FOCUS" to .85f, "LENGTH" to .3f), 1f, listOf(
                "f49547751d88ecc7412573f4c04b50c15522f3090f66d28c7a58de301c66d2cf",
                "223bef04f046e0346960c9b89e931eb6fcb110b450eb4fd35e1291432d60e359",
                "47520be21ded64421664360edf2e6e1a30ec35f7a6bf3f87a5d232285ba4097d")),
        )
        for (case in cases) {
            val p = CausticInstrument.probe(CausticVoice.ALLOY, case.macros, case.midi, case.velocity)
            assertEquals(case.hashes, listOf(p.samples, p.prelevel, p.internalRaw).map(::floatHash),
                "ALLOY MIDI${case.midi} ${case.macros} velocity${case.velocity} changed")
        }
    }

    @Test
    fun `high register oversampling reference preserves broad shape and rejects upper edge leakage`() {
        for (voice in voices) {
            val a = CausticInstrument.probe(voice, midi = 84, velocity = 1f, seconds = .6, normalize = false, oversample = 4)
            val b = CausticInstrument.probe(voice, midi = 84, velocity = 1f, seconds = .6, normalize = false, oversample = 16)
            // Dsp.Noise is sampled at each internal rate. Its individual realization
            // differs at16x, so compare broad statistics, never waveform equality.
            val distance = l1(spectrum(a.prelevel, .03, .55), spectrum(b.prelevel, .03, .55))
            val upper4 = upperEdgePower(a.prelevel)
            val upper16 = upperEdgePower(b.prelevel)
            println("CAUSTIC $voice C6 spectralL1 4x/16x=$distance upper20k=$upper4/$upper16")
            assertTrue(distance < .10, "$voice 4x/16x broad shape diverged: $distance")
            assertTrue(upper4 < 1e-4 && upper16 < 1e-4,
                "$voice has excessive upper-edge power: $upper4/$upper16")
            if (voice == CausticVoice.TRAVELLING_CATCHES)
                assertTrue(upper4 - upper16 < 1e-5, "$voice 4x adds upper-edge power: $upper4/$upper16")
        }
    }

    @Test
    fun `new voices honor cancellation before rendering`() {
        for (voice in voices) {
            Thread.currentThread().interrupt()
            try { assertFailsWith<CancellationException> { CausticInstrument.render(voice) } }
            finally { Thread.interrupted() }
        }
    }

    private fun capture(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), seconds: Double = 1.5) =
        CausticInstrument.probe(voice, macros, seconds = seconds, normalize = false)

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))

    private fun windowRms(x: FloatArray, from: Double, to: Double, sampleRate: Int = rate): Double {
        val start = (from * sampleRate).roundToInt().coerceAtMost(x.size)
        val end = (to * sampleRate).roundToInt().coerceAtMost(x.size)
        return sqrt((start until end).sumOf { x[it].toDouble() * x[it] } / (end - start).coerceAtLeast(1))
    }

    private fun rmsEnvelope(x: FloatArray): DoubleArray = DoubleArray(58) { i ->
        windowRms(x, .05 + i * .025, .05 + (i + 1) * .025)
    }

    private fun normalizedEnvelope(x: FloatArray): DoubleArray {
        val out = rmsEnvelope(x).map { it * it }.toDoubleArray()
        val total = out.sum().coerceAtLeast(1e-30)
        return DoubleArray(out.size) { out[it] / total }
    }

    private fun envelopeResidual(a: FloatArray, b: FloatArray): Double {
        val x = rmsEnvelope(a); val y = rmsEnvelope(b)
        val gain = x.indices.sumOf { x[it] * y[it] } / x.sumOf { it * it }.coerceAtLeast(1e-30)
        return sqrt(x.indices.sumOf { (y[it] - gain * x[it]).pow(2) } / y.sumOf { it * it }.coerceAtLeast(1e-30))
    }

    private fun spectrum(x: FloatArray, from: Double = .05, to: Double = 1.5): DoubleArray {
        val n = 4096
        val out = DoubleArray(56)
        for (start in (from * rate).roundToInt()..((to * rate).roundToInt() - n) step 1024) {
            val re = FloatArray(n) { i -> if (start + i < x.size)
                (x[start + i] * (.5 - .5 * cos(2 * PI * i / n))).toFloat() else 0f }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val hz = bin * rate.toDouble() / n
                if (hz < 40) continue
                val band = (ln(hz / 40.0) / ln(2.0) * 6).toInt().coerceIn(out.indices)
                out[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
        }
        val total = out.sum().coerceAtLeast(1e-30)
        return DoubleArray(out.size) { out[it] / total }
    }

    private fun upperEdgePower(x: FloatArray): Double {
        val n = 16384
        val start = (.08 * rate).roundToInt()
        val re = FloatArray(n) { i -> (x[start + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat() }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var upper = 0.0; var total = 0.0
        for (bin in 1 until n / 2) {
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (bin * rate.toDouble() / n > 20000) upper += power
        }
        return upper / total.coerceAtLeast(1e-30)
    }

    /** Independent diagnostic, not a target-constrained root search. Only the
     * observed strongest40..1200Hz spectral peak defines its correlation period. */
    private fun unconstrainedSpectralPitch(x: FloatArray): Pair<Double, Double> {
        val count = minOf(x.size, (.25 * rate).roundToInt())
        val size = 1 shl 18
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count)
            re[i] = (x[i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        fun power(bin: Int) = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
        val lower = ceil(40.0 * size / rate).toInt()
        val upper = (1200.0 * size / rate).toInt()
        val peak = (lower..upper).maxBy { power(it) }
        assertTrue(power(peak) > 0 && power(peak) >= power(peak - 1) && power(peak) >= power(peak + 1),
            "Unconstrained spectrum has no interior pitch peak")
        val left = ln(power(peak - 1).coerceAtLeast(1e-30))
        val middle = ln(power(peak).coerceAtLeast(1e-30))
        val right = ln(power(peak + 1).coerceAtLeast(1e-30))
        val curve = left - 2 * middle + right
        val shift = if (abs(curve) > 1e-12) (.5 * (left - right) / curve).coerceIn(-.5, .5) else 0.0
        val hz = (peak + shift) * rate / size
        val period = rate / hz
        val lag = period.toInt()
        val fraction = period - lag
        var cross = 0.0
        for (i in 0 until count - lag - 1) {
            val delayed = x[i + lag] * (1 - fraction) + x[i + lag + 1] * fraction
            cross += x[i] * delayed
        }
        val energy = (0 until count).sumOf { x[it].toDouble() * x[it] }.coerceAtLeast(1e-30)
        return hz to (cross / energy)
    }

    private fun l1(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) }

    private fun interaction(grid: List<List<DoubleArray>>): Double = grid[0][0].indices.sumOf { i ->
        abs(grid[1][1][i] - grid[1][0][i] - grid[0][1][i] + grid[0][0][i])
    }

    private fun floatHash(x: FloatArray): String {
        val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}
